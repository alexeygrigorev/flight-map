package com.flightmap.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CorridorImageryTest {

    private fun sampleFlight(
        number: String,
        originIata: String,
        originLat: Double,
        originLon: Double,
        destIata: String,
        destLat: Double,
        destLon: Double
    ): FlightPlan {
        val o = Airport(originIata, "$originIata Airport", GeoPoint(originLat, originLon), "UTC")
        val d = Airport(destIata, "$destIata Airport", GeoPoint(destLat, destLon), "UTC")
        return FlightPlan(number, o, d, Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T22:00:00Z"))
    }

    @Test
    fun tileUrlMatchesGibsRestPattern() {
        val url = CorridorImagery.tileUrl(8, 170, 80)
        assertEquals(
            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble_ShadedRelief_Bathymetry" +
                "/default/2020-01-01/GoogleMapsCompatible_Level8/8/80/170.jpg",
            url
        )
        assertEquals("8/170/80.jpg", CorridorImagery.cacheRelPath(8, 170, 80))
    }

    @Test
    fun keyEncodeDecodeRoundTrip() {
        val key = CorridorImagery.encodeKey(8, 170, 80)
        assertEquals(Triple(8, 170, 80), CorridorImagery.decodeKey(key))
        assertEquals(key, CorridorImagery.tileKeyFor(55.0, 60.0, 8))
        val (z, x, y) = CorridorImagery.decodeKey(key)
        assertEquals(SlippyTiles.tileX(60.0, z), x)
        assertEquals(SlippyTiles.tileY(55.0, z), y)
    }

    @Test
    fun mediumRouteSelectsZ8AndZ7WithinBudget() {
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val (flightKey, selection) = CorridorImagery.selectForPlan(plan)
        assertTrue(flightKey.isNotBlank())
        assertEquals(listOf(7, 8), selection.levels)
        assertTrue("Imagery tile count ${selection.tileCount} must fit budget", selection.tileCount <= CorridorImagery.MAX_TILES)
        assertTrue(selection.estimatedBytes <= CorridorImagery.MAX_BYTES)
        assertTrue("Corridor imagery should add real detail (got ${selection.tileCount})", selection.tileCount > 100)
        // Every selected tile must lie inside one of the planner boxes (no stray tiles).
        val boxes = OfflinePlan.create(plan).layers
            .filter { it.id == "corridor" || it.id == "airport" }
            .flatMap { it.boxes }
        for (key in selection.tiles) {
            val (z, x, y) = CorridorImagery.decodeKey(key)
            assertTrue("Tile $z/$x/$y outside corridor boxes", boxes.any { box ->
                val minX = SlippyTiles.tileX(box.west, z)
                val maxX = SlippyTiles.tileX(box.east, z)
                val yN = SlippyTiles.tileY(box.north, z)
                val yS = SlippyTiles.tileY(box.south, z)
                x in minX..maxX && y in minOf(yN, yS)..maxOf(yN, yS)
            })
        }
        println("AY87 imagery: ${selection.formattedSummary()}")
    }

    @Test
    fun ultraLongRouteStillFitsBudget() {
        // SIN-EWR: ~15,300 km, longest realistic commercial route.
        val plan = sampleFlight("SQ22", "SIN", 1.3592, 103.9894, "EWR", 40.6925, -74.1686)
        val (_, selection) = CorridorImagery.selectForPlan(plan)
        assertTrue("Ultra-long route must fit tile budget (got ${selection.tileCount})", selection.tileCount <= CorridorImagery.MAX_TILES)
        assertTrue(selection.estimatedBytes <= CorridorImagery.MAX_BYTES)
        println("SQ22 imagery: ${selection.formattedSummary()}")
    }

    @Test
    fun dateLineRouteTilesStayInSlippyBounds() {
        val plan = sampleFlight("NH183", "HNL", 21.3187, -157.9224, "NRT", 35.7647, 140.3864)
        val (_, selection) = CorridorImagery.selectForPlan(plan)
        assertTrue(selection.tileCount <= CorridorImagery.MAX_TILES)
        for (key in selection.tiles) {
            val (z, x, y) = CorridorImagery.decodeKey(key)
            val n = 1 shl z
            assertTrue(x in 0 until n)
            assertTrue(y in 0 until n)
        }
        // Tiles must exist on both sides of the date line.
        assertTrue(selection.tiles.any { CorridorImagery.decodeKey(it).second < 8 })
        assertTrue(selection.tiles.any {
            val (_, x, _) = CorridorImagery.decodeKey(it)
            val z = CorridorImagery.decodeKey(it).first
            x > (1 shl z) - 8
        })
    }

    @Test
    fun polarRouteHasNoSingularities() {
        val plan = sampleFlight("EK215", "DXB", 25.2528, 55.3644, "LAX", 33.9425, -118.4081)
        val (_, selection) = CorridorImagery.selectForPlan(plan)
        assertTrue(selection.tileCount <= CorridorImagery.MAX_TILES)
        assertTrue(selection.tiles.isNotEmpty())
    }

    @Test
    fun adaptiveFallbackUnderTightBudget() {
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val (_, tight) = CorridorImagery.selectForPlan(plan, maxTiles = 200, maxBytes = 5_000_000L)
        assertTrue(tight.tileCount <= 200)
        assertTrue(tight.levels == listOf(7) || tight.levels == listOf(6))
        println("AY87 tight-budget imagery: ${tight.formattedSummary()}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleBudgetIsRejected() {
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        CorridorImagery.selectForPlan(plan, maxTiles = 1, maxBytes = 1L)
    }

    @Test
    fun summaryFormatIsHumanReadable() {
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val (_, selection) = CorridorImagery.selectForPlan(plan)
        val summary = selection.formattedSummary()
        assertTrue(summary, summary.contains("Imagery z7\u20138"))
        assertTrue(summary, summary.contains("tiles"))
        assertTrue(summary, summary.contains("MB"))
    }

    @Test
    fun featherScaleIsFullInsideCoverage() {
        assertEquals(1.0, CorridorImagery.featherScale(0.5, 0.5, true, true, true, true, true), 1e-9)
        // Missing neighbour far from its edge still blends fully.
        assertEquals(1.0, CorridorImagery.featherScale(0.5, 0.5, false, true, true, true, true), 1e-9)
    }

    // Effective imagery alpha over synthetic cache coverage, as used by the renderer.
    private fun alpha(x: Double, y: Double, coverage: Set<Pair<Int, Int>>): Double {
        val tx = kotlin.math.floor(x).toInt()
        val ty = kotlin.math.floor(y).toInt()
        fun cached(dx: Int, dy: Int) = (tx + dx to ty + dy) in coverage
        return 0.8 * CorridorImagery.featherScale(
            x - tx, y - ty, cached(-1, 0), cached(1, 0), cached(0, -1), cached(0, 1), cached(0, 0),
            northWest = cached(-1, -1), northEast = cached(1, -1),
            southWest = cached(-1, 1), southEast = cached(1, 1)
        )
    }

    @Test
    fun effectiveAlphaIsContinuousOnBothSidesOfEveryOuterEdge() {
        val coverage = setOf(0 to 0)
        val epsilon = 1e-7
        // Rotate a west-edge probe to exercise all four edges and their outside samples.
        val edges: List<(Double) -> Pair<Double, Double>> = listOf(
            { d -> d to 0.5 }, { d -> 1.0 - d to 0.5 },
            { d -> 0.5 to d }, { d -> 0.5 to 1.0 - d }
        )
        for (edge in edges) {
            fun at(d: Double): Double {
                val (x, y) = edge(d)
                return alpha(x, y, coverage)
            }
            assertEquals(0.0, at(-epsilon), 0.0)
            assertEquals(0.0, at(0.0), 0.0)
            assertEquals(at(-epsilon), at(epsilon), 1e-9)
            val width = CorridorImagery.FEATHER_PX / CorridorImagery.TILE_PX
            assertTrue(at(width / 4) < at(width / 2))
            assertEquals(0.4, at(width / 2), 1e-9)
            assertEquals(0.8, at(width), 1e-9)
            // A broad enough band to soften the high-zoom resolution/color transition.
            assertTrue(at(12.0 / 256.0) < 0.8)
        }
    }

    @Test
    fun outsideCoverageAlwaysHasZeroEffectiveAlpha() {
        val coverage = setOf(0 to 0)
        for (x in listOf(-1.0, -0.01, 0.0, 0.5, 1.0, 1.01, 2.0)) {
            for (y in listOf(-1.0, -0.01, 0.0, 0.5, 1.0, 1.01, 2.0)) {
                if (x < 0 || x >= 1 || y < 0 || y >= 1) {
                    assertEquals("outside ($x, $y)", 0.0, alpha(x, y, coverage), 0.0)
                }
            }
        }
        assertEquals(0.0, CorridorImagery.featherScale(0.5, 0.5, true, true, true, true, false), 0.0)
    }

    @Test
    fun adjacentCachedTilesKeepFullAlphaAcrossSharedEdges() {
        val epsilon = 1e-7
        for (coverage in listOf(setOf(0 to 0, 1 to 0), setOf(0 to 0, 0 to 1))) {
            for (d in listOf(-epsilon, 0.0, epsilon)) {
                val x = if ((1 to 0) in coverage) 1.0 + d else 0.5
                val y = if ((0 to 1) in coverage) 1.0 + d else 0.5
                assertEquals(0.8, alpha(x, y, coverage), 1e-9)
            }
        }
        val block = setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        assertEquals(0.8, alpha(1.0, 1.0, block), 1e-9)
    }

    @Test
    fun convexAndMissingDiagonalCornersFadeContinuously() {
        val epsilon = 1e-7
        val block = setOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)
        // Each missing quadrant creates a concave corner at (1, 1).
        for (missing in block) {
            val coverage = block - missing
            assertEquals(0.0, alpha(1.0, 1.0, coverage), 1e-9)
            for (dx in listOf(-epsilon, epsilon)) {
                for (dy in listOf(-epsilon, epsilon)) {
                    assertEquals(0.0, alpha(1.0 + dx, 1.0 + dy, coverage), 1e-9)
                }
            }
            // Across either cached shared edge near the missing corner, alpha agrees.
            for (offset in listOf(-0.02, 0.02)) {
                assertEquals(alpha(1.0 - epsilon, 1.0 + offset, coverage),
                    alpha(1.0 + epsilon, 1.0 + offset, coverage), 1e-5)
                assertEquals(alpha(1.0 + offset, 1.0 - epsilon, coverage),
                    alpha(1.0 + offset, 1.0 + epsilon, coverage), 1e-5)
            }
        }
        val single = setOf(0 to 0)
        assertEquals(0.0, alpha(epsilon, epsilon, single), 1e-9)
        assertEquals(0.0, alpha(-epsilon, -epsilon, single), 0.0)
    }
}
