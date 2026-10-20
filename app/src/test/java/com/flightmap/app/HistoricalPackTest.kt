package com.flightmap.app

import com.flightmap.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class HistoricalPackTest {
    private val departure = Instant.parse("2026-10-20T02:00:00Z")
    private val origin = Airport("PVG", "Pudong", GeoPoint(31.1434, 121.805), "Asia/Shanghai")
    private val destination = Airport("DYG", "Hehua", GeoPoint(29.1048, 110.4428), "Asia/Shanghai")
    private val gson = FlightPackCodec.createGson()

    private fun bundled(): HistoricalTrack {
        val file = File("src/main/assets/data/history/MU689-PVG-DYG.json")
        return gson.fromJson(file.readText(), HistoricalTrack::class.java)
    }

    @Test fun realBundlePreservesSourceAndNeverClaimsCompletePlayback() {
        val history = bundled()
        val plan = FlightPlan("MU689", origin, destination, departure, departure.plusSeconds(7800), historicalTrack = history)
        assertTrue(history.valid)
        assertEquals(103, history.points.size)
        assertEquals("2026-10-06T03:53:38Z", history.observedDeparture.toString())
        assertEquals(false, history.coverageComplete)
        assertNull(history.airborneDuration)
        assertNull(history.blockDuration)
        assertNull(plan.effectiveHistoricalTrack)
        assertFalse(plan.isRealTrajectory)
        assertTrue(plan.effectiveRouteLabel.contains("partial history"))
        assertEquals("ODbL-1.0", history.license)
    }

    @Test fun codecAndEnvelopeRoundTripKeepHistoricalProvenanceAndUserTimes() {
        val plan = FlightPlan("MU689", origin, destination, departure, departure.plusSeconds(7800),
            historicalTrack = bundled(), actualTakeoff = departure.plusSeconds(600),
            landedAt = departure.plusSeconds(7500), estimatedAirborneMinutes = 115)
        val pack = FlightPack(plan, emptyList(), emptyMap(), departure, true)
        val single = FlightPackCodec.parsePacks(FlightPackCodec.exportPackJson(pack)).single()
        assertEquals(plan.historicalTrack, single.plan.historicalTrack)
        assertEquals(plan.actualTakeoff, single.plan.actualTakeoff)
        assertEquals(plan.landedAt, single.plan.landedAt)
        assertEquals(plan.estimatedAirborneMinutes, single.plan.estimatedAirborneMinutes)
        assertNotNull(single.offlineContext)
        val envelope = """{"format":"flight-map-pack","flight":${gson.toJson(pack)}}"""
        val restored = FlightPackCodec.parsePacks(envelope).single()
        assertEquals(plan.historicalTrack, restored.plan.historicalTrack)
        assertEquals(plan.actualTakeoff, restored.plan.actualTakeoff)
        assertEquals(plan.landedAt, restored.plan.landedAt)
        assertEquals(115, restored.plan.estimatedAirborneMinutes)
        assertNotNull(restored.offlineContext)
    }

    @Test fun lightweightImportRetainsUnverifiedUserRouteRatherThanReplacingIt() {
        val plan = FlightPlan("MU689", origin, destination, departure, departure.plusSeconds(7800),
            waypoints = listOf(origin.point, GeoPoint(31.0, 118.0), destination.point), routeSource = "USER_SUPPLIED_ROUTE")
        val json = """{"format":"flight-map-multipack","flights":[{"plan":${gson.toJson(plan)}}]}"""
        val restored = FlightPackCodec.parsePacks(json).single().plan
        assertEquals(plan.waypoints, restored.effectiveWaypoints)
        assertEquals("USER_SUPPLIED_ROUTE", restored.routeSource)
        assertFalse(restored.isRealTrajectory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedHistoricalImportIsRejected() {
        val plan = FlightPlan("MU689", origin, destination, departure, departure.plusSeconds(7800))
        val json = gson.toJsonTree(FlightPack(plan, emptyList(), emptyMap(), departure, false)).asJsonObject
        json.getAsJsonObject("plan").add("historicalTrack", com.google.gson.JsonObject())
        FlightPackCodec.parsePacks(json.toString())
    }
}
