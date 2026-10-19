package com.flightmap.core

import java.time.Duration
import java.time.Instant

/**
 * Takeoff-play / "starts now" clock.
 *
 * Pressing Play anchors wall-clock now to scheduled departure: the simulated
 * flight clock then advances 1:1 with wall time from takeoff (pressing late is
 * fine — the clock still begins at departure). Scrub temporarily overrides the
 * live position; [returnToCurrent] clears the scrub so the plane snaps back to
 * where the live clock says it is.
 */
data class LivePlayClock(
    val wallStartedAt: Instant,
    val departure: Instant,
    val arrival: Instant,
    val scrubFraction: Double? = null
) {
    fun flightNow(wallNow: Instant): Instant {
        scrubFraction?.let { fraction ->
            val totalMs = Duration.between(departure, arrival).toMillis()
            if (totalMs <= 0L) return arrival
            val clamped = fraction.coerceIn(0.0, 1.0)
            return departure.plusMillis((totalMs * clamped).toLong())
        }
        val elapsed = Duration.between(wallStartedAt, wallNow)
        if (elapsed.isNegative) return departure
        val candidate = departure.plus(elapsed)
        return when {
            candidate <= departure -> departure
            candidate >= arrival -> arrival
            else -> candidate
        }
    }

    fun fraction(wallNow: Instant): Double {
        val totalMs = Duration.between(departure, arrival).toMillis().toDouble()
        if (totalMs <= 0.0) return 1.0
        val now = flightNow(wallNow)
        return (Duration.between(departure, now).toMillis().toDouble() / totalMs).coerceIn(0.0, 1.0)
    }

    fun withScrub(fraction: Double): LivePlayClock =
        copy(scrubFraction = fraction.coerceIn(0.0, 1.0))

    /** Clear scrub and resume the live takeoff-anchored clock. */
    fun returnToCurrent(): LivePlayClock = copy(scrubFraction = null)

    val isScrubbing: Boolean get() = scrubFraction != null

    companion object {
        fun start(plan: FlightPlan, wallNow: Instant = Instant.now()): LivePlayClock =
            LivePlayClock(
                wallStartedAt = wallNow,
                departure = plan.departure,
                arrival = plan.arrival
            )
    }
}
