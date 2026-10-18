package com.flightmap.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flightmap.core.*
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import java.time.*

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    val repository = FlightRepository(application)
    private val maps = OfflineMaps(application)
    var loaded by mutableStateOf(false); private set
    var pack by mutableStateOf<FlightPack?>(null); private set
    var busy by mutableStateOf(false); private set
    var stage by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var mapDetail by mutableStateOf(""); private set
    var weatherDetail by mutableStateOf(""); private set
    var routeReady by mutableStateOf(false); private set
    var citiesReady by mutableStateOf(false); private set
    var weatherReady by mutableStateOf(false); private set
    var mapReady by mutableStateOf(false); private set
    var screen by mutableStateOf("prepare"); private set
    var previewFraction by mutableStateOf<Float?>(null)
    var now by mutableStateOf(Instant.now()); private set
    private var preparation: Job? = null

    init {
        viewModelScope.launch {
            runCatching {
                repository.loadData()
                pack = repository.readPack()?.let { it.copy(mapReady = it.mapReady && maps.verify(it.mapRegionIds)) }
                if (pack != null) syncPack()
                loaded = true
            }.onFailure { error = "Could not load bundled data: ${it.message}" }
        }
        viewModelScope.launch { while (isActive) { now = Instant.now(); delay(1000) } }
    }
    private fun syncPack() {
        pack?.let {
            routeReady = true; citiesReady = true
            weatherReady = it.cities.isNotEmpty() && it.cities.all { c -> !it.weather[c.id].isNullOrEmpty() }
            mapReady = it.mapReady
            weatherDetail = "${it.weather.size} city forecasts saved"
            mapDetail = if (mapReady) "Corridor saved · zoom 0–5" else "Bundled overview only"
        }
    }
    fun prepare(number: String, origin: String, destination: String, departure: String, arrival: String) {
        if (busy || !loaded) return
        error = null
        val plan = runCatching {
            val from = repository.airports.firstOrNull { it.iata == origin.trim().uppercase() } ?: error("Origin airport not found")
            val to = repository.airports.firstOrNull { it.iata == destination.trim().uppercase() } ?: error("Destination airport not found")
            require(from.iata != to.iata) { "Choose two different airports" }
            fun local(text: String, airport: Airport): Instant {
                val date = LocalDateTime.parse(text.trim())
                val zone = ZoneId.of(airport.timezone)
                val offsets = zone.rules.getValidOffsets(date)
                require(offsets.size == 1) { "${airport.iata}: time is ambiguous or skipped by daylight saving. Choose an unambiguous time." }
                return date.toInstant(offsets.single())
            }
            val dep = local(departure, from); val arr = local(arrival, to)
            require(Duration.between(dep, arr).toHours() in 0..30) { "Arrival must be after departure, within 30 hours" }
            FlightPlan(number.trim().uppercase().ifBlank { "MANUAL" }, from, to, dep, arr)
        }.getOrElse { error = it.message ?: "Check airports and local dates/times"; return }
        preparation = viewModelScope.launch {
            val previous = pack
            var newIds = emptyList<Long>()
            var saved = false
            busy = true; routeReady = false; citiesReady = false; weatherReady = false; mapReady = false
            weatherDetail = "Waiting"; mapDetail = "Waiting"
            MapLibre.setConnected(true)
            try {
                stage = "Saving route and schedule"; routeReady = true
                val cities = repository.corridor(plan)
                citiesReady = true; stage = "Downloading city forecasts"
                var weather = emptyMap<String, List<WeatherHour>>()
                val issues = mutableListOf<String>()
                try {
                    weather = repository.weather(plan, cities) { done, total -> viewModelScope.launch { weatherDetail = "$done / $total cities" } }
                    weatherReady = weather.isNotEmpty() && weather.size == cities.size
                    weatherDetail = if (cities.isEmpty()) "No cities in this corridor" else "${weather.size} city forecasts saved"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { issues += e.message ?: "Weather download failed"; weatherDetail = "Forecast download incomplete" }
                stage = "Downloading offline vector corridor"
                var ids = emptyList<Long>()
                try {
                    ids = maps.download(plan) { detail -> mapDetail = detail }
                    newIds = ids
                    mapReady = maps.verify(ids)
                    mapDetail = if (mapReady) "Corridor saved · zoom 0–5" else "Map verification failed"
                } catch (e: TimeoutCancellationException) { issues += "Map download timed out. Retry on Wi-Fi."; mapDetail = "Bundled overview available · retry detail" }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { issues += e.message ?: "Map download failed"; mapDetail = "Bundled overview available · retry detail" }
                val readyPack = FlightPack(plan, cities, weather, Instant.now(), mapReady, ids)
                repository.save(readyPack); pack = readyPack
                saved = true
                maps.discard(previous?.mapRegionIds.orEmpty().filter { it !in ids })
                error = issues.takeIf { it.isNotEmpty() }?.joinToString("\n")
                stage = if (weatherReady && mapReady) "Ready for airplane mode" else "Saved with missing downloads"
            } catch (e: CancellationException) { syncPack(); stage = "Preparation cancelled. Previous flight preserved." }
            catch (e: Exception) { syncPack(); error = e.message; stage = "Preparation failed" }
            finally { if (!saved) maps.discard(newIds); busy = false }
        }
    }
    fun cancel() { preparation?.cancel() }
    fun fly() { if (pack == null || busy) return; previewFraction = null; MapLibre.setConnected(false); screen = "flight" }
    fun edit() { screen = "prepare"; previewFraction = null; MapLibre.setConnected(true) }
    fun effectiveNow(): Instant = pack?.plan?.let { p -> previewFraction?.let { p.departure.plusMillis((Duration.between(p.departure, p.arrival).toMillis() * it).toLong()) } } ?: now
}
