package com.flightmap.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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

/**
 * Decoded high-detail corridor raster tiles (NASA GIBS Blue Marble, public domain,
 * cached offline during preparation) covering the current viewport. Tiles absent
 * from the map fall back to the bundled relief texture - the globe never touches
 * the network in flight.
 */
data class ImageryOverlay(val zoom: Int, val tiles: Map<Long, IntArray>)

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
    var rotationDeg by mutableDoubleStateOf(0.0)
    var tiltDeg by mutableDoubleStateOf(0.0)
    var selectedFeature by mutableStateOf<SelectedGlobeFeature?>(null)

    val isDefaultOverview: Boolean
        get() = !followPlane && !headingUp && abs(rotationDeg) < 0.5 && tiltDeg < 0.5 && abs(zoom - 1.0) < 0.001 &&
            hypot(cameraLat - defaultCenter.lat, GlobeCameraTransform.wrapDegrees(cameraLon - defaultCenter.lon)) < 0.001

    fun zoomIn() {
        zoom = (zoom * 1.55).coerceAtMost(GlobeProjection.MAX_ZOOM)
    }

    fun zoomOut() {
        zoom = (zoom / 1.55).coerceAtLeast(0.75)
    }

    fun resetToRouteOverview() {
        followPlane = false
        headingUp = false
        rotationDeg = 0.0
        tiltDeg = 0.0
        selectedFeature = null
        zoom = 1.0
        cameraLat = defaultCenter.lat
        cameraLon = defaultCenter.lon
    }

    fun toggleFollowPlane(progress: FlightProgress, candidateCities: List<City>) {
        followPlane = !followPlane
        if (followPlane) {
            cameraLat = progress.point.lat.coerceIn(-78.0, 78.0)
            cameraLon = progress.point.lon
            if (zoom < 1.35) zoom = 1.65
            // Tap-only city cards: following the plane never auto-opens a city card.
            // City detail appears only when the user explicitly taps a marker on the map.
        }
    }

    fun toggleHeadingUp() {
        headingUp = !headingUp
    }

    fun focusPoint(pt: GeoPoint) {
        followPlane = false
        cameraLat = pt.lat.coerceIn(-78.0, 78.0)
        cameraLon = pt.lon
        zoom = maxOf(zoom, 2.2)
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
            cameraState.cameraLat = progress.point.lat.coerceIn(-78.0, 78.0)
            cameraState.cameraLon = progress.point.lon
        }
    }

    val effectiveRotation = cameraState.rotationDeg

    val camLatBucket = (cameraState.cameraLat * 2.0).roundToInt()
    val camLonBucket = (cameraState.cameraLon * 2.0).roundToInt()
    val camZoomBucket = (cameraState.zoom * 2.5).roundToInt()

    val displayCities = remember(
        pack.plan,
        pack.cities,
        allCities,
        camLatBucket,
        camLonBucket,
        camZoomBucket
    ) {
        val majorNames = setOf(
            "Helsinki", "Tallinn", "Riga", "Vilnius", "Warsaw", "Bucharest", "Tbilisi", "Baku",
            "Aktau", "Kyzylorda", "Shymkent", "Almaty", "Astana", "Tashkent", "Ürümqi", "Urumqi",
            "Turpan", "Hami", "Dunhuang", "Jiayuguan", "Lanzhou", "Xi'an", "Nanyang", "Hefei",
            "Nanjing", "Suzhou", "Shanghai", "Stockholm", "Oslo", "Berlin", "Dublin", "London",
            "Paris", "Rome", "Athens", "Moscow", "Arkhangel'sk", "Arkhangelsk", "Norilsk",
            "Surgut", "Novosibirsk", "Yekaterinburg", "Kazan", "Ufa", "Tehran", "Jerusalem",
            "Beijing", "Ulaanbaatar", "Seoul", "Tokyo", "Istanbul", "Kiev", "Kyiv", "Minsk"
        )
        val picked = LinkedHashMap<String, City>(320)
        for (c in allCities) {
            if (c.name in majorNames && c.name !in picked) {
                picked[c.name] = c
            }
        }
        for (c in PlaceLabels.overview(pack.plan, pack.cities, 42)) {
            if (c.name !in picked) picked[c.name] = c
        }
        if (cameraState.zoom >= 1.25) {
            val latHalfSpan = (54.0 / cameraState.zoom).coerceIn(4.5, 44.0)
            val cosLat = cos(Math.toRadians(cameraState.cameraLat)).coerceAtLeast(0.22)
            val lonHalfSpan = (latHalfSpan / cosLat).coerceAtMost(90.0)
            val minLat = cameraState.cameraLat - latHalfSpan
            val maxLat = cameraState.cameraLat + latHalfSpan
            val cLon = cameraState.cameraLon
            var localCount = 0
            for (c in allCities) {
                val lat = c.point.lat
                if (lat < minLat || lat > maxLat) continue
                var dLon = abs(c.point.lon - cLon)
                if (dLon > 180.0) dLon = 360.0 - dLon
                if (dLon <= lonHalfSpan) {
                    if (c.name !in picked) {
                        picked[c.name] = c
                    }
                    localCount++
                    if (localCount >= 220) break
                }
            }
        }
        for (c in allCities.take(160)) {
            if (c.name !in picked) picked[c.name] = c
        }
        picked.values.toList()
    }

    val indexedRings = remember(countries) {
        indexCountryRings(countries)
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
        effectiveRotation,
        cameraState.tiltDeg
    ) {
        GlobeProjection(
            width = viewportSize.width.toFloat().coerceAtLeast(1f),
            height = viewportSize.height.toFloat().coerceAtLeast(1f),
            centerLat = cameraState.cameraLat,
            centerLon = cameraState.cameraLon,
            zoom = cameraState.zoom,
            headingRotationDeg = effectiveRotation,
            tiltDeg = cameraState.tiltDeg
        )
    }

    // High-detail corridor imagery (offline cache): z7 once zoomed past overview, z8 deeper.
    // Outside cached coverage the renderer falls back to bundled relief - never network.
    val corridorTiles = remember(context) { CorridorTiles(context) }
    val imageryZoom = remember(cameraState.zoom) {
        when {
            cameraState.zoom >= 3.4 -> 8
            cameraState.zoom >= 2.0 -> 7
            else -> -1
        }
    }
    var imageryOverlay by remember(pack.mapTileKey) { mutableStateOf<ImageryOverlay?>(null) }
    LaunchedEffect(pack.mapTileKey, imageryZoom, camLatBucket, camLonBucket, camZoomBucket, viewportSize) {
        if (pack.mapTileKey == null || imageryZoom < 0) {
            imageryOverlay = null
            return@LaunchedEffect
        }
        imageryOverlay = withContext(Dispatchers.IO) {
            loadImageryOverlay(corridorTiles, pack.mapTileKey, projection, imageryZoom)
        }
    }
    val imageryAlpha = remember(cameraState.zoom, imageryZoom, imageryOverlay) {
        if (imageryOverlay == null || imageryZoom < 0) {
            0f
        } else {
            val rampStart = if (imageryZoom == 8) 3.2 else 2.0
            ((cameraState.zoom - rampStart) / 0.8).toFloat().coerceIn(0f, 1f)
        }
    }

    // Render the 3D globe raster synchronously in lockstep with `projection` using parallel CPU workers
    // so vector borders, route lines, and satellite relief NEVER lag or drift apart during pan/zoom.
    val globeBitmap = remember(
        projection,
        (subsolar.lat * 4).roundToInt(),
        (subsolar.lon * 4).roundToInt(),
        reliefReady,
        imageryOverlay,
        imageryAlpha
    ) {
        val reliefPixels = if (reliefReady) EarthReliefCache.ensureLoaded(context) else null
        render3DGlobeRaster(
            proj = projection,
            subsolar = subsolar,
            reliefPixels = reliefPixels,
            texW = EarthReliefCache.width,
            texH = EarthReliefCache.height,
            imagery = imageryOverlay,
            imageryAlpha = imageryAlpha
        )
    }

    val hasSelectedCard = cameraState.selectedFeature != null
    val sceneLayout = remember(projection, pack.plan, progress.point, displayCities, countries, hasSelectedCard) {
        val topSafe = if (projection.isPortrait) {
            (projection.height * 0.11f).coerceAtLeast(96f)
        } else {
            (projection.height * 0.13f).coerceAtLeast(72f)
        }
        val bottomSafe = if (projection.isPortrait) {
            if (hasSelectedCard) (projection.height * 0.28f).coerceAtLeast(220f)
            else (projection.height * 0.14f).coerceAtLeast(140f)
        } else {
            if (hasSelectedCard) (projection.height * 0.34f).coerceAtLeast(180f)
            else (projection.height * 0.14f).coerceAtLeast(92f)
        }
        GlobeLabelPlanner.plan(
            proj = projection,
            plan = pack.plan,
            progress = progress,
            peaks = IfeCatalog.mountainPeaks,
            pins = IfeCatalog.landmarkPins,
            cities = displayCities,
            countries = countries,
            topSafePx = topSafe,
            bottomSafePx = bottomSafe
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
                var lastSingleTapTimeMs = 0L
                var lastSingleTapPos = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTimeMs = System.currentTimeMillis()
                    val downPos = down.position
                    var lastPos = downPos
                    var totalMovedPx = 0f
                    var hadMultiTouch = false
                    var previousPointerCount = 1
                    var dragging = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            hadMultiTouch = true
                        }
                        // Rebase whenever a finger enters/leaves: never apply a delta across
                        // different pointer sets, and never turn the end of a pinch into a tap.
                        if (pressedCount == previousPointerCount && pressedCount > 0) {
                            val zoomChange = event.calculateZoom()
                            val rotationChange = event.calculateRotation()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            totalMovedPx += panChange.getDistance() + abs(zoomChange - 1f) * 320f + abs(rotationChange) * 3f
                            if (totalMovedPx > viewConfiguration.touchSlop) dragging = true
                            if (dragging && centroid != Offset.Unspecified) {
                                cameraState.followPlane = false
                                cameraState.headingUp = false
                                // Build from current state rather than the last rendered frame.
                                fun cameraProjection() = GlobeProjection(
                                    viewportSize.width.toFloat().coerceAtLeast(1f),
                                    viewportSize.height.toFloat().coerceAtLeast(1f),
                                    cameraState.cameraLat, cameraState.cameraLon, cameraState.zoom,
                                    cameraState.rotationDeg, cameraState.tiltDeg
                                )
                                val before = cameraProjection()
                                val anchor = before.unproject(centroid.x, centroid.y)
                                cameraState.zoom = (cameraState.zoom * zoomChange).coerceIn(0.75, GlobeProjection.MAX_ZOOM)
                                cameraState.rotationDeg = GlobeCameraTransform.wrapDegrees(cameraState.rotationDeg - rotationChange)
                                // Parallel two-finger vertical drag tilts; single-finger drag pans.
                                if (pressedCount >= 2) {
                                    cameraState.tiltDeg = (cameraState.tiltDeg - panChange.y * 0.16).coerceIn(0.0, GlobeProjection.MAX_TILT_DEG)
                                }
                                if (anchor != null) {
                                    val center = GlobeCameraTransform.anchoredCenter(
                                        cameraProjection(), GeoPoint(anchor.first, anchor.second),
                                        centroid.x + panChange.x,
                                        centroid.y + if (pressedCount >= 2) 0f else panChange.y
                                    )
                                    cameraState.cameraLat = center.lat
                                    cameraState.cameraLon = center.lon
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                        previousPointerCount = pressedCount
                        event.changes.firstOrNull()?.let { lastPos = it.position }
                    } while (event.changes.any { it.pressed })

                    val upTimeMs = System.currentTimeMillis()
                    if (!hadMultiTouch && !dragging && totalMovedPx < 22f && (upTimeMs - downTimeMs) < 450L) {
                        val proj = currentProjection
                        if (upTimeMs - lastSingleTapTimeMs < 320L && (lastPos - lastSingleTapPos).getDistance() < 64f) {
                            // Double-tap zooms smoothly into the tapped geographic point
                            cameraState.followPlane = false
                            proj.unproject(lastPos.x, lastPos.y)?.let { (tapLat, tapLon, _) ->
                                cameraState.cameraLat = (cameraState.cameraLat * 0.35 + tapLat * 0.65).coerceIn(-78.0, 78.0)
                                cameraState.cameraLon = tapLon
                            }
                            cameraState.zoom = (cameraState.zoom * 1.75).coerceAtMost(GlobeProjection.MAX_ZOOM)
                            lastSingleTapTimeMs = 0L
                        } else {
                            lastSingleTapTimeMs = upTimeMs
                            lastSingleTapPos = lastPos

                            val layout = currentSceneLayout
                            var bestFeature: SelectedGlobeFeature? = null
                            var bestDistPx = 56f

                            for (plannedPin in layout.pins) {
                                val d = hypot(plannedPin.x - lastPos.x, (plannedPin.y - 16f) - lastPos.y)
                                if (d < bestDistPx) {
                                    bestDistPx = d
                                    bestFeature = SelectedGlobeFeature.PinFeature(plannedPin.pin)
                                }
                            }

                            for (plannedPeak in layout.peaks) {
                                val d = hypot(plannedPeak.x - lastPos.x, plannedPeak.y - lastPos.y)
                                if (d < bestDistPx) {
                                    bestDistPx = d
                                    bestFeature = SelectedGlobeFeature.PeakFeature(plannedPeak.peak)
                                }
                            }

                            for (plannedCity in layout.cities) {
                                val d = hypot(plannedCity.x - lastPos.x, plannedCity.y - lastPos.y)
                                if (d < bestDistPx) {
                                    bestDistPx = d
                                    bestFeature = SelectedGlobeFeature.CityFeature(plannedCity.city)
                                }
                            }

                            if (bestFeature == null) {
                                val searchRadiusKm = (260.0 / sqrt(proj.zoom)).coerceIn(60.0, 260.0)
                                proj.unproject(lastPos.x, lastPos.y)?.let { (tapLat, tapLon, _) ->
                                    val tapGeo = GeoPoint(tapLat, tapLon)
                                    val allCandidates = (currentPackCities + currentDisplayCities).distinctBy { it.id }
                                    val prominent = allCandidates
                                        .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, tapGeo) <= searchRadiusKm }
                                        .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                                    val fallback = allCandidates
                                        .minByOrNull { GreatCircle.distanceKm(it.point, tapGeo) }
                                        ?.takeIf { GreatCircle.distanceKm(it.point, tapGeo) <= searchRadiusKm }
                                    val chosen = prominent ?: fallback
                                    if (chosen != null) {
                                        bestFeature = SelectedGlobeFeature.CityFeature(chosen)
                                    }
                                }
                            }

                            cameraState.selectedFeature = bestFeature
                        }
                    }
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

                // 3. Bundled Natural Earth vector lakes, rivers, and country borders clipped strictly to the 3D sphere disc
                drawGraticule(canvas, projection)
                drawCountryBorders(canvas, projection, indexedRings, imageryAlpha)

                // 4. Solar day/night terminator curve
                drawSolarTerminator(canvas, projection, subsolar)

                // 5. Planned/filed trajectory ribbon (flown trail + remaining airway + waypoint fixes + endpoint markers)
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
        // Map attribution lives above the bottom dock in FlightScreen (always
        // legible, clear of the panel in portrait/landscape, expanded or not).
    }
}

/**
 * Loads decoded corridor imagery tiles covering the current viewport from the
 * offline cache (file reads only). Returns null when nothing cached is visible,
 * letting the renderer fall back to bundled relief.
 */
private suspend fun loadImageryOverlay(
    tiles: CorridorTiles,
    flightKey: String,
    proj: GlobeProjection,
    zoom: Int
): ImageryOverlay? {
    val w = proj.width
    val h = proj.height
    val pts = listOf(
        proj.unproject(0f, 0f),
        proj.unproject(w, 0f),
        proj.unproject(0f, h),
        proj.unproject(w, h),
        proj.unproject(w / 2f, h / 2f)
    ).filterNotNull()
    if (pts.size < 2) return null
    val n = 1 shl zoom.coerceIn(0, 8)
    val minLat = pts.minOf { it.first }
    val maxLat = pts.maxOf { it.first }
    val yNorth = SlippyTiles.tileY(maxLat, zoom)
    val ySouth = SlippyTiles.tileY(minLat, zoom)
    val yRange = (minOf(yNorth, ySouth) - 1).coerceAtLeast(0)..(maxOf(yNorth, ySouth) + 1).coerceAtMost(n - 1)
    val lons = pts.map { it.second }
    val xRange = if (lons.max() - lons.min() > 180.0) {
        // Date line inside the viewport: scan all columns; the tile cap trims.
        0 until n
    } else {
        val xWest = SlippyTiles.tileX(lons.min(), zoom)
        val xEast = SlippyTiles.tileX(lons.max(), zoom)
        (minOf(xWest, xEast) - 1).coerceAtLeast(0)..(maxOf(xWest, xEast) + 1).coerceAtMost(n - 1)
    }
    val map = tiles.loadViewport(flightKey, zoom, xRange, yRange)
    return if (map.isEmpty()) null else ImageryOverlay(zoom, map)
}

/**
 * Bilinear decode of one cached 256px imagery tile at fractional tile position.
 */
private fun decodeImageryTexel(tex: IntArray, u: Double, v: Double): Triple<Int, Int, Int> {
    val tx = (u.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
    val ty = (v.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
    val tx1 = (tx + 1).coerceAtMost(255)
    val ty1 = (ty + 1).coerceAtMost(255)
    val fu = ((u.coerceIn(0.0, 1.0) * 255.0 - tx) * 256.0).toInt().coerceIn(0, 256)
    val fv = ((v.coerceIn(0.0, 1.0) * 255.0 - ty) * 256.0).toInt().coerceIn(0, 256)
    val iu = 256 - fu
    val iv = 256 - fv
    val row0 = ty * 256
    val row1 = ty1 * 256
    val c00 = tex[row0 + tx]
    val c10 = tex[row0 + tx1]
    val c01 = tex[row1 + tx]
    val c11 = tex[row1 + tx1]
    val w00 = iu * iv
    val w10 = fu * iv
    val w01 = iu * fv
    val w11 = fu * fv
    val r = (((c00 shr 16 and 0xFF) * w00 + (c10 shr 16 and 0xFF) * w10 +
        (c01 shr 16 and 0xFF) * w01 + (c11 shr 16 and 0xFF) * w11) shr 16)
    val g = (((c00 shr 8 and 0xFF) * w00 + (c10 shr 8 and 0xFF) * w10 +
        (c01 shr 8 and 0xFF) * w01 + (c11 shr 8 and 0xFF) * w11) shr 16)
    val b = (((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
        (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) shr 16)
    return Triple(r, g, b)
}

/**
 * Coverage-feathered sample of cached corridor imagery at the given geo position.
 *
 * Returns the graded sample plus the effective local blend weight (0..1), or
 * null when no cached tile covers the position. Near a coverage edge the
 * weight ramps smoothly to zero ([CorridorImagery.featherScale]) so detailed
 * imagery dissolves into the bundled fallback instead of ending in a hard
 * rectangular seam. Outside coverage the imagery weight stays exactly zero.
 */
private fun sampleImageryFeathered(
    imagery: ImageryOverlay,
    lat: Double,
    lon: Double,
    globalAlpha: Float
): Quad<Int, Int, Int, Float>? {
    if (globalAlpha <= 0.001f || imagery.tiles.isEmpty()) return null
    val z = imagery.zoom
    val n = 1 shl z.coerceIn(0, 8)
    val fx = (lon + 180.0) / 360.0 * n
    val latC = lat.coerceIn(-SlippyTiles.MAX_LATITUDE, SlippyTiles.MAX_LATITUDE)
    val latRad = Math.toRadians(latC)
    val fy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
    val xi = floor(fx).toInt()
    val yi = floor(fy).toInt()
    if (yi < 0 || yi >= n) return null
    val xx = ((xi % n) + n) % n
    val u = (fx - floor(fx)).coerceIn(0.0, 1.0)
    val v = (fy - floor(fy)).coerceIn(0.0, 1.0)
    val tiles = imagery.tiles
    val center = tiles[CorridorImagery.encodeKey(z, xx, yi)] ?: return null
    fun cached(dx: Int, dy: Int): Boolean =
        yi + dy in 0 until n &&
            tiles.containsKey(CorridorImagery.encodeKey(z, (xx + dx + n) % n, yi + dy))
    val scale = CorridorImagery.featherScale(
        u, v, cached(-1, 0), cached(1, 0), cached(0, -1), cached(0, 1), true,
        northWest = cached(-1, -1), northEast = cached(1, -1),
        southWest = cached(-1, 1), southEast = cached(1, 1)
    )
    if (scale == 0.0) return null
    val (r, g, b) = decodeImageryTexel(center, u, v)
    return Quad(r, g, b, (globalAlpha * scale).toFloat().coerceIn(0f, 1f))
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
    canvas.drawCircle(proj.sphereCenterX, proj.sphereCenterY, proj.silhouetteRadius + 1.5f, outerGlowPaint)
    canvas.drawCircle(proj.sphereCenterX, proj.sphereCenterY, proj.silhouetteRadius, innerRimPaint)
}

/**
 * Smooth 2-octave procedural micro-relief [-1.0, 1.0] for tactile terrain grain at high zoom.
 */
private fun sampleMicroRelief(lat: Double, lon: Double): Double {
    fun octave(freq: Double): Double {
        val gx = (lon + 180.0) * freq
        val gy = (lat + 90.0) * freq
        val ix = gx.toInt()
        val iy = gy.toInt()
        val fx = gx - ix
        val fy = gy - iy
        val sx = fx * fx * (3.0 - 2.0 * fx)
        val sy = fy * fy * (3.0 - 2.0 * fy)
        fun h(x: Int, y: Int): Double {
            var n = x * 374761393 + y * 668265263
            n = (n xor (n ushr 13)) * 1274126177
            return ((n xor (n ushr 16)) and 0xFFFF) * (1.0 / 32768.0) - 1.0
        }
        val n00 = h(ix, iy)
        val n10 = h(ix + 1, iy)
        val n01 = h(ix, iy + 1)
        val n11 = h(ix + 1, iy + 1)
        val nx0 = n00 + sx * (n10 - n00)
        val nx1 = n01 + sx * (n11 - n01)
        return nx0 + sy * (nx1 - nx0)
    }
    return octave(38.0) * 0.65 + octave(92.0) * 0.35
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
    texH: Int,
    imagery: ImageryOverlay? = null,
    imageryAlpha: Float = 0f
): Bitmap {
    val isPortrait = proj.isPortrait
    val targetW = if (isPortrait) 620 else 920
    val targetH = ((proj.height.toDouble() / proj.width.toDouble()) * targetW).roundToInt().coerceIn(360, 960)

    val outPixels = IntArray(targetW * targetH)
    val cx = proj.sphereCenterX.toDouble()
    val cy = proj.sphereCenterY.toDouble()
    val r = proj.silhouetteRadius.toDouble()
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
        val screenY = cy - sy
        val normalizedY = screenY * invR
        val y2Sq = normalizedY * normalizedY

        for (px in 0 until targetW) {
            val sx = (px + 0.5) * scaleX
            val screenX = sx - cx
            val normalizedX = screenX * invR
            val rSq = normalizedX * normalizedX + y2Sq

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
                // Exact same pinhole ray/sphere intersection as GlobeProjection.unproject.
                val f = proj.focalPx
                val d = proj.cameraDistance
                val invLen = 1.0 / sqrt(screenX * screenX + screenY * screenY + f * f)
                val dx = screenX * invLen
                val dy = screenY * invLen
                val dz = -f * invLen
                val b = d * dz
                val t = -b - sqrt((b * b - (d * d - 1.0)).coerceAtLeast(0.0))
                val x2 = t * dx
                val y2 = t * dy
                val z2 = d + t * dz

                // Undo X-axis pitch rotation
                val y1 = y2 * cosPitch - z2 * sinPitch
                val z1 = y2 * sinPitch + z2 * cosPitch

                // Undo Z-axis heading rotation
                val xEast = x2 * cosRot + y1 * sinRot
                val yNorth = -x2 * sinRot + y1 * cosRot

                val sinPhi = (z1 * sinLat0 + yNorth * cosLat0).coerceIn(-1.0, 1.0)
                val cosPhiCosDLon = z1 * cosLat0 - yNorth * sinLat0

                val lat = Math.toDegrees(asin(sinPhi))
                var lon = centerLon + Math.toDegrees(atan2(xEast, cosPhiCosDLon))
                if (lon > 180.0) lon -= 360.0 else if (lon < -180.0) lon += 360.0

                var baseR = 12
                var baseG = 58
                var baseB = 122

                if (reliefPixels != null && texW > 1 && texH > 1) {
                    // C1 smoothstep sub-texel interpolation for silky, non-blocky terrain at high zoom
                    val uf = (((lon + 180.0) * (1.0 / 360.0)) * (texW - 1)).coerceIn(0.0, (texW - 1).toDouble())
                    val vf = (((90.0 - lat) * (1.0 / 180.0)) * (texH - 1)).coerceIn(0.0, (texH - 1).toDouble())
                    val u0 = uf.toInt().coerceIn(0, texW - 2)
                    val v0 = vf.toInt().coerceIn(0, texH - 2)
                    val tu = (uf - u0).coerceIn(0.0, 1.0)
                    val tv = (vf - v0).coerceIn(0.0, 1.0)
                    val su = tu * tu * (3.0 - 2.0 * tu)
                    val sv = tv * tv * (3.0 - 2.0 * tv)
                    val fu = (su * 256.0).toInt().coerceIn(0, 256)
                    val fv = (sv * 256.0).toInt().coerceIn(0, 256)
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

                    // Continuous, branchless tonal grading (no discontinuous color steps on coastlines or lakes)
                    var rF = baseR * 1.08 + 10.0
                    var gF = baseG * 1.09 + 12.0
                    var bF = baseB * 1.08 + 12.0

                    if (proj.zoom > 1.55) {
                        val landWeight = ((gF + rF) * 0.5 - bF + 18.0).coerceIn(0.0, 36.0) * (1.0 / 36.0)
                        if (landWeight > 0.02) {
                            val zoomBlend = ((proj.zoom - 1.55) * 0.35).coerceIn(0.0, 1.0)
                            val micro = sampleMicroRelief(lat, lon) * zoomBlend * landWeight * 10.5
                            rF += micro
                            gF += micro * 1.05
                            bF += micro * 0.75
                        }
                    }

                    baseR = rF.toInt().coerceIn(6, 244)
                    baseG = gF.toInt().coerceIn(22, 244)
                    baseB = bF.toInt().coerceIn(20, 244)
                }

                // Deep-zoom corridor detail: blend cached offline imagery over bundled
                // relief along the route. Coverage edges feather into the fallback
                // (no hard rectangular seam); missing tiles keep bundled relief.
                if (imagery != null && imageryAlpha > 0.001f && imagery.tiles.isNotEmpty()) {
                    sampleImageryFeathered(imagery, lat, lon, imageryAlpha)?.let { q ->
                        // Same tonal grading as bundled relief for seamless tile edges.
                        val iR = (q.first * 1.08 + 10.0).coerceIn(6.0, 244.0)
                        val iG = (q.second * 1.09 + 12.0).coerceIn(22.0, 244.0)
                        val iB = (q.third * 1.08 + 12.0).coerceIn(20.0, 244.0)
                        val a = q.fourth.toDouble().coerceIn(0.0, 1.0)
                        baseR = (baseR * (1.0 - a) + iR * a).toInt().coerceIn(6, 244)
                        baseG = (baseG * (1.0 - a) + iG * a).toInt().coerceIn(22, 244)
                        baseB = (baseB * (1.0 - a) + iB * a).toInt().coerceIn(20, 244)
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

private class IndexedRing(
    val iso: String,
    val points: List<GeoPoint>,
    val cx: Double,
    val cy: Double,
    val cz: Double,
    val maxAngRad: Double
)

private fun indexCountryRings(countries: List<CountryBoundary>): List<IndexedRing> {
    val out = ArrayList<IndexedRing>(1600)
    for (c in countries) {
        for (ring in c.rings) {
            if (ring.size < 2) continue
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (pt in ring) {
                val phi = Math.toRadians(pt.lat)
                val lam = Math.toRadians(pt.lon)
                val cosP = cos(phi)
                sx += cosP * cos(lam)
                sy += cosP * sin(lam)
                sz += sin(phi)
            }
            val len = sqrt(sx * sx + sy * sy + sz * sz)
            val cx = if (len > 1e-6) sx / len else 1.0
            val cy = if (len > 1e-6) sy / len else 0.0
            val cz = if (len > 1e-6) sz / len else 0.0
            var minDot = 1.0
            val step = maxOf(1, ring.size / 24)
            var i = 0
            while (i < ring.size) {
                val pt = ring[i]
                val phi = Math.toRadians(pt.lat)
                val lam = Math.toRadians(pt.lon)
                val cosP = cos(phi)
                val d = (cosP * cos(lam)) * cx + (cosP * sin(lam)) * cy + sin(phi) * cz
                if (d < minDot) minDot = d
                i += step
            }
            val maxAng = acos(minDot.coerceIn(-1.0, 1.0)) + 0.08
            out.add(IndexedRing(c.iso, ring, cx, cy, cz, maxAng))
        }
    }
    return out
}

private fun drawGraticule(canvas: Canvas, proj: GlobeProjection) {
    val stepDeg = when {
        proj.zoom >= 5.0 -> 2
        proj.zoom >= 2.2 -> 5
        else -> 10
    }
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(if (proj.zoom >= 2.0) 34 else 22, 165, 208, 248)
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
    }
    canvas.save()
    val clipDisc = Path().apply {
        addCircle(proj.sphereCenterX, proj.sphereCenterY, proj.silhouetteRadius - 1.5f, Path.Direction.CW)
    }
    canvas.clipPath(clipDisc)

    val latSpan = (75.0 / proj.zoom).coerceIn(8.0, 85.0)
    val minLat = ((proj.centerLat - latSpan).toInt() / stepDeg * stepDeg).coerceIn(-80, 80)
    val maxLat = ((proj.centerLat + latSpan).toInt() / stepDeg * stepDeg).coerceIn(-80, 80)
    val lonSpan = (latSpan / cos(Math.toRadians(proj.centerLat)).coerceAtLeast(0.25)).coerceIn(12.0, 180.0)
    val minLon = ((proj.centerLon - lonSpan).toInt() / stepDeg * stepDeg).coerceIn(-180, 180)
    val maxLon = ((proj.centerLon + lonSpan).toInt() / stepDeg * stepDeg).coerceIn(-180, 180)

    val path = Path()
    val sampleStep = maxOf(1, stepDeg / 2)
    for (lat in minLat..maxLat step stepDeg) {
        path.reset()
        var penDown = false
        var lon = minLon
        while (lon <= maxLon) {
            val p = proj.project(GeoPoint(lat.toDouble(), lon.toDouble()))
            if (p.visible && p.z > 0.10) {
                if (!penDown) {
                    path.moveTo(p.x, p.y)
                    penDown = true
                } else {
                    path.lineTo(p.x, p.y)
                }
            } else {
                penDown = false
            }
            lon += sampleStep
        }
        if (!path.isEmpty) canvas.drawPath(path, gridPaint)
    }
    for (lon in minLon..maxLon step stepDeg) {
        path.reset()
        var penDown = false
        var lat = minLat
        while (lat <= maxLat) {
            val p = proj.project(GeoPoint(lat.toDouble(), lon.toDouble()))
            if (p.visible && p.z > 0.10) {
                if (!penDown) {
                    path.moveTo(p.x, p.y)
                    penDown = true
                } else {
                    path.lineTo(p.x, p.y)
                }
            } else {
                penDown = false
            }
            lat += sampleStep
        }
        if (!path.isEmpty) canvas.drawPath(path, gridPaint)
    }
    canvas.restore()
}

private fun drawCountryBorders(
    canvas: Canvas,
    proj: GlobeProjection,
    indexedRings: List<IndexedRing>,
    detailBlend: Float = 0f
) {
    // Coarse bundled inland-water polygons are far wider than real rivers/lakes
    // at deep zoom; fade them out wherever detailed NASA imagery is displayed
    // (the imagery already shows real water). Country borders always stay.
    val waterFade = (1f - detailBlend).coerceIn(0f, 1f)
    val showWater = waterFade > 0.01f
    val zoomBoost = (proj.zoom.toFloat() - 1.0f).coerceIn(0f, 3.0f)
    val borderHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(95, 8, 18, 36)
        style = Paint.Style.STROKE
        strokeWidth = 2.2f + zoomBoost * 0.25f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb((118 + zoomBoost * 18f).toInt().coerceAtMost(185), 224, 236, 252)
        style = Paint.Style.STROKE
        strokeWidth = 1.05f + zoomBoost * 0.15f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    val lakeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb((120 * waterFade).toInt().coerceIn(0, 255), 14, 52, 102)
        style = Paint.Style.FILL
    }
    val lakeShorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb((145 * waterFade).toInt().coerceIn(0, 255), 105, 188, 242)
        style = Paint.Style.STROKE
        strokeWidth = 1.0f + zoomBoost * 0.12f
        strokeJoin = Paint.Join.ROUND
    }
    val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(((85 + zoomBoost * 24f).toInt().coerceAtMost(165) * waterFade).toInt().coerceIn(0, 255), 88, 178, 238)
        style = Paint.Style.STROKE
        strokeWidth = 1.15f + zoomBoost * 0.18f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    canvas.save()
    val clipDisc = Path().apply {
        addCircle(proj.sphereCenterX, proj.sphereCenterY, proj.silhouetteRadius - 1.5f, Path.Direction.CW)
    }
    canvas.clipPath(clipDisc)

    // Camera center 3D unit vector and angular field-of-view radius for fast frustum culling
    val camPhi = Math.toRadians(proj.centerLat)
    val camLam = Math.toRadians(proj.centerLon)
    val camCosP = cos(camPhi)
    val camX = camCosP * cos(camLam)
    val camY = camCosP * sin(camLam)
    val camZ = sin(camPhi)
    val viewportPoints = listOf(0f to 0f, proj.width to 0f, 0f to proj.height, proj.width to proj.height,
        proj.width / 2 to 0f, proj.width / 2 to proj.height)
    val fovAngRad = viewportPoints.maxOf { (x, y) ->
        proj.unproject(x, y)?.let { GreatCircle.distanceKm(GeoPoint(proj.centerLat, proj.centerLon), GeoPoint(it.first, it.second)) / 6371.0 }
            ?: Math.PI
    } + 0.15

    val path = Path()
    val maxSegmentPx = proj.radius * 0.14f

    for (ring in indexedRings) {
        if (ring.iso == "RIVER" && proj.zoom < 1.30) continue
        if (!showWater && (ring.iso == "RIVER" || ring.iso == "LAKE")) continue

        val dot = ring.cx * camX + ring.cy * camY + ring.cz * camZ
        val maxCos = cos((fovAngRad + ring.maxAngRad).coerceAtMost(Math.PI))
        if (dot < maxCos) continue

        path.reset()
        var penDown = false
        var prevX = 0f
        var prevY = 0f
        var allVisible = true
        for (pt in ring.points) {
            val p = proj.project(pt)
            if (p.visible && p.z > 0.06) {
                if (!penDown || hypot((p.x - prevX).toDouble(), (p.y - prevY).toDouble()) > maxSegmentPx) {
                    path.moveTo(p.x, p.y)
                    penDown = true
                    if (prevX != 0f || prevY != 0f) allVisible = false
                } else {
                    path.lineTo(p.x, p.y)
                }
                prevX = p.x
                prevY = p.y
            } else {
                penDown = false
                allVisible = false
            }
        }
        if (!path.isEmpty) {
            when (ring.iso) {
                "LAKE" -> {
                    if (showWater && allVisible && proj.zoom >= 1.35) {
                        path.close()
                        canvas.drawPath(path, lakeFillPaint)
                    }
                    if (showWater) canvas.drawPath(path, lakeShorePaint)
                }
                "RIVER" -> {
                    if (showWater) canvas.drawPath(path, riverPaint)
                }
                else -> {
                    if (proj.zoom >= 1.65) {
                        canvas.drawPath(path, borderHaloPaint)
                    }
                    canvas.drawPath(path, borderPaint)
                }
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
    val segCount = if (proj.zoom >= 2.2) 360 else 200
    val densePoints = GreatCircle.routeWaypoints(waypoints, segCount)
    if (densePoints.size < 2) return

    val splitIdx = (progress.fraction * (densePoints.size - 1)).roundToInt().coerceIn(0, densePoints.lastIndex)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(95, 4, 12, 28)
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val aheadGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(80, 125, 211, 252)
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val aheadCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(215, 224, 242, 254)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val flownGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(145, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val flownCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        val aheadPts = ArrayList<GeoPoint>(densePoints.size - splitIdx + 1)
        aheadPts.add(progress.point)
        aheadPts.addAll(densePoints.subList((splitIdx + 1).coerceAtMost(densePoints.lastIndex), densePoints.size))
        val aheadPath = buildProjectedPath(aheadPts)
        if (!aheadPath.isEmpty) {
            canvas.drawPath(aheadPath, aheadGlowPaint)
            canvas.drawPath(aheadPath, aheadCorePaint)
        }
    }

    if (splitIdx > 0) {
        val flownPts = ArrayList<GeoPoint>(splitIdx + 2)
        flownPts.addAll(densePoints.subList(0, splitIdx))
        flownPts.add(progress.point)
        val flownPath = buildProjectedPath(flownPts)
        if (!flownPath.isEmpty) {
            canvas.drawPath(flownPath, flownGlowPaint)
            canvas.drawPath(flownPath, flownCorePaint)
        }
    }

    // Subtle aviation RNAV diamond waypoint fixes along the route when zoomed in
    if (proj.zoom >= 1.85 && waypoints.size > 2) {
        val wpFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(15, 23, 42)
            style = Paint.Style.FILL
        }
        val wpStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(125, 211, 252)
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        val diamond = Path()
        for (i in 1 until waypoints.lastIndex) {
            val p = proj.project(waypoints[i])
            if (!p.visible || p.z < 0.15) continue
            val r = 4.2f
            diamond.reset()
            diamond.moveTo(p.x, p.y - r)
            diamond.lineTo(p.x + r, p.y)
            diamond.lineTo(p.x, p.y + r)
            diamond.lineTo(p.x - r, p.y)
            diamond.close()
            canvas.drawPath(diamond, wpFill)
            canvas.drawPath(diamond, wpStroke)
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
