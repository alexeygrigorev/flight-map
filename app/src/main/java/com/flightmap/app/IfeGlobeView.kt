package com.flightmap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flightmap.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Holds the decoded equirectangular satellite relief texture (NASA Blue Marble Next Generation
 * with Topography and Bathymetry) so it is loaded once per process.
 */
object EarthReliefCache {
    @Volatile private var pixels: IntArray? = null
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set

    fun ensureLoaded(context: Context): IntArray? {
        pixels?.let { return it }
        synchronized(this) {
            pixels?.let { return it }
            return runCatching {
                val bmp = context.assets.open("data/earth_relief.jpg").use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: return null
                val w = bmp.width
                val h = bmp.height
                val arr = IntArray(w * h)
                bmp.getPixels(arr, 0, w, 0, 0, w, h)
                bmp.recycle()
                width = w
                height = h
                pixels = arr
                arr
            }.getOrNull()
        }
    }
}

sealed class SelectedGlobeFeature {
    data class CityFeature(val city: City) : SelectedGlobeFeature()
    data class PeakFeature(val peak: MountainPeak) : SelectedGlobeFeature()
    data class PinFeature(val pin: LandmarkPin) : SelectedGlobeFeature()
}

/**
 * Airline IFE 3D Satellite Earth Globe Visualization (matching ~/tmp/agent-reports/flight-map-ref-ife.jpg).
 *
 * Features:
 * - Curved 3D Earth horizon with deep space starfield and luminous blue atmospheric limb at top.
 * - Real NASA Blue Marble satellite topography & bathymetry projected onto the 3D sphere.
 * - Live solar terminator (daylight, twilight, and night-side shading with city-light warmth).
 * - Crisp Natural Earth country boundary lines and country labels.
 * - Iconic mountain peaks with yellow triangles (▲) and elevation in feet.
 * - Featured indigo teardrop landmark pins with white borders and building icons.
 * - Real planned/filed trajectory ribbon (flown + remaining) and 3D swept-wing aircraft icon.
 * - Interactive tap inspection for cities, landmarks, and peaks + pan/zoom/follow controls.
 */
@Composable
fun IfeGlobeView(
    pack: FlightPack,
    progress: FlightProgress,
    countries: List<CountryBoundary>,
    allCities: List<City>,
    onOpenCitiesSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var viewportSize by remember { mutableStateOf(IntSize(1920, 1080)) }

    // Camera state: default overview frames the route across Eurasia like flight-map-ref-ife.jpg
    val defaultCameraCenter = remember(pack.plan) {
        val wps = pack.plan.effectiveWaypoints
        val samplePt = GreatCircle.interpolateWaypointsWithHeading(wps, 0.28).first
        // Bias slightly north/west so Europe, Scandinavia, Black Sea, Caspian, Kazakhstan & Western China
        // all appear simultaneously beneath the curved northern horizon.
        val lat = (samplePt.lat * 0.65 + 48.5 * 0.35).coerceIn(-65.0, 62.0)
        val lon = samplePt.lon
        GeoPoint(lat, lon)
    }

    var cameraLat by remember(pack.plan) { mutableDoubleStateOf(defaultCameraCenter.lat) }
    var cameraLon by remember(pack.plan) { mutableDoubleStateOf(defaultCameraCenter.lon) }
    var zoom by remember(pack.plan) { mutableDoubleStateOf(1.0) }
    var followPlane by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var selectedFeature by remember { mutableStateOf<SelectedGlobeFeature?>(null) }

    val flightTime: Instant = remember(progress.fraction, pack.plan) {
        val totalMs = Duration.between(pack.plan.departure, pack.plan.arrival).toMillis()
        pack.plan.departure.plusMillis((totalMs * progress.fraction).toLong())
    }

    val subsolar = remember(flightTime.epochSecond / 60) {
        SolarCalculator.subsolarPoint(flightTime)
    }

    // Smoothly track aircraft when followPlane is active
    LaunchedEffect(followPlane, progress.point.lat, progress.point.lon) {
        if (followPlane) {
            cameraLat = progress.point.lat.coerceIn(-75.0, 72.0)
            cameraLon = progress.point.lon
        }
    }

    val effectiveRotation = if (headingUp) -(progress.heading - 90.0) else 0.0

    // Pre-select prominent context cities (like Moscow, Stockholm, Oslo, Berlin, London, Paris, Rome,
    // Norilsk, Surgut, Novosibirsk, Yekaterinburg, Kazan, Ufa, Astana, Tashkent, Tehran, Urumqi, Almaty)
    // plus major corridor cities along the real trajectory.
    val displayCities = remember(pack.plan, pack.cities, allCities) {
        val majorNames = setOf(
            "Helsinki", "Tallinn", "Riga", "Vilnius", "Warsaw", "Bucharest", "Tbilisi", "Baku",
            "Aktau", "Kyzylorda", "Shymkent", "Almaty", "Astana", "Tashkent", "Ürümqi", "Urumqi",
            "Turpan", "Hami", "Dunhuang", "Jiayuguan", "Lanzhou", "Xi'an", "Nanyang", "Hefei",
            "Nanjing", "Suzhou", "Shanghai", "Stockholm", "Oslo", "Berlin", "Dublin", "London",
            "Paris", "Rome", "Athens", "Moscow", "Arkhangel'sk", "Arkhangelsk", "Norilsk",
            "Surgut", "Novosibirsk", "Yekaterinburg", "Kazan", "Ufa", "Tehran", "Jerusalem",
            "Beijing", "Ulaanbaatar", "Seoul", "Tokyo", "Istanbul", "Kiev", "Kyiv", "Minsk"
        )
        val picked = LinkedHashMap<String, City>()
        // 1. Named iconic IFE reference cities from global dataset
        for (c in allCities) {
            if (c.name in majorNames && c.name !in picked) {
                picked[c.name] = c
            }
        }
        // 2. Corridor overview cities along the real trajectory
        for (c in PlaceLabels.overview(pack.plan, pack.cities, 42)) {
            if (c.name !in picked) picked[c.name] = c
        }
        // 3. Top global cities by population for zoomed-in context
        for (c in allCities.take(220)) {
            if (c.name !in picked) picked[c.name] = c
        }
        picked.values.toList()
    }

    // Offscreen 3D satellite globe bitmap rendered on background dispatcher
    var globeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Quantize camera parameters slightly for raster caching so sub-pixel ticks don't re-raycast unnecessarily
    val rasterKey = remember(
        viewportSize.width,
        viewportSize.height,
        (cameraLat * 10).roundToInt(),
        (cameraLon * 10).roundToInt(),
        (zoom * 20).roundToInt(),
        (effectiveRotation * 2).roundToInt(),
        (subsolar.lat * 2).roundToInt(),
        (subsolar.lon * 2).roundToInt()
    ) {
        arrayOf(
            viewportSize.width,
            viewportSize.height,
            (cameraLat * 10).roundToInt(),
            (cameraLon * 10).roundToInt(),
            (zoom * 20).roundToInt(),
            (effectiveRotation * 2).roundToInt(),
            (subsolar.lat * 2).roundToInt(),
            (subsolar.lon * 2).roundToInt()
        ).contentHashCode()
    }

    LaunchedEffect(rasterKey) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            val reliefPixels = EarthReliefCache.ensureLoaded(context)
            val texW = EarthReliefCache.width
            val texH = EarthReliefCache.height
            render3DGlobeRaster(
                viewWidth = viewportSize.width,
                viewHeight = viewportSize.height,
                centerLat = cameraLat,
                centerLon = cameraLon,
                zoom = zoom,
                headingRotationDeg = effectiveRotation,
                subsolar = subsolar,
                reliefPixels = reliefPixels,
                texW = texW,
                texH = texH
            )
        }
        globeBitmap = rendered
    }

    val projection = remember(viewportSize, cameraLat, cameraLon, zoom, effectiveRotation) {
        GlobeProjection(
            width = viewportSize.width.toFloat().coerceAtLeast(1f),
            height = viewportSize.height.toFloat().coerceAtLeast(1f),
            centerLat = cameraLat,
            centerLon = cameraLon,
            zoom = zoom,
            headingRotationDeg = effectiveRotation
        )
    }

    val currentProjection by rememberUpdatedState(projection)
    val currentDisplayCities by rememberUpdatedState(displayCities)
    val currentPackCities by rememberUpdatedState(pack.cities)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (it.width > 0 && it.height > 0) viewportSize = it }
            .pointerInput(zoom) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    followPlane = false
                    val degPerPx = (75.0 / maxOf(viewportSize.width, viewportSize.height)) / zoom
                    cameraLon = (((cameraLon - dragAmount.x * degPerPx) + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                    cameraLat = (cameraLat + dragAmount.y * degPerPx * 0.75).coerceIn(-75.0, 75.0)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val proj = currentProjection
                    // Check if user tapped near a landmark pin, mountain peak, or city
                    var bestFeature: SelectedGlobeFeature? = null
                    var bestDistPx = 56f

                    for (pin in IfeCatalog.landmarkPins) {
                        val p = proj.project(pin.point)
                        if (p.visible) {
                            val d = hypot(p.x - tapOffset.x, (p.y - 16f) - tapOffset.y)
                            if (d < bestDistPx) {
                                bestDistPx = d
                                bestFeature = SelectedGlobeFeature.PinFeature(pin)
                            }
                        }
                    }

                    for (peak in IfeCatalog.mountainPeaks) {
                        val p = proj.project(peak.point)
                        if (p.visible) {
                            val d = hypot(p.x - tapOffset.x, p.y - tapOffset.y)
                            if (d < bestDistPx) {
                                bestDistPx = d
                                bestFeature = SelectedGlobeFeature.PeakFeature(peak)
                            }
                        }
                    }

                    val candidateCities = (currentPackCities.take(150) + currentDisplayCities).distinctBy { it.id }
                    for (city in candidateCities) {
                        val p = proj.project(city.point)
                        if (p.visible) {
                            val d = hypot(p.x - tapOffset.x, p.y - tapOffset.y)
                            if (d < bestDistPx) {
                                bestDistPx = d
                                bestFeature = SelectedGlobeFeature.CityFeature(city)
                            }
                        }
                    }

                    // If no specific pin/city was directly under the tap, unproject tap onto globe
                    // and pick the nearest prominent corridor city within 320 km
                    if (bestFeature == null) {
                        proj.unproject(tapOffset.x, tapOffset.y)?.let { (tapLat, tapLon, _) ->
                            val tapGeo = GeoPoint(tapLat, tapLon)
                            val allCandidates = (currentPackCities + currentDisplayCities).distinctBy { it.id }
                            val prominent = allCandidates
                                .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, tapGeo) <= 280.0 }
                                .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                            val fallback = allCandidates
                                .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                                ?.takeIf { GreatCircle.distanceKm(it.point, tapGeo) <= 280.0 }
                            val chosen = prominent ?: fallback
                            if (chosen != null) {
                                bestFeature = SelectedGlobeFeature.CityFeature(chosen)
                            }
                        }
                    }

                    selectedFeature = bestFeature
                }
            }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                val w = size.width
                val h = size.height

                // 1. Draw the 3D raycast satellite globe + atmospheric limb bitmap
                val bmp = globeBitmap
                if (bmp != null) {
                    val dstRect = RectF(0f, 0f, w, h)
                    val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    canvas.drawBitmap(bmp, null, dstRect, bmpPaint)
                } else {
                    canvas.drawColor(android.graphics.Color.rgb(4, 12, 28))
                }

                // 2. Draw crisp country borders on the 3D sphere
                drawCountryBorders(canvas, projection, countries)

                // 3. Draw solar day/night terminator curve
                drawSolarTerminator(canvas, projection, subsolar)

                // 4. Draw country names (Finland, Sweden, Poland, Ukraine, Romania, Türkiye, Kazakhstan, China, etc.)
                drawCountryLabels(canvas, projection, countries, zoom)

                // 5. Draw mountain peaks with yellow triangles (▲) and yellow elevation in feet
                drawMountainPeaks(canvas, projection, IfeCatalog.mountainPeaks)

                // 6. Draw city dots & labels (• Moscow, • Stockholm, • Urumqi, • Almaty, etc.) with collision avoidance
                drawCityDotsAndLabels(canvas, projection, displayCities, pack, flightTime, zoom)

                // 7. Draw featured IFE dark-indigo teardrop landmark pins (matching flight-map-ref-ife.jpg)
                drawLandmarkPins(canvas, projection, IfeCatalog.landmarkPins)

                // 8. Draw the real planned/filed trajectory ribbon (flown trail + remaining airway)
                drawTrajectoryRibbon(canvas, projection, pack.plan, progress)

                // 9. Draw the 3D swept-wing white aircraft icon at current position
                drawAircraftIcon(canvas, projection, pack.plan, progress)
            }
        }

        // Left-edge IFE drawer handle ("||" tab on the left margin, exactly like flight-map-ref-ife.jpg!)
        Surface(
            color = Color(0xEEEDEFF5),
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                .offset(y = (-34).dp)
                .width(24.dp)
                .height(68.dp)
                .semantics { contentDescription = "Along the route" },
            onClick = onOpenCitiesSheet
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "||",
                    color = Color(0xFF1E2246),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Right-side Zoom & Route Controls (respecting landscape safeDrawing nav-bar insets)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(top = 66.dp, end = 14.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                onClick = { zoom = (zoom * 1.32).coerceAtMost(3.6) },
                shape = CircleShape,
                color = Color(0xCC1E1B4B),
                border = BorderStroke(1.2.dp, Color(0xCCFFFFFF)),
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Zoom in" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }

            Surface(
                onClick = { zoom = (zoom / 1.32).coerceAtLeast(0.78) },
                shape = CircleShape,
                color = Color(0xCC1E1B4B),
                border = BorderStroke(1.2.dp, Color(0xCCFFFFFF)),
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Zoom out" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("−", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }

            Surface(
                onClick = {
                    followPlane = false
                    headingUp = false
                    selectedFeature = null
                    zoom = 1.0
                    cameraLat = defaultCameraCenter.lat
                    cameraLon = defaultCameraCenter.lon
                },
                shape = RoundedCornerShape(18.dp),
                color = if (!followPlane && abs(zoom - 1.0) < 0.1) Color(0xFF3949AB) else Color(0xCC1E1B4B),
                border = BorderStroke(1.2.dp, Color(0xCCFFFFFF)),
                modifier = Modifier.height(32.dp)
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("Route", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Bottom-right twin circular indigo IFE buttons positioned cleanly above the bottom HUD bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
                .padding(end = 14.dp, bottom = 148.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Button 1: Aircraft camera follow button ("Plane")
            Surface(
                onClick = {
                    followPlane = !followPlane
                    if (followPlane) {
                        cameraLat = progress.point.lat.coerceIn(-75.0, 72.0)
                        cameraLon = progress.point.lon
                        if (zoom < 1.25) zoom = 1.35
                        // Highlight nearest prominent overflown hub when engaging follow mode
                        val candidates = (pack.cities + displayCities).distinctBy { it.id }
                        val nearestHub = candidates
                            .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, progress.point) <= 320.0 }
                            .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
                            ?: candidates.minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
                        if (nearestHub != null) {
                            selectedFeature = SelectedGlobeFeature.CityFeature(nearestHub)
                        }
                    }
                },
                shape = CircleShape,
                color = if (followPlane) Color(0xFF3B82F6) else Color(0xE6221E54),
                border = BorderStroke(1.5.dp, Color.White),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Plane" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Flight,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                    Text("Plane", fontSize = 1.sp, color = Color.Transparent)
                }
            }

            // Button 2: Compass / Heading-Up orientation button ("North" / "Heading")
            Surface(
                onClick = { headingUp = !headingUp },
                shape = CircleShape,
                color = if (headingUp) Color(0xFF3B82F6) else Color(0xE6221E54),
                border = BorderStroke(1.5.dp, Color.White),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = if (headingUp) "Heading" else "North" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Explore,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(if (headingUp) "Heading" else "North", fontSize = 1.sp, color = Color.Transparent)
                }
            }
        }

        // Selected city / landmark / mountain peak floating inspection card
        selectedFeature?.let { feat ->
            SelectedFeatureOverlayCard(
                feature = feat,
                pack = pack,
                progress = progress,
                flightTime = flightTime,
                onFocusPoint = { pt ->
                    followPlane = false
                    cameraLat = pt.lat.coerceIn(-75.0, 72.0)
                    cameraLon = pt.lon
                    zoom = maxOf(zoom, 1.55)
                },
                onDismiss = { selectedFeature = null },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(top = 64.dp, start = 40.dp)
                    .widthIn(max = 360.dp)
            )
        }
    }
}

@Composable
private fun SelectedFeatureOverlayCard(
    feature: SelectedGlobeFeature,
    pack: FlightPack,
    progress: FlightProgress,
    flightTime: Instant,
    onFocusPoint: (GeoPoint) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val title: String
    val subtitle: String
    val point: GeoPoint
    val timezone: String?
    val fact: String
    val weather: WeatherHour?

    when (feature) {
        is SelectedGlobeFeature.CityFeature -> {
            val c = feature.city
            title = c.name
            subtitle = IfeCatalog.countryDisplayName(c.country)
            point = c.point
            timezone = c.timezone
            fact = IfeCatalog.cityFact(c)
            weather = WeatherParser.nearest(pack.weather[c.id].orEmpty(), flightTime)
                ?: SolarCalculator.estimateSurfaceWeather(c.point, flightTime)
        }
        is SelectedGlobeFeature.PeakFeature -> {
            val p = feature.peak
            title = "▲ ${p.name}"
            subtitle = "${"%,d".format(p.elevationFt)} ft (${"%,d".format(p.elevationM)} m) · ${p.region}"
            point = p.point
            timezone = null
            fact = p.fact
            weather = null
        }
        is SelectedGlobeFeature.PinFeature -> {
            val pin = feature.pin
            title = pin.name
            subtitle = pin.country
            point = pin.point
            timezone = pin.timezone
            fact = pin.fact
            val nearestCityWithWx = pack.cities
                .filter { it.id in pack.weather }
                .minByOrNull { GreatCircle.distanceKm(it.point, pin.point) }
            weather = nearestCityWithWx?.let { WeatherParser.nearest(pack.weather[it.id].orEmpty(), flightTime) }
                ?: SolarCalculator.estimateSurfaceWeather(pin.point, flightTime)
        }
    }

    val distKm = GreatCircle.distanceKm(progress.point, point).roundToInt()
    val bearingDesc = GreatCircle.relativeBearingDescription(progress.point, progress.heading, point)
    val dayNight = SolarCalculator.dayNightLabel(point, flightTime)
    val localClock = timezone?.let { tz ->
        runCatching {
            val clock = flightTime.atZone(ZoneId.of(tz)).format(timeFormatter)
            val offset = SolarCalculator.utcOffsetLabel(tz, flightTime)
            "$clock ($offset)"
        }.getOrNull()
    }

    Surface(
        color = Color(0xEE111936),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0x887C93C3)),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color(0xFF93C5FD), fontSize = 11.sp)
                }
                TextButton(
                    onClick = { onFocusPoint(point) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("View", color = Mint, fontSize = 12.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Outlined.Close, "Dismiss waypoint info", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
            Text(
                "$bearingDesc · %,d km from aircraft".format(distKm),
                color = Mint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (localClock != null) {
                    Text("Local $localClock", color = Color(0xFFDbeafe), fontSize = 11.sp)
                }
                Text(dayNight, color = Color(0xFFFDE68A), fontSize = 11.sp)
                if (weather != null) {
                    Text(
                        "${weather.temperature.roundToInt()}°C · ${WeatherParser.conditionText(weather.code)}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                fact,
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

/**
 * Raycasts the 3D globe into an offscreen bitmap buffer with:
 * - Outer space & deterministic stars above the curved Earth limb
 * - Glowing blue atmospheric horizon halo (matching flight-map-ref-ife.jpg)
 * - Equirectangular NASA Blue Marble satellite relief + bathymetry sampling
 * - Solar day/night illumination & twilight transition
 */
private fun render3DGlobeRaster(
    viewWidth: Int,
    viewHeight: Int,
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    headingRotationDeg: Double,
    subsolar: GeoPoint,
    reliefPixels: IntArray?,
    texW: Int,
    texH: Int
): Bitmap {
    // Render at ~420p internal resolution and scale smoothly with bilinear filter
    val targetW = 520
    val targetH = ((viewHeight.toDouble() / viewWidth.toDouble()) * targetW).toInt().coerceIn(220, 520)
    val proj = GlobeProjection(
        width = targetW.toFloat(),
        height = targetH.toFloat(),
        centerLat = centerLat,
        centerLon = centerLon,
        zoom = zoom,
        headingRotationDeg = headingRotationDeg
    )

    val outPixels = IntArray(targetW * targetH)
    val cx = proj.sphereCenterX
    val cy = proj.sphereCenterY
    val r = proj.radius

    val sunLatRad = Math.toRadians(subsolar.lat)
    val sunLonRad = Math.toRadians(subsolar.lon)
    val sinSunLat = sin(sunLatRad)
    val cosSunLat = cos(sunLatRad)

    for (py in 0 until targetH) {
        val rowOffset = py * targetW
        val dy = (cy - py) / r
        for (px in 0 until targetW) {
            val dx = (px - cx) / r
            val rSq = dx * dx + dy * dy

            if (rSq > 1.0f) {
                // Outer space + blue atmospheric limb glow above the curved horizon
                val distFromLimb = sqrt(rSq.toDouble()) - 1.0
                val haloWidth = 0.095 / zoom.coerceAtLeast(0.8)
                val glow = exp(-distFromLimb / haloWidth).coerceIn(0.0, 1.0)
                val glowSq = glow * glow

                // Space base gradient + atmospheric Rayleigh scatter
                val spaceR = (3 + 42 * glowSq + 110 * glow.pow(5.0)).toInt().coerceIn(0, 255)
                val spaceG = (8 + 115 * glowSq + 140 * glow.pow(4.0)).toInt().coerceIn(0, 255)
                val spaceB = (22 + 215 * glow + 35 * glowSq).toInt().coerceIn(0, 255)
                var color = (0xFF shl 24) or (spaceR shl 16) or (spaceG shl 8) or spaceB

                // Subtle starfield in deep space where glow is low
                if (glow < 0.28) {
                    val hash = ((px * 73856093) xor (py * 19349663)) and 0x7FFFFFFF
                    if (hash % 397 == 0) {
                        val starBright = 150 + (hash % 95)
                        color = (0xFF shl 24) or (starBright shl 16) or (starBright shl 8) or (starBright + 10).coerceAtMost(255)
                    }
                }
                outPixels[rowOffset + px] = color
            } else {
                val unproj = proj.unproject(px.toFloat(), py.toFloat())
                if (unproj == null) {
                    outPixels[rowOffset + px] = (0xFF shl 24) or (4 shl 16) or (12 shl 8) or 28
                    continue
                }
                val (lat, lon, zDepth) = unproj

                // Sample NASA Blue Marble equirectangular relief bitmap
                var baseR = 12
                var baseG = 58
                var baseB = 122

                if (reliefPixels != null && texW > 0 && texH > 0) {
                    val u = (((lon + 180.0) / 360.0) * (texW - 1)).toInt().coerceIn(0, texW - 1)
                    val v = (((90.0 - lat) / 180.0) * (texH - 1)).toInt().coerceIn(0, texH - 1)
                    val argb = reliefPixels[v * texW + u]
                    baseR = (argb shr 16) and 0xFF
                    baseG = (argb shr 8) and 0xFF
                    baseB = argb and 0xFF

                    // Boost ocean sapphire richness & terrain clarity to match IFE display
                    if (baseB > baseR + 18 && baseB > baseG + 10) {
                        // Ocean water pixel: rich IFE deep cobalt-sapphire
                        baseR = (baseR * 0.85 + 8).toInt().coerceIn(6, 55)
                        baseG = (baseG * 1.05 + 28).toInt().coerceIn(38, 135)
                        baseB = (baseB * 1.18 + 52).toInt().coerceIn(95, 215)
                    } else {
                        // Land terrain pixel: lift midtones so forests, plains, steppes & peaks pop
                        baseR = (baseR * 1.14 + 18).toInt().coerceIn(24, 242)
                        baseG = (baseG * 1.14 + 20).toInt().coerceIn(34, 242)
                        baseB = (baseB * 1.08 + 14).toInt().coerceIn(22, 238)
                    }
                }

                // Solar illumination (Day / Twilight / Night terminator)
                val latRad = Math.toRadians(lat)
                val lonRad = Math.toRadians(lon)
                val sunDot = sin(latRad) * sinSunLat + cos(latRad) * cosSunLat * cos(lonRad - sunLonRad)

                // Keep night side clearly visible (0.56 ambient) so IFE terrain & labels stay vibrant
                val solarFactor = when {
                    sunDot >= 0.08 -> 1.0
                    sunDot <= -0.14 -> 0.56
                    else -> {
                        val t = ((sunDot + 0.14) / 0.22).coerceIn(0.0, 1.0)
                        0.56 + 0.44 * (t * t * (3.0 - 2.0 * t))
                    }
                }

                // 3D spherical limb shading + blue atmospheric edge scatter near the curved horizon
                val limbShade = 0.78 + 0.22 * zDepth.pow(0.42)
                val atmoScatter = (1.0 - zDepth).pow(2.6).coerceIn(0.0, 1.0) * 0.58

                var finalR = (baseR * solarFactor * limbShade * (1.0 - atmoScatter) + 45.0 * atmoScatter).toInt()
                var finalG = (baseG * solarFactor * limbShade * (1.0 - atmoScatter) + 135.0 * atmoScatter).toInt()
                var finalB = (baseB * solarFactor * limbShade * (1.0 - atmoScatter) + 245.0 * atmoScatter).toInt()

                // Slight cool night tint on night hemisphere
                if (sunDot < -0.02) {
                    val nightBlend = ((-sunDot - 0.02) / 0.35).coerceIn(0.0, 0.25)
                    finalB = (finalB * (1.0 - nightBlend) + (finalB + 28) * nightBlend).toInt()
                }

                finalR = finalR.coerceIn(0, 255)
                finalG = finalG.coerceIn(0, 255)
                finalB = finalB.coerceIn(0, 255)

                outPixels[rowOffset + px] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }
    }

    return Bitmap.createBitmap(outPixels, targetW, targetH, Bitmap.Config.ARGB_8888)
}

private fun drawCountryBorders(
    canvas: Canvas,
    proj: GlobeProjection,
    countries: List<CountryBoundary>
) {
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(165, 232, 238, 248)
        style = Paint.Style.STROKE
        strokeWidth = 1.25f
        strokeJoin = Paint.Join.ROUND
    }
    val path = Path()
    for (country in countries) {
        for (ring in country.rings) {
            path.reset()
            var penDown = false
            var prevX = 0f
            var prevY = 0f
            for (pt in ring) {
                val p = proj.project(pt)
                if (p.visible && p.z > 0.06) {
                    if (!penDown || hypot((p.x - prevX).toDouble(), (p.y - prevY).toDouble()) > proj.radius * 0.25f) {
                        path.moveTo(p.x, p.y)
                        penDown = true
                    } else {
                        path.lineTo(p.x, p.y)
                    }
                    prevX = p.x
                    prevY = p.y
                } else {
                    penDown = false
                }
            }
            if (!path.isEmpty) {
                canvas.drawPath(path, borderPaint)
            }
        }
    }
}

private fun drawSolarTerminator(
    canvas: Canvas,
    proj: GlobeProjection,
    subsolar: GeoPoint
) {
    // Draw a subtle golden-blue solar terminator line where solar altitude == 0
    val terminatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(95, 255, 215, 110)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    val sunLatRad = Math.toRadians(subsolar.lat)
    val sunLonRad = Math.toRadians(subsolar.lon)
    // Construct great circle orthogonal to subsolar vector
    val sx = cos(sunLatRad) * cos(sunLonRad)
    val sy = cos(sunLatRad) * sin(sunLonRad)
    val sz = sin(sunLatRad)

    // Basis vectors u, v orthogonal to (sx, sy, sz)
    val ux = -sin(sunLonRad)
    val uy = cos(sunLonRad)
    val uz = 0.0
    val vx = -sin(sunLatRad) * cos(sunLonRad)
    val vy = -sin(sunLatRad) * sin(sunLonRad)
    val vz = cos(sunLatRad)

    val path = Path()
    var penDown = false
    for (deg in 0..360 step 4) {
        val theta = Math.toRadians(deg.toDouble())
        val cosT = cos(theta)
        val sinT = sin(theta)
        val px = ux * cosT + vx * sinT
        val py = uy * cosT + vy * sinT
        val pz = uz * cosT + vz * sinT
        val lat = Math.toDegrees(asin(pz.coerceIn(-1.0, 1.0)))
        val lon = Math.toDegrees(atan2(py, px))
        val p = proj.project(GeoPoint(lat, lon))
        if (p.visible && p.z > 0.08) {
            if (!penDown) {
                path.moveTo(p.x, p.y)
                penDown = true
            } else {
                path.lineTo(p.x, p.y)
            }
        } else {
            penDown = false
        }
    }
    if (!path.isEmpty) {
        canvas.drawPath(path, terminatorPaint)
    }
}

private fun drawCountryLabels(
    canvas: Canvas,
    proj: GlobeProjection,
    countries: List<CountryBoundary>,
    zoom: Double
) {
    val priorityCountries = setOf(
        "Finland", "Sweden", "Norway", "Denmark", "United Kingdom", "Germany", "Poland",
        "Belarus", "Ukraine", "Romania", "Bulgaria", "Austria", "France", "Italy", "Greece",
        "Türkiye", "Cyprus", "Kazakhstan", "Turkmenistan", "Uzbekistan", "Iran", "Iraq",
        "Afghanistan", "China", "Mongolia", "India", "Pakistan", "Georgia", "Azerbaijan",
        "Estonia", "Latvia", "Lithuania", "Serbia", "Bosnia and Herzegovina", "Albania"
    )
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(230, 240, 244, 252)
        textSize = 21f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.argb(230, 0, 0, 0))
    }

    val placedBoxes = mutableListOf<RectF>()
    val candidates = countries.filter {
        it.name in priorityCountries || (zoom >= 1.3 && it.population >= 8_000_000)
    }.sortedByDescending { if (it.name in priorityCountries) it.population + 500_000_000L else it.population }

    for (country in candidates) {
        val p = proj.project(country.labelPoint)
        if (!p.visible || p.z < 0.22) continue
        if (p.x < 40f || p.x > proj.width - 40f || p.y < proj.horizonTopY + 25f || p.y > proj.height - 70f) continue

        val tw = textPaint.measureText(country.name)
        val box = RectF(p.x - tw / 2f - 8f, p.y - 16f, p.x + tw / 2f + 8f, p.y + 8f)
        if (placedBoxes.any { RectF.intersects(it, box) }) continue
        placedBoxes.add(box)
        canvas.drawText(country.name, p.x, p.y, textPaint)
    }
}

private fun drawMountainPeaks(
    canvas: Canvas,
    proj: GlobeProjection,
    peaks: List<MountainPeak>
) {
    val triFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 204, 34)
        style = Paint.Style.FILL
    }
    val triStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 20, 20, 20)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 208, 44)
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 200, 38)
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }

    for (peak in peaks) {
        val p = proj.project(peak.point)
        if (!p.visible || p.z < 0.14) continue
        if (p.x < 70f || p.x > proj.width - 50f || p.y < proj.horizonTopY + 15f || p.y > proj.height - 65f) continue

        // Draw yellow mountain triangle (▲)
        val triPath = Path().apply {
            moveTo(p.x, p.y - 8f)
            lineTo(p.x - 7.5f, p.y + 6f)
            lineTo(p.x + 7.5f, p.y + 6f)
            close()
        }
        canvas.drawPath(triPath, triFill)
        canvas.drawPath(triPath, triStroke)

        // Draw peak name & elevation in ft (just like flight-map-ref-ife.jpg: "Mount Narodnaya / 6214 ft")
        canvas.drawText(peak.name, p.x - 10f, p.y - 2f, labelPaint)
        canvas.drawText("${peak.elevationFt} ft", p.x - 10f, p.y + 18f, subPaint)
    }
}

private fun drawCityDotsAndLabels(
    canvas: Canvas,
    proj: GlobeProjection,
    cities: List<City>,
    pack: FlightPack,
    flightTime: Instant,
    zoom: Double
) {
    val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(210, 12, 20, 38)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(165, 243, 252)
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, android.graphics.Color.BLACK)
    }

    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val placed = mutableListOf<RectF>()

    for (city in cities) {
        val p = proj.project(city.point)
        if (!p.visible || p.z < 0.16) continue
        if (p.x < 45f || p.x > proj.width - 95f || p.y < proj.horizonTopY + 12f || p.y > proj.height - 65f) continue

        val label = city.name
        val tw = namePaint.measureText(label)
        val rect = RectF(p.x - 6f, p.y - 18f, p.x + tw + 18f, p.y + 16f)
        if (placed.any { RectF.intersects(it, rect) }) continue
        placed.add(rect)

        // White city dot "•"
        canvas.drawCircle(p.x, p.y, 4.2f, dotFill)
        canvas.drawCircle(p.x, p.y, 4.2f, dotStroke)
        canvas.drawText(label, p.x + 8.5f, p.y + 6f, namePaint)

        // When zoomed in or for corridor cities with weather, show local time & temperature below
        if (zoom >= 1.25 || city.id in pack.weather) {
            val wx = WeatherParser.nearest(pack.weather[city.id].orEmpty(), flightTime)
            if (wx != null && zoom >= 1.18) {
                val localT = runCatching { flightTime.atZone(ZoneId.of(city.timezone)).format(timeFmt) }.getOrDefault("")
                canvas.drawText("$localT · ${wx.temperature.roundToInt()}°", p.x + 8.5f, p.y + 22f, subPaint)
            }
        }
    }
}

private fun drawLandmarkPins(
    canvas: Canvas,
    proj: GlobeProjection,
    pins: List<LandmarkPin>
) {
    val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(37, 33, 94) // Deep IFE indigo (#25215E)
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 2f, android.graphics.Color.argb(180, 0, 0, 0))
    }
    val pinBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }

    for (pin in pins) {
        val p = proj.project(pin.point)
        if (!p.visible || p.z < 0.18) continue
        if (p.x < 40f || p.x > proj.width - 40f || p.y < proj.horizonTopY + 35f || p.y > proj.height - 65f) continue

        val r = 13.5f
        val cy = p.y - 20f
        val pinPath = Path().apply {
            addCircle(p.x, cy, r, Path.Direction.CW)
            moveTo(p.x - 6.5f, cy + r * 0.72f)
            lineTo(p.x, p.y)
            lineTo(p.x + 6.5f, cy + r * 0.72f)
            close()
        }
        canvas.drawPath(pinPath, pinFill)
        canvas.drawCircle(p.x, cy, r, pinBorder)

        // Mini building / monument icon inside the indigo pin (matching flight-map-ref-ife.jpg)
        canvas.drawRect(p.x - 5.5f, cy - 3f, p.x - 1f, cy + 5.5f, iconPaint)
        canvas.drawRect(p.x - 1f, cy - 6.5f, p.x + 3.5f, cy + 5.5f, iconPaint)
        canvas.drawLine(p.x + 5.5f, cy - 1f, p.x + 5.5f, cy + 5.5f, iconPaint)
    }
}

private fun drawTrajectoryRibbon(
    canvas: Canvas,
    proj: GlobeProjection,
    plan: FlightPlan,
    progress: FlightProgress
) {
    val waypoints = plan.effectiveWaypoints
    val densePoints = GreatCircle.routeWaypoints(waypoints, 180)
    if (densePoints.size < 2) return

    val splitIdx = (progress.fraction * (densePoints.size - 1)).roundToInt().coerceIn(0, densePoints.lastIndex)

    // 1. Ground shadow under the trajectory
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(95, 4, 12, 28)
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    // 2. Remaining planned trajectory (ahead of aircraft)
    val aheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 125, 211, 252)
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
    }
    val aheadCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(215, 224, 242, 254)
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
    }
    // 3. Flown trajectory (behind aircraft)
    val flownGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(145, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 8.5f
        strokeCap = Paint.Cap.ROUND
    }
    val flownCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.4f
        strokeCap = Paint.Cap.ROUND
    }

    fun buildProjectedPath(points: List<GeoPoint>, yOffset: Float = 0f): Path {
        val path = Path()
        var penDown = false
        for (pt in points) {
            val p = proj.project(pt)
            if (p.visible) {
                if (!penDown) {
                    path.moveTo(p.x, p.y + yOffset)
                    penDown = true
                } else {
                    path.lineTo(p.x, p.y + yOffset)
                }
            } else {
                penDown = false
            }
        }
        return path
    }

    val fullShadowPath = buildProjectedPath(densePoints, yOffset = 4f)
    if (!fullShadowPath.isEmpty) canvas.drawPath(fullShadowPath, shadowPaint)

    if (splitIdx < densePoints.lastIndex) {
        val aheadPts = densePoints.subList(splitIdx, densePoints.size)
        val aheadPath = buildProjectedPath(aheadPts)
        if (!aheadPath.isEmpty) {
            canvas.drawPath(aheadPath, aheadGlowPaint)
            canvas.drawPath(aheadPath, aheadCorePaint)
        }
    }

    if (splitIdx > 0) {
        val flownPts = densePoints.subList(0, splitIdx + 1)
        val flownPath = buildProjectedPath(flownPts)
        if (!flownPath.isEmpty) {
            canvas.drawPath(flownPath, flownGlowPaint)
            canvas.drawPath(flownPath, flownCorePaint)
        }
    }

    // Draw Origin ("HEL") and Destination ("PVG") airport rings & callouts
    val endRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
    }
    val endFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(37, 99, 235)
        style = Paint.Style.FILL
    }
    val iataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
    }

    listOf(plan.origin to plan.origin.iata, plan.destination to plan.destination.iata).forEach { (airport, code) ->
        val p = proj.project(airport.point)
        if (p.visible) {
            canvas.drawCircle(p.x, p.y, 7f, endFillPaint)
            canvas.drawCircle(p.x, p.y, 7f, endRingPaint)
            canvas.drawText(code, p.x, p.y + 27f, iataPaint)
        }
    }
}

private fun drawAircraftIcon(
    canvas: Canvas,
    proj: GlobeProjection,
    plan: FlightPlan,
    progress: FlightProgress
) {
    val p = proj.project(progress.point)
    if (!p.visible) return

    val angleDeg = proj.screenHeadingDegrees(progress.point, progress.heading) + 90f

    canvas.save()
    canvas.translate(p.x, p.y)
    canvas.rotate(angleDeg)

    // Swept-wing A350 silhouette (pointing up at 0 deg before rotation)
    val planePath = Path().apply {
        moveTo(0f, -24f)
        lineTo(3.2f, -17f)
        lineTo(3.8f, -5f)
        lineTo(23f, 8f)
        lineTo(23f, 12.5f)
        lineTo(3.8f, 4.5f)
        lineTo(3.2f, 16f)
        lineTo(10.5f, 21f)
        lineTo(10.5f, 24f)
        lineTo(0f, 20.5f)
        lineTo(-10.5f, 24f)
        lineTo(-10.5f, 21f)
        lineTo(-3.2f, 16f)
        lineTo(-3.8f, 4.5f)
        lineTo(-23f, 12.5f)
        lineTo(-23f, 8f)
        lineTo(-3.8f, -5f)
        lineTo(-3.2f, -17f)
        close()
    }

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.save()
    canvas.translate(3f, 5f)
    canvas.drawPath(planePath, shadowPaint)
    canvas.restore()

    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 2f, android.graphics.Color.argb(220, 0, 0, 0))
    }
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(30, 41, 59)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    canvas.drawPath(planePath, bodyPaint)
    canvas.drawPath(planePath, outlinePaint)
    canvas.restore()
}
