package com.flightmap.app

import com.flightmap.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FlightPresentationTest {
    private val departure = Instant.parse("2026-10-20T02:00:00Z")
    private val plan = FlightPlan("MU689",
        Airport("PVG", "Shanghai", GeoPoint(31.14, 121.8), "Asia/Shanghai"),
        Airport("DYG", "Zhangjiajie", GeoPoint(29.1, 110.44), "Asia/Shanghai"),
        departure, departure.plusSeconds(7800))

    @Test fun localTakeoffUsesOriginZoneAndExplicitDay() {
        assertEquals(departure.plusSeconds(1800), FlightPresentation.parseTakeoff("2026-10-20T10:30", "Asia/Shanghai"))
        assertEquals(Instant.parse("2026-10-20T16:15:00Z"), FlightPresentation.parseTakeoff("2026-10-21T00:15", "Asia/Shanghai"))
    }

    @Test fun daylightSavingAmbiguityRequiresAnExplicitChoice() {
        for (value in listOf("2026-10-25T02:30", "2026-03-29T02:30")) {
            assertThrows(IllegalArgumentException::class.java) { FlightPresentation.parseTakeoff(value, "Europe/Berlin") }
        }
    }

    @Test fun delayedTakeoffMovesBothProgressAndDisplayedEta() {
        val delayed = plan.copy(actualTakeoff = departure.plusSeconds(1800))
        val now = departure.plusSeconds(3600)
        val progress = FlightTiming.progress(delayed, now)
        assertTrue(progress.fraction < FlightTiming.progress(plan, now).fraction)
        assertEquals("ETA 12:40 destination time", FlightPresentation.arrival(delayed, progress, now))
    }

    @Test fun arrivalDistinguishesEstimateFromConfirmedLanding() {
        val estimated = FlightTiming.progress(plan, plan.arrival.plusSeconds(60))
        assertEquals("Arrived · estimated", FlightPresentation.status(estimated))
        assertEquals("Estimated arrival reached", FlightPresentation.arrival(plan, estimated, plan.arrival))
        val landed = plan.copy(landedAt = departure.plusSeconds(3600))
        val progress = FlightTiming.progress(landed, departure.plusSeconds(3601))
        assertEquals("Landed ✓", FlightPresentation.status(progress, true))
        assertEquals("Landed 11:00 local", FlightPresentation.arrival(landed, progress, departure.plusSeconds(3601)))
        assertEquals(1.0, progress.fraction, 0.0)
    }

    @Test fun unknownAltitudeNeverBecomesAClaimedObservation() {
        val progress = FlightTiming.progress(plan, departure.plusSeconds(600)).copy(altitudeMeters = null)
        assertEquals("Altitude unavailable", FlightPresentation.altitude(progress))
        assertTrue(FlightPresentation.altitude(progress.copy(altitudeMeters = 9800.0)).startsWith("Estimated altitude "))
    }

    @Test fun explicitAirborneEstimateOverridesScheduledBlockDuration() {
        val corrected = plan.copy(actualTakeoff = departure.plusSeconds(1800), estimatedAirborneMinutes = 90)
        val now = departure.plusSeconds(3600)
        val progress = FlightTiming.progress(corrected, now)
        assertEquals("ETA 12:00 destination time", FlightPresentation.arrival(corrected, progress, now))
        assertEquals("User estimate: 90 min airborne", FlightPresentation.durationSource(corrected))
        assertTrue(FlightPresentation.durationSource(plan).contains("includes taxi time"))
        assertNull(FlightPresentation.parseAirborneMinutes(""))
        assertEquals(90, FlightPresentation.parseAirborneMinutes("90"))
        for (input in listOf("0", "1441", "1.5", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) { FlightPresentation.parseAirborneMinutes(input) }
        }
    }

    @Test fun timingEditsKeepPackIdentityAndHistoricalMetadata() {
        val updated = plan.copy(actualTakeoff = departure.plusSeconds(1800), landedAt = departure.plusSeconds(7800))
        assertTrue(isSameFlight(plan, updated))
        val restored = FlightPackCodec.createGson().fromJson(FlightPackCodec.createGson().toJson(updated), FlightPlan::class.java)
        assertEquals(updated, restored)
    }
}
