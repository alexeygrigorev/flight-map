package com.flightmap.app

import android.content.Context
import com.flightmap.core.*
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val sampledWeatherIds: List<String>? = emptyList(),
    /** Sanitized OfflinePlan flightKey identifying cached corridor raster imagery (null = bundled relief only). */
    val mapTileKey: String? = null,
    val offlineContext: PreparedFlightContext? = null
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

/** True when high-detail corridor raster imagery (NASA GIBS, offline cache) is attached to this pack. */
val FlightPack.hasDetailedImagery: Boolean
    get() = mapTileKey != null

fun isSameFlight(a: FlightPlan, b: FlightPlan): Boolean =
    a.number == b.number &&
    a.origin.iata == b.origin.iata &&
    a.destination.iata == b.destination.iata &&
    a.departure == b.departure &&
    a.arrival == b.arrival

object FlightPackCodec {
    const val MAX_CORRIDOR_CITIES = 36
    const val MAX_WEATHER_SAMPLES = 24

    fun createGson(): Gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ -> Instant.parse(json.asString) })
        .create()

    fun exportPackJson(pack: FlightPack, gson: Gson = createGson()): String =
        gson.toJson(pack)

    fun parsePacksWithMeta(
        rawJson: String,
        gson: Gson = createGson(),
        fallbackCities: List<City> = emptyList(),
        fallbackAirports: List<Airport> = emptyList()
    ): ImportedFlightPacks {
        val trimmed = rawJson.trim().removePrefix("\uFEFF")
        require(trimmed.isNotEmpty()) { "Flight pack file is empty" }
        val element = runCatching { JsonParser.parseString(trimmed) }.getOrElse {
            throw IllegalArgumentException("File is not valid JSON: ${it.message}")
        }
        val airportsByIata = fallbackAirports.associateBy { it.iata }
        var demoStartsNow = false
        var title: String? = null

        val rawPacks: List<FlightPack> = when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val isMultipackOrEnvelope = obj.has("format") || obj.has("flights") ||
                    obj.has("packs") || obj.has("flight") || obj.has("demoStartsNow") ||
                    (obj.has("plan") && obj.getAsJsonObject("plan")?.has("originIata") == true)
                if (isMultipackOrEnvelope) {
                    val imported = FlightPackImport.parse(trimmed, airportsByIata)
                    demoStartsNow = imported.demoStartsNow
                    title = imported.title
                    // Also preserve inline cities/weather if present in multipack entries
                    val arr = obj.getAsJsonArray("flights") ?: obj.getAsJsonArray("packs")
                    if (arr != null && arr.size() == imported.packs.size) {
                        imported.packs.mapIndexed { idx, skeleton ->
                            val itemObj = arr[idx].takeIf { it.isJsonObject }?.asJsonObject
                            val full = itemObj?.let { runCatching { gson.fromJson(it, FlightPack::class.java) }.getOrNull() }
                            if (full != null && !full.cities.isNullOrEmpty()) {
                                full.copy(plan = skeleton.plan)
                            } else {
                                skeleton
                            }
                        }
                    } else if (obj.has("flight") && obj.get("flight").isJsonObject && imported.packs.size == 1) {
                        val full = runCatching { gson.fromJson(obj.get("flight"), FlightPack::class.java) }.getOrNull()
                        if (full != null && !full.cities.isNullOrEmpty()) {
                            listOf(full.copy(plan = imported.packs[0].plan))
                        } else {
                            imported.packs
                        }
                    } else {
                        imported.packs
                    }
                } else {
                    val single = gson.fromJson(element, FlightPack::class.java)
                        ?: throw IllegalArgumentException("Could not read FlightPack object")
                    listOf(single)
                }
            }
            element.isJsonArray -> {
                val type = object : TypeToken<List<FlightPack>>() {}.type
                val list: List<FlightPack>? = runCatching { gson.fromJson<List<FlightPack>>(element, type) }.getOrNull()
                val valid = list?.filterNotNull().orEmpty().filter { runCatching { it.plan.origin.iata }.getOrNull()?.isNotBlank() == true }
                if (valid.isNotEmpty()) {
                    valid
                } else {
                    FlightPackImport.parse(trimmed, airportsByIata).packs
                }
            }
            else -> throw IllegalArgumentException("Expected a FlightPack JSON object or array")
        }
        require(rawPacks.isNotEmpty()) { "No flight packs found in file" }
        return ImportedFlightPacks(
            packs = rawPacks.map { normalizeImportedPack(it, fallbackCities) },
            demoStartsNow = demoStartsNow,
            title = title
        )
    }

    fun parsePacks(
        rawJson: String,
        gson: Gson = createGson(),
        fallbackCities: List<City> = emptyList(),
        fallbackAirports: List<Airport> = emptyList()
    ): List<FlightPack> = parsePacksWithMeta(rawJson, gson, fallbackCities, fallbackAirports).packs

    fun curateCities(plan: FlightPlan, candidates: List<City>, maxCount: Int = MAX_CORRIDOR_CITIES): List<City> {
        if (candidates.size <= maxCount) return candidates
        return PlaceLabels.overview(plan, candidates, maxCount)
    }

    fun normalizeImportedPack(pack: FlightPack, fallbackCities: List<City> = emptyList()): FlightPack {
        val rawPlan = pack.plan ?: throw IllegalArgumentException("Flight pack is missing flight plan")
        require(rawPlan.origin.iata.isNotBlank() && rawPlan.destination.iata.isNotBlank()) {
            "Flight pack is missing origin or destination airport"
        }
        require(rawPlan.arrival > rawPlan.departure) {
            "Flight pack arrival must be after departure"
        }
        require(rawPlan.historicalTrack?.identifies(rawPlan) != false) {
            "Historical track is invalid or belongs to another flight"
        }
        val enrichedPlan = if (rawPlan.waypoints.isNullOrEmpty() || rawPlan.routeSource == null || rawPlan.routeSource == "GREAT_CIRCLE_ESTIMATE" || rawPlan.routeSource?.startsWith("FILED_ATS") == true) {
            TrajectoryCatalog.enrichPlan(rawPlan)
        } else {
            rawPlan
        }
        val safeCities = (pack.cities as List<City>?).orEmpty().let { existing ->
            val rawList = if (existing.isEmpty() && fallbackCities.isNotEmpty()) {
                GreatCircle.corridorCities(enrichedPlan, fallbackCities, 180.0)
            } else {
                existing
            }
            curateCities(enrichedPlan, rawList, MAX_CORRIDOR_CITIES)
        }
        val rawWeather = (pack.weather as Map<String, List<WeatherHour>>?).orEmpty()
        val sampledCities = WeatherSampling.selectCities(safeCities, MAX_WEATHER_SAMPLES)
        val safeSampled = pack.effectiveSampledWeatherIds
            .filter { id -> safeCities.any { it.id == id } }
            .ifEmpty { sampledCities.map { it.id } }

        // Ensure every sampled city has hourly weather (using approximate solar/climate model if missing)
        val safeWeather = LinkedHashMap<String, List<WeatherHour>>(rawWeather)
        for (city in safeCities) {
            if (city.id in safeSampled) {
                val existingHours = safeWeather[city.id]
                val valid = !existingHours.isNullOrEmpty() &&
                    WeatherParser.nearest(existingHours, enrichedPlan.departure) != null &&
                    WeatherParser.nearest(existingHours, enrichedPlan.arrival) != null
                if (!valid) {
                    safeWeather[city.id] = SolarCalculator.estimateHourlySeries(
                        city.point,
                        enrichedPlan.departure,
                        enrichedPlan.arrival
                    )
                }
            }
        }

        val safePreparedAt = (pack.preparedAt as Instant?) ?: Instant.now()
        val summary = pack.mapDetailSummary?.takeIf { it.isNotBlank() && !it.startsWith("Import skeleton") }
            ?: "Bundled 3D satellite globe & corridor ready offline"
        return FlightPack(
            plan = enrichedPlan,
            cities = safeCities,
            weather = safeWeather,
            preparedAt = safePreparedAt,
            mapReady = true,
            mapRegionIds = emptyList(),
            mapDetailSummary = summary,
            sampledWeatherIds = safeSampled,
            mapTileKey = pack.mapTileKey,
            offlineContext = pack.offlineContext ?: OfflineFlightContext.prepare(enrichedPlan)
        )
    }
}

class FlightRepository(private val context: Context) {
    companion object {
        val fileMutex = Mutex()
    }

    val gson: Gson = FlightPackCodec.createGson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val packFile = File(context.filesDir, "flight-pack.json")
    private val packsFile = File(context.filesDir, "flight-packs.json")
    private val checkpointFile = File(context.filesDir, "preparation-checkpoint.json")
    private val historyDirectory = File(context.filesDir, "historical-tracks")

    /** Resolve/cache history before building any route-dependent offline data. No in-flight I/O. */
    suspend fun prepareHistory(plan: FlightPlan, refresh: Boolean = true): FlightPlan = withContext(Dispatchers.IO) {
        plan.effectiveHistoricalTrack?.let {
            cacheHistory(plan, it)
            return@withContext TrajectoryCatalog.enrichPlan(plan)
        }
        val key = historyKey(plan)
        var track = plan.historicalTrack?.takeIf { it.identifies(plan) } ?: runCatching {
            gson.fromJson(File(historyDirectory, "$key.json").readText(), HistoricalTrack::class.java)
        }.getOrNull()?.takeIf { it.identifies(plan) }
        if (track == null) {
            track = runCatching {
                context.assets.open("data/history/$key.json").bufferedReader().use {
                    gson.fromJson(it, HistoricalTrack::class.java)
                }
            }.getOrNull()?.takeIf { it.identifies(plan) }
        }
        // Only published, explicitly curated history is fetched. Never treat an airport lookup as a track.
        if (refresh && key == "MU689-PVG-DYG") {
            val downloaded = runCatching {
                val historyClient = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
                val url = "https://raw.githubusercontent.com/alexeygrigorev/flight-map/main/app/src/main/assets/data/history/$key.json"
                historyClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    val bytes = ByteArray(1_000_001)
                    var count = 0
                    body.byteStream().use { input ->
                        while (count < bytes.size) {
                            val read = input.read(bytes, count, bytes.size - count)
                            if (read < 0) break
                            count += read
                        }
                    }
                    if (count > 1_000_000) return@use null
                    gson.fromJson(String(bytes, 0, count, Charsets.UTF_8), HistoricalTrack::class.java)
                }
            }.getOrNull()?.takeIf { it.identifies(plan) }
            coroutineContext.ensureActive()
            // Do not replace a more complete cached user observation with a partial catalog capture.
            if (downloaded != null && (track == null || downloaded.matches(plan) || !track.matches(plan))) track = downloaded
        }
        track?.let { cacheHistory(plan, it) }
        TrajectoryCatalog.enrichPlan(plan.copy(historicalTrack = track))
    }

    private fun historyKey(plan: FlightPlan): String =
        "${TrajectoryCatalog.normalizeFlightNumber(plan.number)}-${plan.origin.iata}-${plan.destination.iata}"
            .replace(Regex("[^A-Za-z0-9_-]"), "_")

    private suspend fun cacheHistory(plan: FlightPlan, track: HistoricalTrack) = fileMutex.withLock {
        check(historyDirectory.isDirectory || historyDirectory.mkdirs()) { "Cannot create history cache" }
        val temporary = File(historyDirectory, "${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(gson.toJson(track))
            check(temporary.renameTo(File(historyDirectory, "${historyKey(plan)}.json"))) { "Cannot save historical track" }
        } finally {
            temporary.delete()
        }
    }

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
        val raw = GreatCircle.corridorCities(enriched, allCities, 180.0)
        FlightPackCodec.curateCities(enriched, raw, FlightPackCodec.MAX_CORRIDOR_CITIES)
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

    suspend fun importPacksFromJsonWithMeta(rawJson: String): ImportedFlightPacks = withContext(Dispatchers.IO) {
        if (allCities.isEmpty() || airports.isEmpty()) {
            loadData()
        }
        val parsed = FlightPackCodec.parsePacksWithMeta(rawJson, gson, allCities, airports)
        val imported = parsed.copy(packs = parsed.packs.map {
            FlightPackCodec.normalizeImportedPack(it.copy(plan = prepareHistory(it.plan, refresh = false)), allCities)
        })
        for (pack in imported.packs.asReversed()) {
            save(pack)
        }
        imported
    }

    suspend fun importPacksFromJson(rawJson: String): List<FlightPack> =
        importPacksFromJsonWithMeta(rawJson).packs

    suspend fun lookupOnlineFlightRoute(
        flightNumber: String,
        date: java.time.LocalDate,
        departureTime: java.time.LocalTime = java.time.LocalTime.of(9, 0)
    ): TrajectoryCatalog.ResolvedSchedule? = withContext(Dispatchers.IO) {
        val norm = TrajectoryCatalog.normalizeFlightNumber(flightNumber)
        if (norm.length < 3) return@withContext null
        TrajectoryCatalog.lookupSchedule(norm, date)?.let { return@withContext it }

        if (airports.isEmpty()) {
            runCatching { loadData() }
        }
        runCatching {
            val url = "https://api.adsbdb.com/v0/callsign/$norm"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "FlightMapAndroid/0.2 (personal offline flight companion)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val root = JsonParser.parseString(body).asJsonObject
                val flightRoute = root.getAsJsonObject("response")?.getAsJsonObject("flightroute") ?: return@runCatching null
                val originIata = flightRoute.getAsJsonObject("origin")?.get("iata_code")?.asString?.uppercase() ?: return@runCatching null
                val destIata = flightRoute.getAsJsonObject("destination")?.get("iata_code")?.asString?.uppercase() ?: return@runCatching null
                val airlineName = flightRoute.getAsJsonObject("airline")?.get("name")?.asString.orEmpty()
                val from = airports.firstOrNull { it.iata == originIata } ?: return@runCatching null
                val to = airports.firstOrNull { it.iata == destIata } ?: return@runCatching null
                if (from.iata == to.iata) return@runCatching null
                TrajectoryCatalog.buildScheduleForRoute(
                    flightNumber = norm,
                    origin = from,
                    destination = to,
                    date = date,
                    departureTime = departureTime,
                    airline = airlineName
                )
            }
        }.getOrNull()
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

    fun selectWeatherSampleCities(
        cities: List<City>,
        maxLocations: Int = FlightPackCodec.MAX_WEATHER_SAMPLES
    ): List<City> = WeatherSampling.selectCities(cities, maxLocations)

    suspend fun weather(
        plan: FlightPlan,
        cities: List<City>,
        existingWeather: Map<String, List<WeatherHour>> = emptyMap(),
        onChunkSaved: suspend (Map<String, List<WeatherHour>>, Int, Int) -> Unit = { _, _, _ -> },
        progress: (Int, Int) -> Unit
    ): Map<String, List<WeatherHour>> = withContext(Dispatchers.IO) {
        if (cities.isEmpty()) return@withContext emptyMap()
        val sampleCities = selectWeatherSampleCities(cities, FlightPackCodec.MAX_WEATHER_SAMPLES)
        val result = existingWeather.toMutableMap()

        val neededSamples = sampleCities.filter { it.id !in result }
        val totalSamples = sampleCities.size
        var completedSamples = sampleCities.count { it.id in result }

        progress(completedSamples, totalSamples)

        val start = plan.departure.atZone(ZoneOffset.UTC).toLocalDate()
        val end = plan.arrival.atZone(ZoneOffset.UTC).toLocalDate()
        val cacheDir = File(context.filesDir, "weather").apply { mkdirs() }

        for (chunk in neededSamples.chunked(24)) {
            runCatching {
                val lat = chunk.joinToString(",") { it.point.lat.toString() }
                val lon = chunk.joinToString(",") { it.point.lon.toString() }
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,weather_code,is_day&timeformat=unixtime&timezone=UTC&start_date=$start&end_date=$end"
                val key = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val cache = File(cacheDir, "$key.json")
                val raw = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) {
                    cache.readText()
                } else {
                    val response = client.newCall(
                        Request.Builder().url(url).header("User-Agent", "FlightMapAndroid/0.2 (personal offline flight companion)").build()
                    ).execute()
                    response.use {
                        check(it.isSuccessful) { "Weather HTTP ${it.code}" }
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
                    if (hours.isNotEmpty() &&
                        WeatherParser.nearest(hours, plan.departure) != null &&
                        WeatherParser.nearest(hours, plan.arrival) != null
                    ) {
                        result[city.id] = hours
                    } else {
                        result[city.id] = SolarCalculator.estimateHourlySeries(city.point, plan.departure, plan.arrival)
                    }
                }
            }.onFailure {
                // Fast approximate fallback when offline, rate-limited, or beyond 16-day forecast horizon (AC #6)
                for (city in chunk) {
                    result[city.id] = SolarCalculator.estimateHourlySeries(city.point, plan.departure, plan.arrival)
                }
            }
            completedSamples = sampleCities.count { it.id in result }
            progress(completedSamples, totalSamples)
            onChunkSaved(result, completedSamples, totalSamples)
        }

        result
    }
}
