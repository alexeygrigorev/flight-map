package com.flightmap.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaceLabelsTest {
    private val start = Airport("AAA", "Start", GeoPoint(0.0, 0.0), "UTC")
    private val end = Airport("BBB", "End", GeoPoint(0.0, 60.0), "UTC")
    private val plan = FlightPlan("TEST", start, end, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600))
    private fun city(id: String, lon: Double, population: Int) = City(id, id, "XX", GeoPoint(0.0, lon), "UTC", population)

    @Test fun sparseTownsSurviveDenseBigCityCluster() {
        val cluster = (0..20).map { city("metro$it", it * 0.01, 1_000_000) }
        val towns = (1..5).map { city("town$it", it * 10.0, 2000) }
        val labels = PlaceLabels.overview(plan, cluster + towns, 12)
        assertTrue(labels.containsAll(towns))
        assertTrue(labels.size <= 12)
        assertEquals(labels.size, labels.map { it.id }.distinct().size)
    }

    @Test fun emptyAndZeroLimitAreSafe() {
        assertTrue(PlaceLabels.overview(plan, emptyList()).isEmpty())
        assertTrue(PlaceLabels.overview(plan, listOf(city("x", 0.0, 1)), 0).isEmpty())
    }

    @Test fun datelineTownSelected() {
        val p = plan.copy(origin = start.copy(point = GeoPoint(0.0, 170.0)), destination = end.copy(point = GeoPoint(0.0, -170.0)))
        val town = city("island", 179.0, 1000)
        assertTrue(PlaceLabels.overview(p, listOf(town)).contains(town))
    }
}
