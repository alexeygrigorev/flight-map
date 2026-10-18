package com.flightmap.core

/** Cap free forecast requests while retaining major and geographically ordered smaller places. */
object WeatherSampling {
    fun selectCities(cities: List<City>, maxLocations: Int = 600): List<City> {
        if (cities.size <= maxLocations) return cities
        val major = cities.filter { it.population >= 15000 }
        if (major.size >= maxLocations) {
            val step = major.size.toDouble() / maxLocations
            return (0 until maxLocations).map { i -> major[(i * step).toInt()] }.distinctBy { it.id }
        }
        val remaining = maxLocations - major.size
        val minor = cities.filter { it.population < 15000 }
        if (minor.isEmpty() || remaining <= 0) return major
        val step = minor.size.toDouble() / remaining
        val sampledMinor = (0 until remaining).map { i -> minor[(i * step).toInt()] }
        return (major + sampledMinor).distinctBy { it.id }
    }

}
