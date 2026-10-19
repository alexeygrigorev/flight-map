package com.flightmap.app

import android.content.Context
import com.flightmap.core.*
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FlightPack(
    val plan: FlightPlan,
    val cities: List<City>,
    val weather: Map<String, List<WeatherHour>>,
    val preparedAt: Instant,
    val mapReady: Boolean,
    val mapRegionIds: List<Long> = emptyList(),
    val mapDetailSummary: String? = "Corridor saved · zoom 0–5",
    val sampledWeatherIds: List<String>? = emptyList()
) {
    val effectiveMapDetailSummary: String
        get() = if (mapDetailSummary.isNullOrBlank()) "Corridor saved · zoom 0–5" else mapDetailSummary

    val effectiveSampledWeatherIds: List<String>
        get() = sampledWeatherIds.orEmpty()
}

val FlightPack.forecastsReady: Boolean
    get() {
        if (cities.isEmpty()) return true
        val targets = if (!sampledWeatherIds.isNullOrEmpty()) sampledWeatherIds else cities.map { it.id }
        if (targets.isEmpty()) return true
        return targets.all { id ->
            val hours = weather[id]
            if (hours.isNullOrEmpty()) {
                false
            } else {
                WeatherParser.nearest(hours, plan.departure) != null &&
                    WeatherParser.nearest(hours, plan.arrival) != null
            }
        }
    }

fun FlightPack.isWeatherReady(): Boolean = forecastsReady

fun isSameFlight(a: FlightPlan, b: FlightPlan): Boolean =
    a.number == b.number &&
    a.origin.iata == b.origin.iata &&
    a.destination.iata == b.destination.iata &&
    a.departure == b.departure &&
    a.arrival == b.arrival

class FlightRepository(private val context: Context) {
    companion object {
        val fileMutex = Mutex()
    }

    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ -> Instant.parse(json.asString) })
        .create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val packFile = File(context.filesDir, "flight-pack.json")
    private val packsFile = File(context.filesDir, "flight-packs.json")
    private val checkpointFile = File(context.filesDir, "preparation-checkpoint.json")

    var airports: List<Airport> = emptyList(); private set
    var allCities: List<City> = emptyList(); private set
    var countries: List<CountryBoundary> = emptyList(); private set

    suspend fun loadData() = withContext(Dispatchers.IO) {
        val airportList = mutableListOf<Airport>()
        context.assets.open("data/airports.json").bufferedReader().use { reader ->
            val jsonReader = JsonReader(reader)
            jsonReader.beginArray()
            while (jsonReader.hasNext()) {
                jsonReader.beginObject()
                var iata = ""; var name = ""; var lat = 0.0; var lon = 0.0; var tz = ""
                while (jsonReader.hasNext()) {
                    when (jsonReader.nextName()) {
                        "iata" -> iata = jsonReader.nextString()
                        "name" -> name = jsonReader.nextString()
                        "lat" -> lat = jsonReader.nextDouble()
                        "lon" -> lon = jsonReader.nextDouble()
                        "timezone" -> tz = jsonReader.nextString()
                        else -> jsonReader.skipValue()
                    }
                }
                jsonReader.endObject()
                airportList.add(Airport(iata, name, GeoPoint(lat, lon), tz))
            }
            jsonReader.endArray()
        }
        airports = airportList.sortedBy { it.iata }

        val cityList = ArrayList<City>(70000)
        context.assets.open("data/cities.json").bufferedReader().use { reader ->
            val jsonReader = JsonReader(reader)
            jsonReader.beginArray()
            while (jsonReader.hasNext()) {
                jsonReader.beginObject()
                var id = ""; var name = ""; var country = ""; var lat = 0.0; var lon = 0.0; var tz = ""; var pop = 0
                while (jsonReader.hasNext()) {
                    when (jsonReader.nextName()) {
                        "id" -> id = jsonReader.nextString()
                        "name" -> name = jsonReader.nextString()
                        "country" -> country = jsonReader.nextString()
                        "lat" -> lat = jsonReader.nextDouble()
                        "lon" -> lon = jsonReader.nextDouble()
                        "timezone" -> tz = jsonReader.nextString()
                        "population" -> pop = jsonReader.nextInt()
                        else -> jsonReader.skipValue()
                    }
                }
                jsonReader.endObject()
                cityList.add(City(id, name, country, GeoPoint(lat, lon), tz, pop))
            }
            jsonReader.endArray()
        }
        allCities = cityList

        runCatching {
            val countryList = mutableListOf<CountryBoundary>()
            context.assets.open("data/countries.json").bufferedReader().use { reader ->
                val jr = JsonReader(reader)
                jr.beginArray()
                while (jr.hasNext()) {
                    jr.beginObject()
                    var name = ""; var iso = ""; var lat = 0.0; var lon = 0.0; var pop = 0L
                    val rings = mutableListOf<List<GeoPoint>>()
                    while (jr.hasNext()) {
                        when (jr.nextName()) {
                            "name" -> name = jr.nextString()
                            "iso" -> iso = jr.nextString()
                            "lat" -> lat = jr.nextDouble()
                            "lon" -> lon = jr.nextDouble()
                            "pop" -> pop = jr.nextLong()
                            "rings" -> {
                                jr.beginArray()
                                while (jr.hasNext()) {
                                    jr.beginArray()
                                    val ring = mutableListOf<GeoPoint>()
                                    while (jr.hasNext()) {
                                        jr.beginArray()
                                        val rLon = jr.nextDouble()
                                        val rLat = jr.nextDouble()
                                        jr.endArray()
                                        ring.add(GeoPoint(rLat, rLon))
                                    }
                                    jr.endArray()
                                    if (ring.size >= 3) rings.add(ring)
                                }
                                jr.endArray()
                            }
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                    countryList.add(CountryBoundary(name, iso, GeoPoint(lat, lon), pop, rings))
                }
                jr.endArray()
            }
            countries = countryList
        }
    }

    suspend fun corridor(plan: FlightPlan): List<City> = withContext(Dispatchers.Default) {
        val enriched = TrajectoryCatalog.enrichPlan(plan)
        GreatCircle.corridorCities(enriched, allCities, 180.0)
    }

    suspend fun readPacks(): List<FlightPack> = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (packsFile.exists()) {
                runCatching {
                    val type = object : TypeToken<List<FlightPack>>() {}.type
                    val list: List<FlightPack>? = gson.fromJson(packsFile.readText(), type)
                    list?.filterNotNull()?.map { p ->
                        val enrichedPlan = if (p.plan.waypoints.isNullOrEmpty() || p.plan.routeSource == null) {
                            TrajectoryCatalog.enrichPlan(p.plan)
                        } else p.plan
                        p.copy(
                            plan = enrichedPlan,
                            mapDetailSummary = p.effectiveMapDetailSummary,
                            sampledWeatherIds = p.effectiveSampledWeatherIds
                        )
                    }
                }.getOrNull() ?: emptyList()
            } else if (packFile.exists()) {
                val single = runCatching {
                    gson.fromJson(packFile.readText(), FlightPack::class.java)
                }.getOrNull()
                if (single != null) {
                    val enrichedPlan = if (single.plan.waypoints.isNullOrEmpty() || single.plan.routeSource == null) {
                        TrajectoryCatalog.enrichPlan(single.plan)
                    } else single.plan
                    val migrated = single.copy(
                        plan = enrichedPlan,
                        mapDetailSummary = single.effectiveMapDetailSummary,
                        sampledWeatherIds = single.effectiveSampledWeatherIds
                    )
                    val list = listOf(migrated)
                    val temp = File(context.filesDir, "flight-packs-${UUID.randomUUID()}.tmp")
                    temp.writeText(gson.toJson(list))
                    check(temp.renameTo(packsFile)) { "Could not migrate legacy flight pack." }
                    list
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    suspend fun readPack(): FlightPack? = withContext(Dispatchers.IO) {
        readPacks().firstOrNull()
    }

    suspend fun save(pack: FlightPack) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val normalized = pack.copy(
                mapDetailSummary = pack.effectiveMapDetailSummary,
                sampledWeatherIds = pack.effectiveSampledWeatherIds
            )
            val current = if (packsFile.exists()) {
                runCatching {
                    val type = object : TypeToken<List<FlightPack>>() {}.type
                    gson.fromJson<List<FlightPack>>(packsFile.readText(), type)
                }.getOrNull()?.filterNotNull()?.toMutableList() ?: mutableListOf()
            } else mutableListOf()

            val index = current.indexOfFirst { isSameFlight(it.plan, normalized.plan) }
            if (index >= 0) {
                current[index] = normalized
            } else {
                current.add(0, normalized)
            }

            val temp = File(context.filesDir, "flight-packs-${UUID.randomUUID()}.tmp")
            temp.writeText(gson.toJson(current))
            check(temp.renameTo(packsFile)) { "Could not save flight pack. Please free some storage." }

            runCatching {
                val legacyTemp = File(context.filesDir, "flight-pack-${UUID.randomUUID()}.tmp")
                legacyTemp.writeText(gson.toJson(normalized))
                legacyTemp.renameTo(packFile)
            }
        }
    }

    suspend fun deletePack(pack: FlightPack) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val current = if (packsFile.exists()) {
                runCatching {
                    val type = object : TypeToken<List<FlightPack>>() {}.type
                    gson.fromJson<List<FlightPack>>(packsFile.readText(), type)
                }.getOrNull()?.filterNotNull() ?: emptyList()
            } else emptyList()

            val remaining = current.filterNot { isSameFlight(it.plan, pack.plan) }
            val temp = File(context.filesDir, "flight-packs-${UUID.randomUUID()}.tmp")
            temp.writeText(gson.toJson(remaining))
            check(temp.renameTo(packsFile)) { "Could not update flight packs after delete." }

            runCatching {
                if (remaining.isNotEmpty()) {
                    val legacyTemp = File(context.filesDir, "flight-pack-${UUID.randomUUID()}.tmp")
                    legacyTemp.writeText(gson.toJson(remaining.first()))
                    legacyTemp.renameTo(packFile)
                } else {
                    packFile.delete()
                }
            }
        }
    }

    suspend fun readCheckpoint(): PreparationCheckpoint? = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (!checkpointFile.exists()) return@withLock null
            runCatching {
                gson.fromJson(checkpointFile.readText(), PreparationCheckpoint::class.java)
            }.getOrNull()
        }
    }

    suspend fun saveCheckpoint(checkpoint: PreparationCheckpoint) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val temp = File(context.filesDir, "preparation-checkpoint-${UUID.randomUUID()}.tmp")
            temp.writeText(gson.toJson(checkpoint))
            check(temp.renameTo(checkpointFile)) { "Could not save checkpoint. Please free some storage." }
        }
    }

    suspend fun clearCheckpoint() = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (checkpointFile.exists()) {
                checkpointFile.delete()
            }
        }
    }

    fun selectWeatherSampleCities(cities: List<City>, maxLocations: Int = 180): List<City> =
        WeatherSampling.selectCities(cities, maxLocations)

    suspend fun weather(
        plan: FlightPlan,
        cities: List<City>,
        existingWeather: Map<String, List<WeatherHour>> = emptyMap(),
        onChunkSaved: suspend (Map<String, List<WeatherHour>>, Int, Int) -> Unit = { _, _, _ -> },
        progress: (Int, Int) -> Unit
    ): Map<String, List<WeatherHour>> = withContext(Dispatchers.IO) {
        if (cities.isEmpty()) return@withContext emptyMap()
        val sampleCities = selectWeatherSampleCities(cities, 180)
        val result = existingWeather.toMutableMap()

        val neededSamples = sampleCities.filter { it.id !in result }
        val totalSamples = sampleCities.size
        var completedSamples = sampleCities.count { it.id in result }

        progress(completedSamples, totalSamples)

        val start = plan.departure.atZone(ZoneOffset.UTC).toLocalDate()
        val end = plan.arrival.atZone(ZoneOffset.UTC).toLocalDate()
        val cacheDir = File(context.filesDir, "weather").apply { mkdirs() }

        for (chunk in neededSamples.chunked(30)) {
            val lat = chunk.joinToString(",") { it.point.lat.toString() }
            val lon = chunk.joinToString(",") { it.point.lon.toString() }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,weather_code,is_day&timeformat=unixtime&timezone=UTC&start_date=$start&end_date=$end"
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cache = File(cacheDir, "$key.json")
            val raw = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) {
                cache.readText()
            } else {
                delay(750)
                val response = client.newCall(
                    Request.Builder().url(url).header("User-Agent", "FlightMapAndroid/0.2 (personal offline flight companion)").build()
                ).execute()
                response.use {
                    check(it.isSuccessful) { "Weather unavailable (${it.code}). Check connection and forecast date (within 16 days)." }
                    val body = it.body?.string() ?: error("Empty weather response")
                    cache.writeText(body)
                    body
                }
            }
            val parsed = JsonParser.parseString(raw)
            val values = if (parsed.isJsonArray) parsed.asJsonArray.toList() else listOf(parsed)
            check(values.size == chunk.size) { "Incomplete weather response" }
            chunk.zip(values).forEach { (city, json) ->
                val hours = WeatherParser.parse(json.toString())
                check(hours.isNotEmpty()) { "No hourly forecast for ${city.name}" }
                check(WeatherParser.nearest(hours, plan.departure) != null && WeatherParser.nearest(hours, plan.arrival) != null) {
                    "Forecast does not cover the flight for ${city.name}"
                }
                result[city.id] = hours
            }
            completedSamples = sampleCities.count { it.id in result }
            progress(completedSamples, totalSamples)
            onChunkSaved(result, completedSamples, totalSamples)
        }

        result
    }
}
