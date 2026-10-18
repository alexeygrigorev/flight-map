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
    val arrival: Instant
) {
    init {
        require(arrival > departure) {
            "Arrival ($arrival) must be after departure ($departure)"
        }
    }
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
