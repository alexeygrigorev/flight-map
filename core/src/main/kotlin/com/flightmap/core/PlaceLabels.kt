package com.flightmap.core

/** Geographic diversity first: dense metropolitan clusters must not hide remote towns. */
object PlaceLabels {
    fun overview(plan: FlightPlan, cities: List<City>, limit: Int = 36): List<City> {
        if (limit <= 0 || cities.isEmpty()) return emptyList()
        val selected = linkedMapOf<String, City>()
        val samples = GreatCircle.routeWaypoints(plan.effectiveWaypoints, limit - 1)
        for (point in samples) {
            val nearby = cities.filter { GreatCircle.distanceKm(point, it.point) <= 250 }
            val city = nearby.minByOrNull {
                // Prefer proximity, with a modest population bonus for recognizable names.
                GreatCircle.distanceKm(point, it.point) - kotlin.math.ln((it.population + 1).toDouble()) * 3
            }
            if (city != null) selected[city.id] = city
        }
        for (city in cities.sortedByDescending { it.population }) {
            if (selected.size >= limit) break
            if (selected.values.all { GreatCircle.distanceKm(it.point, city.point) >= 100 }) selected[city.id] = city
        }
        return selected.values.take(limit)
    }
}
