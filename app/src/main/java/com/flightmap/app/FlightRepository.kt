package com.flightmap.app

import android.content.Context
import com.flightmap.core.*
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

data class FlightPack(
    val plan: FlightPlan,
    val cities: List<City>,
    val weather: Map<String, List<WeatherHour>>,
    val preparedAt: Instant,
    val mapReady: Boolean,
    val mapRegionIds: List<Long> = emptyList()
)

class FlightRepository(private val context: Context) {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ -> Instant.parse(json.asString) })
        .create()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()
    private val packFile = File(context.filesDir, "flight-pack.json")
    var airports: List<Airport> = emptyList(); private set
    private var allCities: List<City> = emptyList()

    suspend fun loadData() = withContext(Dispatchers.IO) {
        val airportJson = JsonParser.parseString(context.assets.open("data/airports.json").bufferedReader().use { it.readText() }).asJsonArray
        airports = airportJson.map { it.asJsonObject.let { a ->
            Airport(a["iata"].asString, a["name"].asString, GeoPoint(a["lat"].asDouble, a["lon"].asDouble), a["timezone"].asString)
        } }.sortedBy { it.iata }
        val cityJson = JsonParser.parseString(context.assets.open("data/cities.json").bufferedReader().use { it.readText() }).asJsonArray
        allCities = cityJson.map { it.asJsonObject.let { c ->
            City(c["id"].asString, c["name"].asString, c["country"].asString, GeoPoint(c["lat"].asDouble, c["lon"].asDouble), c["timezone"].asString, c["population"].asInt)
        } }
    }
    suspend fun corridor(plan: FlightPlan): List<City> = withContext(Dispatchers.Default) {
        GreatCircle.corridorCities(plan, allCities, 180.0)
    }
    suspend fun readPack(): FlightPack? = withContext(Dispatchers.IO) {
        runCatching { gson.fromJson(packFile.readText(), FlightPack::class.java) }.getOrNull()
    }
    suspend fun save(pack: FlightPack) = withContext(Dispatchers.IO) {
        val temp = File(context.filesDir, "flight-pack.tmp")
        temp.writeText(gson.toJson(pack))
        check(temp.renameTo(packFile)) { "Could not save flight. Please free some storage." }
    }
    suspend fun weather(plan: FlightPlan, cities: List<City>, progress: (Int, Int) -> Unit): Map<String, List<WeatherHour>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, List<WeatherHour>>()
        val start = plan.departure.atZone(ZoneOffset.UTC).toLocalDate()
        val end = plan.arrival.atZone(ZoneOffset.UTC).toLocalDate()
        val cacheDir = File(context.filesDir, "weather").apply { mkdirs() }
        for (chunk in cities.chunked(30)) {
            val lat = chunk.joinToString(",") { it.point.lat.toString() }
            val lon = chunk.joinToString(",") { it.point.lon.toString() }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,weather_code,is_day&timeformat=unixtime&timezone=UTC&start_date=$start&end_date=$end"
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cache = File(cacheDir, "$key.json")
            val raw = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 3 * 3600_000L) cache.readText() else {
                // The free service counts each location, even in a batched request.
                delay(3300)
                val response = client.newCall(Request.Builder().url(url).header("User-Agent", "FlightMapAndroid/0.1 (personal offline flight companion)").build()).execute()
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
                check(WeatherParser.nearest(hours, plan.departure) != null && WeatherParser.nearest(hours, plan.arrival) != null) { "Forecast does not cover the flight for ${city.name}" }
                result[city.id] = hours
            }
            progress(result.size, cities.size)
        }
        result
    }
}
