package com.flightmap.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flightmap.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.MapLibre
import java.time.*

class FlightViewModel(application: Application) : AndroidViewModel(application) {
    val repository = FlightRepository(application)
    private val maps = OfflineMaps(application)
    private val timingEdits = Mutex()
    var monitoringPlan by mutableStateOf<FlightPlan?>(null); private set
    var timingBusy by mutableStateOf(false); private set

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
    /** Simulation-only clock. The real flight and notification always use wall time. */
    var livePlay by mutableStateOf<LivePlayClock?>(null); private set
    var simulationSpeed by mutableIntStateOf(1); private set
    var isLandscapeIfe by mutableStateOf(true); private set
    var preDeparturePushEnabled by mutableStateOf(true); private set
    var now by mutableStateOf(Instant.now()); private set

    private val prefs by lazy {
        application.getSharedPreferences("flight_map_prefs", android.content.Context.MODE_PRIVATE)
    }

    init {
        preDeparturePushEnabled = prefs.getBoolean("pre_departure_push_1h", true)
        viewModelScope.launch {
            runCatching {
                repository.loadData()
                val tiles = CorridorTiles(getApplication<Application>())
                val loadedPacks = repository.readPacks().map { p ->
                    val vectorOk = p.mapRegionIds.isEmpty() || maps.verify(p.mapRegionIds)
                    val rasterOk = p.mapTileKey == null || runCatching {
                        val (_, selection, coverage) = withContext(Dispatchers.IO) { tiles.coverage(p.plan) }
                        coverage.total == selection.tileCount && coverage.isComplete
                    }.getOrDefault(false)
                    p.copy(mapReady = p.mapReady && vectorOk && rasterOk)
                }
                packs = loadedPacks
                pack = loadedPacks.firstOrNull()

                val checkpoint = repository.readCheckpoint()
                val pending = checkpoint?.takeIf { it.status != PreparationStatus.COMPLETED }
                val checkpointDiscovered = pending?.let { maps.regionsFor(it.plan) }.orEmpty()
                val inProgressIds = (pending?.mapRegionIds.orEmpty() + checkpointDiscovered).distinct()
                val allReferenced = (loadedPacks.flatMap { it.mapRegionIds } + inProgressIds).distinct()
                maps.discardUnreferenced(allReferenced)
                withContext(Dispatchers.IO) {
                    runCatching {
                        val keepPlans = loadedPacks.map { it.plan } + listOfNotNull(pending?.plan)
                        tiles.pruneExceptPlans(keepPlans)
                    }
                }

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

        viewModelScope.launch { while (isActive) {
            now = Instant.now()
            monitoringPlan = FlightNotificationService.monitoredPlan(getApplication())
            delay(1000)
        } }
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

    fun toggleOrientation() {
        isLandscapeIfe = !isLandscapeIfe
    }

    fun setPreDeparturePush(enabled: Boolean, targetPlan: FlightPlan? = pack?.plan) {
        preDeparturePushEnabled = enabled
        prefs.edit().putBoolean("pre_departure_push_1h", enabled).apply()
        val p = targetPlan ?: pack?.plan
        stage = if (enabled) {
            if (p != null) {
                "1h pre-departure weather refresh push enabled for ${p.number}"
            } else {
                "1h pre-departure weather refresh push enabled"
            }
        } else {
            "1h pre-departure weather refresh push off"
        }
    }

    fun selectPack(selected: FlightPack) {
        pack = selected
        if (!pendingPreparation) syncPack()
    }

    fun importPackFromUri(uri: android.net.Uri, onImported: (FlightPack) -> Unit = {}) {
        viewModelScope.launch {
            error = null
            runCatching {
                val jsonText = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    if (uri.scheme == "file") {
                        val f = java.io.File(uri.path ?: error("Invalid file path"))
                        runCatching { f.readText() }.getOrElse { e ->
                            val fallbackExt = java.io.File(app.getExternalFilesDir(null), f.name)
                            val fallbackInt = java.io.File(app.filesDir, f.name)
                            when {
                                fallbackExt.exists() && fallbackExt.canRead() -> fallbackExt.readText()
                                fallbackInt.exists() && fallbackInt.canRead() -> fallbackInt.readText()
                                else -> throw e
                            }
                        }
                    } else {
                        app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not read selected file")
                    }
                }
                handleImportedJson(jsonText, onImported)
            }.onFailure {
                error = "Could not load flight pack file: ${it.message}"
            }
        }
    }

    fun importPackFromJson(rawJson: String, onImported: (FlightPack) -> Unit = {}) {
        viewModelScope.launch {
            error = null
            runCatching {
                handleImportedJson(rawJson, onImported)
            }.onFailure {
                error = "Could not load flight pack file: ${it.message}"
            }
        }
    }

    private suspend fun handleImportedJson(rawJson: String, onImported: (FlightPack) -> Unit) {
        val importedMeta = repository.importPacksFromJsonWithMeta(rawJson)
        val imported = importedMeta.packs
        val refreshed = repository.readPacks().map { p ->
            p.copy(mapReady = p.mapReady && (p.mapRegionIds.isEmpty() || maps.verify(p.mapRegionIds)))
        }
        packs = refreshed
        val primary = imported.first()
        val selected = refreshed.firstOrNull { isSameFlight(it.plan, primary.plan) } ?: primary
        pack = selected
        pendingPreparation = false
        syncPack()
        stage = if (imported.size > 1) {
            "Loaded ${imported.size} flights (${imported.joinToString { it.plan.number }}) · ready offline"
        } else {
            "Loaded ${selected.plan.number} (${selected.plan.origin.iata} → ${selected.plan.destination.iata}) · ready offline"
        }
        onImported(selected)
        if (importedMeta.demoStartsNow) {
            fly()
            startLivePlay(speedMultiplier = 1)
        }
    }

    fun deletePack(target: FlightPack) {
        if (monitoringPlan?.let { isSameFlight(it, target.plan) } == true) stopFlightNotification()
        viewModelScope.launch {
            runCatching { CorridorTiles(getApplication<Application>()).deletePlan(target.plan) }
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

    fun prepare(
        number: String,
        dateStr: String,
        origin: String? = null,
        destination: String? = null,
        departure: String? = null,
        arrival: String? = null
    ) {
        if (busy || !loaded) return
        error = null
        viewModelScope.launch {
            val plan = runCatching {
                var resolvedOrigin = origin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                var resolvedDest = destination?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                var resolvedDep = departure?.trim()?.takeIf { it.isNotBlank() }
                var resolvedArr = arrival?.trim()?.takeIf { it.isNotBlank() }

                val tmpl = TrajectoryCatalog.lookupTemplate(number)
                if (tmpl == null && (resolvedOrigin == null || resolvedDest == null)) {
                    val parsedDate = runCatching { LocalDate.parse(dateStr.trim()) }.getOrDefault(LocalDate.now())
                    val online = repository.lookupOnlineFlightRoute(number, parsedDate)
                    if (online != null) {
                        resolvedOrigin = resolvedOrigin ?: online.originIata
                        resolvedDest = resolvedDest ?: online.destinationIata
                        if (resolvedDep == null) resolvedDep = online.departureLocalIso
                        if (resolvedArr == null) resolvedArr = online.arrivalLocalIso
                    }
                }

                TrajectoryCatalog.resolvePlan(
                    flightNumber = number,
                    dateStr = dateStr,
                    airports = repository.airports,
                    originOverride = resolvedOrigin,
                    destinationOverride = resolvedDest,
                    departureOverride = resolvedDep,
                    arrivalOverride = resolvedArr
                )
            }.getOrElse {
                error = it.message ?: "Check flight number and date"
                return@launch
            }

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
    }

    /**
     * Launches simple simulation mode immediately (AC #14 & #15), synthesizing an offline pack
     * with approximate weather if the flight has not been downloaded yet, and starting takeoff play.
     */
    fun simulateFlightNow(candidatePlan: FlightPlan? = null, speedMultiplier: Int = 60) {
        if (busy || !loaded) return
        viewModelScope.launch {
            val targetPlan = candidatePlan ?: pack?.plan ?: return@launch
            val existing = packs.firstOrNull { isSameFlight(it.plan, targetPlan) }
            val readyPack = if (existing != null && existing.cities.isNotEmpty()) {
                existing
            } else {
                val skeleton = FlightPack(
                    plan = TrajectoryCatalog.enrichPlan(targetPlan),
                    cities = emptyList(),
                    weather = emptyMap(),
                    preparedAt = Instant.now(),
                    mapReady = true
                )
                val normalized = FlightPackCodec.normalizeImportedPack(skeleton, repository.allCities)
                repository.save(normalized)
                packs = repository.readPacks()
                normalized
            }
            pack = readyPack
            syncPack()
            fly()
            startLivePlay(speedMultiplier = speedMultiplier)
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
        if (pack == null || busy) return
        // Real flight always follows wall time. Only the explicitly labelled simulation anchors a clock.
        previewFraction = null
        livePlay = null
        simulationSpeed = 1
        MapLibre.setConnected(false)
        screen = "flight"
    }

    /** Press Play at takeoff: anchor wall-now to departure and advance 1:1 (or at simulationSpeed). */
    fun startLivePlay(speedMultiplier: Int = simulationSpeed) {
        val plan = simulationPlan() ?: return
        simulationSpeed = speedMultiplier.coerceIn(1, 600)
        livePlay = LivePlayClock(Instant.now(), FlightTiming.effectiveDeparture(plan), FlightTiming.estimatedArrival(plan))
        previewFraction = null
    }

    fun setSimSpeed(multiplier: Int) {
        val currentFrac = simulationPlan()?.let { p ->
            FlightTiming.progress(p, effectiveNow()).fraction.toFloat()
        } ?: 0f
        simulationSpeed = multiplier.coerceIn(1, 600)
        if (livePlay != null) {
            resumeLivePlayFromFraction(currentFrac)
        }
    }

    /**
     * Resume Play after a scrub: re-anchor so the scrubbed flight instant is "now"
     * and the clock continues from there toward arrival.
     */
    fun resumeLivePlayFromFraction(fraction: Float) {
        val plan = simulationPlan() ?: return
        val clamped = fraction.coerceIn(0f, 1f).toDouble()
        val end = FlightTiming.estimatedArrival(plan)
        val total = Duration.between(plan.departure, end).toMillis()
        val scrubInstant = plan.departure.plusMillis((total * clamped).toLong())
        val elapsedFlightMs = Duration.between(plan.departure, scrubInstant).toMillis()
        val scaledWallElapsedMs = (elapsedFlightMs / simulationSpeed.coerceAtLeast(1).toLong())
        livePlay = LivePlayClock(
            wallStartedAt = Instant.now().minusMillis(scaledWallElapsedMs),
            departure = plan.departure,
            arrival = end
        )
        previewFraction = null
    }

    fun pauseLivePlayKeepPosition() {
        val plan = simulationPlan() ?: return
        val frac = FlightTiming.progress(plan, effectiveNow()).fraction.toFloat()
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
        simulationSpeed = 1
    }

    fun edit() {
        screen = "prepare"
        previewFraction = null
        livePlay = null
        simulationSpeed = 1
        MapLibre.setConnected(true)
    }

    fun openNotifiedFlight(number: String, departure: String) {
        val selected = packs.firstOrNull { it.plan.number == number && it.plan.departure.toString() == departure } ?: return
        selectPack(selected)
        fly()
    }

    fun enableFlightNotification() {
        val plan = pack?.plan ?: return
        runCatching { FlightNotificationService.start(getApplication(), plan) }
            .onSuccess { monitoringPlan = plan }
            .onFailure { error = "Could not enable flight card: ${it.message}" }
    }

    fun stopFlightNotification() {
        FlightNotificationService.stop(getApplication())
        monitoringPlan = null
    }

    fun clearError() { error = null }

    fun setActualTakeoff(instant: Instant) = updateTiming { it.copy(actualTakeoff = instant, landedAt = null) }
    fun delayTakeoff() = updateTiming { it.copy(actualTakeoff = (it.actualTakeoff ?: it.departure).plusSeconds(1800), landedAt = null) }
    fun markLanded() = updateTiming { it.copy(landedAt = Instant.now()) }

    private fun updateTiming(transform: (FlightPlan) -> FlightPlan) {
        val target = pack?.plan ?: return
        if (timingBusy) return
        timingBusy = true
        viewModelScope.launch {
            try {
                timingEdits.withLock {
                    val latest = repository.readPacks().firstOrNull { isSameFlight(it.plan, target) }
                        ?: throw IllegalStateException("Flight pack no longer exists")
                    val changed = latest.copy(plan = transform(latest.plan))
                    repository.save(changed)
                    packs = repository.readPacks()
                    // An asynchronous save must never switch a different selected flight.
                    if (pack?.let { isSameFlight(it.plan, target) } == true) {
                        pack = packs.firstOrNull { isSameFlight(it.plan, target) } ?: changed
                        stopLivePlay()
                    }
                }
            } catch (failure: Exception) {
                error = "Could not save flight timing: ${failure.message}"
            } finally { timingBusy = false }
        }
    }

    private fun simulationPlan(): FlightPlan? = pack?.plan?.copy(actualTakeoff = null, landedAt = null)

    fun effectiveNow(): Instant {
        val plan = simulationPlan()
        val clock = livePlay
        if (plan != null && clock != null) {
            if (clock.isScrubbing || simulationSpeed <= 1) {
                return clock.flightNow(now)
            }
            val wallElapsedMs = Duration.between(clock.wallStartedAt, now).toMillis().coerceAtLeast(0L)
            val candidate = clock.departure.plusMillis(wallElapsedMs * simulationSpeed.toLong())
            return when {
                candidate <= clock.departure -> clock.departure
                candidate >= clock.arrival -> clock.arrival
                else -> candidate
            }
        }
        if (plan != null) {
            previewFraction?.let { frac ->
                return FlightTiming.timeAtFraction(plan, frac.toDouble())
            }
        }
        return now
    }
}

