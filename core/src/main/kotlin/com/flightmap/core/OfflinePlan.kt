package com.flightmap.core

import java.util.Locale
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Slippy map tile coordinate and tile counting utilities for Web Mercator (EPSG:3857).
 */
object SlippyTiles {
    /** Clamps latitude to valid Web Mercator limits (~85.0511°). */
    const val MAX_LATITUDE = 85.05112878

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val n = 1 shl zoom
        val latRad = Math.toRadians(clampedLat)
        val y = (((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt())
        return y.coerceIn(0, n - 1)
    }

    /**
     * Computes the set of unique (zoom, x, y) tile keys covering a bounding box.
     * Tile keys are encoded as a 64-bit Long: (zoom in bits 48..55, x in 24..47, y in 0..23).
     */
    fun tilesForBox(box: GeoBox, zoom: Int): Set<Long> {
        val minX = tileX(box.west, zoom)
        val maxX = tileX(box.east, zoom)
        val yNorth = tileY(box.north, zoom)
        val ySouth = tileY(box.south, zoom)
        val minY = minOf(yNorth, ySouth)
        val maxY = maxOf(yNorth, ySouth)
        val count = (maxX - minX + 1) * (maxY - minY + 1)
        val tiles = HashSet<Long>(count)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val key = (zoom.toLong() shl 48) or (x.toLong() shl 24) or (y.toLong() and 0xFFFFFFL)
                tiles.add(key)
            }
        }
        return tiles
    }

    /**
     * Counts the number of unique tiles covering the given boxes across zoom levels [minZoom, maxZoom].
     */
    fun countUniqueTiles(boxes: List<GeoBox>, minZoom: Int, maxZoom: Int): Int {
        if (minZoom > maxZoom || boxes.isEmpty()) return 0
        val tiles = mutableSetOf<Long>()
        for (z in minZoom..maxZoom) {
            for (box in boxes) {
                tiles.addAll(tilesForBox(box, z))
            }
        }
        return tiles.size
    }
}

/**
 * Bounding box in geographic coordinates.
 * [south] <= [north] within [-85.0511, 85.0511], [west] <= [east] within [-180, 180].
 */
data class GeoBox(
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double
) {
    init {
        require(south <= north) { "south ($south) must be <= north ($north)" }
        require(west <= east) { "west ($west) must be <= east ($east)" }
        require(south >= -SlippyTiles.MAX_LATITUDE && north <= SlippyTiles.MAX_LATITUDE) {
            "Latitude out of Web Mercator bounds: south=$south, north=$north"
        }
        require(west >= -180.0 && east <= 180.0) {
            "Longitude out of bounds [-180, 180]: west=$west, east=$east"
        }
    }

    /** Closed 5-point polygon ring in GeoJSON order: SW -> SE -> NE -> NW -> SW. */
    fun toPolygonRing(): List<GeoPoint> = listOf(
        GeoPoint(south, west),
        GeoPoint(south, east),
        GeoPoint(north, east),
        GeoPoint(north, west),
        GeoPoint(south, west)
    )
}

/**
 * Geometric bounding box helpers with date line splitting and polar clamping.
 */
object OfflineGeometry {
    /**
     * Creates bounding boxes around a point with latitude margin [latMargin].
     * Clamps center lat to [-85.0, 85.0] and bounds to [-85.0511, 85.0511].
     * Handles polar convergence without singularities and splits at the 180° date line.
     */
    fun createBoxesForPoint(lat: Double, lon: Double, latMargin: Double): List<GeoBox> {
        val centerLat = lat.coerceIn(-85.0, 85.0)
        val south = (centerLat - latMargin).coerceIn(-SlippyTiles.MAX_LATITUDE, SlippyTiles.MAX_LATITUDE)
        val north = (centerLat + latMargin).coerceIn(-SlippyTiles.MAX_LATITUDE, SlippyTiles.MAX_LATITUDE)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.086) // cos(85°) ≈ 0.087
        val lonMargin = (latMargin / cosLat).coerceAtMost(180.0)
        val west = lon - lonMargin
        val east = lon + lonMargin

        return when {
            lonMargin >= 180.0 || (east - west >= 360.0) -> listOf(GeoBox(south, north, -180.0, 180.0))
            west < -180.0 -> listOf(
                GeoBox(south, north, west + 360.0, 180.0),
                GeoBox(south, north, -180.0, east)
            )
            east > 180.0 -> listOf(
                GeoBox(south, north, west, 180.0),
                GeoBox(south, north, -180.0, east - 360.0)
            )
            else -> listOf(GeoBox(south, north, west, east))
        }
    }

    fun createBoxesForPoints(points: List<GeoPoint>, latMargin: Double): List<GeoBox> {
        return points.flatMap { createBoxesForPoint(it.lat, it.lon, latMargin) }
    }
}

object OfflinePlanConfig {
    const val PLANNER_VERSION: String = "v2"
    /** Provider policy permits at most 3,000 tiles / 50 MB per download. */
    const val MAX_TILES: Int = 2900
    const val MAX_BYTES: Long = 49_000_000L // 49 MB total per pack

    /** Calibrated average byte estimates per tile by detail level. */
    const val BYTES_PER_BROAD_TILE: Long = 95_000L   // coarse tiles carry extensive global coast/land features
    const val BYTES_PER_CORRIDOR_TILE: Long = 25_000L // medium zoom vector tiles
    const val BYTES_PER_AIRPORT_TILE: Long = 18_000L  // high zoom localized tiles
}

enum class OfflineLayerId(val idString: String) {
    BROAD("broad"),
    CORRIDOR("corridor"),
    AIRPORT("airport")
}

data class OfflineLayerPlan(
    val id: String,
    val minZoom: Double,
    val maxZoom: Double,
    val boxes: List<GeoBox>,
    val estimatedTiles: Int,
    val estimatedBytes: Long
)

data class OfflineLayerSummary(
    val id: String,
    val minZoom: Double,
    val maxZoom: Double,
    val estimatedTiles: Int,
    val estimatedBytes: Long
)

data class OfflinePlanSummary(
    val estimatedTiles: Int,
    val maxTiles: Int,
    val estimatedBytes: Long,
    val maxBytes: Long,
    val layers: List<OfflineLayerSummary>
) {
    fun formattedSummary(): String {
        val mb = String.format(Locale.US, "%.1f", estimatedBytes.toDouble() / (1024 * 1024))
        return "~$estimatedTiles tiles · ~$mb MB"
    }

    fun formattedBudget(): String {
        val mbBudget = maxBytes / (1024 * 1024)
        return "Budget: max $maxTiles tiles · $mbBudget MB"
    }
}

data class OfflineFlightPlan(
    val flightKey: String,
    val layers: List<OfflineLayerPlan>,
    val totalEstimatedTiles: Int,
    val totalEstimatedBytes: Long,
    val summary: OfflinePlanSummary
)

/**
 * Pure offline map planner producing bounded multi-layer vector tile coverage.
 * Layers:
 *  1. Broad overview (zoom 0–6)
 *  2. Narrow continuous corridor along route (zoom 7–8)
 *  3. Airport close-ups around origin and destination (zoom 9–10)
 *
 * Automatically adapts corridor and detail boundaries to stay strictly within
 * the 2,900 tile and 49 MB download budget.
 */
object OfflinePlan {
    fun create(
        plan: FlightPlan,
        maxTiles: Int = OfflinePlanConfig.MAX_TILES,
        maxBytes: Long = OfflinePlanConfig.MAX_BYTES
    ): OfflineFlightPlan {
        // flightKey includes airports, arrival, and planner version for complete identity
        val flightKey = "${plan.number}:${plan.origin.iata}-${plan.destination.iata}:${plan.departure}:${plan.arrival}:${OfflinePlanConfig.PLANNER_VERSION}"
        val origin = plan.origin.point
        val destination = plan.destination.point
        val totalDistanceKm = GreatCircle.distanceKm(origin, destination)

        // Helper to generate dense points for continuous corridor without gaps
        fun corridorPointsForMargin(margin: Double): List<GeoPoint> {
            val maxStepKm = (margin * 111.0 * 0.75).coerceAtLeast(5.0)
            val segments = maxOf(80, (totalDistanceKm / maxStepKm).toInt())
            return GreatCircle.route(origin, destination, segments)
        }

        // Helper for continuous broad overview points
        fun broadPointsForMargin(margin: Double): List<GeoPoint> {
            val maxStepKm = (margin * 111.0 * 0.75).coerceAtLeast(10.0)
            val segments = maxOf(60, (totalDistanceKm / maxStepKm).toInt())
            return GreatCircle.route(origin, destination, segments)
        }

        // 1. Broad layer: zoom 0–6
        var broadMinZoom = 0.0
        var broadMaxZoom = 6.0
        var broadLatMargin = 2.0 // ~220km margin
        var broadBoxes = OfflineGeometry.createBoxesForPoints(broadPointsForMargin(broadLatMargin), broadLatMargin)
        var broadTiles = SlippyTiles.countUniqueTiles(broadBoxes, broadMinZoom.toInt(), broadMaxZoom.toInt())
        var broadBytes = broadTiles.toLong() * OfflinePlanConfig.BYTES_PER_BROAD_TILE

        // 2. Airport layer: zoom 9–10
        var includeAirport = true
        var airportLatMargin = 0.25 // ~28km margin
        var airportMinZoom = 9.0
        var airportMaxZoom = 10.0
        var airportBoxes = OfflineGeometry.createBoxesForPoints(listOf(origin, destination), airportLatMargin)
        var airportTiles = SlippyTiles.countUniqueTiles(airportBoxes, airportMinZoom.toInt(), airportMaxZoom.toInt())
        var airportBytes = airportTiles.toLong() * OfflinePlanConfig.BYTES_PER_AIRPORT_TILE

        // 3. Narrow continuous corridor: zoom 7–8
        var includeCorridor = true
        var corrLatMargin = 0.35 // ~39km margin
        var corrMinZoom = 7.0
        var corrMaxZoom = 8.0
        var corrBoxes = OfflineGeometry.createBoxesForPoints(corridorPointsForMargin(corrLatMargin), corrLatMargin)
        var corrTiles = SlippyTiles.countUniqueTiles(corrBoxes, corrMinZoom.toInt(), corrMaxZoom.toInt())
        var corrBytes = corrTiles.toLong() * OfflinePlanConfig.BYTES_PER_CORRIDOR_TILE

        var totalTiles = broadTiles + corrTiles + airportTiles
        var totalBytes = broadBytes + corrBytes + airportBytes

        // Adaptive bounding: deterministic tuning to stay strictly under maxTiles and maxBytes
        // Stage A: Narrow corridor margin (maintaining continuous sample point density)
        while ((totalTiles > maxTiles || totalBytes > maxBytes) && corrLatMargin > 0.15) {
            corrLatMargin -= 0.05
            corrBoxes = OfflineGeometry.createBoxesForPoints(corridorPointsForMargin(corrLatMargin), corrLatMargin)
            corrTiles = SlippyTiles.countUniqueTiles(corrBoxes, corrMinZoom.toInt(), corrMaxZoom.toInt())
            corrBytes = corrTiles.toLong() * OfflinePlanConfig.BYTES_PER_CORRIDOR_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage B: Narrow broad margin (maintaining continuous sample point density)
        while ((totalTiles > maxTiles || totalBytes > maxBytes) && broadLatMargin > 1.0) {
            broadLatMargin -= 0.25
            broadBoxes = OfflineGeometry.createBoxesForPoints(broadPointsForMargin(broadLatMargin), broadLatMargin)
            broadTiles = SlippyTiles.countUniqueTiles(broadBoxes, broadMinZoom.toInt(), broadMaxZoom.toInt())
            broadBytes = broadTiles.toLong() * OfflinePlanConfig.BYTES_PER_BROAD_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage C: Narrow airport margin
        while ((totalTiles > maxTiles || totalBytes > maxBytes) && airportLatMargin > 0.12) {
            airportLatMargin -= 0.04
            airportBoxes = OfflineGeometry.createBoxesForPoints(listOf(origin, destination), airportLatMargin)
            airportTiles = SlippyTiles.countUniqueTiles(airportBoxes, airportMinZoom.toInt(), airportMaxZoom.toInt())
            airportBytes = airportTiles.toLong() * OfflinePlanConfig.BYTES_PER_AIRPORT_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage D: Reduce corridor max zoom from 8 to 7
        if ((totalTiles > maxTiles || totalBytes > maxBytes) && corrMaxZoom == 8.0) {
            corrMaxZoom = 7.0
            corrTiles = SlippyTiles.countUniqueTiles(corrBoxes, corrMinZoom.toInt(), corrMaxZoom.toInt())
            corrBytes = corrTiles.toLong() * OfflinePlanConfig.BYTES_PER_CORRIDOR_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage E: Reduce airport max zoom from 10 to 9
        if ((totalTiles > maxTiles || totalBytes > maxBytes) && airportMaxZoom == 10.0) {
            airportMaxZoom = 9.0
            airportTiles = SlippyTiles.countUniqueTiles(airportBoxes, airportMinZoom.toInt(), airportMaxZoom.toInt())
            airportBytes = airportTiles.toLong() * OfflinePlanConfig.BYTES_PER_AIRPORT_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage F: Drop airport layer if still exceeding budget
        if (totalTiles > maxTiles || totalBytes > maxBytes) {
            includeAirport = false
            airportTiles = 0
            airportBytes = 0L
            airportBoxes = emptyList()
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage G: Drop corridor layer if still exceeding budget
        if (totalTiles > maxTiles || totalBytes > maxBytes) {
            includeCorridor = false
            corrTiles = 0
            corrBytes = 0L
            corrBoxes = emptyList()
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        // Stage H: Reduce broad max zoom from 6 to 5, then 4
        while ((totalTiles > maxTiles || totalBytes > maxBytes) && broadMaxZoom > 4.0) {
            broadMaxZoom -= 1.0
            broadTiles = SlippyTiles.countUniqueTiles(broadBoxes, broadMinZoom.toInt(), broadMaxZoom.toInt())
            broadBytes = broadTiles.toLong() * OfflinePlanConfig.BYTES_PER_BROAD_TILE
            totalTiles = broadTiles + corrTiles + airportTiles
            totalBytes = broadBytes + corrBytes + airportBytes
        }

        if (totalTiles > maxTiles || totalBytes > maxBytes) {
            throw IllegalArgumentException(
                "Cannot fit route into requested budget: requested $maxTiles tiles / $maxBytes bytes, minimum possible is $totalTiles tiles / $totalBytes bytes"
            )
        }

        val layerPlans = mutableListOf<OfflineLayerPlan>()
        layerPlans.add(
            OfflineLayerPlan(
                id = OfflineLayerId.BROAD.idString,
                minZoom = broadMinZoom,
                maxZoom = broadMaxZoom,
                boxes = broadBoxes,
                estimatedTiles = broadTiles,
                estimatedBytes = broadBytes
            )
        )
        if (includeCorridor) {
            layerPlans.add(
                OfflineLayerPlan(
                    id = OfflineLayerId.CORRIDOR.idString,
                    minZoom = corrMinZoom,
                    maxZoom = corrMaxZoom,
                    boxes = corrBoxes,
                    estimatedTiles = corrTiles,
                    estimatedBytes = corrBytes
                )
            )
        }
        if (includeAirport) {
            layerPlans.add(
                OfflineLayerPlan(
                    id = OfflineLayerId.AIRPORT.idString,
                    minZoom = airportMinZoom,
                    maxZoom = airportMaxZoom,
                    boxes = airportBoxes,
                    estimatedTiles = airportTiles,
                    estimatedBytes = airportBytes
                )
            )
        }

        val layerSummaries = layerPlans.map {
            OfflineLayerSummary(
                id = it.id,
                minZoom = it.minZoom,
                maxZoom = it.maxZoom,
                estimatedTiles = it.estimatedTiles,
                estimatedBytes = it.estimatedBytes
            )
        }

        val summary = OfflinePlanSummary(
            estimatedTiles = totalTiles,
            maxTiles = maxTiles,
            estimatedBytes = totalBytes,
            maxBytes = maxBytes,
            layers = layerSummaries
        )

        return OfflineFlightPlan(
            flightKey = flightKey,
            layers = layerPlans,
            totalEstimatedTiles = totalTiles,
            totalEstimatedBytes = totalBytes,
            summary = summary
        )
    }

    fun summary(plan: FlightPlan): OfflinePlanSummary = create(plan).summary
}
