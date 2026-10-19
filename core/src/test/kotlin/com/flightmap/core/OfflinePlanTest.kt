package com.flightmap.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OfflinePlanTest {

    @Test
    fun continuousCoverageAcrossPolarDatelineAndExtremeRoutes() {
        val routes = listOf(
            sampleFlight("POLAR", "AAA", 84.5, -40.0, "BBB", 84.5, 140.0),
            sampleFlight("DATE", "AAA", 30.0, 170.0, "BBB", 40.0, -170.0),
            sampleFlight("LONG", "AAA", -20.0, -40.0, "BBB", 20.0, 139.5)
        )
        routes.forEach { route ->
            val planned = OfflinePlan.create(route)
            planned.layers.filter { it.id != "airport" }.forEach { layer ->
                GreatCircle.route(route.origin.point, route.destination.point, 3000).forEach { point ->
                    val lat = point.lat.coerceIn(-SlippyTiles.MAX_LATITUDE, SlippyTiles.MAX_LATITUDE)
                    assertTrue("${route.number}/${layer.id} misses $point", layer.boxes.any {
                        lat >= it.south - 1e-8 && lat <= it.north + 1e-8 && point.lon >= it.west - 1e-8 && point.lon <= it.east + 1e-8
                    })
                }
            }
        }
    }

    @Test
    fun estimatesMatchActualGeometryAndZoomRanges() {
        val route = sampleFlight("TEST", "HEL", 60.3, 25.0, "PVG", 31.1, 121.8)
        for ((tiles, bytes) in listOf(2900 to 49_000_000L, 300 to 5_000_000L)) {
            val planned = OfflinePlan.create(route, tiles, bytes)
            planned.layers.forEach {
                assertTrue(it.minZoom <= it.maxZoom)
                assertTrue(it.boxes.isNotEmpty())
                assertEquals(SlippyTiles.countUniqueTiles(it.boxes, it.minZoom.toInt(), it.maxZoom.toInt()), it.estimatedTiles)
            }
            assertEquals(planned.layers.sumOf { it.estimatedTiles }, planned.totalEstimatedTiles)
            assertEquals(planned.layers.sumOf { it.estimatedBytes }, planned.totalEstimatedBytes)
            assertTrue(planned.totalEstimatedTiles <= tiles)
            assertTrue(planned.totalEstimatedBytes <= bytes)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleByteBudgetIsRejected() {
        OfflinePlan.create(sampleFlight("TEST", "HEL", 60.3, 25.0, "PVG", 31.1, 121.8), maxBytes = 1)
    }

    @Test
    fun changedRouteCannotReuseOldMetadata() {
        val a = sampleFlight("TEST", "HEL", 60.3, 25.0, "PVG", 31.1, 121.8)
        val b = a.copy(destination = a.destination.copy(iata = "LAX", point = GeoPoint(34.0, -118.4)))
        assertNotEquals(OfflinePlan.create(a).flightKey, OfflinePlan.create(b).flightKey)
    }

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
    fun testSlippyTileMath() {
        // Zoom 0 must cover exactly 1 tile (0, 0)
        val worldBox = GeoBox(-85.0, 85.0, -180.0, 180.0)
        val tilesZ0 = SlippyTiles.tilesForBox(worldBox, 0)
        assertEquals(1, tilesZ0.size)

        // Zoom 1 must cover exactly 4 tiles
        val tilesZ1 = SlippyTiles.tilesForBox(worldBox, 1)
        assertEquals(4, tilesZ1.size)

        // Coordinate clamping and mapping
        assertEquals(0, SlippyTiles.tileX(-180.0, 5))
        assertEquals(31, SlippyTiles.tileX(179.9, 5))
        assertEquals(0, SlippyTiles.tileY(85.0, 5))
        assertEquals(31, SlippyTiles.tileY(-85.0, 5))
    }

    @Test
    fun testShortRoute() {
        // JFK to BOS (~300 km)
        val plan = sampleFlight("B6123", "JFK", 40.6398, -73.7789, "BOS", 42.3643, -71.0052)
        val offlinePlan = OfflinePlan.create(plan)

        assertEquals("B6123:JFK-BOS:2026-10-20T10:00:00Z:2026-10-20T22:00:00Z:v2", offlinePlan.flightKey)
        assertEquals(3, offlinePlan.layers.size)

        val broad = offlinePlan.layers.first { it.id == "broad" }
        val corridor = offlinePlan.layers.first { it.id == "corridor" }
        val airport = offlinePlan.layers.first { it.id == "airport" }

        assertEquals(0.0, broad.minZoom, 0.01)
        assertEquals(6.0, broad.maxZoom, 0.01)
        assertEquals(7.0, corridor.minZoom, 0.01)
        assertEquals(9.0, corridor.maxZoom, 0.01)
        assertEquals(9.0, airport.minZoom, 0.01)
        assertEquals(10.0, airport.maxZoom, 0.01)

        assertTrue("Short route should have modest tile count", offlinePlan.totalEstimatedTiles in 20..200)
        assertTrue(offlinePlan.totalEstimatedTiles <= OfflinePlanConfig.MAX_TILES)
        assertTrue(offlinePlan.totalEstimatedBytes <= OfflinePlanConfig.MAX_BYTES)

        // Verify per-layer tile count matches geometry and zoom levels
        offlinePlan.layers.forEach { layer ->
            val actual = SlippyTiles.countUniqueTiles(layer.boxes, layer.minZoom.toInt(), layer.maxZoom.toInt())
            assertEquals("Tile count for layer ${layer.id} must match geometry", actual, layer.estimatedTiles)
            assertTrue("Layer ${layer.id} minZoom <= maxZoom", layer.minZoom <= layer.maxZoom)
        }
    }

    @Test
    fun testMediumRoute() {
        // HEL to PVG (Finnair AY87, ~7,400 km)
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val offlinePlan = OfflinePlan.create(plan)

        assertTrue(offlinePlan.totalEstimatedTiles in 250..1200)
        assertTrue(offlinePlan.totalEstimatedTiles <= 2900)
        assertTrue(offlinePlan.totalEstimatedBytes <= 49_000_000L)

        // All boxes must have valid coordinates
        offlinePlan.layers.flatMap { it.boxes }.forEach { box ->
            assertTrue(box.south <= box.north)
            assertTrue(box.west <= box.east)
            assertTrue(box.south >= -85.05112878 && box.north <= 85.05112878)
            assertTrue(box.west >= -180.0 && box.east <= 180.0)
        }

        offlinePlan.layers.forEach { layer ->
            val actual = SlippyTiles.countUniqueTiles(layer.boxes, layer.minZoom.toInt(), layer.maxZoom.toInt())
            assertEquals("Tile count for layer ${layer.id} must match geometry", actual, layer.estimatedTiles)
        }
    }

    @Test
    fun testLongRoute() {
        // SIN to EWR (~15,300 km ultra-long haul)
        val plan = sampleFlight("SQ22", "SIN", 1.3592, 103.9894, "EWR", 40.6925, -74.1686)
        val offlinePlan = OfflinePlan.create(plan)

        assertTrue(offlinePlan.totalEstimatedTiles > 500)
        assertTrue("Must strictly stay within 2900 tile limit", offlinePlan.totalEstimatedTiles <= 2900)
        assertTrue("Must strictly stay within 49MB limit", offlinePlan.totalEstimatedBytes <= 49_000_000L)

        offlinePlan.layers.forEach { layer ->
            val actual = SlippyTiles.countUniqueTiles(layer.boxes, layer.minZoom.toInt(), layer.maxZoom.toInt())
            assertEquals("Tile count for layer ${layer.id} must match geometry", actual, layer.estimatedTiles)
        }
    }

    @Test
    fun testDateLineCrossingRoute() {
        // HNL to NRT (Honolulu to Tokyo Narita, crosses 180° meridian)
        val plan = sampleFlight("NH183", "HNL", 21.3187, -157.9224, "NRT", 35.7647, 140.3864)
        val offlinePlan = OfflinePlan.create(plan)

        assertTrue(offlinePlan.totalEstimatedTiles in 100..600)
        assertTrue(offlinePlan.totalEstimatedTiles <= 2900)

        // Verify that boxes near the date line split properly and stay within [-180, 180]
        val allBoxes = offlinePlan.layers.flatMap { it.boxes }
        allBoxes.forEach { box ->
            assertTrue(box.west >= -180.0)
            assertTrue(box.east <= 180.0)
            assertTrue(box.west <= box.east)
        }

        // Must have boxes on both sides of the date line (positive east longitude and negative west longitude)
        assertTrue(allBoxes.any { it.east > 170.0 })
        assertTrue(allBoxes.any { it.west < -170.0 })
    }

    @Test
    fun testPolarRoute() {
        // DXB to LAX (passes near Arctic / 83°N)
        val plan = sampleFlight("EK215", "DXB", 25.2528, 55.3644, "LAX", 33.9425, -118.4081)
        val offlinePlan = OfflinePlan.create(plan)

        assertTrue(offlinePlan.totalEstimatedTiles <= 2900)
        assertTrue(offlinePlan.totalEstimatedBytes <= 49_000_000L)

        offlinePlan.layers.flatMap { it.boxes }.forEach { box ->
            assertFalse(box.south.isNaN())
            assertFalse(box.north.isNaN())
            assertFalse(box.west.isNaN())
            assertFalse(box.east.isNaN())
            assertTrue(box.north <= 85.05112878)
            assertTrue(box.south >= -85.05112878)
        }
    }

    @Test
    fun testAdaptationUnderConstrainedBudget() {
        // Under a constrained budget, the planner should adaptively tune corridor and detail
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val constrainedPlan = OfflinePlan.create(plan, maxTiles = 300, maxBytes = 25_000_000L)

        assertTrue(
            "Constrained tile count (${constrainedPlan.totalEstimatedTiles}) must be <= 300",
            constrainedPlan.totalEstimatedTiles <= 300
        )
        assertTrue(
            "Constrained byte count (${constrainedPlan.totalEstimatedBytes}) must be <= 25MB",
            constrainedPlan.totalEstimatedBytes <= 25_000_000L
        )

        constrainedPlan.layers.forEach { layer ->
            val actual = SlippyTiles.countUniqueTiles(layer.boxes, layer.minZoom.toInt(), layer.maxZoom.toInt())
            assertEquals("Tile count for layer ${layer.id} must match geometry", actual, layer.estimatedTiles)
            assertTrue("Layer ${layer.id} minZoom <= maxZoom", layer.minZoom <= layer.maxZoom)
        }

        // Custom tiny budget that cannot fit any valid coverage throws IllegalArgumentException
        try {
            OfflinePlan.create(plan, maxTiles = 2)
            fail("Expected IllegalArgumentException for impossible tile budget")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot fit route into requested budget") == true)
        }

        // Custom tiny byte budget throws IllegalArgumentException
        try {
            OfflinePlan.create(plan, maxBytes = 500L)
            fail("Expected IllegalArgumentException for impossible byte budget")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot fit route into requested budget") == true)
        }
    }

    @Test
    fun testDenserCorridorZoom9AndAdaptiveFallback() {
        val standardPlan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val standardOffline = OfflinePlan.create(standardPlan)
        val standardCorridor = standardOffline.layers.first { it.id == "corridor" }
        assertEquals(9.0, standardCorridor.maxZoom, 0.01)
        assertTrue(standardOffline.totalEstimatedTiles in 500..1500)
        assertTrue(standardOffline.totalEstimatedTiles <= 2900)

        // When budget is constrained to 350 tiles, corridor adaptively falls back to zoom 8
        val constrained = OfflinePlan.create(standardPlan, maxTiles = 350, maxBytes = 20_000_000L)
        val constrainedCorridor = constrained.layers.first { it.id == "corridor" }
        assertTrue(constrainedCorridor.maxZoom <= 8.0)
        assertTrue(constrained.totalEstimatedTiles <= 350)
    }

    @Test
    fun testSummaryAPI() {
        val plan = sampleFlight("AY87", "HEL", 60.3172, 24.9633, "PVG", 31.1443, 121.8083)
        val summary = OfflinePlan.summary(plan)

        assertEquals(2900, summary.maxTiles)
        assertEquals(49_000_000L, summary.maxBytes)
        assertTrue(summary.layers.isNotEmpty())

        val formatted = summary.formattedSummary()
        assertTrue("Formatted summary should contain 'tiles': $formatted", formatted.contains("tiles"))
        assertTrue("Formatted summary should contain 'MB': $formatted", formatted.contains("MB"))

        val budget = summary.formattedBudget()
        assertTrue(budget.contains("2900 tiles"))
    }
}
