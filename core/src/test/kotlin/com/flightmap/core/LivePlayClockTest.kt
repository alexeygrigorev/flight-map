package com.flightmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LivePlayClockTest {

    private val origin = Airport("PVG", "Shanghai Pudong", GeoPoint(31.1434, 121.805), "Asia/Shanghai")
    private val dest = Airport("DYG", "Zhangjiajie Hehua", GeoPoint(29.10475, 110.44279), "Asia/Shanghai")
    private val departure = Instant.parse("2026-10-20T02:00:00Z") // 10:00 Asia/Shanghai
    private val arrival = Instant.parse("2026-10-20T04:10:00Z") // 12:10 Asia/Shanghai
    private val plan = FlightPlan("MU689", origin, dest, departure, arrival)

    @Test
    fun playAtTakeoffAnchorsWallNowToDeparture() {
        val wallStart = Instant.parse("2026-10-19T12:00:00Z")
        val clock = LivePlayClock.start(plan, wallStart)
        assertEquals(departure, clock.flightNow(wallStart))
        assertEquals(0.0, clock.fraction(wallStart), 1e-9)
    }

    @Test
    fun clockAdvancesOneToOneWithWallTime() {
        val wallStart = Instant.parse("2026-10-19T12:00:00Z")
        val clock = LivePlayClock.start(plan, wallStart)
        val after65m = wallStart.plus(Duration.ofMinutes(65))
        val expected = departure.plus(Duration.ofMinutes(65))
        assertEquals(expected, clock.flightNow(after65m))
        val total = Duration.between(departure, arrival).toMillis().toDouble()
        val expectedFrac = Duration.ofMinutes(65).toMillis().toDouble() / total
        assertEquals(expectedFrac, clock.fraction(after65m), 1e-6)
    }

    @Test
    fun latePressStillStartsAtTakeoff() {
        // Pressing Play hours after scheduled departure still anchors to takeoff.
        val wallStart = Instant.parse("2026-10-25T08:00:00Z")
        val clock = LivePlayClock.start(plan, wallStart)
        assertEquals(departure, clock.flightNow(wallStart))
        assertEquals(0.0, clock.fraction(wallStart), 1e-9)
    }

    @Test
    fun scrubThenReturnToCurrent() {
        val wallStart = Instant.parse("2026-10-19T12:00:00Z")
        var clock = LivePlayClock.start(plan, wallStart)
        val wallNow = wallStart.plus(Duration.ofMinutes(30))
        clock = clock.withScrub(0.8)
        assertTrue(clock.isScrubbing)
        assertEquals(0.8, clock.fraction(wallNow), 1e-9)
        clock = clock.returnToCurrent()
        assertFalse(clock.isScrubbing)
        val expected = departure.plus(Duration.ofMinutes(30))
        assertEquals(expected, clock.flightNow(wallNow))
    }

    @Test
    fun clampsAtArrival() {
        val wallStart = Instant.parse("2026-10-19T12:00:00Z")
        val clock = LivePlayClock.start(plan, wallStart)
        val afterFlight = wallStart.plus(Duration.between(departure, arrival)).plus(Duration.ofHours(2))
        assertEquals(arrival, clock.flightNow(afterFlight))
        assertEquals(1.0, clock.fraction(afterFlight), 1e-9)
    }
}
