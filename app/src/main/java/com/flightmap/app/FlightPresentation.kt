package com.flightmap.app

import com.flightmap.core.FlightPlan
import com.flightmap.core.FlightProgress
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Shared wording for the screen and ongoing flight card. All positions remain estimates. */
object FlightPresentation {
    fun identity(plan: FlightPlan): String = listOf(plan.number, plan.origin.iata, plan.destination.iata, plan.departure.toString(), plan.arrival.toString()).joinToString("|")

    fun route(plan: FlightPlan) = "${plan.origin.iata} → ${plan.destination.iata}"

    fun status(progress: FlightProgress, confirmedLanded: Boolean = false): String = when (progress.phase) {
        "ARRIVED" -> if (confirmedLanded) "Landed ✓" else "Arrived · estimated"
        "SCHEDULED" -> "Awaiting takeoff"
        else -> "${progress.remaining.toHours()}h ${progress.remaining.toMinutes() % 60}m remaining · ${progress.remainingKm.roundToInt()} km"
    }

    fun altitude(progress: FlightProgress): String = progress.altitudeMeters?.let {
        "Estimated altitude ${"%,d".format(it.roundToInt())} m"
    } ?: "Altitude unavailable"

    fun arrival(plan: FlightPlan, progress: FlightProgress, now: Instant): String {
        if (progress.phase == "ARRIVED") return plan.landedAt?.let {
            "Landed ${it.atZone(ZoneId.of(plan.destination.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))} local"
        } ?: "Estimated arrival reached"
        val eta = now.plus(progress.remaining)
        return "ETA ${eta.atZone(ZoneId.of(plan.destination.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))} destination time"
    }

    fun durationSource(plan: FlightPlan): String = when {
        plan.estimatedAirborneMinutes != null -> "User estimate: ${plan.estimatedAirborneMinutes} min airborne"
        plan.effectiveHistoricalTrack != null -> "Historical replay duration: ${Duration.between(com.flightmap.core.FlightTiming.effectiveDeparture(plan), com.flightmap.core.FlightTiming.estimatedArrival(plan)).toMinutes()} min"
        else -> "Scheduled block-time fallback: ${Duration.between(plan.departure, plan.arrival).toMinutes()} min · includes taxi time"
    }

    fun parseAirborneMinutes(value: String): Int? {
        if (value.isBlank()) return null
        val minutes = value.trim().toIntOrNull()
        require(minutes != null && minutes in 1..1440) { "Enter 1–1440 minutes, or leave empty for the default." }
        return minutes
    }

    /** Full local date and time avoids silently choosing the wrong day around midnight. */
    fun parseTakeoff(value: String, timezone: String): Instant {
        val local = LocalDateTime.parse(value.trim())
        val zone = ZoneId.of(timezone)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "This local time is ambiguous or skipped by daylight saving. Choose another time." }
        return local.toInstant(offsets.single())
    }
}
