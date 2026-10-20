package com.flightmap.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.security.MessageDigest

/** A published timetable entry, never an aircraft observation or a flight-status claim. */
data class OfflineScheduledFlight(
    val number: String,
    val originCode: String,
    val destinationCode: String,
    val origin: GeoPoint,
    val destination: GeoPoint,
    val departure: Instant,
    val arrival: Instant,
    val aircraftType: String,
    val sourceUrl: String,
    val sourceCheckedOn: String = "2026-10-20"
)

/** Serializable with the same Instant adapter as FlightPlan. No connection needed to replay. */
data class PreparedFlightContext(
    val peaks: List<MountainPeak>,
    val flights: List<OfflineScheduledFlight>,
    val windowStart: Instant,
    val windowEnd: Instant,
    val catalogVersion: Int = 1,
    val planKey: String = ""
)

data class EstimatedAircraft(
    val schedule: OfflineScheduledFlight,
    val point: GeoPoint,
    val heading: Double,
    val distanceKm: Double
) {
    val evidenceLabel: String get() = "Schedule estimate · not live · not ADS-B"
}

/**
 * Small, deliberately bounded offline context catalog. Traffic is interpolated at constant
 * scheduled block-time fraction along airport-to-airport great circles. It includes taxi time,
 * cannot identify a visible aircraft, and supplies no registration, observed altitude or track.
 * No unverified recurrence beyond the published validity dates, no generated traffic filler.
 */
object OfflineFlightContext {
    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private const val SCHEDULE_SOURCE = "https://zbordirect.com/en/tools/schedule?departure_city=SHA&arrival_city=CSX"

    /** Reuse preparation output only for precisely the same route and playback window. */
    fun forPlan(plan: FlightPlan, saved: PreparedFlightContext?): PreparedFlightContext =
        saved?.takeIf { it.catalogVersion == 1 && it.planKey == planKey(plan) } ?: prepare(plan)

    private fun planKey(plan: FlightPlan): String {
        val input = buildString {
            append(plan.number); append('|'); append(plan.origin.iata); append('|'); append(plan.destination.iata)
            append('|'); append(plan.departure); append('|'); append(plan.arrival)
            for (point in plan.effectiveWaypoints) {
                append('|'); append(point.lat); append(','); append(point.lon)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Published daily summer timetable, 17 September through 24 October 2026 inclusive.
     * Airport points: bundled OurAirports dataset. Equipment is scheduled, not observed.
     */
    fun schedulesOn(date: LocalDate): List<OfflineScheduledFlight> {
        if (date < LocalDate.of(2026, 9, 17) || date > LocalDate.of(2026, 10, 24)) return emptyList()
        fun entry(number: String, code: String, point: GeoPoint, dep: String, arr: String, equipment: String) =
            OfflineScheduledFlight(number, code, "CSX", point, GeoPoint(28.1892, 113.2200),
                date.atTime(LocalTime.parse(dep)).atZone(chinaZone).toInstant(),
                date.atTime(LocalTime.parse(arr)).atZone(chinaZone).toInstant(), equipment, SCHEDULE_SOURCE)
        return listOf(
            entry("MU5323", "SHA", GeoPoint(31.1981, 121.33426), "09:45", "11:40", "Scheduled Airbus A321"),
            entry("FM9393", "PVG", GeoPoint(31.1434, 121.8050), "10:25", "12:35", "Scheduled Boeing 737-800")
        )
    }

    /** Called during preparation; old packs can derive exactly the same result offline. */
    fun prepare(plan: FlightPlan): PreparedFlightContext {
        val start = plan.departure
        val end = plan.arrival
        val firstDay = start.atZone(chinaZone).toLocalDate().minusDays(1)
            .coerceAtLeast(LocalDate.of(2026, 9, 17))
        val lastDay = end.atZone(chinaZone).toLocalDate().coerceAtMost(LocalDate.of(2026, 10, 24))
        val flights = mutableListOf<OfflineScheduledFlight>()
        var date = firstDay
        while (date <= lastDay) {
            flights += schedulesOn(date).filter { flight ->
                flight.number != plan.number.replace(" ", "").uppercase() &&
                    flight.arrival > start && flight.departure < end && overlapsRoute(plan, flight)
            }
            date = date.plusDays(1)
        }
        val peakCities = IfeCatalog.mountainPeaks.map { peak ->
            City(peak.id, peak.name, "", peak.point, "UTC", 0)
        }
        val ids = GreatCircle.corridorCities(plan, peakCities, 200.0).map { it.id }.toSet()
        return PreparedFlightContext(IfeCatalog.mountainPeaks.filter { it.id in ids }, flights, start, end,
            planKey = planKey(plan))
    }

    // One-minute synchronized samples, with 50 km margin beyond the displayed 150 km radius.
    // This is an estimate filter, not an assertion that the two aircraft actually met.
    private fun overlapsRoute(plan: FlightPlan, flight: OfflineScheduledFlight): Boolean {
        var time = maxOf(plan.departure, flight.departure)
        val end = minOf(plan.arrival, flight.arrival)
        while (time <= end) {
            val point = estimatedPosition(flight, time).first
            if (GreatCircle.distanceKm(FlightTiming.progress(plan, time).point, point) <= 200.0) return true
            time = time.plusSeconds(60)
        }
        return false
    }

    fun nearby(context: PreparedFlightContext, now: Instant, ownPoint: GeoPoint, radiusKm: Double = 150.0): List<EstimatedAircraft> {
        require(radiusKm.isFinite() && radiusKm >= 0.0)
        if (now < context.windowStart || now >= context.windowEnd) return emptyList()
        return context.flights.asSequence()
            .filter { now >= it.departure && now < it.arrival && it.arrival > it.departure }
            .map { flight ->
                val (point, heading) = estimatedPosition(flight, now)
                EstimatedAircraft(flight, point, heading, GreatCircle.distanceKm(ownPoint, point))
            }.filter { it.distanceKm <= radiusKm }
            .sortedWith(compareBy<EstimatedAircraft> { it.distanceKm }.thenBy { it.schedule.number })
            .toList()
    }

    private fun estimatedPosition(flight: OfflineScheduledFlight, time: Instant): Pair<GeoPoint, Double> {
        val fraction = Duration.between(flight.departure, time).toMillis().toDouble() /
            Duration.between(flight.departure, flight.arrival).toMillis()
        return GreatCircle.interpolateWaypointsWithHeading(listOf(flight.origin, flight.destination), fraction.coerceIn(0.0, 1.0))
    }
}
