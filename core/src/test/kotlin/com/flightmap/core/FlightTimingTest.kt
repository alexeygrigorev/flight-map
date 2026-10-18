package com.flightmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FlightTimingTest {

    private val origin = Airport("JFK", "John F. Kennedy", GeoPoint(40.6413, -73.7781), "America/New_York")
    private val dest = Airport("LHR", "Heathrow", GeoPoint(51.4700, -0.4543), "Europe/London")
    private val departure = Instant.parse("2026-10-18T10:00:00Z")
    private val arrival = Instant.parse("2026-10-18T18:00:00Z") // 8 hours duration
    private val plan = FlightPlan("BA178", origin, dest, departure, arrival)

    @Test
    fun testProgressBeforeDeparture() {
        val now = departure.minusSeconds(1800) // 30 min before
        val progress = FlightTiming.progress(plan, now)

        assertEquals(0.0, progress.fraction, 1e-6)
        assertEquals(origin.point.lat, progress.point.lat, 1e-6)
        assertEquals(origin.point.lon, progress.point.lon, 1e-6)
        assertEquals(0.0, progress.flownKm, 1e-6)
        val totalDist = GreatCircle.distanceKm(origin.point, dest.point)
        assertEquals(totalDist, progress.remainingKm, 1e-3)
        assertEquals(Duration.ofMinutes(510), progress.remaining)
        assertEquals("SCHEDULED", progress.phase)
    }

    @Test
    fun testProgressHalfway() {
        val now = departure.plusSeconds(14400) // 4 hours into 8 hour flight
        val progress = FlightTiming.progress(plan, now)

        assertEquals(0.5, progress.fraction, 1e-4)
        val expectedMid = GreatCircle.interpolate(origin.point, dest.point, 0.5)
        assertEquals(expectedMid.lat, progress.point.lat, 1e-4)
        assertEquals(expectedMid.lon, progress.point.lon, 1e-4)
        val totalDist = GreatCircle.distanceKm(origin.point, dest.point)
        assertEquals(totalDist / 2.0, progress.flownKm, 1e-1)
        assertEquals(totalDist / 2.0, progress.remainingKm, 1e-1)
        assertEquals(Duration.ofHours(4), progress.remaining)
        assertEquals("EN_ROUTE", progress.phase)
        assertTrue(progress.heading in 0.0..360.0)
    }

    @Test
    fun testProgressAtArrival() {
        val progress = FlightTiming.progress(plan, arrival)

        assertEquals(1.0, progress.fraction, 1e-6)
        assertEquals(dest.point.lat, progress.point.lat, 1e-4)
        assertEquals(dest.point.lon, progress.point.lon, 1e-4)
        val totalDist = GreatCircle.distanceKm(origin.point, dest.point)
        assertEquals(totalDist, progress.flownKm, 1e-1)
        assertEquals(0.0, progress.remainingKm, 1e-6)
        assertEquals(Duration.ZERO, progress.remaining)
        assertEquals("ARRIVED", progress.phase)
    }

    @Test
    fun testProgressAfterArrival() {
        val now = arrival.plusSeconds(3600)
        val progress = FlightTiming.progress(plan, now)

        assertEquals(1.0, progress.fraction, 1e-6)
        assertEquals(dest.point.lat, progress.point.lat, 1e-4)
        assertEquals(0.0, progress.remainingKm, 1e-6)
        assertEquals(Duration.ZERO, progress.remaining)
        assertEquals("ARRIVED", progress.phase)
    }
}
