package com.flightmap.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Computes real-time solar geometry (subsolar point, solar elevation angle, day/night/twilight)
 * and extracts the time-zone sequence beneath a flight route.
 */
object SolarCalculator {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Computes the approximate subsolar point (latitude and longitude where the Sun is directly overhead)
     * at the given UTC [instant].
     */
    fun subsolarPoint(instant: Instant): GeoPoint {
        val zdt = instant.atZone(ZoneOffset.UTC)
        val dayOfYear = zdt.dayOfYear.toDouble()
        val utcHours = zdt.hour + zdt.minute / 60.0 + zdt.second / 3600.0

        // Fractional year in radians
        val gamma = 2.0 * Math.PI / 365.25 * (dayOfYear - 1.0 + (utcHours - 12.0) / 24.0)

        // Equation of time (minutes)
        val eqTimeMin = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2.0 * gamma) -
                0.040849 * sin(2.0 * gamma)
            )

        // Solar declination (radians)
        val declRad = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) +
            0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) +
            0.00148 * sin(3.0 * gamma)

        val sunLat = Math.toDegrees(declRad).coerceIn(-23.45, 23.45)
        var sunLon = (12.0 - utcHours - eqTimeMin / 60.0) * 15.0
        sunLon = ((sunLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return GeoPoint(sunLat, sunLon)
    }

    /**
     * Returns the solar elevation angle in degrees above the horizon at [point] at [instant].
     * Positive = day, [-6, 0] = civil twilight, < -6 = night.
     */
    fun solarElevationDeg(point: GeoPoint, instant: Instant): Double {
        val sun = subsolarPoint(instant)
        val latRad = Math.toRadians(point.lat)
        val declRad = Math.toRadians(sun.lat)
        val hourAngleRad = Math.toRadians(point.lon - sun.lon)

        val sinAlt = (sin(latRad) * sin(declRad) + cos(latRad) * cos(declRad) * cos(hourAngleRad))
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(sinAlt))
    }

    fun isDaylight(point: GeoPoint, instant: Instant): Boolean =
        solarElevationDeg(point, instant) >= -0.833 // standard sunrise/sunset horizon refraction

    fun dayNightLabel(point: GeoPoint, instant: Instant): String {
        val alt = solarElevationDeg(point, instant)
        return when {
            alt >= 2.0 -> "☀ Daylight"
            alt >= -6.0 -> "◐ Twilight"
            else -> "☾ Night"
        }
    }

    fun utcOffsetLabel(timezoneId: String, instant: Instant): String {
        return runCatching {
            val zone = ZoneId.of(timezoneId)
            val offsetSec = zone.rules.getOffset(instant).totalSeconds
            val hours = offsetSec / 3600
            val mins = kotlin.math.abs((offsetSec % 3600) / 60)
            if (mins == 0) {
                if (hours >= 0) "UTC+$hours" else "UTC$hours"
            } else {
                val sign = if (offsetSec >= 0) "+" else "-"
                "UTC$sign${kotlin.math.abs(hours)}:%02d".format(mins)
            }
        }.getOrDefault("UTC")
    }

    /**
     * Builds the ordered sequence of time zones traversed along the flight trajectory,
     * highlighting the active timezone under the aircraft's current position.
     */
    fun routeTimeZones(
        plan: FlightPlan,
        cities: List<City>,
        currentPoint: GeoPoint,
        now: Instant
    ): List<RouteTimezoneBand> {
        val nearestCity = cities.minByOrNull { GreatCircle.distanceKm(it.point, currentPoint) }
        val currentTz = when {
            GreatCircle.distanceKm(plan.origin.point, currentPoint) < 120.0 -> plan.origin.timezone
            GreatCircle.distanceKm(plan.destination.point, currentPoint) < 120.0 -> plan.destination.timezone
            nearestCity != null -> nearestCity.timezone
            else -> plan.origin.timezone
        }
        val currentOffset = utcOffsetLabel(currentTz, now)

        data class TzStop(val tz: String, val offsetLabel: String, val city: String, val point: GeoPoint)
        val rawStops = mutableListOf<TzStop>()

        rawStops.add(
            TzStop(
                tz = plan.origin.timezone,
                offsetLabel = utcOffsetLabel(plan.origin.timezone, now),
                city = plan.origin.iata,
                point = plan.origin.point
            )
        )

        val sampledPoints = GreatCircle.routeWaypoints(plan.effectiveWaypoints, 24)
        for (samplePt in sampledPoints) {
            val city = cities
                .filter { GreatCircle.distanceKm(samplePt, it.point) <= 220.0 }
                .maxByOrNull { it.population }
            if (city != null && city.timezone.isNotBlank()) {
                rawStops.add(
                    TzStop(
                        tz = city.timezone,
                        offsetLabel = utcOffsetLabel(city.timezone, now),
                        city = city.name,
                        point = city.point
                    )
                )
            }
        }

        rawStops.add(
            TzStop(
                tz = plan.destination.timezone,
                offsetLabel = utcOffsetLabel(plan.destination.timezone, now),
                city = plan.destination.iata,
                point = plan.destination.point
            )
        )

        // Collapse consecutive identical UTC offsets while keeping origin and destination
        val distinctBands = mutableListOf<TzStop>()
        for (stop in rawStops) {
            val prev = distinctBands.lastOrNull()
            if (prev == null || prev.offsetLabel != stop.offsetLabel || stop.city == plan.destination.iata) {
                if (prev != null && prev.offsetLabel == stop.offsetLabel && stop.city == plan.destination.iata) {
                    distinctBands[distinctBands.lastIndex] = stop
                } else {
                    distinctBands.add(stop)
                }
            }
        }

        // Find closest band to current point
        val activeIdx = distinctBands.indices.minByOrNull { idx ->
            val d = GreatCircle.distanceKm(distinctBands[idx].point, currentPoint)
            if (distinctBands[idx].offsetLabel == currentOffset) d * 0.25 else d
        } ?: 0

        return distinctBands.mapIndexed { idx, stop ->
            val localStr = runCatching {
                now.atZone(ZoneId.of(stop.tz)).format(timeFormat)
            }.getOrDefault("--:--")
            RouteTimezoneBand(
                timezone = stop.tz,
                utcOffsetLabel = stop.offsetLabel,
                representativeCity = stop.city,
                localTimeLabel = localStr,
                isCurrent = idx == activeIdx,
                isDaylight = isDaylight(stop.point, now)
            )
        }
    }

    /**
     * Estimates realistic upper-troposphere (FL350–FL390) prevailing wind along the current track
     * based on latitude and heading (subtropical/polar jet stream model), formatted like IFE telemetry.
     */
    fun estimateCruiseWindLabel(point: GeoPoint, headingDeg: Double): String {
        val absLat = kotlin.math.abs(point.lat)
        // Mid-latitude westerlies peak near 35°–55°N blowing towards ~95° E
        val jetStrengthKmh = when {
            absLat in 28.0..62.0 -> 36.0 + 24.0 * cos(Math.toRadians((absLat - 44.0) * 3.5))
            else -> 18.0
        }
        val headingRad = Math.toRadians(headingDeg - 95.0)
        val tailComponentKmh = (jetStrengthKmh * cos(headingRad)).roundToInt()
        return if (tailComponentKmh >= 0) {
            val kts = (tailComponentKmh * 0.621371).roundToInt().coerceAtLeast(1)
            "Tail Wind: ${tailComponentKmh.coerceAtLeast(4)} km/h | ${kts.coerceAtLeast(3)} mph"
        } else {
            val hw = kotlin.math.abs(tailComponentKmh)
            val kts = (hw * 0.621371).roundToInt().coerceAtLeast(1)
            "Head Wind: $hw km/h | $kts mph"
        }
    }

    /**
     * Provides a deterministic offline surface weather estimate based on latitude, season,
     * continental position, and solar elevation when live Open-Meteo data is unavailable for a point.
     */
    fun estimateSurfaceWeather(point: GeoPoint, instant: Instant): WeatherHour {
        val zdt = instant.atZone(ZoneOffset.UTC)
        val dayOfYear = zdt.dayOfYear.toDouble()
        // Seasonal phase: peaks around day 200 (mid-July) in Northern Hemisphere
        val seasonPhase = cos(2.0 * Math.PI * (dayOfYear - 200.0) / 365.25)
        val hemiSeason = if (point.lat >= 0) seasonPhase else -seasonPhase

        val absLat = kotlin.math.abs(point.lat)
        // Mean annual baseline temperature by latitude (°C)
        val annualMean = 27.0 - 0.58 * absLat
        // Continental seasonal amplitude (larger in interior Eurasia 50°E–110°E)
        val continentalFactor = if (point.lon in 45.0..115.0 && point.lat in 35.0..65.0) 16.5 else 10.5
        val seasonalTemp = annualMean + hemiSeason * continentalFactor

        // Diurnal solar warming based on solar elevation angle
        val solarAlt = solarElevationDeg(point, instant)
        val diurnalOffset = (sin(Math.toRadians(solarAlt.coerceIn(-25.0, 65.0))) * 4.5)
        val tempC = (seasonalTemp + diurnalOffset).coerceIn(-32.0, 42.0)

        // Deterministic weather code (0 = Clear, 1 = Mainly clear, 2 = Partly cloudy, 3 = Overcast)
        val locHash = ((point.lat * 100).roundToInt() * 31 + (point.lon * 100).roundToInt() * 17 + zdt.dayOfYear) and 0x7FFFFFFF
        val code = when {
            point.lon in 52.0..98.0 && point.lat in 36.0..47.0 -> if (locHash % 3 == 0) 1 else 0 // Arid Central Asia / Hexi
            absLat > 56.0 -> if (locHash % 3 == 0) 3 else 2 // Subarctic / Baltic cloudiness
            else -> locHash % 3
        }
        return WeatherHour(
            time = instant,
            temperature = tempC,
            code = code,
            isDay = isDaylight(point, instant)
        )
    }

    /**
     * Generates an approximate hourly weather series covering [departure] through [arrival]
     * (padded by 2 hours on each side) so corridor weather is immediately available offline
     * or when dates fall outside live forecast windows.
     */
    fun estimateHourlySeries(
        point: GeoPoint,
        departure: Instant,
        arrival: Instant
    ): List<WeatherHour> {
        val startEpochHour = (departure.epochSecond / 3600L) - 2L
        val endEpochHour = maxOf(startEpochHour + 4L, (arrival.epochSecond / 3600L) + 2L)
        val hours = ArrayList<WeatherHour>((endEpochHour - startEpochHour + 1L).toInt().coerceAtMost(96))
        var h = startEpochHour
        while (h <= endEpochHour && hours.size < 96) {
            val t = Instant.ofEpochSecond(h * 3600L)
            hours.add(estimateSurfaceWeather(point, t))
            h++
        }
        return hours
    }
}

