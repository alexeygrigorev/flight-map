package com.flightmap.app

import com.flightmap.core.Airport
import com.flightmap.core.FlightPlan
import com.flightmap.core.GeoPoint
import com.flightmap.core.HistoricalTrack
import com.flightmap.core.TrajectoryCatalog
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Portable `.flightpack.json` / multi-flight import format.
 *
 * Single flight: a FlightPack object, or { "format":"flight-map-pack", "flight": {...} }.
 * Multi flight: { "format":"flight-map-multipack", "flights":[ ...plan-or-pack... ] }.
 * Demo-now: { "format":"flight-map-pack", "demoStartsNow": true, "flight": {...} }.
 *
 * Lightweight plan entries (no cities/weather) are accepted for trip planning; the
 * prepare pipeline fills offline data after import.
 */
data class ImportedFlightPacks(
    val packs: List<FlightPack>,
    val demoStartsNow: Boolean = false,
    val title: String? = null
)

object FlightPackImport {
    fun parse(json: String, airportsByIata: Map<String, Airport>): ImportedFlightPacks {
        val root = JsonParser.parseString(json)
        if (root.isJsonArray) {
            val packs = root.asJsonArray.mapNotNull { el -> parseOne(el, airportsByIata) }
            return ImportedFlightPacks(packs = packs)
        }
        require(root.isJsonObject) { "flightpack root must be object or array" }
        val obj = root.asJsonObject
        val format = obj.get("format")?.asString
        val demo = obj.get("demoStartsNow")?.asBoolean == true ||
            obj.get("kind")?.asString.equals("demo-starts-now", ignoreCase = true)
        val title = obj.get("title")?.asString

        when {
            format == "flight-map-multipack" || obj.has("flights") || obj.has("packs") -> {
                val arr = obj.getAsJsonArray("flights") ?: obj.getAsJsonArray("packs")
                require(arr != null) { "multipack requires flights[] or packs[]" }
                val packs = arr.mapNotNull { el -> parseOne(el, airportsByIata) }
                return ImportedFlightPacks(packs = packs, demoStartsNow = demo, title = title)
            }
            obj.has("flight") -> {
                val pack = parseOne(obj.get("flight"), airportsByIata)
                    ?: error("could not parse flight")
                return ImportedFlightPacks(packs = listOf(pack), demoStartsNow = demo, title = title)
            }
            obj.has("plan") -> {
                val pack = parseOne(obj, airportsByIata) ?: error("could not parse pack")
                return ImportedFlightPacks(packs = listOf(pack), demoStartsNow = demo, title = title)
            }
            else -> {
                // Raw FlightPack
                val pack = parseOne(obj, airportsByIata) ?: error("could not parse pack")
                return ImportedFlightPacks(packs = listOf(pack), demoStartsNow = demo, title = title)
            }
        }
    }

    private fun parseOne(el: JsonElement, airportsByIata: Map<String, Airport>): FlightPack? {
        if (!el.isJsonObject) return null
        val obj = el.asJsonObject
        val planObj = when {
            obj.has("plan") && obj.get("plan").isJsonObject -> obj.getAsJsonObject("plan")
            obj.has("number") || obj.has("flightNumber") -> obj
            else -> return null
        }
        val plan = parsePlan(planObj, airportsByIata)
        val preparedAt = obj.get("preparedAt")?.asString?.let { Instant.parse(it) } ?: Instant.now()
        val mapReady = obj.get("mapReady")?.asBoolean ?: false
        val mapRegionIds = obj.getAsJsonArray("mapRegionIds")?.map { it.asLong } ?: emptyList()
        val mapDetail = obj.get("mapDetailSummary")?.asString
        // Cities/weather left empty for lightweight trip packs — prepare fills later.
        return FlightPack(
            plan = plan,
            cities = emptyList(),
            weather = emptyMap(),
            preparedAt = preparedAt,
            mapReady = mapReady,
            mapRegionIds = mapRegionIds,
            mapDetailSummary = mapDetail,
            sampledWeatherIds = emptyList()
        )
    }

    private fun parsePlan(obj: JsonObject, airportsByIata: Map<String, Airport>): FlightPlan {
        val number = (obj.get("number") ?: obj.get("flightNumber"))?.asString
            ?: error("flight number required")
        val originIata = when {
            obj.has("origin") && obj.get("origin").isJsonObject ->
                obj.getAsJsonObject("origin").get("iata").asString
            obj.has("originIata") -> obj.get("originIata").asString
            else -> error("origin required")
        }.uppercase()
        val destIata = when {
            obj.has("destination") && obj.get("destination").isJsonObject ->
                obj.getAsJsonObject("destination").get("iata").asString
            obj.has("destinationIata") -> obj.get("destinationIata").asString
            else -> error("destination required")
        }.uppercase()

        val origin = resolveAirport(obj, "origin", originIata, airportsByIata)
        val dest = resolveAirport(obj, "destination", destIata, airportsByIata)

        val departure = parseInstant(obj, "departure", "departureLocal", origin.timezone)
        val arrival = parseInstant(obj, "arrival", "arrivalLocal", dest.timezone)
        val gson = FlightPackCodec.createGson()
        val history = obj.get("historicalTrack")?.takeUnless { it.isJsonNull }?.let {
            gson.fromJson(it, HistoricalTrack::class.java)
        }
        val waypoints = obj.getAsJsonArray("waypoints")?.map { gson.fromJson(it, GeoPoint::class.java) }
        val plan = FlightPlan(
            number.trim().uppercase(), origin, dest, departure, arrival,
            waypoints = waypoints,
            routeSource = if (waypoints != null) "USER_SUPPLIED_ROUTE" else null,
            routeLabel = if (waypoints != null) "User supplied route · unverified" else null,
            historicalTrack = history,
            actualTakeoff = obj.get("actualTakeoff")?.takeUnless { it.isJsonNull }?.asString?.let(Instant::parse),
            landedAt = obj.get("landedAt")?.takeUnless { it.isJsonNull }?.asString?.let(Instant::parse),
            estimatedAirborneMinutes = obj.get("estimatedAirborneMinutes")?.takeUnless { it.isJsonNull }?.asInt
        )
        require(history == null || history.identifies(plan)) { "Historical track is invalid or belongs to another flight" }
        return TrajectoryCatalog.enrichPlan(plan)
    }

    private fun resolveAirport(
        obj: JsonObject,
        key: String,
        iata: String,
        airportsByIata: Map<String, Airport>
    ): Airport {
        airportsByIata[iata]?.let { return it }
        val nested = obj.getAsJsonObject(key) ?: error("airport $iata not in catalog and no inline data")
        val name = nested.get("name")?.asString ?: iata
        val lat = nested.get("lat")?.asDouble
            ?: nested.getAsJsonObject("point")?.get("lat")?.asDouble
            ?: error("lat required for $iata")
        val lon = nested.get("lon")?.asDouble
            ?: nested.getAsJsonObject("point")?.get("lon")?.asDouble
            ?: error("lon required for $iata")
        val tz = nested.get("timezone")?.asString ?: "UTC"
        return Airport(iata, name, GeoPoint(lat, lon), tz)
    }

    private fun parseInstant(obj: JsonObject, utcKey: String, localKey: String, timezone: String): Instant {
        obj.get(utcKey)?.asString?.let { raw ->
            // Accept Instant or local datetime without zone
            return runCatching { Instant.parse(raw) }.getOrElse {
                localToInstant(raw, timezone)
            }
        }
        obj.get(localKey)?.asString?.let { return localToInstant(it, timezone) }
        error("missing $utcKey / $localKey")
    }

    private fun localToInstant(text: String, timezone: String): Instant {
        val date = LocalDateTime.parse(text.trim())
        val zone = ZoneId.of(timezone)
        val offsets = zone.rules.getValidOffsets(date)
        require(offsets.size == 1) { "$timezone: ambiguous or skipped local time $text" }
        return date.toInstant(offsets.single())
    }
}
