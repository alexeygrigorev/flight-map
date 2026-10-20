package com.flightmap.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class HistoricalTrackTest {
    private val start = Instant.parse("2026-09-06T02:00:00Z")
    private val origin = Airport("AAA", "Test origin", GeoPoint(0.0, 0.0), "UTC")
    private val destination = Airport("BBB", "Test destination", GeoPoint(0.0, 2.0), "UTC")
    // Synthetic test fixture only; never bundled or represented as an actual flight.
    private val track = HistoricalTrack("TEST1", "AAA", "BBB", start,
        listOf(HistoricalTrackPoint(0.0, origin.point, 0.0),
            HistoricalTrackPoint(60.0, GeoPoint(0.0, 1.0), 1000.0),
            HistoricalTrackPoint(600.0, destination.point, 100.0)),
        "Synthetic unit-test fixture", "https://example.org/test", "CC0",
        observedTakeoff = start.plusSeconds(60), observedLanding = start.plusSeconds(540),
        observedArrival = start.plusSeconds(600), coverageComplete = true)
    private val plan = FlightPlan("TEST1", origin, destination, start.plus(Duration.ofDays(1)),
        start.plus(Duration.ofDays(1)).plusSeconds(1200), historicalTrack = track)

    @Test fun nonuniformObservationsUseTimeNotDistanceAndInterpolateAltitude() {
        val p = FlightTiming.progress(plan, plan.departure.plusSeconds(60))
        assertEquals(0.1, p.fraction, 0.00001)
        assertEquals(1.0, p.point.lon, 0.00001)
        assertEquals(1000.0, p.altitudeMeters!!, 0.001)
        assertTrue(p.altitudeSource!!.startsWith("Estimated from historical profile"))
        assertEquals(p.flownKm, p.remainingKm, 0.001)
        assertEquals(Instant.parse("2026-09-07T02:10:00Z"), FlightTiming.estimatedArrival(plan))
    }

    @Test fun historicalAirborneDurationAnchorsActualTakeoffWithoutTaxi() {
        val p = plan.copy(actualTakeoff = plan.departure.plusSeconds(900))
        assertEquals(Duration.ofSeconds(480), track.airborneDuration)
        assertEquals(Duration.ofSeconds(600), track.blockDuration)
        assertEquals(p.actualTakeoff!!.plusSeconds(480), FlightTiming.estimatedArrival(p))
        assertEquals(1.0, FlightTiming.progress(p, p.actualTakeoff).point.lon, 0.0001)
        assertEquals("SCHEDULED", FlightTiming.progress(p, p.actualTakeoff.minusSeconds(1)).phase)
    }

    @Test fun landedOverrideImmediatelyCompletesAndRemovesAirborneAltitude() {
        val p = plan.copy(actualTakeoff = plan.departure, landedAt = plan.departure.plusSeconds(120))
        val arrived = FlightTiming.progress(p, p.landedAt!!)
        assertEquals("ARRIVED", arrived.phase)
        assertEquals(destination.point, arrived.point)
        assertEquals(0.0, arrived.remainingKm, 0.0)
        assertEquals(Duration.ZERO, arrived.remaining)
        assertNull(arrived.altitudeMeters)
        assertNull(arrived.altitudeSource)
        assertEquals(p.landedAt, FlightTiming.timeAtFraction(p, 1.0))
    }

    @Test fun importedEstimateOverridesBlockAndClampsInvalidLegacyValues() {
        val p = plan.copy(historicalTrack = null, actualTakeoff = plan.departure.plusSeconds(300), estimatedAirborneMinutes = 90)
        assertEquals(p.actualTakeoff!!.plusSeconds(5400), FlightTiming.estimatedArrival(p))
        assertEquals(p.actualTakeoff.plusSeconds(1200), FlightTiming.estimatedArrival(p.copy(estimatedAirborneMinutes = -5)))
        assertTrue(FlightTiming.progress(p, p.actualTakeoff.plusSeconds(1800)).altitudeSource!!.contains("not measured"))
    }

    @Test fun malformedGsonAndIncompleteTracksNeverReplaceEstimatedRoute() {
        val gson = GsonBuilder().registerTypeAdapter(Instant::class.java,
            JsonDeserializer { json, _, _ -> Instant.parse(json.asString) }).create()
        assertFalse(gson.fromJson("{}", HistoricalTrack::class.java).valid)
        assertFalse(track.copy(points = track.points.reversed()).valid)
        assertFalse(track.copy(points = track.points.map { it.copy(offsetSeconds = Double.NaN) }).valid)
        val partial = plan.copy(historicalTrack = track.copy(coverageComplete = false))
        assertNull(partial.effectiveHistoricalTrack)
        assertFalse(partial.isRealTrajectory)
        assertTrue(partial.effectiveRouteLabel.contains("partial history"))
        assertEquals(listOf(origin.point, destination.point), partial.effectiveWaypoints)
        assertNull(plan.copy(historicalTrack = track.copy(flightNumber = "WRONG")).effectiveHistoricalTrack)
    }

    @Test fun partialOverlayOmitsReceiverGapsAndRejectsMismatchedOrCompleteHistory() {
        val points = listOf(
            HistoricalTrackPoint(0.0, GeoPoint(0.0, 0.5)),
            HistoricalTrackPoint(60.0, GeoPoint(0.1, 0.6)),
            HistoricalTrackPoint(400.0, GeoPoint(0.2, 0.7)),
            HistoricalTrackPoint(450.0, GeoPoint(0.3, 0.8)))
        val partial = track.copy(points = points, coverageComplete = false,
            observedTakeoff = null, observedLanding = null, observedArrival = null)
        val p = plan.copy(historicalTrack = partial)
        assertEquals(listOf(points.take(2).map { it.point }, points.drop(2).map { it.point }), p.partialObservationSegments())
        assertEquals(listOf(origin.point, destination.point), p.effectiveWaypoints)
        assertFalse(p.isRealTrajectory)
        assertTrue(p.copy(historicalTrack = partial.copy(flightNumber = "WRONG")).partialObservationSegments().isEmpty())
        assertTrue(plan.partialObservationSegments().isEmpty())
    }

    @Test fun missingHistoricalAltitudeIsUnknownRatherThanInvented() {
        val p = plan.copy(historicalTrack = track.copy(points = track.points.map { it.copy(altitudeMeters = null) }))
        assertNull(FlightTiming.progress(p, p.departure.plusSeconds(200)).altitudeMeters)
    }

    @Test fun legacyGsonMissingOptionalFieldsRemainSafe() {
        val gson = GsonBuilder()
            .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value.toString()) })
            .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ -> Instant.parse(json.asString) }).create()
        val json = gson.toJsonTree(plan.copy(historicalTrack = null)).asJsonObject
        listOf("actualTakeoff", "landedAt", "historicalTrack", "estimatedAirborneMinutes").forEach(json::remove)
        val restored = gson.fromJson(json, FlightPlan::class.java)
        assertNull(restored.historicalTrack)
        assertNull(restored.actualTakeoff)
        assertEquals(plan.arrival, FlightTiming.estimatedArrival(restored))
        assertEquals("ARRIVED", FlightTiming.progress(restored, plan.arrival).phase)
    }
}
