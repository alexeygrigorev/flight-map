package com.flightmap.core

import java.time.Duration
import java.time.Instant

data class GeoPoint(
    val lat: Double,
    val lon: Double
)

data class Airport(
    val iata: String,
    val name: String,
    val point: GeoPoint,
    val timezone: String
)

data class City(
    val id: String,
    val name: String,
    val country: String,
    val point: GeoPoint,
    val timezone: String,
    val population: Int
)

data class FlightPlan(
    val number: String,
    val origin: Airport,
    val destination: Airport,
    val departure: Instant,
    val arrival: Instant,
    val waypoints: List<GeoPoint>? = emptyList(),
    val waypointNames: List<String>? = emptyList(),
    val routeSource: String? = null,
    val routeLabel: String? = null
) {
    init {
        require(arrival > departure) {
            "Arrival ($arrival) must be after departure ($departure)"
        }
    }

    val effectiveWaypoints: List<GeoPoint>
        get() = if (waypoints != null && waypoints.size >= 2) waypoints else listOf(origin.point, destination.point)

    val effectiveWaypointNames: List<String>
        get() = waypointNames.orEmpty()

    val isRealTrajectory: Boolean
        get() = waypoints != null && waypoints.size > 2 && routeSource != null && routeSource != "GREAT_CIRCLE_ESTIMATE"

    val effectiveRouteLabel: String
        get() = routeLabel?.takeIf { it.isNotBlank() }
            ?: if (isRealTrajectory) "Filed ATS trajectory" else "Great-circle estimate"
}

data class FlightProgress(
    val fraction: Double,
    val point: GeoPoint,
    val heading: Double,
    val flownKm: Double,
    val remainingKm: Double,
    val remaining: Duration,
    val phase: String
)

data class WeatherHour(
    val time: Instant,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean = true
)

data class MountainPeak(
    val id: String,
    val name: String,
    val elevationFt: Int,
    val elevationM: Int,
    val point: GeoPoint,
    val region: String,
    val fact: String
)

data class LandmarkPin(
    val id: String,
    val name: String,
    val country: String,
    val point: GeoPoint,
    val timezone: String,
    val fact: String
)

data class RouteTimezoneBand(
    val timezone: String,
    val utcOffsetLabel: String,
    val representativeCity: String,
    val localTimeLabel: String,
    val isCurrent: Boolean,
    val isDaylight: Boolean
)

