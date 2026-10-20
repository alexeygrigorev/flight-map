package com.flightmap.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class LabelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun intersects(other: LabelBox): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class PlannedEndpoint(
    val code: String,
    val x: Float,
    val y: Float,
    val box: LabelBox
)

data class PlannedPin(
    val pin: LandmarkPin,
    val x: Float,
    val y: Float,
    val box: LabelBox
)

data class PlannedPeak(
    val peak: MountainPeak,
    val x: Float,
    val y: Float,
    val box: LabelBox
)

data class PlannedCity(
    val city: City,
    val x: Float,
    val y: Float,
    val box: LabelBox
)

data class PlannedCountry(
    val name: String,
    val x: Float,
    val y: Float,
    val box: LabelBox
)

data class GlobeSceneLayout(
    val endpoints: List<PlannedEndpoint>,
    val pins: List<PlannedPin>,
    val peaks: List<PlannedPeak>,
    val cities: List<PlannedCity>,
    val countries: List<PlannedCountry>,
    val occupiedBoxes: List<LabelBox>
)

/**
 * Unified screen-space label and marker layout planner for the 3D IFE globe.
 *
 * Enforces:
 * 1. Low label density at continental overview zoom (only major hubs, signature peaks,
 *    and primary countries) so the satellite relief and trajectory ribbon remain uncluttered.
 * 2. Strict single-table collision avoidance across aircraft icon, airport endpoints,
 *    landmark pins, mountain peaks, cities, and country labels (zero overlapping text).
 * 3. Screen-edge safe zones so globe labels never collide with the slim top progress header
 *    or the discreet bottom panel.
 */
object GlobeLabelPlanner {

    private val overviewPeakIds = setOf(
        "kebnekaise",
        "narodnaya",
        "mont-blanc",
        "ararat",
        "k2"
    )

    private val overviewPrimaryCities = setOf(
        "Stockholm", "Oslo", "Moscow", "London", "Paris", "Berlin", "Rome",
        "Warsaw", "Bucharest", "Istanbul", "Kyiv", "Minsk", "Tbilisi", "Baku",
        "Tehran", "Astana", "Almaty", "Tashkent", "Ürümqi", "Urumqi",
        "Novosibirsk", "Yekaterinburg", "Kazan", "Norilsk", "Surgut",
        "Xi'an", "Beijing", "Dublin", "Athens", "Jerusalem"
    )

    private val overviewCountries = setOf(
        "Finland", "Sweden", "Norway", "United Kingdom", "France", "Germany",
        "Poland", "Belarus", "Ukraine", "Romania", "Türkiye", "Kazakhstan",
        "Uzbekistan", "Turkmenistan", "Iran", "Iraq", "Afghanistan",
        "Pakistan", "India", "China", "Mongolia"
    )

    private val zoomedCountries = overviewCountries + setOf(
        "Estonia", "Latvia", "Lithuania", "Austria", "Italy", "Greece",
        "Bulgaria", "Georgia", "Azerbaijan", "Kyrgyzstan", "Tajikistan"
    )

    fun plan(
        proj: GlobeProjection,
        plan: FlightPlan,
        progress: FlightProgress,
        peaks: List<MountainPeak>,
        pins: List<LandmarkPin>,
        cities: List<City>,
        countries: List<CountryBoundary>,
        topSafePx: Float = 72f,
        bottomSafePx: Float = 88f,
        measureTextWidth: (text: String, textSizePx: Float) -> Float = { text, sizePx ->
            text.length * sizePx * 0.56f
        }
    ): GlobeSceneLayout {
        val actualHorizonTopY = proj.sphereCenterY - proj.radius
        val topLimit = max(actualHorizonTopY + 22f, topSafePx)
        val bottomLimit = proj.height - bottomSafePx
        val leftLimit = 42f
        val rightLimit = proj.width - 42f

        val occupied = mutableListOf<LabelBox>()
        val plannedEndpoints = mutableListOf<PlannedEndpoint>()
        val plannedPins = mutableListOf<PlannedPin>()
        val plannedPeaks = mutableListOf<PlannedPeak>()
        val plannedCities = mutableListOf<PlannedCity>()
        val plannedCountries = mutableListOf<PlannedCountry>()

        // 1. Reserve space around the live aircraft icon so labels never sit under the wings
        val acProj = proj.project(progress.point)
        if (acProj.visible) {
            occupied.add(
                LabelBox(
                    left = acProj.x - 30f,
                    top = acProj.y - 30f,
                    right = acProj.x + 30f,
                    bottom = acProj.y + 30f
                )
            )
        }

        // 2. Origin & Destination airport endpoints (e.g. HEL, PVG)
        for ((airport, code) in listOf(plan.origin to plan.origin.iata, plan.destination to plan.destination.iata)) {
            val p = proj.project(airport.point)
            if (p.visible) {
                val tw = measureTextWidth(code, 21f)
                val box = LabelBox(
                    left = min(p.x - 12f, p.x - tw / 2f - 8f),
                    top = p.y - 12f,
                    right = max(p.x + 12f, p.x + tw / 2f + 8f),
                    bottom = p.y + 30f
                )
                occupied.add(box)
                plannedEndpoints.add(PlannedEndpoint(code, p.x, p.y, box))
            }
        }

        val isOverview = proj.zoom < 1.35

        // 3. Curated Landmark Pins (quiet, well-spaced along the route)
        val maxPins = if (isOverview) 6 else 9
        val minPinDistPx = if (isOverview) 145f else 95f
        for (pin in pins) {
            if (plannedPins.size >= maxPins) break
            val p = proj.project(pin.point)
            if (!p.visible || p.z < 0.20) continue
            val box = LabelBox(
                left = p.x - 16f,
                top = p.y - 35f,
                right = p.x + 16f,
                bottom = p.y + 4f
            )
            if (box.left < leftLimit || box.right > rightLimit || box.top < topLimit || box.bottom > bottomLimit) continue
            if (plannedPins.any { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < minPinDistPx }) continue
            if (occupied.any { it.intersects(box) }) continue

            occupied.add(box)
            plannedPins.add(PlannedPin(pin, p.x, p.y, box))
        }

        // 4. Mountain Peaks (yellow triangle + name + elevation in ft)
        val candidatePeaks = if (isOverview) {
            peaks.filter { it.id in overviewPeakIds }
        } else {
            peaks
        }
        val maxPeaks = if (isOverview) 5 else 7
        for (peak in candidatePeaks) {
            if (plannedPeaks.size >= maxPeaks) break
            val p = proj.project(peak.point)
            if (!p.visible || p.z < 0.16) continue

            val nameW = measureTextWidth(peak.name, 19f)
            val elevW = measureTextWidth("${peak.elevationFt} ft", 16f)
            val textW = max(nameW, elevW)
            val box = LabelBox(
                left = p.x - 12f - textW - 8f,
                top = p.y - 20f,
                right = p.x + 10f,
                bottom = p.y + 22f
            )
            if (box.left < 20f || box.right > proj.width - 20f || box.top < topLimit || box.bottom > bottomLimit) continue
            if (occupied.any { it.intersects(box) }) continue

            occupied.add(box)
            plannedPeaks.add(PlannedPeak(peak, p.x, p.y, box))
        }

        // 5. Cities (single-line label • CityName, zoom-adaptive density)
        val endpointExclusionKm = when {
            isOverview -> 60.0
            proj.zoom < 3.0 -> 30.0
            else -> 12.0
        }
        val filteredCities = cities.filter { city ->
            val distOrigin = GreatCircle.distanceKm(city.point, plan.origin.point)
            val distDest = GreatCircle.distanceKm(city.point, plan.destination.point)
            if (distOrigin < endpointExclusionKm || distDest < endpointExclusionKm) {
                false
            } else if (isOverview) {
                city.name in overviewPrimaryCities || city.population >= 800_000
            } else if (proj.zoom < 1.95) {
                city.name in overviewPrimaryCities || city.population >= 150_000
            } else if (proj.zoom < 3.2) {
                city.name in overviewPrimaryCities || city.population >= 45_000
            } else {
                true
            }
        }.sortedByDescending { city ->
            val primaryBonus = if (city.name in overviewPrimaryCities) 50_000_000L else 0L
            primaryBonus + city.population.toLong()
        }

        val maxCities = when {
            isOverview -> 14
            proj.zoom < 1.95 -> 18
            proj.zoom < 3.5 -> 24
            else -> 30
        }
        val minCityDistPx = when {
            isOverview -> 92f
            proj.zoom < 1.95 -> 76f
            proj.zoom < 3.5 -> 62f
            else -> 54f
        }

        for (city in filteredCities) {
            if (plannedCities.size >= maxCities) break
            val p = proj.project(city.point)
            if (!p.visible || p.z < 0.18) continue
            if (plannedCities.any { hypot((it.x - p.x).toDouble(), (it.y - p.y).toDouble()) < minCityDistPx }) continue

            val tw = measureTextWidth(city.name, 19f)
            val box = LabelBox(
                left = p.x - 8f,
                top = p.y - 15f,
                right = p.x + 10f + tw + 12f,
                bottom = p.y + 13f
            )
            if (box.left < leftLimit || box.right > rightLimit || box.top < topLimit || box.bottom > bottomLimit) continue
            if (occupied.any { it.intersects(box) }) continue

            occupied.add(box)
            plannedCities.add(PlannedCity(city, p.x, p.y, box))
        }

        // 6. Country Labels (soft geographic context, nudged or skipped if a city/peak is at centroid)
        val allowedCountrySet = if (isOverview) overviewCountries else zoomedCountries
        val candidateCountries = countries
            .filter {
                it.iso != "LAKE" && it.iso != "RIVER" && (
                    it.name in allowedCountrySet ||
                        (!isOverview && it.population >= 5_000_000) ||
                        (proj.zoom >= 2.2 && it.population >= 500_000)
                    )
            }
            .sortedByDescending { it.population }

        val maxCountries = if (isOverview) 9 else 13
        val minCountryDistPx = if (isOverview) 125f else 105f

        for (country in candidateCountries) {
            if (plannedCountries.size >= maxCountries) break
            val p = proj.project(country.labelPoint)
            if (!p.visible || p.z < 0.24) continue

            val tw = measureTextWidth(country.name, 18f)
            for (dy in listOf(0f, 24f, -24f)) {
                val cy = p.y + dy
                val box = LabelBox(
                    left = p.x - tw / 2f - 10f,
                    top = cy - 15f,
                    right = p.x + tw / 2f + 10f,
                    bottom = cy + 9f
                )
                if (box.left < leftLimit || box.right > rightLimit || box.top < topLimit || box.bottom > bottomLimit) continue
                if (plannedCountries.any { hypot((it.x - p.x).toDouble(), (it.y - cy).toDouble()) < minCountryDistPx }) continue
                if (occupied.none { it.intersects(box) }) {
                    occupied.add(box)
                    plannedCountries.add(PlannedCountry(country.name, p.x, cy, box))
                    break
                }
            }
        }

        return GlobeSceneLayout(
            endpoints = plannedEndpoints,
            pins = plannedPins,
            peaks = plannedPeaks,
            cities = plannedCities,
            countries = plannedCountries,
            occupiedBoxes = occupied
        )
    }
}
