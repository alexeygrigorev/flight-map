package com.flightmap.core

import java.time.Duration
import java.time.Instant

object FlightTiming {
    fun effectiveDeparture(plan: FlightPlan): Instant = plan.actualTakeoff ?: plan.departure

    fun timeAtFraction(plan: FlightPlan, fraction: Double): Instant {
        val start = effectiveDeparture(plan)
        val elapsed = Duration.between(start, estimatedArrival(plan)).toMillis().coerceAtLeast(0)
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
        return start.plusMillis((elapsed * safeFraction).toLong())
    }

    /** Historical durations are a replay estimate for this flight, never live tracking. */
    fun estimatedArrival(plan: FlightPlan): Instant {
        plan.landedAt?.let { return it }
        plan.estimatedAirborneMinutes?.takeIf { it in 1..1440 }?.let {
            return effectiveDeparture(plan).plus(Duration.ofMinutes(it.toLong()))
        }
        val track = plan.effectiveHistoricalTrack
        val duration = if (track != null) {
            if (plan.actualTakeoff != null) track.airborneDuration ?: track.duration else track.duration
        } else Duration.between(plan.departure, plan.arrival)
        return effectiveDeparture(plan).plus(duration)
    }

    fun progress(plan: FlightPlan, now: Instant): FlightProgress {
        val start = effectiveDeparture(plan)
        val end = estimatedArrival(plan)
        val fraction = when {
            now >= end -> 1.0
            now <= start -> 0.0
            end <= start -> 1.0
            else -> (Duration.between(start, now).toMillis().toDouble() /
                Duration.between(start, end).toMillis()).coerceIn(0.0, 1.0)
        }
        val track = plan.effectiveHistoricalTrack
        val sample = track?.let { historicalSample(it, plan.actualTakeoff != null, fraction) }
        val waypoints = plan.effectiveWaypoints
        val totalKm = GreatCircle.routeDistanceKm(waypoints)
        val (samplePoint, heading) = sample?.let { it.point to it.heading }
            ?: GreatCircle.interpolateWaypointsWithHeading(waypoints, fraction)
        val flownKm = if (fraction >= 1.0) totalKm else sample?.flownKm ?: (totalKm * fraction)
        val phase = when {
            now >= end -> "ARRIVED"
            now < start -> "SCHEDULED"
            else -> "EN_ROUTE"
        }
        val altitude = if (phase != "EN_ROUTE") null else if (track != null) sample?.altitude else {
            // An explicit generic fallback, never stored as historical observations.
            val cruise = 10000.0
            cruise * minOf(fraction / 0.15, (1.0 - fraction) / 0.18, 1.0).coerceAtLeast(0.0)
        }
        return FlightProgress(
            fraction = fraction,
            point = if (phase == "ARRIVED") plan.destination.point else samplePoint,
            heading = heading,
            flownKm = flownKm,
            remainingKm = (totalKm - flownKm).coerceAtLeast(0.0),
            remaining = if (now >= end) Duration.ZERO else Duration.between(now, end),
            phase = phase,
            altitudeMeters = altitude,
            altitudeSource = altitude?.let {
                if (track != null) "Estimated from historical profile · ${track.source}"
                else "Generic flight profile estimate · not measured"
            }
        )
    }

    private data class Sample(val point: GeoPoint, val heading: Double, val altitude: Double?, val flownKm: Double)

    private fun historicalSample(track: HistoricalTrack, takeoffAnchored: Boolean, fraction: Double): Sample {
        val points = track.points
        val useAirborneSpan = takeoffAnchored && track.airborneDuration != null
        val from = if (useAirborneSpan) track.observedTakeoff?.let {
            Duration.between(track.observedDeparture, it).toMillis() / 1000.0
        } ?: 0.0 else 0.0
        val to = if (useAirborneSpan) track.observedLanding?.let {
            Duration.between(track.observedDeparture, it).toMillis() / 1000.0
        } ?: points.last().offsetSeconds else points.last().offsetSeconds
        val offset = from + (to - from) * fraction
        // Binary search retains the original observation times, including nonuniform sample spacing.
        var low = 1
        var high = points.lastIndex
        while (low < high) {
            val mid = (low + high) / 2
            if (points[mid].offsetSeconds < offset) low = mid + 1 else high = mid
        }
        val a = points[low - 1]
        val b = points[low]
        val local = ((offset - a.offsetSeconds) / (b.offsetSeconds - a.offsetSeconds)).coerceIn(0.0, 1.0)
        val point = GreatCircle.interpolate(a.point, b.point, local)
        val altitude = when {
            local <= 0.0 -> a.altitudeMeters
            local >= 1.0 -> b.altitudeMeters
            a.altitudeMeters != null && b.altitudeMeters != null -> a.altitudeMeters + (b.altitudeMeters - a.altitudeMeters) * local
            else -> null
        }
        val flown = points.take(low).zipWithNext().sumOf { (x, y) -> GreatCircle.distanceKm(x.point, y.point) } +
            GreatCircle.distanceKm(a.point, b.point) * local
        return Sample(point, GreatCircle.heading(a.point, b.point), altitude, flown)
    }
}
