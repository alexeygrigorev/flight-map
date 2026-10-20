package com.flightmap.core

/**
 * High-detail corridor raster imagery planner (pure Kotlin, JVM-testable).
 *
 * Source: NASA Visible Earth Blue Marble Next Generation, Shaded Relief and Bathymetry,
 * served as static 256px JPEG tiles through the NASA GIBS WMTS REST endpoint
 * (https://gibs.earthdata.nasa.gov/wmts/epsg3857/best). GIBS is a programmatic tile
 * API designed for machine tile access; this is NOT bulk-scraping the
 * community-funded tile.openstreetmap.org raster servers (whose Tile Usage Policy
 * explicitly forbids bulk download/prefetch and offline use).
 *
 * License: NASA content is generally not subject to copyright in the United States and
 * NASA's media guidelines permit its use in educational/informational works including
 * computer graphical simulations, with NASA acknowledged as the source. The app shows
 * "Imagery: NASA Visible Earth (public domain)" attribution on the globe screen.
 *
 * Coverage: bounded corridor + airport boxes from [OfflinePlan] (which already splits
 * at the date line and clamps poles), rasterized at slippy zooms 7-8 (GIBS publishes
 * this layer up to GoogleMapsCompatible_Level8, ~0.6 km/px at the equator - roughly
 * 16x sharper than the bundled 4096x2048 equirectangular relief texture at ~9.4 km/px).
 * The in-flight globe samples these cached tiles offline at deeper zoom and falls back
 * to the bundled relief outside cached coverage.
 */
object CorridorImagery {
    /** GIBS layer: static shaded relief + bathymetry, same visual family as bundled texture. */
    const val LAYER = "BlueMarble_ShadedRelief_Bathymetry"

    /** GIBS tile matrix set: Web Mercator, slippy-compatible 256px tiles, levels 0..8. */
    const val TILE_MATRIX_SET = "GoogleMapsCompatible_Level8"

    /** Reference date for the static layer (any valid date resolves to the same tiles). */
    const val REFERENCE_DATE = "2020-01-01"

    const val BASE_URL = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best"

    /** Highest imagery zoom GIBS publishes for this layer. */
    const val MAX_ZOOM = 8

    /** Lowest imagery zoom worth caching (below this the bundled texture suffices). */
    const val MIN_ZOOM = 7

    const val TILE_PX = 256

    /** Tile budget: keeps long routes practical for phone storage. */
    const val MAX_TILES = 1200

    /** Byte budget for the raster cache per flight. */
    const val MAX_BYTES = 25_000_000L

    /**
     * Calibrated average bytes per cached JPEG tile.
     * Probed 2026-10-20 against the live GIBS endpoint along HEL-PVG at z7/z8:
     * observed 8.4-13.6 KB per 256px JPEG tile; 12 KB is a conservative estimate.
     */
    const val BYTES_PER_TILE = 12_000L

    /** Maximum tiles decoded for one rendered viewport frame (bounds phone memory). */
    const val MAX_VIEWPORT_TILES = 28

    /**
     * Width of the soft blend band where cached imagery meets bundled-fallback
     * terrain, in tile texels. The globe renderer fades imagery out over this
     * distance from the nearest missing tile so corridor edges never show a
     * hard rectangular seam (see [featherScale]).
     */
    const val FEATHER_PX = 12.0

    /**
     * Coverage-edge feather factor in 0..1 for one imagery sample.
     *
     * Pure math, JVM-testable: [u]/[v] are the fractional position within the
     * current tile (0..1). [west]/[east]/[north]/[south] report whether the
     * 4-neighbour tiles are cached. When the current tile itself is missing
     * ([centerPresent] false), [distOutTexels] is the distance in texels from
     * the nearest cached edge (used for a short outward fade skirt).
     */
    fun featherScale(
        u: Double,
        v: Double,
        west: Boolean,
        east: Boolean,
        north: Boolean,
        south: Boolean,
        centerPresent: Boolean,
        distOutTexels: Double = 0.0
    ): Double {
        if (!centerPresent) {
            if (distOutTexels >= FEATHER_PX) return 0.0
            val t = (1.0 - distOutTexels / FEATHER_PX).coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }
        var d = Double.POSITIVE_INFINITY
        if (!west) d = minOf(d, u * TILE_PX)
        if (!east) d = minOf(d, (1.0 - u) * TILE_PX)
        if (!north) d = minOf(d, v * TILE_PX)
        if (!south) d = minOf(d, (1.0 - v) * TILE_PX)
        if (d.isInfinite()) return 1.0
        val t = (d / FEATHER_PX).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    fun tileUrl(z: Int, x: Int, y: Int): String =
        "$BASE_URL/$LAYER/default/$REFERENCE_DATE/$TILE_MATRIX_SET/$z/$y/$x.jpg"

    fun cacheRelPath(z: Int, x: Int, y: Int): String = "$z/$x/$y.jpg"

    /** Decodes a [SlippyTiles] 64-bit tile key into (zoom, x, y). */
    fun decodeKey(key: Long): Triple<Int, Int, Int> {
        val z = ((key ushr 48) and 0xFF).toInt()
        val x = ((key ushr 24) and 0xFFFFFF).toInt()
        val y = (key and 0xFFFFFF).toInt()
        return Triple(z, x, y)
    }

    fun encodeKey(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 48) or (x.toLong() shl 24) or (y.toLong() and 0xFFFFFFL)

    fun tileKeyFor(lat: Double, lon: Double, zoom: Int): Long =
        encodeKey(zoom, SlippyTiles.tileX(lon, zoom), SlippyTiles.tileY(lat, zoom))

    data class ImagerySelection(
        val levels: List<Int>,
        val tiles: Set<Long>,
        val estimatedBytes: Long
    ) {
        val tileCount: Int get() = tiles.size

        fun tilesAtZoom(zoom: Int): Set<Long> = tiles.filter { (it ushr 48).toInt() == zoom }.toSet()

        fun formattedSummary(): String {
            val mb = estimatedBytes.toDouble() / (1024 * 1024)
            val levelText = if (levels.size == 1) "z${levels.first()}" else "z${levels.min()}\u2013${levels.max()}"
            return "Imagery $levelText \u00b7 $tileCount tiles \u00b7 %.1f MB".format(java.util.Locale.US, mb)
        }
    }

    /**
     * Selects raster imagery tile levels for the given corridor boxes within budget.
     * Prefers z8+z7; adaptively falls back to z7-only, then z6-only, so even the
     * longest routes fit. Boxes must already be date-line split (see [OfflineGeometry]).
     */
    fun selectImagery(
        boxes: List<GeoBox>,
        maxTiles: Int = MAX_TILES,
        maxBytes: Long = MAX_BYTES
    ): ImagerySelection {
        require(boxes.isNotEmpty()) { "CorridorImagery needs at least one coverage box" }
        val candidates = listOf(listOf(8, 7), listOf(7), listOf(6))
        for (levels in candidates) {
            val tiles = mutableSetOf<Long>()
            for (z in levels) {
                for (box in boxes) {
                    tiles.addAll(SlippyTiles.tilesForBox(box, z))
                }
            }
            val bytes = tiles.size.toLong() * BYTES_PER_TILE
            if (tiles.size <= maxTiles && bytes <= maxBytes) {
                return ImagerySelection(levels.sorted(), tiles, bytes)
            }
        }
        // Longest routes: z6-only still exceeds the caller's custom budget - report honestly.
        val tiles = mutableSetOf<Long>()
        for (box in boxes) tiles.addAll(SlippyTiles.tilesForBox(box, 6))
        val bytes = tiles.size.toLong() * BYTES_PER_TILE
        if (tiles.size <= maxTiles && bytes <= maxBytes) {
            return ImagerySelection(listOf(6), tiles, bytes)
        }
        throw IllegalArgumentException(
            "Cannot fit corridor imagery into requested budget: max $maxTiles tiles / $maxBytes bytes, minimum is ${tiles.size} tiles / $bytes bytes"
        )
    }

    /**
     * Selects imagery coverage for a flight plan, reusing the corridor + airport
     * boxes from [OfflinePlan] so vector and raster coverage stay aligned.
     * Returns the selection plus the shared flight key used for on-disk cache identity.
     */
    fun selectForPlan(
        plan: FlightPlan,
        maxTiles: Int = MAX_TILES,
        maxBytes: Long = MAX_BYTES
    ): Pair<String, ImagerySelection> {
        val offline = OfflinePlan.create(plan)
        val imageryBoxes = offline.layers
            .filter { it.id == OfflineLayerId.CORRIDOR.idString || it.id == OfflineLayerId.AIRPORT.idString }
            .flatMap { it.boxes }
        val boxes = if (imageryBoxes.isNotEmpty()) imageryBoxes
        else offline.layers.flatMap { it.boxes }
        return offline.flightKey to selectImagery(boxes, maxTiles, maxBytes)
    }
}
