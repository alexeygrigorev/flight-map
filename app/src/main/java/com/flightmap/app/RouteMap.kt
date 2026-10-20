package com.flightmap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flightmap.core.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun GeoPoint.mapPoint() = Point.fromLngLat(lon, lat)

@Composable
fun RouteMap(pack: FlightPack, progress: FlightProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var followPlane by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var selectedCity by remember { mutableStateOf<City?>(null) }
    var selectedContext by remember { mutableStateOf<Pair<String, String>?>(null) }
    val offlineContext = remember(pack.plan, pack.offlineContext) {
        OfflineFlightContext.forPlan(pack.plan, pack.offlineContext)
    }

    val flightTime = remember(progress.fraction, pack.plan) {
        FlightTiming.timeAtFraction(pack.plan, progress.fraction)
    }
    val nearbyAircraft = remember(offlineContext, flightTime, progress.point) {
        OfflineFlightContext.nearby(offlineContext, flightTime, progress.point)
    }
    val currentAircraft by rememberUpdatedState(nearbyAircraft)

    val view = remember {
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .compassEnabled(false)
                .logoEnabled(false)
                .attributionEnabled(false)
                .minZoomPreference(1.0)
                .maxZoomPreference(11.0)
                .tiltGesturesEnabled(false)
                .foregroundLoadColor(android.graphics.Color.rgb(11, 28, 43))
        )
    }

    DisposableEffect(owner, view) {
        view.onCreate(null)
        view.onStart()
        view.onResume()
        view.getMapAsync { m ->
            map = m
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followPlane = false
                }
            }
            m.setStyle(Style.Builder().fromJson(OfflineMaps.styleJson(context))) { style ->
                val route = if (pack.plan.effectiveHistoricalTrack != null) pack.plan.effectiveWaypoints
                    else GreatCircle.routeWaypoints(pack.plan.effectiveWaypoints)
                // Break lines crossing the date line to avoid a false line across the world.
                val parts = mutableListOf<MutableList<Point>>(mutableListOf())
                route.forEachIndexed { i, p ->
                    if (i > 0 && kotlin.math.abs(p.lon - route[i - 1].lon) > 180) parts.add(mutableListOf())
                    parts.last().add(p.mapPoint())
                }
                style.addSource(GeoJsonSource("route", MultiLineString.fromLngLats(parts.filter { it.size > 1 })))
                style.addLayer(LineLayer("route-shadow", "route").withProperties(lineColor("#8DE0C6"), lineWidth(8f), lineOpacity(0.12f)))
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#8DE0C6"), lineWidth(2.2f), lineOpacity(0.95f)))
                val observedSegments = pack.plan.partialObservationSegments()
                if (observedSegments.isNotEmpty()) {
                    style.addSource(GeoJsonSource("partial-observations", MultiLineString.fromLngLats(
                        observedSegments.map { segment -> segment.map { it.mapPoint() } }
                    )))
                    style.addLayer(LineLayer("partial-observations-line", "partial-observations")
                        .withProperties(lineColor("#F9A8D4"), lineWidth(3.0f)))
                }
                style.addSource(
                    GeoJsonSource(
                        "ends",
                        FeatureCollection.fromFeatures(
                            listOf(
                                Feature.fromGeometry(pack.plan.origin.point.mapPoint()),
                                Feature.fromGeometry(pack.plan.destination.point.mapPoint())
                            )
                        )
                    )
                )
                style.addLayer(CircleLayer("ends", "ends").withProperties(circleColor("#8DE0C6"), circleRadius(5f), circleStrokeColor("#0B1420"), circleStrokeWidth(2f)))

                val overview = PlaceLabels.overview(pack.plan, pack.cities)
                val detail = pack.cities.sortedByDescending { it.population }
                    .distinctBy { kotlin.math.floor(it.point.lat * 2) to kotlin.math.floor(it.point.lon * 2) }
                    .filter { city -> overview.none { it.id == city.id } }.take(450)

                val originCity = City("origin", pack.plan.origin.iata, "", pack.plan.origin.point, pack.plan.origin.timezone, 0)
                val destCity = City("destination", pack.plan.destination.iata, "", pack.plan.destination.point, pack.plan.destination.timezone, 0)
                val selected = (listOf(originCity, destCity) + overview + detail)

                m.addOnMapClickListener { latLng ->
                    val clickPt = GeoPoint(latLng.latitude, latLng.longitude)
                    val screen = m.projection.toScreenLocation(latLng)
                    fun hit(point: GeoPoint): Boolean {
                        val projected = m.projection.toScreenLocation(LatLng(point.lat, point.lon))
                        return kotlin.math.hypot((projected.x - screen.x).toDouble(),
                            (projected.y - screen.y).toDouble()) <= 32 * context.resources.displayMetrics.density
                    }
                    val aircraft = currentAircraft.firstOrNull { hit(it.point) }
                    val peak = offlineContext.peaks.takeIf { m.cameraPosition.zoom >= 3.0 }
                        ?.firstOrNull { hit(it.point) }
                    if (aircraft != null) {
                        selectedCity = null
                        selectedContext = aircraft.schedule.number to (
                            "${aircraft.schedule.originCode} → ${aircraft.schedule.destinationCode}\n" +
                                "${aircraft.evidenceLabel}\n\nAirport-to-airport great-circle estimate from " +
                                "scheduled block time (includes taxi). Actual operation and route unverified.\n\n" +
                                "Source checked ${aircraft.schedule.sourceCheckedOn}: ${aircraft.schedule.sourceUrl}")
                    } else if (peak != null) {
                        selectedCity = null
                        selectedContext = peak.name to "${peak.elevationM} m · ${peak.region}\n\n${peak.fact}"
                    } else {
                        val candidate = selected.minByOrNull { GreatCircle.distanceKm(it.point, clickPt) }
                            ?.takeIf { GreatCircle.distanceKm(it.point, clickPt) <= 150.0 }
                        selectedCity = candidate
                    }
                    true
                }

                offlineContext.peaks.forEachIndexed { index, peak ->
                    style.addImage("peak-$index", labelPillBitmap("▲ ${peak.name}", "${peak.elevationM} m"))
                    style.addSource(GeoJsonSource("peak-$index", peak.point.mapPoint()))
                    style.addLayer(SymbolLayer("peak-$index", "peak-$index").apply { minZoom = 3.0f }
                        .withProperties(iconImage("peak-$index"), iconSize(0.62f), iconAnchor(Property.ICON_ANCHOR_BOTTOM)))
                }
                style.addImage("estimated-plane", planeBitmap(android.graphics.Color.rgb(255, 197, 117)))
                style.addSource(GeoJsonSource("estimated-aircraft", FeatureCollection.fromFeatures(emptyList())))
                style.addLayer(SymbolLayer("estimated-aircraft", "estimated-aircraft").withProperties(
                    iconImage("estimated-plane"), iconSize(0.40f), iconOpacity(0.65f),
                    iconRotate(org.maplibre.android.style.expressions.Expression.get("heading")),
                    textField(org.maplibre.android.style.expressions.Expression.get("label")),
                    textColor("#FFC575"), textSize(12f), textOffset(arrayOf(0f, 1.5f)),
                    textHaloColor("#0B1420"), textHaloWidth(1.5f)
                ))

                selected.forEachIndexed { index, city ->
                    val isOrigin = index == 0
                    val isDest = index == 1
                    val isEndpoint = isOrigin || isDest

                    val title = when {
                        isOrigin -> "🛫 " + pack.plan.origin.iata
                        isDest -> "🛬 " + pack.plan.destination.iata
                        else -> city.name
                    }

                    val subtitle = when {
                        isOrigin -> {
                            val depTime = pack.plan.departure.atZone(ZoneId.of(pack.plan.origin.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
                            val depWx = WeatherParser.nearest(pack.weather[pack.plan.origin.iata].orEmpty(), pack.plan.departure)
                            if (depWx != null) "$depTime · ${depWx.temperature.roundToInt()}°" else "$depTime · DEP"
                        }
                        isDest -> {
                            val arrTime = pack.plan.arrival.atZone(ZoneId.of(pack.plan.destination.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
                            val arrWx = WeatherParser.nearest(pack.weather[pack.plan.destination.iata].orEmpty(), pack.plan.arrival)
                            if (arrWx != null) "$arrTime · ${arrWx.temperature.roundToInt()}°" else "$arrTime · ARR"
                        }
                        else -> {
                            val localTime = flightTime.atZone(ZoneId.of(city.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
                            val wx = WeatherParser.nearest(pack.weather[city.id].orEmpty(), flightTime)
                            if (wx != null) "$localTime · ${wx.temperature.roundToInt()}°" else localTime
                        }
                    }

                    val bitmap = labelPillBitmap(title, subtitle, isEndpoint)
                    style.addImage("city-$index", bitmap)
                    style.addSource(GeoJsonSource("city-$index", city.point.mapPoint()))
                    style.addLayer(
                        SymbolLayer("city-$index", "city-$index").apply {
                            minZoom = if (index < overview.size + 2) 0f else 5.5f
                        }.withProperties(
                            iconImage("city-$index"),
                            iconSize(0.62f),
                            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                            iconAllowOverlap(isEndpoint)
                        )
                    )
                }

                style.addImage("plane", planeBitmap())
                style.addSource(GeoJsonSource("plane", progress.point.mapPoint()))
                style.addLayer(
                    SymbolLayer("plane", "plane").withProperties(
                        iconImage("plane"),
                        iconSize(0.72f),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconRotate(progress.heading.toFloat())
                    )
                )

                styleReady = true
                val points = route.map { LatLng(it.lat.coerceIn(-84.0, 84.0), it.lon) }
                if (points.maxOf { it.longitude } - points.minOf { it.longitude } < 180) {
                    view.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 40, 50, 40, 50)) }
                } else {
                    m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(progress.point.lat, progress.point.lon), 2.0))
                }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(styleReady, progress.point, progress.heading, followPlane, headingUp, nearbyAircraft) {
        if (styleReady) map?.style?.let { style ->
            val estimates = nearbyAircraft.map { aircraft ->
                Feature.fromGeometry(aircraft.point.mapPoint()).apply {
                    addStringProperty("label", "${aircraft.schedule.number} · est.")
                    addNumberProperty("heading", aircraft.heading)
                }
            }
            style.getSourceAs<GeoJsonSource>("estimated-aircraft")?.setGeoJson(FeatureCollection.fromFeatures(estimates))
            style.getSourceAs<GeoJsonSource>("plane")?.setGeoJson(progress.point.mapPoint())
            style.getLayerAs<SymbolLayer>("plane")?.setProperties(iconRotate(progress.heading.toFloat()))

            if (followPlane) {
                val targetLatLng = LatLng(progress.point.lat.coerceIn(-84.0, 84.0), progress.point.lon)
                val targetBearing = if (headingUp) progress.heading else 0.0
                val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(targetLatLng)
                        .bearing(targetBearing)
                        .build()
                )
                map?.animateCamera(cameraUpdate, 500)
            }
        }
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        // Right side control buttons
        Column(Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
            FilledTonalButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(44.dp).semantics { contentDescription = "Zoom in" }
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(44.dp).semantics { contentDescription = "Zoom out" }
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = {
                    followPlane = true
                    val targetLatLng = LatLng(progress.point.lat.coerceIn(-84.0, 84.0), progress.point.lon)
                    val targetBearing = if (headingUp) progress.heading else 0.0
                    map?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(targetLatLng)
                                .zoom(7.5)
                                .bearing(targetBearing)
                                .build()
                        ),
                        700
                    )
                },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.width(66.dp),
                colors = if (followPlane) ButtonDefaults.filledTonalButtonColors(containerColor = Mint, contentColor = Ink) else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text("Plane")
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = {
                    followPlane = false
                    val points = pack.plan.effectiveWaypoints.map { LatLng(it.lat.coerceIn(-84.0, 84.0), it.lon) }
                    if (points.maxOf { it.longitude } - points.minOf { it.longitude } < 180) {
                        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 45))
                    } else {
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(progress.point.lat.coerceIn(-84.0, 84.0), progress.point.lon), 2.0))
                    }
                },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.width(66.dp)
            ) {
                Text("Route")
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(
                onClick = {
                    headingUp = !headingUp
                    val currentCam = map?.cameraPosition
                    if (currentCam != null) {
                        val newBearing = if (headingUp) progress.heading else 0.0
                        map?.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder(currentCam).bearing(newBearing).build()
                            ),
                            400
                        )
                    }
                },
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.width(66.dp),
                colors = if (headingUp) ButtonDefaults.filledTonalButtonColors(containerColor = Mint, contentColor = Ink) else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text(if (headingUp) "Heading" else "North", fontSize = 11.sp)
            }
        }

        // Selected waypoint floating inspection card
        selectedCity?.let { city ->
            val dist = GreatCircle.distanceKm(progress.point, city.point)
            val cityTime = flightTime.atZone(ZoneId.of(city.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
            val forecast = WeatherParser.nearest(pack.weather[city.id].orEmpty(), flightTime)
            val bearingDesc = GreatCircle.relativeBearingDescription(progress.point, progress.heading, city.point)

            Surface(
                color = Panel,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF344857)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(city.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Cream)
                            if (city.country.isNotBlank()) {
                                Text(" · ${city.country}", fontSize = 12.sp, color = Muted)
                            }
                        }
                        Text(
                            "$bearingDesc · %,d km".format(dist.roundToInt()),
                            fontSize = 12.sp,
                            color = Mint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row(
                            Modifier.padding(top = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Local $cityTime (${city.timezone})", fontSize = 11.sp, color = Muted)
                            forecast?.let {
                                Text("${it.temperature.roundToInt()}° · ${WeatherParser.conditionText(it.code)}", fontSize = 11.sp, color = Cream)
                            }
                        }
                    }
                    TextButton(onClick = {
                        followPlane = false
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(city.point.lat, city.point.lon), 8.5), 600)
                    }) {
                        Text("View", fontSize = 12.sp)
                    }
                    IconButton(onClick = { selectedCity = null }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "Dismiss waypoint info", tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    selectedContext?.let { (title, details) ->
        AlertDialog(onDismissRequest = { selectedContext = null }, title = { Text(title) },
            text = { Text(details, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = {
                TextButton(onClick = { selectedContext = null }) { Text("Close") }
            })
    }
}

private fun labelPillBitmap(
    title: String,
    subtitle: String?,
    isEndpoint: Boolean = false
): Bitmap {
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isEndpoint) android.graphics.Color.rgb(141, 224, 198) else android.graphics.Color.rgb(242, 243, 238)
        textSize = if (isEndpoint) 28f else 23f
        typeface = android.graphics.Typeface.create("sans-serif-medium", if (isEndpoint) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isEndpoint) android.graphics.Color.rgb(200, 240, 230) else android.graphics.Color.rgb(141, 224, 198)
        textSize = 17f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    val titleW = titlePaint.measureText(title)
    val subW = subtitle?.let { subPaint.measureText(it) } ?: 0f
    val contentW = maxOf(titleW, subW)
    val padH = 14f
    val boxW = contentW + padH * 2f
    val boxH = if (subtitle != null) 52f else 34f
    val anchorPinH = 8f
    val totalH = boxH + anchorPinH

    val bitmap = Bitmap.createBitmap(boxW.toInt().coerceAtLeast(1), totalH.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 14, 26, 38)
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isEndpoint) android.graphics.Color.argb(220, 141, 224, 198) else android.graphics.Color.argb(160, 52, 72, 87)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    val rect = RectF(1f, 1f, boxW - 1f, boxH - 1f)
    canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
    canvas.drawRoundRect(rect, 10f, 10f, strokePaint)

    // Bottom pointer pin
    val pinPath = Path().apply {
        moveTo(boxW / 2f - 6f, boxH - 1f)
        lineTo(boxW / 2f, totalH - 1f)
        lineTo(boxW / 2f + 6f, boxH - 1f)
        close()
    }
    canvas.drawPath(pinPath, bgPaint)
    canvas.drawPath(pinPath, strokePaint)

    // Draw title
    val titleX = (boxW - titleW) / 2f
    canvas.drawText(title, titleX, if (subtitle != null) 23f else 25f, titlePaint)

    // Draw subtitle (local time + weather)
    if (subtitle != null) {
        val subX = (boxW - subW) / 2f
        canvas.drawText(subtitle, subX, 44f, subPaint)
    }

    return bitmap
}

private fun planeBitmap(colorValue: Int = android.graphics.Color.rgb(246, 246, 240)): Bitmap {
    val bitmap = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888)
    val path = Path().apply {
        moveTo(44f, 4f); lineTo(50f, 12f); lineTo(51f, 34f); lineTo(80f, 55f); lineTo(80f, 63f)
        lineTo(51f, 50f); lineTo(50f, 71f); lineTo(60f, 79f); lineTo(60f, 84f); lineTo(44f, 79f)
        lineTo(28f, 84f); lineTo(28f, 79f); lineTo(38f, 71f); lineTo(37f, 50f); lineTo(8f, 63f)
        lineTo(8f, 55f); lineTo(37f, 34f); lineTo(38f, 12f); close()
    }
    Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorValue; setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK) })
    return bitmap
}

