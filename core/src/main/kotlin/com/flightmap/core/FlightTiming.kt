package com.flightmap.core

import java.time.Duration
import java.time.Instant

object FlightTiming {
    fun progress(plan: FlightPlan, now: Instant): FlightProgress {
        val totalDuration = Duration.between(plan.departure, plan.arrival)
        val totalMillis = totalDuration.toMillis().toDouble()

        val fraction = when {
            now <= plan.departure -> 0.0
            now >= plan.arrival -> 1.0
            totalMillis <= 0.0 -> 1.0
            else -> {
                val elapsedMillis = Duration.between(plan.departure, now).toMillis().toDouble()
                (elapsedMillis / totalMillis).coerceIn(0.0, 1.0)
            }
        }

        val waypoints = plan.effectiveWaypoints
        val totalDistKm = GreatCircle.routeDistanceKm(waypoints)
        val flownKm = totalDistKm * fraction
        val remainingKm = (totalDistKm - flownKm).coerceAtLeast(0.0)

        val remainingDuration = when {
            now >= plan.arrival -> Duration.ZERO
            else -> Duration.between(now, plan.arrival)
        }

        val (point, heading) = GreatCircle.interpolateWaypointsWithHeading(waypoints, fraction)

        val phase = when {
            now < plan.departure -> "SCHEDULED"
            now >= plan.arrival -> "ARRIVED"
            else -> "EN_ROUTE"
        }

        return FlightProgress(
            fraction = fraction,
            point = point,
            heading = heading,
            flownKm = flownKm,
            remainingKm = remainingKm,
            remaining = remainingDuration,
            phase = phase
        )
    }
}
