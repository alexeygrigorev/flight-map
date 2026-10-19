package com.flightmap.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flightmap.core.*
import org.maplibre.android.MapLibre
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

val Ink = Color(0xFF060A14)
val Panel = Color(0xFF10192B)
val IfeIndigo = Color(0xFF283054)
val IfeIndigoBright = Color(0xFF3A4678)
val Mint = Color(0xFF8DE0C6)
val Muted = Color(0xFFA6B9C8)
val Cream = Color(0xFFF2F3EE)
val Amber = Color(0xFFFFCE8B)
val PeakYellow = Color(0xFFFFE042)

private val colors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    background = Ink,
    onBackground = Cream,
    surface = Panel,
    onSurface = Cream,
    onSurfaceVariant = Muted,
    outline = Color(0xFF344857),
    secondaryContainer = Panel,
    onSecondaryContainer = Mint
)

class MainActivity : ComponentActivity() {
    private var pendingIntent by mutableStateOf<android.content.Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIntent = intent
        MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = colors) {
                val vm: FlightViewModel = viewModel()
                val activeIntent = pendingIntent
                LaunchedEffect(activeIntent, vm.loaded) {
                    if (!vm.loaded || activeIntent == null) return@LaunchedEffect
                    val dataUri = activeIntent.data
                    val extraPath = activeIntent.getStringExtra("import_pack_path")
                    if (dataUri != null) {
                        vm.importPackFromUri(dataUri)
                        activeIntent.data = null
                        pendingIntent = null
                    } else if (!extraPath.isNullOrBlank()) {
                        vm.importPackFromUri(android.net.Uri.fromFile(java.io.File(extraPath)))
                        activeIntent.removeExtra("import_pack_path")
                        pendingIntent = null
                    }
                }
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    if (vm.screen == "flight" && vm.pack != null) FlightScreen(vm) else PrepareScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }
}

@Composable
private fun Eyebrow(text: String, color: Color = Muted) {
    Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 2.sp, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun StatusPill(text: String, ready: Boolean = true) {
    Surface(
        color = Color(0xFF0B1324).copy(alpha = 0.78f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (ready) Mint.copy(alpha = .55f) else Amber.copy(alpha = .65f))
    ) {
        Text(
            "●  $text",
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = if (ready) Mint else Amber,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class TripPresetChip(
    val code: String,
    val date: String,
    val label: String
)

private val TRIP_PRESET_CHIPS = listOf(
    TripPresetChip("MU689", "2026-10-20", "MU689 · PVG→DYG · 20 Oct"),
    TripPresetChip("CZ3382", "2026-10-23", "CZ3382 · DYG→CAN · 23 Oct"),
    TripPresetChip("CZ3085", "2026-10-25", "CZ3085 · CAN→HND · 25 Oct"),
    TripPresetChip("EY801", "2026-10-30", "EY801 · NRT→AUH · 30 Oct"),
    TripPresetChip("DE2553", "2026-10-31", "DE2553 · AUH→BER · 31 Oct"),
    TripPresetChip("AY87", "2026-10-20", "AY87 · HEL→PVG · 20 Oct")
)

@Composable
private fun PrepareScreen(vm: FlightViewModel) {
    val defaultDate = "2026-10-20"
    val initialSchedule = remember {
        TrajectoryCatalog.lookupSchedule("MU689", LocalDate.parse(defaultDate))
            ?: TrajectoryCatalog.lookupSchedule("AY87", LocalDate.parse(defaultDate))
    }
    var flight by rememberSaveableState(initialSchedule?.flightNumber ?: "MU689")
    var date by rememberSaveableState(defaultDate)
    var origin by rememberSaveableState(initialSchedule?.originIata ?: "PVG")
    var destination by rememberSaveableState(initialSchedule?.destinationIata ?: "DYG")
    var departure by rememberSaveableState(initialSchedule?.departureLocalIso ?: "${defaultDate}T10:00")
    var arrival by rememberSaveableState(initialSchedule?.arrivalLocalIso ?: "${defaultDate}T12:10")
    var manualExpanded by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FlightPack?>(null) }
    val saved = vm.pack

    fun fillFrom(plan: FlightPlan) {
        flight = plan.number
        origin = plan.origin.iata
        destination = plan.destination.iata
        departure = plan.departure.atZone(ZoneId.of(plan.origin.timezone)).toLocalDateTime().withSecond(0).withNano(0).toString()
        arrival = plan.arrival.atZone(ZoneId.of(plan.destination.timezone)).toLocalDateTime().withSecond(0).withNano(0).toString()
        date = departure.take(10)
    }

    fun applyScheduleLookup(code: String, dateStr: String): Boolean {
        val parsedDate = runCatching { LocalDate.parse(dateStr.trim()) }.getOrNull() ?: return false
        val sched = TrajectoryCatalog.lookupSchedule(code, parsedDate)
        if (sched != null) {
            origin = sched.originIata
            destination = sched.destinationIata
            departure = sched.departureLocalIso
            arrival = sched.arrivalLocalIso
            return true
        }
        val from = vm.repository.airports.firstOrNull { it.iata == origin }
        val to = vm.repository.airports.firstOrNull { it.iata == destination }
        if (from != null && to != null && from.iata != to.iata) {
            val depTime = runCatching {
                LocalTime.parse(departure.substringAfter("T", "09:00").take(5))
            }.getOrDefault(LocalTime.of(9, 0))
            val synth = TrajectoryCatalog.buildScheduleForRoute(code, from, to, parsedDate, depTime)
            departure = synth.departureLocalIso
            arrival = synth.arrivalLocalIso
        }
        return false
    }

    val packFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importPackFromUri(uri) { imported ->
                fillFrom(imported.plan)
            }
        }
    }

    LaunchedEffect(flight, date, vm.loaded) {
        if (!vm.loaded) return@LaunchedEffect
        val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return@LaunchedEffect
        if (TrajectoryCatalog.lookupSchedule(flight, parsedDate) != null) {
            applyScheduleLookup(flight, date)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        val online = vm.repository.lookupOnlineFlightRoute(flight, parsedDate)
        if (online != null) {
            origin = online.originIata
            destination = online.destinationIata
            departure = online.departureLocalIso
            arrival = online.arrivalLocalIso
        }
    }

    val resolvedPreview = remember(flight, date, origin, destination, departure, arrival, vm.loaded) {
        if (!vm.loaded) return@remember null
        runCatching {
            TrajectoryCatalog.resolvePlan(
                flightNumber = flight,
                dateStr = date,
                airports = vm.repository.airports,
                originOverride = origin,
                destinationOverride = destination,
                departureOverride = departure,
                arrivalOverride = arrival
            )
        }.getOrElse {
            val from = vm.repository.airports.firstOrNull { it.iata == origin }
            val to = vm.repository.airports.firstOrNull { it.iata == destination }
            if (from != null && to != null && from.iata != to.iata) {
                TrajectoryCatalog.enrichPlan(FlightPlan(flight.ifBlank { "MANUAL" }, from, to, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))
            } else null
        }
    }

    // Match existing saved pack if user selected or entered an already-prepared flight (avoids re-download trap!)
    val matchingSavedPack = remember(vm.packs, flight, origin, destination, date) {
        val normFlight = TrajectoryCatalog.normalizeFlightNumber(flight)
        vm.packs.firstOrNull { p ->
            val pDate = p.plan.departure.atZone(ZoneId.of(p.plan.origin.timezone)).toLocalDate().toString()
            TrajectoryCatalog.normalizeFlightNumber(p.plan.number) == normFlight &&
                p.plan.origin.iata == origin &&
                p.plan.destination.iata == destination &&
                pDate == date.trim()
        }
    }
    val isCurrentFlightPrepared = matchingSavedPack != null && matchingSavedPack.mapReady && matchingSavedPack.forecastsReady

    LaunchedEffect(saved?.plan) {
        saved?.plan?.let { fillFrom(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Eyebrow("Flight Map · Offline In-Flight Globe", Mint)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { about = true }) {
                Icon(Icons.Outlined.Info, "About & data sources", tint = Muted)
            }
        }
        Text(
            "Prepare for flight",
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.8).sp
        )
        Text(
            "Enter flight number + date to confirm schedule & download once, or load a .flightpack.json file.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // Quick Trip Presets (China / Japan / Abu Dhabi / Berlin + AY87)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            items(TRIP_PRESET_CHIPS) { chip ->
                val selected = TrajectoryCatalog.normalizeFlightNumber(flight) == chip.code && date == chip.date
                Surface(
                    onClick = {
                        flight = chip.code
                        date = chip.date
                        applyScheduleLookup(chip.code, chip.date)
                        vm.packs.firstOrNull {
                            TrajectoryCatalog.normalizeFlightNumber(it.plan.number) == chip.code
                        }?.let { vm.selectPack(it) }
                    },
                    color = if (selected) IfeIndigoBright else Panel,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, if (selected) Mint else Color(0xFF283A52))
                ) {
                    Text(
                        chip.label,
                        color = if (selected) Mint else Cream,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Primary Simplified Inputs: ONLY Flight number + Date
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                flight,
                {
                    val upper = it.uppercase()
                    flight = upper
                    applyScheduleLookup(upper, date)
                },
                label = { Text("Flight number") },
                placeholder = { Text("MU689") },
                singleLine = true,
                enabled = !vm.busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                date,
                {
                    date = it
                    applyScheduleLookup(flight, it)
                },
                label = { Text("Date") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                enabled = !vm.busy,
                modifier = Modifier.weight(1.15f),
                shape = RoundedCornerShape(14.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Confirmed Flight Card (shows confirmed airports, local dep/arr times, carrier, corridor)
        val templateMatch = remember(flight) { TrajectoryCatalog.lookupTemplate(flight) }
        val originAirport = remember(origin, vm.loaded) { vm.repository.airports.firstOrNull { it.iata == origin } }
        val destAirport = remember(destination, vm.loaded) { vm.repository.airports.firstOrNull { it.iata == destination } }
        val originCity = originAirport?.let { IFE_AIRPORT_CITY_NAMES[it.iata] ?: it.name.substringBefore(" ") } ?: origin
        val destCity = destAirport?.let { IFE_AIRPORT_CITY_NAMES[it.iata] ?: it.name.substringBefore(" ") } ?: destination
        val depShort = remember(departure) {
            runCatching {
                LocalDateTime.parse(departure).format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
            }.getOrDefault(departure)
        }
        val arrShort = remember(arrival) {
            runCatching {
                LocalDateTime.parse(arrival).format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
            }.getOrDefault(arrival)
        }

        Surface(
            color = Panel,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (isCurrentFlightPrepared) Mint.copy(alpha = 0.55f) else Color(0xFF23344A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(
                            if (isCurrentFlightPrepared) "CONFIRMED FLIGHT · OFFLINE READY" else "CONFIRMED FLIGHT",
                            if (isCurrentFlightPrepared) Mint else Amber
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${flight.ifBlank { "FLIGHT" }} · $origin ($originCity) → $destination ($destCity)",
                            color = Cream,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (templateMatch != null) {
                            Text(
                                "${templateMatch.airline} · ${templateMatch.aircraft}",
                                color = Mint,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                    StatusPill(
                        if (isCurrentFlightPrepared) "READY" else "CONFIRMED",
                        ready = isCurrentFlightPrepared
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("DEPARTURE", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(depShort, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(originAirport?.timezone ?: "Local", color = Muted, fontSize = 10.5.sp)
                    }
                    Text("✈", color = Mint, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ARRIVAL", color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(arrShort, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(destAirport?.timezone ?: "Local", color = Muted, fontSize = 10.5.sp)
                    }
                }
                resolvedPreview?.let { res ->
                    val wps = res.effectiveWaypoints
                    val km = GreatCircle.routeDistanceKm(wps).roundToInt()
                    Text(
                        "${res.effectiveRouteLabel} · ${wps.size} waypoints · %,d km".format(km),
                        color = if (res.isRealTrajectory) Mint else Amber,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Curated corridor cities & approx weather",
                        color = Muted,
                        fontSize = 10.5.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { manualExpanded = !manualExpanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp),
                        enabled = !vm.busy
                    ) {
                        Text(
                            if (manualExpanded) "Hide manual override ▴" else "Edit route / time ▾",
                            color = Mint,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (manualExpanded) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = Color(0xFF0C1424),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF23344A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Eyebrow("Manual route & time override", Muted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AirportInput("From", origin, {
                            origin = it
                            applyScheduleLookup(flight, date)
                        }, vm, Modifier.weight(1f))
                        AirportInput("To", destination, {
                            destination = it
                            applyScheduleLookup(flight, date)
                        }, vm, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        departure,
                        { newDep ->
                            departure = newDep
                            val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                            val from = vm.repository.airports.firstOrNull { it.iata == origin }
                            val to = vm.repository.airports.firstOrNull { it.iata == destination }
                            val timePart = newDep.trim().substringAfter("T").take(5)
                            val parsedTime = runCatching { LocalTime.parse(timePart) }.getOrNull()
                            if (parsedDate != null && from != null && to != null && parsedTime != null) {
                                val sched = TrajectoryCatalog.buildScheduleForRoute(flight, from, to, parsedDate, parsedTime)
                                arrival = sched.arrivalLocalIso
                            }
                        },
                        label = { Text("Departure local ISO") },
                        singleLine = true,
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        arrival,
                        { arrival = it },
                        label = { Text("Arrival local ISO") },
                        singleLine = true,
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // NO RE-DOWNLOAD TRAP (AC #2 & #3):
        // If this flight is already downloaded, primary button is "Open offline flight" (NOT Download data again!).
        // If not yet downloaded, primary button is "Download data".
        if (isCurrentFlightPrepared && matchingSavedPack != null && !vm.busy) {
            Button(
                onClick = {
                    vm.selectPack(matchingSavedPack)
                    vm.fly()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics { contentDescription = "Open offline flight" },
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.FlightTakeoff, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Open offline flight (${matchingSavedPack.plan.number})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "✓ Data downloaded (${matchingSavedPack.cities.size} cities + offline globe)",
                    color = Mint,
                    fontSize = 11.5.sp
                )
                TextButton(
                    onClick = { vm.prepare(flight, date, origin, destination, departure, arrival) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Refresh weather", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Button(
                onClick = {
                    vm.prepare(flight, date, origin, destination, departure, arrival)
                },
                enabled = vm.loaded && !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics { contentDescription = "Download data" },
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (vm.busy) "Downloading flight data…" else "Download data", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Secondary Action Row: [Simulate flight now] + [Load .flightpack.json]
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    if (matchingSavedPack != null) {
                        vm.selectPack(matchingSavedPack)
                        vm.simulateFlightNow(matchingSavedPack.plan)
                    } else {
                        vm.simulateFlightNow(resolvedPreview)
                    }
                },
                enabled = vm.loaded && !vm.busy,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(0.95f)
                    .height(46.dp)
                    .semantics { contentDescription = "Simulate flight" },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Amber.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(17.dp), tint = Amber)
                Spacer(Modifier.width(5.dp))
                Text("Simulate flight", color = Amber, fontSize = 12.sp, maxLines = 1)
            }

            OutlinedButton(
                onClick = {
                    packFilePicker.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                },
                enabled = vm.loaded && !vm.busy,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1.2f)
                    .height(46.dp)
                    .semantics { contentDescription = "Load prepared file" },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Mint.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Outlined.FileOpen, null, Modifier.size(17.dp), tint = Mint)
                Spacer(Modifier.width(5.dp))
                Text("Load .flightpack.json", color = Mint, fontSize = 12.sp, maxLines = 1)
            }
        }

        // Optional ~1h before departure push notification toggle (AC #5)
        Spacer(Modifier.height(10.dp))
        Surface(
            color = Panel,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF23344A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Push reminder ~1h before departure",
                        color = Cream,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Optional notification to refresh corridor weather before boarding",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = vm.preDeparturePushEnabled,
                    onCheckedChange = { enabled ->
                        vm.setPreDeparturePush(enabled, resolvedPreview ?: saved?.plan)
                    }
                )
            }
        }

        if (vm.stage.isNotBlank()) {
            Text(
                vm.stage,
                Modifier.padding(top = 8.dp),
                color = if (vm.mapReady && vm.weatherReady) Mint else Muted,
                fontSize = 12.5.sp
            )
        }
        vm.error?.let {
            Text(it, color = Amber, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // Saved / Imported Flights list (supports multi-flight trip packs!)
        if (vm.packs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("PREPARED FLIGHTS (${vm.packs.size})", Mint)
            Spacer(Modifier.height(8.dp))
            vm.packs.forEach { flightPack ->
                val enrichedPlan = TrajectoryCatalog.enrichPlan(flightPack.plan)
                val isSelected = flightPack == saved
                Surface(
                    color = if (isSelected) Color(0xFF15233C) else Panel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (isSelected) Mint.copy(alpha = 0.5f) else Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 7.dp)
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(enabled = !vm.busy) {
                                    vm.selectPack(flightPack)
                                    fillFrom(flightPack.plan)
                                }
                        ) {
                            Text(
                                "${flightPack.plan.number} · ${flightPack.plan.origin.iata} → ${flightPack.plan.destination.iata}",
                                color = if (isSelected) Mint else Cream,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${flightPack.cities.size} cities · " +
                                    flightPack.plan.departure.atZone(ZoneId.of(flightPack.plan.origin.timezone))
                                        .format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")) +
                                    " · ${enrichedPlan.effectiveRouteLabel}",
                                color = Muted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = {
                                vm.selectPack(flightPack)
                                vm.fly()
                            },
                            enabled = !vm.busy
                        ) {
                            Text("Open", color = Mint, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { deleting = flightPack }, enabled = !vm.busy, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Delete, "Delete ${flightPack.plan.number}", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    deleting?.let { flightPack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete prepared flight?") },
            text = {
                Text("${flightPack.plan.number} · ${flightPack.plan.origin.iata} → ${flightPack.plan.destination.iata} will be removed from this device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePack(flightPack)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep") }
            }
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Made for the window seat") },
            text = {
                Text(
                    "Flight Map renders an airline IFE 3D satellite globe with filed ATS route corridors, curated overflown cities with Wikidata-style cards, mountain peaks, solar day/night terminator, and offline weather.\n\nData: NASA Blue Marble (public domain), Natural Earth (public domain), OurAirports, GeoNames, Open-Meteo.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { about = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun rememberSaveableState(initial: String) =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun AirportInput(
    label: String,
    value: String,
    change: (String) -> Unit,
    vm: FlightViewModel,
    modifier: Modifier
) {
    Column(modifier) {
        OutlinedTextField(
            value,
            { change(it.uppercase()) },
            label = { Text(label) },
            singleLine = true,
            enabled = !vm.busy,
            textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        val selected = vm.repository.airports.firstOrNull { it.iata == value }
        if (selected != null) {
            Text(selected.name, color = Muted, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlightScreen(vm: FlightViewModel) {
    val rawPack = vm.pack ?: return
    val plan = remember(rawPack.plan) { TrajectoryCatalog.enrichPlan(rawPack.plan) }
    val pack = remember(rawPack, plan) {
        if (rawPack.plan.waypoints == plan.waypoints) rawPack else rawPack.copy(plan = plan)
    }

    // Support both Landscape & Portrait orientations with an on-screen toggle button (AC #20)
    val context = LocalContext.current
    val view = LocalView.current
    val isLandscape = vm.isLandscapeIfe
    DisposableEffect(isLandscape) {
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val window = activity?.window
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, view)
        } else null
        insetsController?.let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isLandscape) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = previousOrientation
        }
    }

    val cameraState = rememberGlobeCameraState(plan)
    val time = vm.effectiveNow()
    val progress = FlightTiming.progress(plan, time)

    // Curated corridor cities ordered from origin to destination
    val corridorCities = remember(pack.cities, plan) {
        pack.cities.sortedBy { city ->
            GreatCircle.distanceKm(plan.origin.point, city.point)
        }
    }

    val nearest = remember(corridorCities, progress.point) {
        corridorCities
            .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, progress.point) <= 185.0 }
            .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
            ?: corridorCities.minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
    }
    val weatherLocation = nearest?.takeIf { it.id in pack.weather } ?: corridorCities
        .filter { it.id in pack.weather }
        .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
    val weather = weatherLocation?.let { WeatherParser.nearest(pack.weather[it.id].orEmpty(), time) }
        ?: SolarCalculator.estimateSurfaceWeather(progress.point, time)
    val solarIsDay = remember(progress.point, time.epochSecond / 60) {
        SolarCalculator.isDaylight(progress.point, time)
    }
    val tailWindText = remember(progress.point, progress.heading) {
        SolarCalculator.estimateCruiseWindLabel(progress.point, progress.heading)
    }

    var previewOpen by remember { mutableStateOf(vm.livePlay != null) }
    var isPlaying by remember { mutableStateOf(vm.livePlay != null && !vm.livePlay!!.isScrubbing) }

    LaunchedEffect(vm.livePlay) {
        if (vm.livePlay != null) {
            previewOpen = true
            isPlaying = !vm.livePlay!!.isScrubbing
        }
    }

    // Live-play advances via wall clock in ViewModel; stop Play UI when flight ends.
    LaunchedEffect(isPlaying, vm.livePlay, progress.fraction) {
        if (isPlaying && vm.livePlay != null && progress.fraction >= 1.0) {
            isPlaying = false
        }
    }

    BackHandler { vm.edit() }

    val originCityName = remember(plan.origin) {
        IFE_AIRPORT_CITY_NAMES[plan.origin.iata] ?: plan.origin.name.substringBefore(" ")
    }
    val destCityName = remember(plan.destination) {
        IFE_AIRPORT_CITY_NAMES[plan.destination.iata] ?: plan.destination.name.substringBefore(" ")
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // 1. Fullscreen 3D Satellite Earth IFE Globe (zero-lag lockstep projection + smooth anti-aliased limb)
        IfeGlobeView(
            pack = pack,
            progress = progress,
            countries = vm.repository.countries,
            allCities = vm.repository.allCities,
            cameraState = cameraState,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Route Progress Header + Orientation Switch Button (Landscape <-> Portrait)
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xCC040812),
                            Color(0x66060B18),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                onClick = { vm.edit() },
                color = Color(0x9916203A),
                shape = CircleShape,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Edit flight",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        originCityName,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    IfeHeaderProgressBar(
                        fraction = progress.fraction.toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                    )
                    Text(
                        destCityName,
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Text(
                    "${plan.number}  ·  ${tailWindText.substringBefore(" |")}  ·  %,d km left  ·  %dh %02dm  ·  ETA %s".format(
                        progress.remainingKm.roundToInt(),
                        progress.remaining.toHours(),
                        progress.remaining.toMinutes() % 60,
                        plan.arrival.atZone(ZoneId.of(plan.destination.timezone))
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                    ),
                    color = Color(0xFFBAC8DC),
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Orientation Toggle Button (Switch between Horizontal Landscape and Vertical Portrait - AC #20)
                Surface(
                    onClick = { vm.toggleOrientation() },
                    color = Color(0x9916203A),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Mint.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .height(32.dp)
                        .semantics {
                            contentDescription = if (isLandscape) "Switch to portrait" else "Switch to landscape"
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.ScreenRotation,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            if (isLandscape) "Portrait" else "Landscape",
                            color = Mint,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    onClick = {
                        previewOpen = !previewOpen
                    },
                    color = if (previewOpen) IfeIndigoBright else Color(0x9916203A),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Takeoff Play & Scrubber",
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        // 3. Discreet Floating Bottom Panel (houses Wikidata city card on tap, Takeoff Play & Sim clock, and controls)
        DiscreetIfePanel(
            pack = pack,
            corridorCities = corridorCities,
            progress = progress,
            time = time,
            nearest = nearest,
            weather = weather,
            solarIsDay = solarIsDay,
            cameraState = cameraState,
            previewOpen = previewOpen,
            isPlaying = isPlaying,
            isLiveAnchored = vm.livePlay != null,
            simulationSpeed = vm.simulationSpeed,
            previewFraction = vm.previewFraction,
            onTogglePreview = {
                previewOpen = !previewOpen
            },
            onTogglePlay = {
                if (isPlaying) {
                    vm.pauseLivePlayKeepPosition()
                    isPlaying = false
                } else {
                    val scrub = vm.livePlay?.scrubFraction
                    if (scrub != null) {
                        vm.resumeLivePlayFromFraction(scrub.toFloat())
                    } else if (vm.previewFraction != null && vm.livePlay == null) {
                        vm.resumeLivePlayFromFraction(vm.previewFraction!!)
                    } else {
                        vm.startLivePlay()
                    }
                    isPlaying = true
                    previewOpen = true
                }
            },
            onToggleSimSpeed = {
                val nextSpeed = if (vm.simulationSpeed > 1) 1 else 60
                vm.setSimSpeed(nextSpeed)
                if (!isPlaying) {
                    vm.startLivePlay(nextSpeed)
                    isPlaying = true
                    previewOpen = true
                }
            },
            onScrub = { value ->
                isPlaying = false
                vm.scrubTo(value)
                previewOpen = true
            },
            onResetTime = {
                val wasLive = vm.livePlay != null
                vm.returnToCurrentPosition()
                isPlaying = wasLive && vm.livePlay != null
            },
            onInspectCity = { city ->
                cameraState.selectedFeature = SelectedGlobeFeature.CityFeature(city)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = if (isLandscape) 18.dp else 10.dp, vertical = 8.dp)
                .widthIn(max = 880.dp)
        )
    }
}

/**
 * Single discreet, low-profile translucent bottom panel that unifies:
 * - Concise structured Wikidata-style card when a city (or peak/pin) on the map is tapped (AC #16)
 * - Takeoff Play clock, 1×/60× simulation toggle, scrubber, and always-available "Return to now" (AC #13, #15)
 * - Current overflown place, local clock, day/night, weather & camera controls
 */
@Composable
private fun DiscreetIfePanel(
    pack: FlightPack,
    corridorCities: List<City>,
    progress: FlightProgress,
    time: Instant,
    nearest: City?,
    weather: WeatherHour,
    solarIsDay: Boolean,
    cameraState: GlobeCameraState,
    previewOpen: Boolean,
    isPlaying: Boolean,
    isLiveAnchored: Boolean,
    simulationSpeed: Int,
    previewFraction: Float?,
    onTogglePreview: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleSimSpeed: () -> Unit,
    onScrub: (Float) -> Unit,
    onResetTime: () -> Unit,
    onInspectCity: (City) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val localClock = remember(nearest, time.epochSecond / 60) {
        val tzId = nearest?.timezone ?: pack.plan.origin.timezone
        runCatching {
            val clock = time.atZone(ZoneId.of(tzId)).format(timeFormatter)
            val offset = SolarCalculator.utcOffsetLabel(tzId, time)
            "$clock ($offset)"
        }.getOrDefault("—")
    }

    Surface(
        color = Color(0xCC081020),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            // 1. Short structured Wikidata-style card when user taps a city on the map (or taps city in dock)
            cameraState.selectedFeature?.let { feature ->
                WikidataFeatureCard(
                    feature = feature,
                    pack = pack,
                    corridorCities = corridorCities,
                    progress = progress,
                    flightTime = time,
                    onSelectCity = { nextCity ->
                        cameraState.selectedFeature = SelectedGlobeFeature.CityFeature(nextCity)
                        cameraState.focusPoint(nextCity.point)
                    },
                    onFocusPoint = { cameraState.focusPoint(it) },
                    onDismiss = { cameraState.selectedFeature = null }
                )
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            }

            // 2. Takeoff Play / Simulation / Scrubber + Always-available "Return to now" row (AC #13 & #15)
            if (previewOpen) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${
                            time.atZone(ZoneId.of(pack.plan.origin.timezone))
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        } (${(progress.fraction * 100).roundToInt()}%)",
                        color = Amber,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        onClick = onTogglePlay,
                        color = if (isPlaying) Mint.copy(alpha = 0.22f) else Color(0xFF152642),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Mint.copy(alpha = 0.5f)),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (isPlaying) "⏸ Pause" else if (isLiveAnchored) "▶ Resume" else "▶ Takeoff Play",
                                fontSize = 10.5.sp,
                                color = Mint,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Surface(
                        onClick = onToggleSimSpeed,
                        color = if (simulationSpeed > 1.0) Amber.copy(alpha = 0.22f) else Color(0xFF152642),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (simulationSpeed > 1.0) Amber else Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .height(24.dp)
                            .semantics { contentDescription = "Toggle simulation speed" }
                    ) {
                        Box(Modifier.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (simulationSpeed > 1.0) "60× Sim" else "1× Live",
                                fontSize = 10.sp,
                                color = if (simulationSpeed > 1.0) Amber else Cream,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Surface(
                        onClick = onResetTime,
                        color = Color(0xFF152642),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                        modifier = Modifier
                            .height(24.dp)
                            .semantics { contentDescription = "Return to current position" }
                    ) {
                        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Return to now", fontSize = 10.sp, color = Cream)
                        }
                    }
                    val sliderValue = ((previewFraction ?: progress.fraction.toFloat()) * 1000f)
                        .roundToInt()
                        .coerceIn(0, 1000)
                    AndroidView(
                        factory = { ctx ->
                            SeekBar(ctx).apply {
                                max = 1000
                                contentDescription = "Preview journey scrubber"
                                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                                        if (fromUser) {
                                            onScrub((value / 1000f).coerceIn(0f, 1f))
                                        }
                                    }
                                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                                })
                            }
                        },
                        update = { seekBar ->
                            if (seekBar.progress != sliderValue) {
                                seekBar.progress = sliderValue
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                    )
                }
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.10f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // 3. Primary single-line discreet bar: [Tap city -> short Wikidata card] | [Controls]
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable {
                            val target = nearest ?: corridorCities.firstOrNull()
                            if (target != null) onInspectCity(target)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (solarIsDay) "☀" else "☾",
                        color = if (solarIsDay) PeakYellow else Color(0xFF9FB8FF),
                        fontSize = 12.sp
                    )
                    Text(
                        nearest?.let { "${it.name}, ${it.country}" } ?: "Open skies",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text("·", color = Muted, fontSize = 11.sp)
                    Text(
                        localClock,
                        color = Color(0xFFBFD4EE),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Text("·", color = Muted, fontSize = 11.sp)
                    Text(
                        "${weather.temperature.roundToInt()}°C ${WeatherParser.conditionText(weather.code)}",
                        color = Cream,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Discreet panel action chips & camera buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        onClick = {
                            val currentCity = (cameraState.selectedFeature as? SelectedGlobeFeature.CityFeature)?.city
                            if (currentCity != null && cameraState.selectedFeature != null) {
                                cameraState.selectedFeature = null
                            } else {
                                val target = nearest ?: corridorCities.firstOrNull()
                                if (target != null) onInspectCity(target)
                            }
                        },
                        color = if (cameraState.selectedFeature != null) IfeIndigoBright else Color(0xFF132038),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Mint.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .height(28.dp)
                            .semantics { contentDescription = "City card" }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "City card",
                                color = Mint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        onClick = onTogglePreview,
                        color = if (previewOpen) IfeIndigoBright else Color(0xFF132038),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (previewOpen) "Hide clock" else "Play / Sim",
                                color = if (previewOpen) Mint else Cream,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    DiscreetIconButton(
                        icon = Icons.Outlined.Flight,
                        description = "Plane",
                        active = cameraState.followPlane,
                        onClick = { cameraState.toggleFollowPlane(progress, pack.cities) }
                    )

                    DiscreetIconButton(
                        icon = Icons.Outlined.Explore,
                        description = if (cameraState.headingUp) "Heading" else "North",
                        active = cameraState.headingUp,
                        onClick = { cameraState.toggleHeadingUp() }
                    )

                    DiscreetTextButton(
                        label = "−",
                        description = "Zoom out",
                        onClick = { cameraState.zoomOut() }
                    )

                    DiscreetTextButton(
                        label = "+",
                        description = "Zoom in",
                        onClick = { cameraState.zoomIn() }
                    )

                    if (!cameraState.isDefaultOverview) {
                        Surface(
                            onClick = { cameraState.resetToRouteOverview() },
                            color = IfeIndigoBright,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Route", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscreetIconButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) Color(0xFF2563EB) else Color(0xFF15223C),
        border = BorderStroke(1.dp, if (active) Color.White else Color.White.copy(alpha = 0.22f)),
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
            Text(description, fontSize = 1.sp, color = Color.Transparent)
        }
    }
}

@Composable
private fun DiscreetTextButton(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color(0xFF15223C),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Concise, structured Wikidata-style card shown when tapping any city/peak/landmark on the globe (AC #16).
 * Replaces the old overloaded 2,600-city bottom sheet list.
 */
@Composable
private fun WikidataFeatureCard(
    feature: SelectedGlobeFeature,
    pack: FlightPack,
    corridorCities: List<City>,
    progress: FlightProgress,
    flightTime: Instant,
    onSelectCity: (City) -> Unit,
    onFocusPoint: (GeoPoint) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val title: String
    val countryTag: String
    val wikidataSubtitle: String
    val populationBadge: String?
    val point: GeoPoint
    val timezone: String?
    val fact: String
    val weather: WeatherHour?
    val currentCityIndex: Int

    when (feature) {
        is SelectedGlobeFeature.CityFeature -> {
            val c = feature.city
            val card = IfeCatalog.wikidataCard(c)
            title = card.name
            countryTag = card.country
            wikidataSubtitle = card.description
            populationBadge = "Pop. ${card.populationLabel}"
            point = c.point
            timezone = c.timezone
            fact = card.summaryFact
            weather = WeatherParser.nearest(pack.weather[c.id].orEmpty(), flightTime)
                ?: SolarCalculator.estimateSurfaceWeather(c.point, flightTime)
            currentCityIndex = corridorCities.indexOfFirst { it.id == c.id }
        }
        is SelectedGlobeFeature.PeakFeature -> {
            val p = feature.peak
            title = "▲ ${p.name}"
            countryTag = p.region
            wikidataSubtitle = "Mountain peak · ${"%,d".format(p.elevationFt)} ft"
            populationBadge = null
            point = p.point
            timezone = null
            fact = p.fact
            weather = null
            currentCityIndex = -1
        }
        is SelectedGlobeFeature.PinFeature -> {
            val pin = feature.pin
            title = pin.name
            countryTag = pin.country
            wikidataSubtitle = "Curated route landmark"
            populationBadge = null
            point = pin.point
            timezone = pin.timezone
            fact = pin.fact
            val nearestCityWithWx = pack.cities
                .filter { it.id in pack.weather }
                .minByOrNull { GreatCircle.distanceKm(it.point, pin.point) }
            weather = nearestCityWithWx?.let { WeatherParser.nearest(pack.weather[it.id].orEmpty(), flightTime) }
                ?: SolarCalculator.estimateSurfaceWeather(pin.point, flightTime)
            currentCityIndex = -1
        }
    }

    val distKm = GreatCircle.distanceKm(progress.point, point).roundToInt()
    val bearingDesc = GreatCircle.relativeBearingDescription(progress.point, progress.heading, point)
        .substringBefore(" ·")
    val localClock = timezone?.let { tz ->
        runCatching {
            val clock = flightTime.atZone(ZoneId.of(tz)).format(timeFormatter)
            val offset = SolarCalculator.utcOffsetLabel(tz, flightTime)
            "$clock ($offset)"
        }.getOrNull()
    }

    val telemetryLine = buildString {
        if (populationBadge != null) {
            append(populationBadge)
            append("  ·  ")
        }
        append("$bearingDesc ($distKm km)")
        if (localClock != null) {
            append("  ·  Local $localClock")
        }
        if (weather != null) {
            append("  ·  ${weather.temperature.roundToInt()}°C ${WeatherParser.conditionText(weather.code)}")
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(countryTag, color = Mint, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                wikidataSubtitle,
                color = Color(0xFF93C5FD),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (corridorCities.size > 1) {
                TextButton(
                    onClick = {
                        val prevIdx = if (currentCityIndex > 0) currentCityIndex - 1 else corridorCities.lastIndex
                        onSelectCity(corridorCities[prevIdx])
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(22.dp)
                ) {
                    Text("◀ Prev", color = Muted, fontSize = 10.sp)
                }
                TextButton(
                    onClick = {
                        val nextIdx = if (currentCityIndex in 0 until corridorCities.lastIndex) currentCityIndex + 1 else 0
                        onSelectCity(corridorCities[nextIdx])
                    },
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                    modifier = Modifier.height(22.dp)
                ) {
                    Text("Next ▶", color = Mint, fontSize = 10.sp)
                }
            }
            TextButton(
                onClick = { onFocusPoint(point) },
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                modifier = Modifier.height(22.dp)
            ) {
                Text("Center", color = Mint, fontSize = 10.5.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss city card",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            telemetryLine,
            color = Amber,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            fact,
            color = Color(0xFFCBD5E1),
            fontSize = 11.sp,
            lineHeight = 14.5.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Top header flight progress track matching `flight-map-ref-ife.jpg`:
 * `Helsinki ——✈————————○ Shanghai`
 */
@Composable
private fun IfeHeaderProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier.semantics {
            contentDescription = "Flight progress ${(clamped * 100).roundToInt()} percent"
        }
    ) {
        val cy = size.height / 2f
        val startX = 6.dp.toPx()
        val endX = size.width - 8.dp.toPx()
        val trackW = (endX - startX).coerceAtLeast(1f)
        val planeX = startX + trackW * clamped

        // Remaining track (subtle grey-blue line)
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = androidx.compose.ui.geometry.Offset(startX, cy),
            end = androidx.compose.ui.geometry.Offset(endX, cy),
            strokeWidth = 2.dp.toPx()
        )
        // Flown track (bright white line)
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(startX, cy),
            end = androidx.compose.ui.geometry.Offset(planeX, cy),
            strokeWidth = 2.8.dp.toPx()
        )
        // Origin dot
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(startX, cy)
        )
        // Destination open ring (matching ref photo `○` right before Shanghai)
        drawCircle(
            color = Color(0xFF0A1020),
            radius = 4.5.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(endX, cy)
        )
        drawCircle(
            color = Color.White,
            radius = 4.5.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(endX, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx())
        )

        // Crisp airplane silhouette pointing right along the progress bar
        val r = 7.5.dp.toPx()
        val planePath = androidx.compose.ui.graphics.Path().apply {
            moveTo(planeX + r, cy)
            lineTo(planeX + r * 0.2f, cy - r * 0.22f)
            lineTo(planeX - r * 0.25f, cy - r * 0.95f)
            lineTo(planeX - r * 0.55f, cy - r * 0.95f)
            lineTo(planeX - r * 0.3f, cy - r * 0.22f)
            lineTo(planeX - r * 0.75f, cy - r * 0.18f)
            lineTo(planeX - r, cy - r * 0.55f)
            lineTo(planeX - r * 1.1f, cy - r * 0.55f)
            lineTo(planeX - r * 0.95f, cy)
            lineTo(planeX - r * 1.1f, cy + r * 0.55f)
            lineTo(planeX - r, cy + r * 0.55f)
            lineTo(planeX - r * 0.75f, cy + r * 0.18f)
            lineTo(planeX - r * 0.3f, cy + r * 0.22f)
            lineTo(planeX - r * 0.55f, cy + r * 0.95f)
            lineTo(planeX - r * 0.25f, cy + r * 0.95f)
            lineTo(planeX + r * 0.2f, cy + r * 0.22f)
            close()
        }
        drawPath(planePath, color = Color.White)
    }
}

private val IFE_AIRPORT_CITY_NAMES = mapOf(
    "HEL" to "Helsinki",
    "PVG" to "Shanghai",
    "SHA" to "Shanghai",
    "DYG" to "Zhangjiajie",
    "CAN" to "Guangzhou",
    "PEK" to "Beijing",
    "PKX" to "Beijing",
    "NRT" to "Tokyo",
    "HND" to "Tokyo",
    "AUH" to "Abu Dhabi",
    "BER" to "Berlin",
    "ICN" to "Seoul",
    "SIN" to "Singapore",
    "BKK" to "Bangkok",
    "HKG" to "Hong Kong",
    "FRA" to "Frankfurt",
    "MUC" to "Munich",
    "LHR" to "London",
    "CDG" to "Paris",
    "AMS" to "Amsterdam",
    "IST" to "Istanbul",
    "DXB" to "Dubai",
    "DOH" to "Doha",
    "JFK" to "New York",
    "LAX" to "Los Angeles",
    "SFO" to "San Francisco"
)
