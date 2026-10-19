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

    var reliefReady by remember { mutableStateOf(EarthReliefCache.width > 0) }
    LaunchedEffect(Unit) {
        if (!reliefReady) {
            withContext(Dispatchers.IO) {
                EarthReliefCache.ensureLoaded(context)
            }
            reliefReady = EarthReliefCache.width > 0
        }
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

    // Render the 3D globe raster synchronously in lockstep with `projection` using parallel CPU workers
    // so vector borders, route lines, and satellite relief NEVER lag or drift apart during pan/zoom.
    val globeBitmap = remember(
        projection,
        (subsolar.lat * 4).roundToInt(),
        (subsolar.lon * 4).roundToInt(),
        reliefReady
    ) {
        val reliefPixels = if (reliefReady) EarthReliefCache.ensureLoaded(context) else null
        render3DGlobeRaster(
            proj = projection,
            subsolar = subsolar,
            reliefPixels = reliefPixels,
            texW = EarthReliefCache.width,
            texH = EarthReliefCache.height
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
            bottomSafePx = if (projection.isPortrait) 136f else 88f
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
            .pointerInput(Unit) {
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

                // 1. 3D raycast satellite globe + atmospheric limb bitmap (rendered with identical `projection`)
                val dstRect = RectF(0f, 0f, w, h)
                val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(globeBitmap, null, dstRect, bmpPaint)

                // 2. Native full-DPI anti-aliased atmospheric horizon limb ring (eliminates any upscale staircase blocks)
                drawNativeHorizonLimb(canvas, projection)

                // 3. Subtle country borders clipped strictly to the 3D sphere disc
                drawCountryBorders(canvas, projection, countries)

                // 4. Solar day/night terminator curve
                drawSolarTerminator(canvas, projection, subsolar)

                // 5. Planned/filed trajectory ribbon (flown trail + remaining airway + endpoint markers)
                drawTrajectoryRibbon(canvas, projection, pack.plan, progress, sceneLayout.endpoints)

                // 6. Decluttered, collision-free country names, mountain peaks, cities, and landmark pins
                drawPlannedCountries(canvas, sceneLayout.countries)
                drawPlannedPeaks(canvas, sceneLayout.peaks)
                drawPlannedCities(canvas, sceneLayout.cities)
                drawPlannedPins(canvas, sceneLayout.pins)

                // 7. 3D swept-wing white aircraft icon at current position
                drawAircraftIcon(canvas, projection, progress)
            }
        }
    }
}

private fun drawNativeHorizonLimb(canvas: Canvas, proj: GlobeProjection) {
    val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(68, 92, 188, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
    }
    val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(145, 168, 224, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    canvas.drawCircle(proj.sphereCenterX, proj.sphereCenterY, proj.radius + 1.5f, outerGlowPaint)
    canvas.drawCircle(proj.sphereCenterX, proj.sphereCenterY, proj.radius, innerRimPaint)
}

/**
 * Fast polynomial approximation of asin(x) in degrees, max error < 0.004°.
 */
private fun fastAsinDeg(x: Double): Double {
    val neg = x < 0.0
    val ax = if (neg) -x else x
    val clamped = if (ax > 1.0) 1.0 else ax
    val poly = ((-0.0187293 * clamped + 0.0742610) * clamped - 0.2121144) * clamped + 1.5707288
    val rad = 1.5707963267948966 - sqrt(1.0 - clamped) * poly
    val deg = rad * 57.29577951308232
    return if (neg) -deg else deg
}

/**
 * Fast atan2(y, x) in degrees, max error < 0.005°.
 */
private fun fastAtan2Deg(y: Double, x: Double): Double {
    if (x == 0.0 && y == 0.0) return 0.0
    val ay = abs(y)
    val ax = abs(x)
    val swap = ay > ax
    val z = if (swap) ax / ay else ay / ax
    val z2 = z * z
    val a = ((0.0776509570923569 * z2 - 0.287434475393028) * z2 + 0.995181681698119) * z
    var rad = if (swap) (1.5707963267948966 - a) else a
    if (x < 0.0) rad = 3.141592653589793 - rad
    if (y < 0.0) rad = -rad
    return rad * 57.29577951308232
}

/**
 * Raycasts the 3D globe into an offscreen bitmap buffer using the EXACT screen [proj]
 * (eliminating any aspect-ratio or horizonTopY mismatch), bilinear texture filtering,
 * smooth anti-aliased horizon blending, and parallel multi-core row execution.
 */
private fun render3DGlobeRaster(
    proj: GlobeProjection,
    subsolar: GeoPoint,
    reliefPixels: IntArray?,
    texW: Int,
    texH: Int
): Bitmap {
    val isPortrait = proj.isPortrait
    val targetW = if (isPortrait) 540 else 820
    val targetH = ((proj.height.toDouble() / proj.width.toDouble()) * targetW).roundToInt().coerceIn(320, 860)

    val outPixels = IntArray(targetW * targetH)
    val cx = proj.sphereCenterX.toDouble()
    val cy = proj.sphereCenterY.toDouble()
    val r = proj.radius.toDouble()
    val invR = 1.0 / r
    val scaleX = proj.width.toDouble() / targetW.toDouble()
    val scaleY = proj.height.toDouble() / targetH.toDouble()

    // Anti-aliasing band width in normalized radius units (~1.8 raster pixels)
    val aaBand = (1.8 * maxOf(scaleX, scaleY)) * invR
    val rInner = (1.0 - aaBand).coerceAtLeast(0.92)
    val rInnerSq = rInner * rInner

    val sinLat0 = proj.sinLat0
    val cosLat0 = proj.cosLat0
    val sinRot = proj.sinRot
    val cosRot = proj.cosRot
    val sinPitch = proj.sinPitch
    val cosPitch = proj.cosPitch
    val centerLon = proj.centerLon

    val sunLatRad = Math.toRadians(subsolar.lat)
    val sunLonRad = Math.toRadians(subsolar.lon)
    val sinSunLat = sin(sunLatRad)
    val cosSunLat = cos(sunLatRad)
    val dSunLon = sunLonRad - proj.lon0
    val cosDSunLon = cos(dSunLon)
    val sinDSunLon = sin(dSunLon)

    val haloInvWidth = proj.zoom.coerceAtLeast(0.8) / 0.095

    java.util.stream.IntStream.range(0, targetH).parallel().forEach { py ->
        val rowOffset = py * targetW
        val sy = (py + 0.5) * scaleY
        val y2 = (cy - sy) * invR
        val y2Sq = y2 * y2

        for (px in 0 until targetW) {
            val sx = (px + 0.5) * scaleX
            val x2 = (sx - cx) * invR
            val rSq = x2 * x2 + y2Sq

            if (rSq >= 1.0) {
                val distFromLimb = sqrt(rSq) - 1.0
                val glow = exp(-distFromLimb * haloInvWidth).coerceIn(0.0, 1.0)
                val glow2 = glow * glow
                val glow4 = glow2 * glow2
                val glow5 = glow4 * glow

                val spaceR = (3.0 + 42.0 * glow2 + 110.0 * glow5).toInt().coerceIn(0, 255)
                val spaceG = (8.0 + 115.0 * glow2 + 140.0 * glow4).toInt().coerceIn(0, 255)
                val spaceB = (22.0 + 215.0 * glow + 35.0 * glow2).toInt().coerceIn(0, 255)
                var color = (0xFF shl 24) or (spaceR shl 16) or (spaceG shl 8) or spaceB

                if (glow < 0.28) {
                    val hash = ((px * 73856093) xor (py * 19349663)) and 0x7FFFFFFF
                    if (hash % 499 == 0) {
                        val starBright = 150 + (hash % 95)
                        color = (0xFF shl 24) or (starBright shl 16) or (starBright shl 8) or (starBright + 10).coerceAtMost(255)
                    }
                }
                outPixels[rowOffset + px] = color
            } else {
                val z2 = sqrt(1.0 - rSq)

                // Undo X-axis pitch rotation
                val y1 = y2 * cosPitch - z2 * sinPitch
                val z1 = y2 * sinPitch + z2 * cosPitch

                // Undo Z-axis heading rotation
                val xEast = x2 * cosRot + y1 * sinRot
                val yNorth = -x2 * sinRot + y1 * cosRot

                val sinPhi = (z1 * sinLat0 + yNorth * cosLat0).coerceIn(-1.0, 1.0)
                val cosPhiCosDLon = z1 * cosLat0 - yNorth * sinLat0

                val lat = fastAsinDeg(sinPhi)
                var lon = centerLon + fastAtan2Deg(xEast, cosPhiCosDLon)
                if (lon > 180.0) lon -= 360.0 else if (lon < -180.0) lon += 360.0

                var baseR = 12
                var baseG = 58
                var baseB = 122

                if (reliefPixels != null && texW > 1 && texH > 1) {
                    // Bilinear texture interpolation for smooth, non-blocky terrain when zoomed in
                    val uf = (((lon + 180.0) * (1.0 / 360.0)) * (texW - 1)).coerceIn(0.0, (texW - 1).toDouble())
                    val vf = (((90.0 - lat) * (1.0 / 180.0)) * (texH - 1)).coerceIn(0.0, (texH - 1).toDouble())
                    val u0 = uf.toInt().coerceIn(0, texW - 2)
                    val v0 = vf.toInt().coerceIn(0, texH - 2)
                    val fu = ((uf - u0) * 256.0).toInt().coerceIn(0, 256)
                    val fv = ((vf - v0) * 256.0).toInt().coerceIn(0, 256)
                    val invFu = 256 - fu
                    val invFv = 256 - fv

                    val row0 = v0 * texW + u0
                    val row1 = row0 + texW
                    val c00 = reliefPixels[row0]
                    val c10 = reliefPixels[row0 + 1]
                    val c01 = reliefPixels[row1]
                    val c11 = reliefPixels[row1 + 1]

                    val w00 = invFu * invFv
                    val w10 = fu * invFv
                    val w01 = invFu * fv
                    val w11 = fu * fv

                    baseR = (((c00 shr 16 and 0xFF) * w00 + (c10 shr 16 and 0xFF) * w10 +
                        (c01 shr 16 and 0xFF) * w01 + (c11 shr 16 and 0xFF) * w11) shr 16)
                    baseG = (((c00 shr 8 and 0xFF) * w00 + (c10 shr 8 and 0xFF) * w10 +
                        (c01 shr 8 and 0xFF) * w01 + (c11 shr 8 and 0xFF) * w11) shr 16)
                    baseB = (((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                        (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) shr 16)

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

                // Direct 3D dot product with subsolar vector (no per-pixel trig!)
                val sunDot = sinPhi * sinSunLat + cosSunLat * (cosPhiCosDLon * cosDSunLon + xEast * sinDSunLon)

                val solarFactor = when {
                    sunDot >= 0.08 -> 1.0
                    sunDot <= -0.14 -> 0.56
                    else -> {
                        val t = ((sunDot + 0.14) * (1.0 / 0.22)).coerceIn(0.0, 1.0)
                        0.56 + 0.44 * (t * t * (3.0 - 2.0 * t))
                    }
                }

                val sqrtZ = sqrt(z2)
                val limbShade = 0.78 + 0.22 * sqrtZ
                val omz = 1.0 - z2
                val atmoScatter = (omz * omz * sqrt(omz)).coerceIn(0.0, 1.0) * 0.58

                var finalR = (baseR * solarFactor * limbShade * (1.0 - atmoScatter) + 45.0 * atmoScatter).toInt()
                var finalG = (baseG * solarFactor * limbShade * (1.0 - atmoScatter) + 135.0 * atmoScatter).toInt()
                var finalB = (baseB * solarFactor * limbShade * (1.0 - atmoScatter) + 245.0 * atmoScatter).toInt()

                if (sunDot < -0.02) {
                    val nightBlend = ((-sunDot - 0.02) * (1.0 / 0.35)).coerceIn(0.0, 0.25)
                    finalB = (finalB * (1.0 - nightBlend) + (finalB + 28) * nightBlend).toInt()
                }

                // Smooth sub-pixel anti-aliasing across the horizon limb [rInner..1.0]
                if (rSq > rInnerSq) {
                    val rNorm = sqrt(rSq)
                    val edgeT = ((rNorm - rInner) / (1.0 - rInner)).coerceIn(0.0, 1.0)
                    val rimR = 155
                    val rimG = 225
                    val rimB = 255
                    finalR = (finalR * (1.0 - edgeT) + rimR * edgeT).toInt()
                    finalG = (finalG * (1.0 - edgeT) + rimG * edgeT).toInt()
                    finalB = (finalB * (1.0 - edgeT) + rimB * edgeT).toInt()
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
        color = android.graphics.Color.argb(105, 226, 234, 248)
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        strokeJoin = Paint.Join.ROUND
    }
    canvas.save()
    val clipDisc = Path().apply {
        addCircle(proj.sphereCenterX, proj.sphereCenterY, proj.radius - 1.5f, Path.Direction.CW)
    }
    canvas.clipPath(clipDisc)
    val path = Path()
    val maxSegmentPx = proj.radius * 0.14f
    for (country in countries) {
        for (ring in country.rings) {
            path.reset()
            var penDown = false
            var prevX = 0f
            var prevY = 0f
            for (pt in ring) {
                val p = proj.project(pt)
                if (p.visible && p.z > 0.08) {
                    if (!penDown || hypot((p.x - prevX).toDouble(), (p.y - prevY).toDouble()) > maxSegmentPx) {
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
    canvas.restore()
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
