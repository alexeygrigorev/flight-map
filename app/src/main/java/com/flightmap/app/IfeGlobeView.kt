package com.flightmap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.flightmap.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
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

@Stable
class GlobeCameraState(
    val defaultCenter: GeoPoint
) {
    var cameraLat by mutableDoubleStateOf(defaultCenter.lat)
    var cameraLon by mutableDoubleStateOf(defaultCenter.lon)
    var zoom by mutableDoubleStateOf(1.0)
    var followPlane by mutableStateOf(false)
    var headingUp by mutableStateOf(false)
    var selectedFeature by mutableStateOf<SelectedGlobeFeature?>(null)

    val isDefaultOverview: Boolean
        get() = !followPlane && !headingUp && abs(zoom - 1.0) < 0.08 &&
            hypot(cameraLat - defaultCenter.lat, cameraLon - defaultCenter.lon) < 3.0

    fun zoomIn() {
        zoom = (zoom * 1.32).coerceAtMost(3.6)
    }

    fun zoomOut() {
        zoom = (zoom / 1.32).coerceAtLeast(0.78)
    }

    fun resetToRouteOverview() {
        followPlane = false
        headingUp = false
        selectedFeature = null
        zoom = 1.0
        cameraLat = defaultCenter.lat
        cameraLon = defaultCenter.lon
    }

    fun toggleFollowPlane(progress: FlightProgress, candidateCities: List<City>) {
        followPlane = !followPlane
        if (followPlane) {
            cameraLat = progress.point.lat.coerceIn(-75.0, 72.0)
            cameraLon = progress.point.lon
            if (zoom < 1.25) zoom = 1.35
            val nearestHub = candidateCities
                .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, progress.point) <= 320.0 }
                .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
                ?: candidateCities.minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
            if (nearestHub != null) {
                selectedFeature = SelectedGlobeFeature.CityFeature(nearestHub)
            }
        }
    }

    fun toggleHeadingUp() {
        headingUp = !headingUp
    }

    fun focusPoint(pt: GeoPoint) {
        followPlane = false
        cameraLat = pt.lat.coerceIn(-75.0, 72.0)
        cameraLon = pt.lon
        zoom = maxOf(zoom, 1.55)
    }
}

@Composable
fun rememberGlobeCameraState(plan: FlightPlan): GlobeCameraState {
    val defaultCenter = remember(plan) {
        val wps = plan.effectiveWaypoints
        val samplePt = GreatCircle.interpolateWaypointsWithHeading(wps, 0.28).first
        val lat = (samplePt.lat * 0.65 + 48.5 * 0.35).coerceIn(-65.0, 62.0)
        GeoPoint(lat, samplePt.lon)
    }
    return remember(plan) { GlobeCameraState(defaultCenter) }
}

/**
 * Fullscreen Airline IFE 3D Satellite Earth Globe Visualization.
 *
 * Renders the 3D sphere, curved atmospheric horizon limb, solar terminator,
 * filed ATS trajectory ribbon, aircraft icon, and a decluttered, collision-free
 * set of cities, mountain peaks, landmark pins, and country labels via [GlobeLabelPlanner].
 */
@Composable
fun IfeGlobeView(
    pack: FlightPack,
    progress: FlightProgress,
    countries: List<CountryBoundary>,
    allCities: List<City>,
    cameraState: GlobeCameraState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var viewportSize by remember { mutableStateOf(IntSize(1920, 1080)) }

    val flightTime: Instant = remember(progress.fraction, pack.plan) {
        val totalMs = Duration.between(pack.plan.departure, pack.plan.arrival).toMillis()
        pack.plan.departure.plusMillis((totalMs * progress.fraction).toLong())
    }

    val subsolar = remember(flightTime.epochSecond / 60) {
        SolarCalculator.subsolarPoint(flightTime)
    }

    // Smoothly track aircraft when followPlane is active
    LaunchedEffect(cameraState.followPlane, progress.point.lat, progress.point.lon) {
        if (cameraState.followPlane) {
            cameraState.cameraLat = progress.point.lat.coerceIn(-75.0, 72.0)
            cameraState.cameraLon = progress.point.lon
        }
    }

    val effectiveRotation = if (cameraState.headingUp) -(progress.heading - 90.0) else 0.0

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
        for (c in allCities) {
            if (c.name in majorNames && c.name !in picked) {
                picked[c.name] = c
            }
        }
        for (c in PlaceLabels.overview(pack.plan, pack.cities, 36)) {
            if (c.name !in picked) picked[c.name] = c
        }
        for (c in allCities.take(180)) {
            if (c.name !in picked) picked[c.name] = c
        }
        picked.values.toList()
    }

    var globeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val rasterKey = remember(
        viewportSize.width,
        viewportSize.height,
        (cameraState.cameraLat * 10).roundToInt(),
        (cameraState.cameraLon * 10).roundToInt(),
        (cameraState.zoom * 20).roundToInt(),
        (effectiveRotation * 2).roundToInt(),
        (subsolar.lat * 2).roundToInt(),
        (subsolar.lon * 2).roundToInt()
    ) {
        arrayOf(
            viewportSize.width,
            viewportSize.height,
            (cameraState.cameraLat * 10).roundToInt(),
            (cameraState.cameraLon * 10).roundToInt(),
            (cameraState.zoom * 20).roundToInt(),
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
                centerLat = cameraState.cameraLat,
                centerLon = cameraState.cameraLon,
                zoom = cameraState.zoom,
                headingRotationDeg = effectiveRotation,
                subsolar = subsolar,
                reliefPixels = reliefPixels,
                texW = texW,
                texH = texH
            )
        }
        globeBitmap = rendered
    }

    val projection = remember(
        viewportSize,
        cameraState.cameraLat,
        cameraState.cameraLon,
        cameraState.zoom,
        effectiveRotation
    ) {
        GlobeProjection(
            width = viewportSize.width.toFloat().coerceAtLeast(1f),
            height = viewportSize.height.toFloat().coerceAtLeast(1f),
            centerLat = cameraState.cameraLat,
            centerLon = cameraState.cameraLon,
            zoom = cameraState.zoom,
            headingRotationDeg = effectiveRotation
        )
    }

    val sceneLayout = remember(projection, pack.plan, progress.point, displayCities, countries) {
        GlobeLabelPlanner.plan(
            proj = projection,
            plan = pack.plan,
            progress = progress,
            peaks = IfeCatalog.mountainPeaks,
            pins = IfeCatalog.landmarkPins,
            cities = displayCities,
            countries = countries,
            topSafePx = 68f,
            bottomSafePx = 88f
        )
    }

    val currentProjection by rememberUpdatedState(projection)
    val currentSceneLayout by rememberUpdatedState(sceneLayout)
    val currentDisplayCities by rememberUpdatedState(displayCities)
    val currentPackCities by rememberUpdatedState(pack.cities)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (it.width > 0 && it.height > 0) viewportSize = it }
            .pointerInput(cameraState.zoom) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    cameraState.followPlane = false
                    val degPerPx = (75.0 / maxOf(viewportSize.width, viewportSize.height)) / cameraState.zoom
                    cameraState.cameraLon = (((cameraState.cameraLon - dragAmount.x * degPerPx) + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                    cameraState.cameraLat = (cameraState.cameraLat + dragAmount.y * degPerPx * 0.75).coerceIn(-75.0, 75.0)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val proj = currentProjection
                    val layout = currentSceneLayout
                    var bestFeature: SelectedGlobeFeature? = null
                    var bestDistPx = 56f

                    for (plannedPin in layout.pins) {
                        val d = hypot(plannedPin.x - tapOffset.x, (plannedPin.y - 16f) - tapOffset.y)
                        if (d < bestDistPx) {
                            bestDistPx = d
                            bestFeature = SelectedGlobeFeature.PinFeature(plannedPin.pin)
                        }
                    }

                    for (plannedPeak in layout.peaks) {
                        val d = hypot(plannedPeak.x - tapOffset.x, plannedPeak.y - tapOffset.y)
                        if (d < bestDistPx) {
                            bestDistPx = d
                            bestFeature = SelectedGlobeFeature.PeakFeature(plannedPeak.peak)
                        }
                    }

                    for (plannedCity in layout.cities) {
                        val d = hypot(plannedCity.x - tapOffset.x, plannedCity.y - tapOffset.y)
                        if (d < bestDistPx) {
                            bestDistPx = d
                            bestFeature = SelectedGlobeFeature.CityFeature(plannedCity.city)
                        }
                    }

                    if (bestFeature == null) {
                        proj.unproject(tapOffset.x, tapOffset.y)?.let { (tapLat, tapLon, _) ->
                            val tapGeo = GeoPoint(tapLat, tapLon)
                            val allCandidates = (currentPackCities + currentDisplayCities).distinctBy { it.id }
                            val prominent = allCandidates
                                .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, tapGeo) <= 260.0 }
                                .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                            val fallback = allCandidates
                                .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                                ?.takeIf { GreatCircle.distanceKm(it.point, tapGeo) <= 260.0 }
                            val chosen = prominent ?: fallback
                            if (chosen != null) {
                                bestFeature = SelectedGlobeFeature.CityFeature(chosen)
                            }
                        }
                    }

                    cameraState.selectedFeature = bestFeature
                }
            }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                val w = size.width
                val h = size.height

                // 1. 3D raycast satellite globe + atmospheric limb bitmap
                val bmp = globeBitmap
                if (bmp != null) {
                    val dstRect = RectF(0f, 0f, w, h)
                    val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    canvas.drawBitmap(bmp, null, dstRect, bmpPaint)
                } else {
                    canvas.drawColor(android.graphics.Color.rgb(4, 12, 28))
                }

                // 2. Subtle country borders on the 3D sphere
                drawCountryBorders(canvas, projection, countries)

                // 3. Solar day/night terminator curve
                drawSolarTerminator(canvas, projection, subsolar)

                // 4. Planned/filed trajectory ribbon (flown trail + remaining airway + endpoint markers)
                drawTrajectoryRibbon(canvas, projection, pack.plan, progress, sceneLayout.endpoints)

                // 5. Decluttered, collision-free country names, mountain peaks, cities, and landmark pins
                drawPlannedCountries(canvas, sceneLayout.countries)
                drawPlannedPeaks(canvas, sceneLayout.peaks)
                drawPlannedCities(canvas, sceneLayout.cities)
                drawPlannedPins(canvas, sceneLayout.pins)

                // 6. 3D swept-wing white aircraft icon at current position
                drawAircraftIcon(canvas, projection, progress)
            }
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
                val distFromLimb = sqrt(rSq.toDouble()) - 1.0
                val haloWidth = 0.095 / zoom.coerceAtLeast(0.8)
                val glow = exp(-distFromLimb / haloWidth).coerceIn(0.0, 1.0)
                val glowSq = glow * glow

                val spaceR = (3 + 42 * glowSq + 110 * glow.pow(5.0)).toInt().coerceIn(0, 255)
                val spaceG = (8 + 115 * glowSq + 140 * glow.pow(4.0)).toInt().coerceIn(0, 255)
                val spaceB = (22 + 215 * glow + 35 * glowSq).toInt().coerceIn(0, 255)
                var color = (0xFF shl 24) or (spaceR shl 16) or (spaceG shl 8) or spaceB

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

                    if (baseB > baseR + 18 && baseB > baseG + 10) {
                        baseR = (baseR * 0.85 + 8).toInt().coerceIn(6, 55)
                        baseG = (baseG * 1.05 + 28).toInt().coerceIn(38, 135)
                        baseB = (baseB * 1.18 + 52).toInt().coerceIn(95, 215)
                    } else {
                        baseR = (baseR * 1.14 + 18).toInt().coerceIn(24, 242)
                        baseG = (baseG * 1.14 + 20).toInt().coerceIn(34, 242)
                        baseB = (baseB * 1.08 + 14).toInt().coerceIn(22, 238)
                    }
                }

                val latRad = Math.toRadians(lat)
                val lonRad = Math.toRadians(lon)
                val sunDot = sin(latRad) * sinSunLat + cos(latRad) * cosSunLat * cos(lonRad - sunLonRad)

                val solarFactor = when {
                    sunDot >= 0.08 -> 1.0
                    sunDot <= -0.14 -> 0.56
                    else -> {
                        val t = ((sunDot + 0.14) / 0.22).coerceIn(0.0, 1.0)
                        0.56 + 0.44 * (t * t * (3.0 - 2.0 * t))
                    }
                }

                val limbShade = 0.78 + 0.22 * zDepth.pow(0.42)
                val atmoScatter = (1.0 - zDepth).pow(2.6).coerceIn(0.0, 1.0) * 0.58

                var finalR = (baseR * solarFactor * limbShade * (1.0 - atmoScatter) + 45.0 * atmoScatter).toInt()
                var finalG = (baseG * solarFactor * limbShade * (1.0 - atmoScatter) + 135.0 * atmoScatter).toInt()
                var finalB = (baseB * solarFactor * limbShade * (1.0 - atmoScatter) + 245.0 * atmoScatter).toInt()

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
        color = android.graphics.Color.argb(110, 226, 234, 248)
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
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
    val terminatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 255, 215, 110)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    val sunLatRad = Math.toRadians(subsolar.lat)
    val sunLonRad = Math.toRadians(subsolar.lon)

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

private fun drawPlannedCountries(
    canvas: Canvas,
    countries: List<PlannedCountry>
) {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(195, 226, 234, 248)
        textSize = 18.5f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.argb(215, 0, 0, 0))
    }
    for (country in countries) {
        canvas.drawText(country.name, country.x, country.y, textPaint)
    }
}

private fun drawPlannedPeaks(
    canvas: Canvas,
    peaks: List<PlannedPeak>
) {
    val triFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 204, 34)
        style = Paint.Style.FILL
    }
    val triStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(220, 20, 20, 20)
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 208, 44)
        textSize = 19f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 200, 38)
        textSize = 16.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }

    for (planned in peaks) {
        val peak = planned.peak
        val x = planned.x
        val y = planned.y
        val triPath = Path().apply {
            moveTo(x, y - 7.5f)
            lineTo(x - 7f, y + 5.5f)
            lineTo(x + 7f, y + 5.5f)
            close()
        }
        canvas.drawPath(triPath, triFill)
        canvas.drawPath(triPath, triStroke)

        canvas.drawText(peak.name, x - 10f, y - 2f, labelPaint)
        canvas.drawText("${peak.elevationFt} ft", x - 10f, y + 16f, subPaint)
    }
}

private fun drawPlannedCities(
    canvas: Canvas,
    cities: List<PlannedCity>
) {
    val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(210, 12, 20, 38)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 19.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1.5f, android.graphics.Color.BLACK)
    }

    for (planned in cities) {
        canvas.drawCircle(planned.x, planned.y, 4.0f, dotFill)
        canvas.drawCircle(planned.x, planned.y, 4.0f, dotStroke)
        canvas.drawText(planned.city.name, planned.x + 8.5f, planned.y + 6f, namePaint)
    }
}

private fun drawPlannedPins(
    canvas: Canvas,
    pins: List<PlannedPin>
) {
    val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(37, 33, 94)
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 2f, android.graphics.Color.argb(180, 0, 0, 0))
    }
    val pinBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    for (planned in pins) {
        val x = planned.x
        val y = planned.y
        val r = 12.5f
        val cy = y - 19f
        val pinPath = Path().apply {
            addCircle(x, cy, r, Path.Direction.CW)
            moveTo(x - 6f, cy + r * 0.72f)
            lineTo(x, y)
            lineTo(x + 6f, cy + r * 0.72f)
            close()
        }
        canvas.drawPath(pinPath, pinFill)
        canvas.drawCircle(x, cy, r, pinBorder)

        canvas.drawRect(x - 5f, cy - 2.8f, x - 0.8f, cy + 5f, iconPaint)
        canvas.drawRect(x - 0.8f, cy - 6f, x + 3.2f, cy + 5f, iconPaint)
        canvas.drawLine(x + 5f, cy - 1f, x + 5f, cy + 5f, iconPaint)
    }
}

private fun drawTrajectoryRibbon(
    canvas: Canvas,
    proj: GlobeProjection,
    plan: FlightPlan,
    progress: FlightProgress,
    endpoints: List<PlannedEndpoint>
) {
    val waypoints = plan.effectiveWaypoints
    val densePoints = GreatCircle.routeWaypoints(waypoints, 180)
    if (densePoints.size < 2) return

    val splitIdx = (progress.fraction * (densePoints.size - 1)).roundToInt().coerceIn(0, densePoints.lastIndex)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(95, 4, 12, 28)
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
    }
    val aheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 125, 211, 252)
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        strokeCap = Paint.Cap.ROUND
    }
    val aheadCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(215, 224, 242, 254)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    val flownGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(145, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        strokeCap = Paint.Cap.ROUND
    }
    val flownCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
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

    val endRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    val endFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(37, 99, 235)
        style = Paint.Style.FILL
    }
    val iataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 21f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
    }

    for (ep in endpoints) {
        canvas.drawCircle(ep.x, ep.y, 6.5f, endFillPaint)
        canvas.drawCircle(ep.x, ep.y, 6.5f, endRingPaint)
        canvas.drawText(ep.code, ep.x, ep.y + 25f, iataPaint)
    }
}

private fun drawAircraftIcon(
    canvas: Canvas,
    proj: GlobeProjection,
    progress: FlightProgress
) {
    val p = proj.project(progress.point)
    if (!p.visible) return

    val angleDeg = proj.screenHeadingDegrees(progress.point, progress.heading) + 90f

    canvas.save()
    canvas.translate(p.x, p.y)
    canvas.rotate(angleDeg)

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
