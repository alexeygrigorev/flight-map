package com.flightmap.app

import com.flightmap.core.City
import com.flightmap.core.FlightPlan
import com.flightmap.core.WeatherHour
import java.time.Instant

enum class PreparationStatus {
    IDLE,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED
}

data class PreparationCheckpoint(
    val plan: FlightPlan,
    val status: PreparationStatus,
    val stage: String = "",
    val cities: List<City> = emptyList(),
    val sampledCityIds: List<String>? = emptyList(),
    val weather: Map<String, List<WeatherHour>> = emptyMap(),
    val weatherDone: Int = 0,
    val weatherTotal: Int = 0,
    val weatherDetail: String = "",
    val mapRegionIds: List<Long> = emptyList(),
    val mapReady: Boolean = false,
    val mapDetail: String = "",
    val mapTileKey: String? = null,
    val routeReady: Boolean = false,
    val citiesReady: Boolean = false,
    val weatherReady: Boolean = false,
    val error: String? = null,
    val updatedAt: Instant = Instant.now(),
    val mapDetailSummary: String? = "Corridor saved · zoom 0–5",
    val sampledWeatherIds: List<String>? = emptyList()
) {
    val effectiveMapDetailSummary: String
        get() = if (mapDetailSummary.isNullOrBlank()) "Corridor saved · zoom 0–5" else mapDetailSummary

    val effectiveSampledWeatherIds: List<String>
        get() = sampledWeatherIds.orEmpty()
}

data class PreparationProgress(
    val plan: FlightPlan? = null,
    val isBusy: Boolean = false,
    val stage: String = "",
    val error: String? = null,
    val routeReady: Boolean = false,
    val citiesReady: Boolean = false,
    val weatherReady: Boolean = false,
    val mapReady: Boolean = false,
    val weatherDetail: String = "",
    val mapDetail: String = "",
    val status: PreparationStatus = PreparationStatus.IDLE,
    val sampledWeatherIds: List<String>? = emptyList()
) {
    val effectiveSampledWeatherIds: List<String>
        get() = sampledWeatherIds.orEmpty()
}
