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
    var packs by mutableStateOf<List<FlightPack>>(emptyList()); private set
    var pack by mutableStateOf<FlightPack?>(null); private set
    var busy by mutableStateOf(false); private set
    var pendingPreparation by mutableStateOf(false); private set
    var preparationPlan by mutableStateOf<FlightPlan?>(null); private set
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

    init {
        viewModelScope.launch {
            runCatching {
                repository.loadData()
                val loadedPacks = repository.readPacks().map { p ->
                    p.copy(mapReady = p.mapReady && maps.verify(p.mapRegionIds))
                }
                packs = loadedPacks
                pack = loadedPacks.firstOrNull()

                val checkpoint = repository.readCheckpoint()
                val pending = checkpoint?.takeIf { it.status != PreparationStatus.COMPLETED }
                val checkpointDiscovered = pending?.let { maps.regionsFor(it.plan) }.orEmpty()
                val inProgressIds = (pending?.mapRegionIds.orEmpty() + checkpointDiscovered).distinct()
                val allReferenced = (loadedPacks.flatMap { it.mapRegionIds } + inProgressIds).distinct()
                maps.discardUnreferenced(allReferenced)

                if (checkpoint != null && checkpoint.status != PreparationStatus.COMPLETED) {
                    pendingPreparation = true
                    preparationPlan = checkpoint.plan
                    routeReady = checkpoint.routeReady
                    citiesReady = checkpoint.citiesReady
                    weatherReady = checkpoint.weatherReady
                    mapReady = checkpoint.mapReady
                    weatherDetail = checkpoint.weatherDetail
                    mapDetail = checkpoint.mapDetail
                    stage = if (checkpoint.status == PreparationStatus.PAUSED) "Preparation paused. Resume when ready." else checkpoint.stage
                    if (PreparationService.isRunning) {
                        busy = true
                    }
                }

                if (pack != null && !busy && !pendingPreparation) syncPack()
                loaded = true
            }.onFailure { error = "Could not load bundled data: ${it.message}" }
        }

        viewModelScope.launch {
            PreparationService.progressFlow.collect { progress ->
                if (progress.status == PreparationStatus.IN_PROGRESS) {
                    busy = true
                    pendingPreparation = false
                    preparationPlan = progress.plan
                    stage = progress.stage
                    error = progress.error
                    routeReady = progress.routeReady
                    citiesReady = progress.citiesReady
                    weatherReady = progress.weatherReady
                    mapReady = progress.mapReady
                    weatherDetail = progress.weatherDetail
                    mapDetail = progress.mapDetail
                } else if (progress.status == PreparationStatus.COMPLETED) {
                    busy = false
                    pendingPreparation = false
                    preparationPlan = null
                    stage = progress.stage
                    error = progress.error
                    routeReady = progress.routeReady
                    citiesReady = progress.citiesReady
                    weatherReady = progress.weatherReady
                    mapReady = progress.mapReady
                    weatherDetail = progress.weatherDetail
                    mapDetail = progress.mapDetail
                    val refreshedPacks = repository.readPacks()
                    packs = refreshedPacks
                    pack = refreshedPacks.firstOrNull { p ->
                        progress.plan?.let { isSameFlight(p.plan, it) } == true
                    } ?: refreshedPacks.firstOrNull()
                    syncPack()
                } else if (progress.status == PreparationStatus.PAUSED) {
                    busy = false
                    pendingPreparation = true
                    preparationPlan = progress.plan
                    stage = progress.stage.ifBlank { "Preparation paused. Resume when ready." }
                    error = progress.error
                    val refreshedPacks = repository.readPacks()
                    packs = refreshedPacks
                    if (pack == null) pack = refreshedPacks.firstOrNull()
                } else if (progress.status == PreparationStatus.FAILED) {
                    busy = false
                    pendingPreparation = true
                    preparationPlan = progress.plan
                    stage = progress.stage.ifBlank { "Preparation failed" }
                    error = progress.error
                }
            }
        }

        viewModelScope.launch { while (isActive) { now = Instant.now(); delay(250) } }
    }

    private fun syncPack() {
        pack?.let {
            routeReady = true; citiesReady = true
            weatherReady = it.forecastsReady
            mapReady = it.mapReady
            weatherDetail = "${it.weather.size} city forecasts saved"
            mapDetail = if (mapReady) it.effectiveMapDetailSummary else "Bundled overview only"
        }
    }

    fun selectPack(selected: FlightPack) {
        pack = selected
        if (!pendingPreparation) syncPack()
    }

    fun deletePack(target: FlightPack) {
        viewModelScope.launch {
            repository.deletePack(target)
            val remainingPacks = repository.readPacks()
            packs = remainingPacks
            if (pack?.let { isSameFlight(it.plan, target.plan) } == true) {
                pack = remainingPacks.firstOrNull()
                syncPack()
            }
            var checkpoint = repository.readCheckpoint()
            if (checkpoint?.status == PreparationStatus.COMPLETED && isSameFlight(checkpoint.plan, target.plan)) {
                repository.clearCheckpoint()
                checkpoint = null
            }
            checkpoint = checkpoint?.takeIf { it.status != PreparationStatus.COMPLETED }
            val checkpointDiscovered = checkpoint?.let { maps.regionsFor(it.plan) }.orEmpty()
            val inProgressIds = ((checkpoint?.mapRegionIds.orEmpty()) + checkpointDiscovered).distinct()
            val remainingReferenced = (remainingPacks.flatMap { it.mapRegionIds } + inProgressIds).toSet()
            val toDiscard = target.mapRegionIds.filter { it !in remainingReferenced }
            if (toDiscard.isNotEmpty()) {
                maps.discard(toDiscard)
            }
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
            TrajectoryCatalog.enrichPlan(FlightPlan(number.trim().uppercase().ifBlank { "MANUAL" }, from, to, dep, arr))
        }.getOrElse { error = it.message ?: "Check airports and local dates/times"; return }

        preparationPlan = plan
        busy = true
        pendingPreparation = false
        routeReady = true
        citiesReady = false
        weatherReady = false
        mapReady = false
        weatherDetail = "Waiting"
        mapDetail = "Waiting"
        stage = "Saving route and schedule"

        runCatching { PreparationService.startPreparation(getApplication(), plan) }.onFailure {
            busy = false; error = "Could not start preparation: ${it.message}"
        }
    }

    fun cancel() {
        stage = "Pausing preparation…"
        runCatching { PreparationService.pausePreparation(getApplication()) }.onFailure {
            error = "Could not pause preparation: ${it.message}"
        }
    }

    fun resume() {
        if (busy) return
        busy = true
        pendingPreparation = false
        stage = "Resuming preparation…"
        runCatching { PreparationService.resumePreparation(getApplication()) }.onFailure {
            busy = false; pendingPreparation = true
            error = "Could not resume preparation: ${it.message}"
        }
    }

    fun fly() {
        val currentPack = pack ?: return
        if (busy) return
        val currentNow = Instant.now()
        previewFraction = if (currentNow < currentPack.plan.departure || currentNow > currentPack.plan.arrival) {
            0.24f
        } else {
            null
        }
        MapLibre.setConnected(false)
        screen = "flight"
    }
    fun edit() { screen = "prepare"; previewFraction = null; MapLibre.setConnected(true) }
    fun effectiveNow(): Instant = pack?.plan?.let { p -> previewFraction?.let { p.departure.plusMillis((Duration.between(p.departure, p.arrival).toMillis() * it).toLong()) } } ?: now
}

