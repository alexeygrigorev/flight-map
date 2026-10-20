package com.flightmap.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OfflineFlightContextTest {
    private val pvg = Airport("PVG", "Shanghai Pudong", GeoPoint(31.1434, 121.805), "Asia/Shanghai")
    private val dyg = Airport("DYG", "Zhangjiajie", GeoPoint(29.1028, 110.443), "Asia/Shanghai")
    private val plan = FlightPlan("MU689", pvg, dyg,
        Instant.parse("2026-10-20T02:00:00Z"), Instant.parse("2026-10-20T04:10:00Z"))

    @Test fun publishedValidityHasHardBoundsAndCorrectUtcConversion() {
        assertTrue(OfflineFlightContext.schedulesOn(LocalDate.of(2026, 9, 16)).isEmpty())
        assertTrue(OfflineFlightContext.schedulesOn(LocalDate.of(2026, 10, 25)).isEmpty())
        val schedule = OfflineFlightContext.schedulesOn(LocalDate.of(2026, 10, 24))
        assertEquals(2, schedule.size)
        assertEquals(Instant.parse("2026-10-24T01:45:00Z"), schedule.first().departure)
        assertEquals(Instant.parse("2026-10-24T04:35:00Z"), schedule.last().arrival)
        assertTrue(schedule.all { it.sourceUrl.startsWith("https://") })
    }

    @Test fun preparationKeepsOnlySynchronizedRouteAndTimeOverlap() {
        val prepared = OfflineFlightContext.prepare(plan)
        assertEquals(setOf("MU5323"), prepared.flights.map { it.number }.toSet())
        assertTrue(OfflineFlightContext.prepare(plan.copy(
            origin = pvg.copy(point = GeoPoint(60.0, 20.0)),
            destination = dyg.copy(point = GeoPoint(50.0, 30.0)))).flights.isEmpty())
        assertTrue(OfflineFlightContext.prepare(plan.copy(
            departure = Instant.parse("2026-10-20T12:00:00Z"),
            arrival = Instant.parse("2026-10-20T14:00:00Z"))).flights.isEmpty())
        assertFalse(OfflineFlightContext.prepare(plan.copy(number = "MU 5323")).flights.any { it.number == "MU5323" })
    }

    @Test fun distantAndInactiveAircraftAreNeverReturned() {
        val context = OfflineFlightContext.prepare(plan)
        val time = Instant.parse("2026-10-20T02:45:00Z")
        val point = FlightTiming.progress(plan, time).point
        val nearby = OfflineFlightContext.nearby(context, time, point)
        assertTrue(nearby.isNotEmpty())
        assertTrue(nearby.all { it.distanceKm <= 150.0 && it.evidenceLabel.contains("not ADS-B") })
        assertTrue(nearby.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
        assertTrue(OfflineFlightContext.nearby(context, time, GeoPoint(-30.0, -70.0)).isEmpty())
        assertTrue(OfflineFlightContext.nearby(context, time, point, 0.0).isEmpty())
        assertTrue(OfflineFlightContext.nearby(context, plan.departure.minusSeconds(1), point).isEmpty())
        assertTrue(OfflineFlightContext.nearby(context, plan.arrival, point).isEmpty())
        val fm = OfflineFlightContext.schedulesOn(LocalDate.of(2026, 10, 20)).first { it.number == "FM9393" }
        assertFalse(OfflineFlightContext.nearby(context, fm.departure.minusSeconds(1), fm.origin, 500.0)
            .any { it.schedule.number == fm.number })
    }

    @Test fun jsonRestoredPlanRegeneratesSameContextAndSavedContextReplaysOffline() {
        val gson = GsonBuilder()
            .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { value, _, _ -> JsonPrimitive(value.toString()) })
            .registerTypeAdapter(Instant::class.java, JsonDeserializer { value, _, _ -> Instant.parse(value.asString) })
            .create()
        val prepared = OfflineFlightContext.prepare(plan)
        val restoredPlan = gson.fromJson(gson.toJson(plan), FlightPlan::class.java)
        val restoredContext = gson.fromJson(gson.toJson(prepared), PreparedFlightContext::class.java)
        assertEquals(prepared, OfflineFlightContext.prepare(restoredPlan))
        assertEquals(prepared, restoredContext)
        val time = plan.departure.plusSeconds(2700)
        val point = FlightTiming.progress(plan, time).point
        assertEquals(OfflineFlightContext.nearby(prepared, time, point), OfflineFlightContext.nearby(restoredContext, time, point))
    }

    @Test fun terrainIsSourcedAlongCorridorAndNeverInsertedInRemoteRoute() {
        val peaks = OfflineFlightContext.prepare(plan).peaks
        assertTrue(peaks.map { it.id }.containsAll(listOf("tianmen", "tianzi", "huangshan-lotus", "lushan-hanyang")))
        assertTrue(peaks.all { it.fact.contains("Sources:") && it.elevationM > 0 })
        assertEquals(GeoPoint(29.049905, 110.478835), peaks.first { it.id == "tianmen" }.point)
        assertTrue(OfflineFlightContext.prepare(plan.copy(
            origin = pvg.copy(point = GeoPoint(-40.0, -70.0)),
            destination = dyg.copy(point = GeoPoint(-35.0, -60.0)))).peaks.isEmpty())
    }

    @Test fun estimatedAircraftLayoutRespectsSharedCollisionBoxes() {
        val time = plan.departure.plusSeconds(2700)
        val progress = FlightTiming.progress(plan, time)
        val aircraft = OfflineFlightContext.nearby(OfflineFlightContext.prepare(plan), time, progress.point)
        val projection = GlobeProjection(1920f, 1080f, 30.0, 117.5, 6.0)
        val layout = GlobeLabelPlanner.plan(projection, plan, progress, IfeCatalog.mountainPeaks,
            emptyList(), emptyList(), emptyList(), aircraft = aircraft)
        assertTrue(layout.aircraft.isNotEmpty())
        for (item in layout.aircraft) {
            assertTrue(layout.peaks.none { it.box.intersects(item.box) })
            assertTrue(layout.cities.none { it.box.intersects(item.box) })
            assertTrue(layout.endpoints.none { it.box.intersects(item.box) })
        }
    }
}
