package com.flightmap.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import com.flightmap.core.*
import kotlinx.coroutines.*
import java.time.Instant

/** User-started, offline flight card. No network, location, alarms or preparation dependency. */
class FlightNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private lateinit var repository: FlightRepository
    private val manager get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL = "ongoing_flight"
        private const val ID = 2002
        private const val STOP = "com.flightmap.app.STOP_FLIGHT_CARD"
        private const val PREFS = "ongoing_flight"
        private const val PLAN = "plan"
        fun monitoredPlan(context: Context): FlightPlan? = runCatching {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PLAN, null)
            FlightPackCodec.createGson().fromJson(json, FlightPlan::class.java)
        }.getOrNull()

        /** Call only from a visible Activity following the user's notification action. */
        fun start(context: Context, plan: FlightPlan) {
            context.startForegroundService(Intent(context, FlightNotificationService::class.java)
                .putExtra(PLAN, FlightPackCodec.createGson().toJson(plan)))
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PLAN).apply()
            context.stopService(Intent(context, FlightNotificationService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ID)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = FlightRepository(this)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Flight progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Offline estimated flight progress, updated while your flight card is enabled"
            setShowBadge(false)
        })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PLAN).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val plan = runCatching {
            intent?.getStringExtra(PLAN)?.let { repository.gson.fromJson(it, FlightPlan::class.java) }
                ?: monitoredPlan(this)
        }.getOrNull()
        if (plan == null) { stopSelf(); return START_NOT_STICKY }
        // Foreground deadline is satisfied before any disk read or coroutine suspension.
        val notification = notification(plan, Instant.now())
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PLAN, repository.gson.toJson(plan)).apply()
        updates?.cancel()
        updates = scope.launch {
            while (isActive) {
                val saved = repository.readPacks().firstOrNull { isSameFlight(it.plan, plan) }?.plan
                if (saved == null) {
                    stop(this@FlightNotificationService)
                    break
                }
                val now = Instant.now()
                val arrived = FlightTiming.progress(saved, now).phase == "ARRIVED"
                val card = notification(saved, now)
                manager.notify(ID, card)
                if (arrived) {
                    // Keep one dismissible arrival card; release foreground work immediately.
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PLAN).apply()
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    break
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    private fun notification(plan: FlightPlan, now: Instant): Notification {
        val progress = FlightTiming.progress(plan, now)
        val arrived = progress.phase == "ARRIVED"
        val open = PendingIntent.getActivity(this, 200, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_flight_number", plan.number)
            putExtra("open_flight_departure", plan.departure.toString())
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 201, Intent(this, FlightNotificationService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val details = "${plan.number} · ${FlightPresentation.status(progress, plan.landedAt != null)}"
        val compact = RemoteViews(packageName, R.layout.notification_flight_compact).apply {
            setImageViewBitmap(R.id.flight_route, routeBitmap(plan, progress))
            setTextViewText(R.id.flight_status, "$details · Estimated")
        }
        val expanded = RemoteViews(packageName, R.layout.notification_flight).apply {
            setImageViewBitmap(R.id.flight_route, routeBitmap(plan, progress))
            setTextViewText(R.id.flight_status, details)
            setTextViewText(R.id.flight_detail, "${FlightPresentation.arrival(plan, progress, now)}\n${FlightPresentation.altitude(progress)} · Estimated position")
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flight_notification)
            .setContentTitle("${plan.number} · ${FlightPresentation.route(plan)}")
            .setContentText("${FlightPresentation.status(progress, plan.landedAt != null)} · Estimated position")
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setProgress(1000, (progress.fraction * 1000).toInt(), false)
            .setContentIntent(open)
            .setOngoing(!arrived)
            .setAutoCancel(arrived)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply { if (!arrived) addAction(Notification.Action.Builder(null, "Stop updates", stop).build()) }
            .build()
    }

    /** Plane on the same origin-to-destination progress track as the on-screen header. */
    private fun routeBitmap(plan: FlightPlan, progress: FlightProgress): Bitmap {
        val bitmap = Bitmap.createBitmap(900, 92, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 90, 78); textSize = 42f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText(plan.origin.iata, 0f, 62f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(plan.destination.iata, 900f, 62f, paint)
        val start = 160f; val end = 740f; val y = 48f
        paint.strokeWidth = 4f; paint.color = Color.rgb(140, 155, 152)
        canvas.drawLine(start, y, end, y, paint)
        val x = start + (end - start) * progress.fraction.toFloat()
        paint.color = Color.rgb(30, 120, 96)
        canvas.drawLine(start, y, x, y, paint)
        canvas.drawCircle(start, y, 6f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(end, y, 8f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 58f
        canvas.drawText("✈", x, y + 20f, paint)
        return bitmap
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
