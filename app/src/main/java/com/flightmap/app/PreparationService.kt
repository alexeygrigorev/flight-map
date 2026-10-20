package com.flightmap.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.flightmap.core.FlightPlan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.MapLibre
import java.time.Instant

class PreparationService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var preparationJob: Job? = null
    private val actionMutex = Mutex()

    private lateinit var repository: FlightRepository
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "flight_preparation"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.flightmap.app.action.START_PREPARATION"
        const val ACTION_PAUSE = "com.flightmap.app.action.PAUSE_PREPARATION"
        const val ACTION_RESUME = "com.flightmap.app.action.RESUME_PREPARATION"
        const val EXTRA_PLAN_JSON = "extra_plan_json"

        private val _progressFlow = MutableStateFlow(PreparationProgress())
        val progressFlow = _progressFlow.asStateFlow()

        @Volatile var isRunning = false
            private set

        fun startPreparation(context: Context, plan: FlightPlan) {
            val repository = FlightRepository(context)
            val intent = Intent(context, PreparationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PLAN_JSON, repository.gson.toJson(plan))
            }
            startServiceCompat(context, intent)
        }

        fun pausePreparation(context: Context) {
            val intent = Intent(context, PreparationService::class.java).apply {
                action = ACTION_PAUSE
            }
            startServiceCompat(context, intent)
        }

        fun resumePreparation(context: Context) {
            val intent = Intent(context, PreparationService::class.java).apply {
                action = ACTION_RESUME
            }
            startServiceCompat(context, intent)
        }

        private fun startServiceCompat(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = FlightRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        // Satisfy the 5-second startForeground deadline immediately before async I/O
        val placeholder = buildNotificationPlaceholder("Preparing flight offline data…")
        startForegroundCompat(placeholder)

        serviceScope.launch {
            actionMutex.withLock {
                when (intent?.action) {
                    ACTION_START -> {
                        val planJson = intent.getStringExtra(EXTRA_PLAN_JSON)
                        if (planJson != null) {
                            val rawPlan = repository.gson.fromJson(planJson, FlightPlan::class.java)
                            val plan = com.flightmap.core.TrajectoryCatalog.enrichPlan(rawPlan)
                            startPreparationWorkflow(plan)
                        } else {
                            stopSelf()
                        }
                    }
                    ACTION_PAUSE -> {
                        pausePreparationWorkflow()
                    }
                    ACTION_RESUME -> {
                        resumePreparationWorkflow()
                    }
                    else -> {
                        handleProcessRestart()
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun handleProcessRestart() {
        val checkpoint = repository.readCheckpoint()
        if (checkpoint != null && checkpoint.status == PreparationStatus.IN_PROGRESS) {
            try {
                val notification = buildNotification(checkpoint.plan, "Resuming flight preparation…", 0, 100, true)
                startForegroundCompat(notification)
                executePreparation(checkpoint.plan, checkpoint)
            } catch (e: Exception) {
                repository.saveCheckpoint(
                    checkpoint.copy(
                        status = PreparationStatus.PAUSED,
                        stage = "Preparation paused",
                        updatedAt = Instant.now()
                    )
                )
                _progressFlow.value = PreparationProgress(
                    plan = checkpoint.plan,
                    isBusy = false,
                    stage = "Preparation paused. Resume when ready.",
                    status = PreparationStatus.PAUSED
                )
                isRunning = false
                stopSelf()
            }
        } else {
            isRunning = false
            stopSelf()
        }
    }

    private suspend fun startPreparationWorkflow(requestedPlan: FlightPlan) {
        preparationJob?.let {
            it.cancel()
            it.join()
        }

        val plan = repository.prepareHistory(requestedPlan)
        val initialMapIds = withContext(Dispatchers.Main.immediate) {
            val maps = OfflineMaps(applicationContext)
            maps.regionsFor(plan)
        }

        val initialCheckpoint = PreparationCheckpoint(
            plan = plan,
            status = PreparationStatus.IN_PROGRESS,
            stage = "Saving route and schedule",
            routeReady = true,
            mapRegionIds = initialMapIds,
            updatedAt = Instant.now()
        )
        repository.saveCheckpoint(initialCheckpoint)

        val notification = buildNotification(plan, "Saving route and schedule…", 0, 100, true)
        startForegroundCompat(notification)

        _progressFlow.value = PreparationProgress(
            plan = plan,
            isBusy = true,
            stage = "Saving route and schedule",
            routeReady = true,
            status = PreparationStatus.IN_PROGRESS
        )

        executePreparation(plan, initialCheckpoint)
    }

    private suspend fun pausePreparationWorkflow() {
        preparationJob?.let {
            it.cancel()
            it.join()
        }

        val checkpoint = repository.readCheckpoint()
        if (checkpoint != null && checkpoint.status == PreparationStatus.IN_PROGRESS) {
            val paused = checkpoint.copy(
                status = PreparationStatus.PAUSED,
                stage = "Preparation paused",
                updatedAt = Instant.now()
            )
            repository.saveCheckpoint(paused)
        }
        _progressFlow.value = _progressFlow.value.copy(
            isBusy = false,
            stage = "Preparation paused. Resume when ready.",
            status = PreparationStatus.PAUSED
        )
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun resumePreparationWorkflow() {
        preparationJob?.let {
            it.cancel()
            it.join()
        }

        val checkpoint = repository.readCheckpoint()
        if (checkpoint != null && checkpoint.status != PreparationStatus.COMPLETED) {
            val discoveredIds = withContext(Dispatchers.Main.immediate) {
                val maps = OfflineMaps(applicationContext)
                (checkpoint.mapRegionIds + maps.regionsFor(checkpoint.plan)).distinct()
            }
            val resumingCheckpoint = checkpoint.copy(
                status = PreparationStatus.IN_PROGRESS,
                stage = "Resuming preparation…",
                mapRegionIds = discoveredIds,
                updatedAt = Instant.now()
            )
            repository.saveCheckpoint(resumingCheckpoint)

            val notification = buildNotification(checkpoint.plan, "Resuming flight preparation…", 0, 100, true)
            startForegroundCompat(notification)

            _progressFlow.value = PreparationProgress(
                plan = checkpoint.plan,
                isBusy = true,
                stage = "Resuming preparation…",
                routeReady = checkpoint.routeReady,
                citiesReady = checkpoint.citiesReady,
                weatherReady = checkpoint.weatherReady,
                mapReady = checkpoint.mapReady,
                weatherDetail = checkpoint.weatherDetail,
                mapDetail = checkpoint.mapDetail,
                status = PreparationStatus.IN_PROGRESS,
                sampledWeatherIds = checkpoint.sampledWeatherIds
            )

            executePreparation(checkpoint.plan, resumingCheckpoint)
        } else {
            isRunning = false
            stopSelf()
        }
    }

    private fun executePreparation(requestedPlan: FlightPlan, initialCheckpoint: PreparationCheckpoint) {
        _progressFlow.value = PreparationProgress(
            plan = requestedPlan, isBusy = true, stage = initialCheckpoint.stage,
            status = PreparationStatus.IN_PROGRESS,
            routeReady = initialCheckpoint.routeReady, citiesReady = initialCheckpoint.citiesReady,
            weatherReady = initialCheckpoint.weatherReady, mapReady = initialCheckpoint.mapReady,
            weatherDetail = initialCheckpoint.weatherDetail, mapDetail = initialCheckpoint.mapDetail
        )
        preparationJob = serviceScope.launch {
            var plan = requestedPlan
            var currentCheckpoint = initialCheckpoint
            val issues = mutableListOf<String>()

            try {
                if (repository.airports.isEmpty()) {
                    repository.loadData()
                }

                plan = repository.prepareHistory(requestedPlan)
                val routeChanged = requestedPlan.effectiveWaypoints != plan.effectiveWaypoints
                currentCheckpoint = if (routeChanged) {
                    // A refreshed real track requires a new corridor, forecasts and map regions.
                    PreparationCheckpoint(plan = plan, status = PreparationStatus.IN_PROGRESS, updatedAt = Instant.now())
                } else currentCheckpoint.copy(plan = plan)
                repository.saveCheckpoint(currentCheckpoint)
                _progressFlow.value = _progressFlow.value.copy(plan = plan)

                // 1. Corridor & cities
                val cities = if (currentCheckpoint.cities.isNotEmpty()) {
                    currentCheckpoint.cities
                } else {
                    repository.corridor(plan)
                }

                currentCheckpoint = currentCheckpoint.copy(
                    cities = cities,
                    routeReady = true,
                    citiesReady = true,
                    stage = "Downloading city forecasts",
                    updatedAt = Instant.now()
                )
                repository.saveCheckpoint(currentCheckpoint)

                _progressFlow.value = _progressFlow.value.copy(
                    routeReady = true,
                    citiesReady = true,
                    stage = "Downloading city forecasts"
                )
                updateNotification(plan, "Downloading city forecasts…", 10, 100, false)

                // 2. Weather
                var weather = currentCheckpoint.weather
                var weatherReady = currentCheckpoint.weatherReady
                var weatherDetail = currentCheckpoint.weatherDetail
                val sampleCities = repository.selectWeatherSampleCities(cities, FlightPackCodec.MAX_WEATHER_SAMPLES)
                val sampleCityIds = sampleCities.map { it.id }

                if (!weatherReady) {
                    try {
                        val totalSamples = sampleCities.size

                        weather = repository.weather(
                            plan = plan,
                            cities = cities,
                            existingWeather = weather,
                            onChunkSaved = { partial, done, total ->
                                val detailText = if (cities.size > total) {
                                    "$done / $total forecasts (${cities.size} corridor cities)"
                                } else {
                                    "$done / $total cities"
                                }
                                currentCheckpoint = currentCheckpoint.copy(
                                    weather = partial.toMap(),
                                    weatherDone = done,
                                    weatherTotal = total,
                                    weatherDetail = detailText,
                                    sampledWeatherIds = sampleCityIds,
                                    updatedAt = Instant.now()
                                )
                                repository.saveCheckpoint(currentCheckpoint)
                                updateNotification(plan, "Weather: $detailText", done, total.coerceAtLeast(1), false)
                                _progressFlow.value = _progressFlow.value.copy(
                                    weatherDetail = detailText,
                                    sampledWeatherIds = sampleCityIds
                                )
                            },
                            progress = { done, total ->
                                val detailText = if (cities.size > total) {
                                    "$done / $total forecasts (${cities.size} corridor cities)"
                                } else {
                                    "$done / $total cities"
                                }
                                _progressFlow.value = _progressFlow.value.copy(weatherDetail = detailText)
                            }
                        )

                        val sampledSavedCount = sampleCities.count { it.id in weather }
                        weatherReady = (sampleCities.isEmpty() && cities.isEmpty()) || sampledSavedCount >= totalSamples
                        weatherDetail = if (cities.isEmpty()) {
                            "No cities in this corridor"
                        } else if (sampleCities.size < cities.size) {
                            "$sampledSavedCount sampled city forecasts saved (${cities.size} corridor cities)"
                        } else {
                            "${weather.size} city forecasts saved"
                        }

                        currentCheckpoint = currentCheckpoint.copy(
                            weather = weather,
                            weatherReady = weatherReady,
                            weatherDetail = weatherDetail,
                            sampledWeatherIds = sampleCityIds,
                            updatedAt = Instant.now()
                        )
                        repository.saveCheckpoint(currentCheckpoint)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        weather = currentCheckpoint.weather
                        issues += e.message ?: "Weather download failed"
                        weatherDetail = "Forecast download incomplete"
                    }
                }

                // 3. Offline map: vector regions (OpenFreeMap, offline-allowed) for the
                // bounded route corridor + high-detail raster imagery (NASA GIBS Blue
                // Marble, public domain) displayed on the in-flight globe at deep zoom.
                // Both persist on disk and resume after pause/restart. mapReady is true
                // only when both are fully downloaded and verified - never on failure.
                var mapReady = false
                var mapFailed = false
                var ids: List<Long> = currentCheckpoint.mapRegionIds
                var mapDetail = currentCheckpoint.mapDetail.ifBlank { "Preparing offline map…" }
                var tileKey: String? = currentCheckpoint.mapTileKey
                try {
                    _progressFlow.value = _progressFlow.value.copy(stage = "Downloading offline map…")
                    updateNotification(plan, "Downloading offline map…", 0, 100, true)

                    // 3a. Vector regions (broad overview + corridor + airports).
                    var vectorDetail = ""
                    val maps = withContext(Dispatchers.Main.immediate) {
                        OfflineMaps(applicationContext)
                    }
                    val vectorIds = withContext(Dispatchers.Main.immediate) {
                        val known = maps.regionsFor(plan)
                        val summary = maps.summary(plan)
                        vectorDetail = "Vector " + summary.formattedSummary()
                        _progressFlow.value = _progressFlow.value.copy(mapDetail = vectorDetail)
                        val downloaded = maps.download(plan) { stage ->
                            val detail = "$vectorDetail · $stage"
                            _progressFlow.value = _progressFlow.value.copy(
                                stage = "Downloading offline map",
                                mapDetail = detail
                            )
                            updateNotification(plan, "Map: $stage", 0, 100, true)
                        }
                        (downloaded + known).distinct()
                    }
                    ids = vectorIds
                    val vectorOk = withContext(Dispatchers.Main.immediate) {
                        maps.verify(vectorIds)
                    }
                    vectorDetail = withContext(Dispatchers.Main.immediate) {
                        runCatching {
                            maps.detailSummary(vectorIds)
                        }.getOrDefault(vectorDetail)
                    }
                    if (!vectorOk) {
                        issues += "Vector map regions incomplete"
                        mapFailed = true
                    }

                    // 3b. Corridor raster imagery (deep-zoom globe detail, offline).
                    var rasterDetail = ""
                    val tiles = CorridorTiles(applicationContext)
                    val rasterCoverage = withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeout(600_000L) {
                            tiles.download(plan) { stage ->
                                val detail = if (vectorDetail.isNotBlank()) {
                                    "$vectorDetail · $stage"
                                } else {
                                    stage
                                }
                                _progressFlow.value = _progressFlow.value.copy(
                                    stage = "Downloading offline map",
                                    mapDetail = detail
                                )
                                updateNotification(plan, "Map: $stage", 0, 100, true)
                            }
                        }
                    }
                    tileKey = tiles.keyForPlan(plan)
                    rasterDetail = tiles.coverageDetail(plan)
                    if (!rasterCoverage.isComplete) {
                        issues += "Corridor imagery incomplete (${rasterCoverage.present}/${rasterCoverage.total} tiles)"
                        mapFailed = true
                    }

                    mapDetail = if (rasterDetail.isNotBlank()) "$vectorDetail · $rasterDetail" else vectorDetail
                    mapReady = vectorOk && rasterCoverage.isComplete && !mapFailed
                    if (!mapReady) mapFailed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    issues += e.message ?: "Map download failed"
                    mapFailed = true
                    mapReady = false
                    // Preserve whatever regions/tiles already persisted for resume.
                    val discoveredIds = withContext(Dispatchers.Main.immediate) {
                        runCatching {
                            val maps = OfflineMaps(applicationContext)
                            (ids + maps.regionsFor(plan)).distinct()
                        }.getOrDefault(ids)
                    }
                    ids = discoveredIds
                    tileKey = tileKey ?: runCatching {
                        CorridorTiles(applicationContext).keyForPlan(plan)
                    }.getOrNull()
                    val partialDetail = runCatching {
                        CorridorTiles(applicationContext).coverageDetail(plan)
                    }.getOrNull()
                    mapDetail = if (partialDetail != null && partialDetail.contains("incomplete")) {
                        "$mapDetail · $partialDetail"
                    } else {
                        "Map download incomplete: ${e.message ?: "network error"}. Resume on Wi-Fi."
                    }
                }

                currentCheckpoint = currentCheckpoint.copy(
                    mapRegionIds = ids,
                    mapReady = mapReady,
                    mapDetail = mapDetail,
                    mapTileKey = tileKey,
                    updatedAt = Instant.now()
                )
                repository.saveCheckpoint(currentCheckpoint)

                _progressFlow.value = _progressFlow.value.copy(
                    weatherReady = weatherReady,
                    weatherDetail = weatherDetail,
                    sampledWeatherIds = sampleCityIds,
                    mapReady = mapReady,
                    mapDetail = mapDetail
                )

                // 4. Save pack
                val readyPack = FlightPack(
                    plan = plan,
                    cities = cities,
                    weather = weather,
                    preparedAt = Instant.now(),
                    mapReady = mapReady,
                    mapRegionIds = ids,
                    mapDetailSummary = mapDetail,
                    sampledWeatherIds = sampleCityIds,
                    mapTileKey = tileKey,
                    offlineContext = com.flightmap.core.OfflineFlightContext.prepare(plan)
                )
                repository.save(readyPack)

                val finalError = issues.takeIf { it.isNotEmpty() }?.joinToString("\n")

                if (mapFailed || (!weatherReady && cities.isNotEmpty())) {
                    val partialStage = if (mapFailed) "Saved with partial map. Resume to complete." else "Saved with missing downloads"
                    val partialCheckpoint = currentCheckpoint.copy(
                        status = PreparationStatus.PAUSED,
                        stage = partialStage,
                        error = finalError,
                        mapRegionIds = ids,
                        mapReady = mapReady,
                        mapDetail = mapDetail,
                        mapTileKey = tileKey,
                        weather = weather,
                        weatherReady = weatherReady,
                        weatherDetail = weatherDetail,
                        sampledWeatherIds = sampleCityIds,
                        updatedAt = Instant.now()
                    )
                    repository.saveCheckpoint(partialCheckpoint)

                    _progressFlow.value = PreparationProgress(
                        plan = plan,
                        isBusy = false,
                        stage = partialStage,
                        error = finalError,
                        routeReady = true,
                        citiesReady = true,
                        weatherReady = weatherReady,
                        mapReady = mapReady,
                        weatherDetail = weatherDetail,
                        mapDetail = mapDetail,
                        status = PreparationStatus.PAUSED,
                        sampledWeatherIds = sampleCityIds
                    )
                } else {
                    val finalStage = "Ready for airplane mode"
                    val completedCheckpoint = currentCheckpoint.copy(
                        status = PreparationStatus.COMPLETED,
                        stage = finalStage,
                        error = finalError,
                        mapRegionIds = ids,
                        mapReady = mapReady,
                        mapDetail = mapDetail,
                        weather = weather,
                        weatherReady = weatherReady,
                        weatherDetail = weatherDetail,
                        sampledWeatherIds = sampleCityIds,
                        updatedAt = Instant.now()
                    )
                    repository.saveCheckpoint(completedCheckpoint)

                    _progressFlow.value = PreparationProgress(
                        plan = plan,
                        isBusy = false,
                        stage = finalStage,
                        error = finalError,
                        routeReady = true,
                        citiesReady = true,
                        weatherReady = weatherReady,
                        mapReady = mapReady,
                        weatherDetail = weatherDetail,
                        mapDetail = mapDetail,
                        status = PreparationStatus.COMPLETED,
                        sampledWeatherIds = sampleCityIds
                    )
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                isRunning = false
                stopSelf()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val discoveredIds = withContext(Dispatchers.Main.immediate) {
                        val maps = OfflineMaps(applicationContext)
                        (currentCheckpoint.mapRegionIds + maps.regionsFor(plan)).distinct()
                    }
                    val paused = currentCheckpoint.copy(
                        status = PreparationStatus.PAUSED,
                        stage = "Preparation paused. Previous flight preserved.",
                        mapRegionIds = discoveredIds,
                        updatedAt = Instant.now()
                    )
                    repository.saveCheckpoint(paused)
                    _progressFlow.value = _progressFlow.value.copy(
                        isBusy = false,
                        stage = "Preparation paused. Resume when ready.",
                        status = PreparationStatus.PAUSED
                    )
                    // Note: cancellation catch only persists paused state and updates flow.
                    // pausePreparationWorkflow() explicitly owns stopForeground/stopSelf.
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val discoveredIds = withContext(Dispatchers.Main.immediate) {
                        val maps = OfflineMaps(applicationContext)
                        (currentCheckpoint.mapRegionIds + maps.regionsFor(plan)).distinct()
                    }
                    val failed = currentCheckpoint.copy(
                        status = PreparationStatus.FAILED,
                        error = e.message,
                        stage = "Preparation failed",
                        mapRegionIds = discoveredIds,
                        updatedAt = Instant.now()
                    )
                    repository.saveCheckpoint(failed)
                    _progressFlow.value = _progressFlow.value.copy(
                        isBusy = false,
                        error = e.message,
                        stage = "Preparation failed",
                        status = PreparationStatus.FAILED
                    )
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch(NonCancellable) {
            val checkpoint = repository.readCheckpoint()
            if (checkpoint != null && checkpoint.status == PreparationStatus.IN_PROGRESS) {
                val discovered = withContext(Dispatchers.Main.immediate) {
                    val maps = OfflineMaps(applicationContext)
                    (checkpoint.mapRegionIds + maps.regionsFor(checkpoint.plan)).distinct()
                }
                repository.saveCheckpoint(
                    checkpoint.copy(
                        status = PreparationStatus.PAUSED,
                        stage = "Preparation paused (system timeout). Resume when ready.",
                        mapRegionIds = discovered,
                        updatedAt = Instant.now()
                    )
                )
            }
            _progressFlow.value = _progressFlow.value.copy(
                isBusy = false,
                stage = "Preparation paused (system timeout). Resume when ready.",
                status = PreparationStatus.PAUSED
            )
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flight Preparation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while preparing offline flight corridors and forecasts"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotificationPlaceholder(detail: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, PreparationService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
        val pauseAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Pause",
            pausePendingIntent
        ).build()

        builder.setContentTitle("Flight Map Preparation")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(pauseAction)
            .setProgress(0, 0, true)

        return builder.build()
    }

    private fun buildNotification(
        plan: FlightPlan,
        detail: String,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, PreparationService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
        val pauseAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Pause",
            pausePendingIntent
        ).build()

        builder.setContentTitle("Preparing ${plan.number} (${plan.origin.iata} → ${plan.destination.iata})")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(pauseAction)

        if (maxProgress > 0) {
            builder.setProgress(maxProgress, progress, indeterminate)
        }

        return builder.build()
    }

    private fun updateNotification(plan: FlightPlan, detail: String, progress: Int, maxProgress: Int, indeterminate: Boolean) {
        val notification = buildNotification(plan, detail, progress, maxProgress, indeterminate)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        isRunning = false
        super.onDestroy()
    }
}
