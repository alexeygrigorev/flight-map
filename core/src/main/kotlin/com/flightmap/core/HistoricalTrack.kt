package com.flightmap.core

import java.time.Duration
import java.time.Instant

/** Observations, never synthesized route vertices. Offsets are seconds since observedDeparture.
 * Altitude is the source's recorded altitude in metres (often pressure altitude), not live height.
 * Event times are nullable: the first/last received position is not proof of takeoff/landing.
 */
data class HistoricalTrackPoint(
    val offsetSeconds: Double,
    val point: GeoPoint,
    val altitudeMeters: Double? = null
)

data class HistoricalTrack(
    val flightNumber: String,
    val originIata: String,
    val destinationIata: String,
    val observedDeparture: Instant,
    val points: List<HistoricalTrackPoint>,
    val source: String,
    val sourceUrl: String,
    val license: String,
    val observedTakeoff: Instant? = null,
    val observedLanding: Instant? = null,
    val observedArrival: Instant? = null,
    val coverageComplete: Boolean? = null,
    val coverageNote: String? = null
) {
    // Gson can bypass constructors and supply null for missing non-null fields. Validate on use.
    val valid: Boolean
        get() = runCatching {
            flightNumber.isNotBlank() && originIata.isNotBlank() && destinationIata.isNotBlank() &&
                source.isNotBlank() && sourceUrl.startsWith("https://") && license.isNotBlank() &&
                points.size in 2..100_000 && points.first().offsetSeconds == 0.0 &&
                points.last().offsetSeconds in 1.0..172_800.0 &&
                points.all {
                    it.offsetSeconds.isFinite() && it.point.lat.isFinite() && it.point.lon.isFinite() &&
                        it.point.lat in -90.0..90.0 && it.point.lon in -180.0..180.0 &&
                        (it.altitudeMeters == null || (it.altitudeMeters.isFinite() && it.altitudeMeters in -1000.0..30000.0))
                } && points.zipWithNext().all { (a, b) -> a.offsetSeconds < b.offsetSeconds } &&
                listOfNotNull(observedTakeoff, observedLanding, observedArrival).all {
                    it >= observedDeparture && it <= observedEnd
                } && (observedLanding == null || observedTakeoff == null || observedLanding > observedTakeoff) &&
                (observedArrival == null || observedLanding == null || observedArrival >= observedLanding)
        }.getOrDefault(false)

    val observedEnd: Instant
        get() = observedDeparture.plusMillis((points.last().offsetSeconds * 1000).toLong())
    val duration: Duration get() = Duration.between(observedDeparture, observedEnd)
    val airborneDuration: Duration?
        get() = if (observedTakeoff != null && observedLanding != null) Duration.between(observedTakeoff, observedLanding) else null
    /** Only available when the importer knows the gate departure/arrival, not inferred from coverage. */
    val blockDuration: Duration?
        get() = observedArrival?.let { Duration.between(observedDeparture, it) }

    fun identifies(plan: FlightPlan): Boolean = valid &&
        TrajectoryCatalog.normalizeFlightNumber(flightNumber) == TrajectoryCatalog.normalizeFlightNumber(plan.number) &&
        originIata == plan.origin.iata && destinationIata == plan.destination.iata

    fun matches(plan: FlightPlan): Boolean = identifies(plan) && coverageComplete != false &&
        GreatCircle.distanceKm(points.first().point, plan.origin.point) <= 30.0 &&
        GreatCircle.distanceKm(points.last().point, plan.destination.point) <= 30.0
}

/** Dated partial evidence only: no airport connectors and no lines across receiver gaps. */
fun FlightPlan.partialObservationSegments(): List<List<GeoPoint>> {
    val track = historicalTrack?.takeIf { it.identifies(this) && !it.matches(this) } ?: return emptyList()
    val segments = mutableListOf<List<GeoPoint>>()
    var segment = mutableListOf<GeoPoint>()
    track.points.forEachIndexed { index, observation ->
        val previous = track.points.getOrNull(index - 1)
        if (previous != null && (observation.offsetSeconds - previous.offsetSeconds > 120.0 ||
                kotlin.math.abs(observation.point.lon - previous.point.lon) > 180.0)) {
            if (segment.size >= 2) segments.add(segment)
            segment = mutableListOf()
        }
        segment.add(observation.point)
    }
    if (segment.size >= 2) segments.add(segment)
    return segments
}
