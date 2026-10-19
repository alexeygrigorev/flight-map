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
    /** Takeoff-play clock: Play anchors wall-now to departure; scrub + return-to-current. */
    var livePlay by mutableStateOf<LivePlayClock?>(null); private set
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
        // Outside the real window: open ready for takeoff-play (clock starts when Play is pressed).
        // Inside the real window: follow wall clock until the user presses Play / scrubs.
        previewFraction = null
        livePlay = null
        if (currentNow < currentPack.plan.departure || currentNow > currentPack.plan.arrival) {
            // Leave clock idle at scheduled takeoff until Play; scrub still works via startLivePlay/scrub.
            previewFraction = 0f
        }
        MapLibre.setConnected(false)
        screen = "flight"
    }

    /** Press Play at takeoff: anchor wall-now to departure and advance 1:1. */
    fun startLivePlay() {
        val plan = pack?.plan ?: return
        livePlay = LivePlayClock.start(plan, Instant.now())
        previewFraction = null
    }

    /**
     * Resume Play after a scrub: re-anchor so the scrubbed flight instant is "now"
     * and the clock continues 1:1 from there toward arrival.
     */
    fun resumeLivePlayFromFraction(fraction: Float) {
        val plan = pack?.plan ?: return
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        val total = Duration.between(plan.departure, plan.arrival).toMillis()
        val scrubInstant = plan.departure.plusMillis((total * clamped).toLong())
        val elapsed = Duration.between(plan.departure, scrubInstant)
        livePlay = LivePlayClock(
            wallStartedAt = Instant.now().minus(elapsed),
            departure = plan.departure,
            arrival = plan.arrival
        )
        previewFraction = null
    }

    fun pauseLivePlayKeepPosition() {
        val clock = livePlay ?: return
        val frac = clock.fraction(Instant.now()).toFloat()
        livePlay = null
        previewFraction = frac
    }

    fun scrubTo(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val clock = livePlay
        if (clock != null) {
            livePlay = clock.withScrub(clamped.toDouble())
            previewFraction = null
        } else {
            previewFraction = clamped
        }
    }

    /** Always available: return to the live clock position (or wall clock if not in live-play). */
    fun returnToCurrentPosition() {
        val clock = livePlay
        if (clock != null) {
            livePlay = clock.returnToCurrent()
            previewFraction = null
        } else {
            previewFraction = null
        }
    }

    fun stopLivePlay() {
        livePlay = null
        previewFraction = null
    }

    fun edit() {
        screen = "prepare"
        previewFraction = null
        livePlay = null
        MapLibre.setConnected(true)
    }

    fun effectiveNow(): Instant {
        val plan = pack?.plan
        val clock = livePlay
        if (plan != null && clock != null) {
            return clock.flightNow(now)
        }
        if (plan != null) {
            previewFraction?.let { frac ->
                val total = Duration.between(plan.departure, plan.arrival).toMillis()
                return plan.departure.plusMillis((total * frac).toLong())
            }
        }
        return now
    }
}

