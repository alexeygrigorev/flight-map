package com.flightmap.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
                    val notifiedKey = activeIntent.getStringExtra("open_flight_key")
                    if (notifiedKey != null) {
                        vm.openNotifiedFlight(notifiedKey)
                        activeIntent.removeExtra("open_flight_key")
                        pendingIntent = null
                        return@LaunchedEffect
                    }
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
    TripPresetChip("MU689", "2026-10-20", "MU689"),
    TripPresetChip("CZ3382", "2026-10-23", "CZ3382"),
    TripPresetChip("CZ3085", "2026-10-25", "CZ3085"),
    TripPresetChip("EY801", "2026-10-30", "EY801"),
    TripPresetChip("DE2553", "2026-10-31", "DE2553"),
    TripPresetChip("AY87", "2026-10-20", "AY87")
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
    var optionsOpen by remember { mutableStateOf(false) }
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

    val originAirport = remember(origin, vm.loaded) { vm.repository.airports.firstOrNull { it.iata == origin } }
    val destAirport = remember(destination, vm.loaded) { vm.repository.airports.firstOrNull { it.iata == destination } }
    val originCity = originAirport?.let { IFE_AIRPORT_CITY_NAMES[it.iata] ?: it.name.substringBefore(" ") } ?: origin
    val destCity = destAirport?.let { IFE_AIRPORT_CITY_NAMES[it.iata] ?: it.name.substringBefore(" ") } ?: destination
    val depTimeOnly = remember(departure) {
        runCatching { LocalDateTime.parse(departure).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(departure.takeLast(5))
    }
    val arrTimeOnly = remember(arrival) {
        runCatching { LocalDateTime.parse(arrival).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault(arrival.takeLast(5))
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // 1. Minimalist Title
        Text(
            "Flight Map",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
            color = Cream
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$origin → $destination  ·  $originCity to $destCity  ·  $depTimeOnly–$arrTimeOnly",
            color = if (isCurrentFlightPrepared) Mint else Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(24.dp))

        // 2. Flight Number & Date Inputs
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                flight,
                {
                    val upper = it.uppercase()
                    flight = upper
                    applyScheduleLookup(upper, date)
                },
                label = { Text("Flight") },
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

        Spacer(Modifier.height(20.dp))

        // 3. Single Main Action Button
        if (isCurrentFlightPrepared && !vm.busy) {
            val readyPack = matchingSavedPack!!
            Button(
                onClick = {
                    vm.selectPack(readyPack)
                    vm.fly()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .semantics { contentDescription = "Open offline flight" },
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.FlightTakeoff, null, Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text("Open flight map", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (readyPack.mapTileKey == null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        vm.prepare(flight, date, origin, destination, departure, arrival)
                    },
                    enabled = vm.loaded && !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Download detailed route map" },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download detailed route map", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Saved flight has the bundled overview only — re-download on Wi-Fi adds detailed imagery for deep zoom.",
                    Modifier.padding(top = 6.dp),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
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
                Icon(Icons.Outlined.Download, null, Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (vm.busy) "Preparing offline map…" else "Download offline map",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (vm.busy && vm.stage.isNotBlank()) {
            Text(
                vm.stage,
                Modifier.padding(top = 10.dp),
                color = Mint,
                fontSize = 12.sp
            )
        }
        if (vm.busy && vm.mapDetail.isNotBlank()) {
            Text(
                vm.mapDetail,
                Modifier.padding(top = 4.dp),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        if (vm.busy && vm.weatherDetail.isNotBlank()) {
            Text(
                vm.weatherDetail,
                Modifier.padding(top = 2.dp),
                color = Muted,
                fontSize = 11.sp
            )
        }
        vm.error?.let {
            Text(it, color = Amber, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(16.dp))

        // 4. Single Quiet Trigger for Secondary UI (Saved flights, Simulate, Import, Options)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = { optionsOpen = true },
                enabled = !vm.busy,
                modifier = Modifier.semantics { contentDescription = "Saved flights and options" }
            ) {
                val label = if (vm.packs.isNotEmpty()) {
                    "Saved flights (${vm.packs.size}) & options"
                } else {
                    "Saved flights & options"
                }
                Text(label, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (optionsOpen) {
        AlertDialog(
            onDismissRequest = { optionsOpen = false },
            containerColor = Color(0xFF0D1628),
            titleContentColor = Cream,
            textContentColor = Cream,
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Saved flights & options", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(
                        onClick = { about = true },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Outlined.Info, "About & data sources", tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (vm.packs.isNotEmpty()) {
                        Eyebrow("SAVED FLIGHTS (${vm.packs.size})", Muted)
                        Spacer(Modifier.height(6.dp))
                        vm.packs.forEach { flightPack ->
                            val isSelected = flightPack == matchingSavedPack || flightPack == saved
                            val depDateStr = flightPack.plan.departure
                                .atZone(ZoneId.of(flightPack.plan.origin.timezone))
                                .format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
                            Surface(
                                color = if (isSelected) Color(0xFF162744) else Panel,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isSelected) Mint.copy(alpha = 0.45f) else Color.Transparent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 5.dp)
                            ) {
                                Row(
                                    Modifier
                                        .clickable(enabled = !vm.busy) {
                                            vm.selectPack(flightPack)
                                            fillFrom(flightPack.plan)
                                            optionsOpen = false
                                        }
                                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        flightPack.plan.number,
                                        color = if (isSelected) Mint else Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(62.dp)
                                    )
                                    Text(
                                        "${flightPack.plan.origin.iata} → ${flightPack.plan.destination.iata}",
                                        color = Cream,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            depDateStr,
                                            color = Muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (flightPack.mapReady) flightPack.effectiveMapDetailSummary
                                            else "Map incomplete — resume preparation",
                                            color = if (flightPack.mapReady) Muted else Amber,
                                            fontSize = 10.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            optionsOpen = false
                                            vm.selectPack(flightPack)
                                            vm.fly()
                                        },
                                        enabled = !vm.busy,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Open", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    IconButton(
                                        onClick = { deleting = flightPack },
                                        enabled = !vm.busy,
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, "Delete ${flightPack.plan.number}", tint = Muted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Eyebrow("TOOLS", Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                optionsOpen = false
                                if (matchingSavedPack != null) {
                                    vm.selectPack(matchingSavedPack)
                                    vm.simulateFlightNow(matchingSavedPack.plan)
                                } else {
                                    vm.simulateFlightNow(resolvedPreview)
                                }
                            },
                            enabled = vm.loaded && !vm.busy,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Simulate flight" },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(15.dp), tint = Amber)
                            Spacer(Modifier.width(4.dp))
                            Text("Simulate", color = Amber, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                optionsOpen = false
                                packFilePicker.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                            },
                            enabled = vm.loaded && !vm.busy,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Load prepared file" },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.FileOpen, null, Modifier.size(15.dp), tint = Cream)
                            Spacer(Modifier.width(4.dp))
                            Text("Import pack", color = Cream, fontSize = 12.sp)
                        }
                    }

                    if (isCurrentFlightPrepared) {
                        if (matchingSavedPack?.mapTileKey == null) {
                            TextButton(
                                onClick = {
                                    optionsOpen = false
                                    vm.prepare(flight, date, origin, destination, departure, arrival)
                                },
                                enabled = !vm.busy,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("Download detailed route map", color = Mint, fontSize = 12.sp)
                            }
                            Text(
                                "Saved flight has the bundled overview only.",
                                color = Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    optionsOpen = false
                                    vm.prepare(flight, date, origin, destination, departure, arrival)
                                },
                                enabled = !vm.busy,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text("Refresh offline map & weather", color = Mint, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { manualExpanded = !manualExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        enabled = !vm.busy
                    ) {
                        Text(
                            if (manualExpanded) "Hide custom route & schedule ▴" else "Custom route & schedule ▾",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    if (manualExpanded) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            arrival,
                            { arrival = it },
                            label = { Text("Arrival local ISO") },
                            singleLine = true,
                            enabled = !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Reminder ~1h before departure",
                                color = Cream,
                                fontSize = 12.sp
                            )
                            Switch(
                                checked = vm.preDeparturePushEnabled,
                                onCheckedChange = { enabled ->
                                    vm.setPreDeparturePush(enabled, resolvedPreview ?: saved?.plan)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { optionsOpen = false }) {
                    Text("Done", color = Mint)
                }
            }
        )
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
                    "Flight Map renders an airline IFE 3D satellite globe with filed ATS route corridors, curated overflown cities with Wikidata-style cards, mountain peaks, solar day/night terminator, and offline weather.\n\nData: NASA Blue Marble (public domain), Natural Earth (public domain), OurAirports, GeoNames, Open-Meteo.\n\nOffline maps: route-corridor vector regions cached via OpenFreeMap (© OpenStreetMap contributors, OpenMapTiles; offline use explicitly permitted) for retention, plus corridor imagery via NASA GIBS Blue Marble Shaded Relief (public domain) which is the detail rendered on the 3D globe at deep zoom. The globe screen shows full attribution.",
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
    val simulation = vm.livePlay != null || vm.previewFraction != null
    val plan = remember(rawPack.plan, simulation) {
        val enriched = TrajectoryCatalog.enrichPlan(rawPack.plan)
        if (simulation) enriched.copy(actualTakeoff = null, landedAt = null) else enriched
    }
    val pack = remember(rawPack, plan) {
        if (rawPack.plan == plan) rawPack else rawPack.copy(plan = plan)
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
    var controlsOpen by remember { mutableStateOf(false) }
    var simulationOpen by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxSize(),
            flightTime = time
        )

        RouteFlightHeader(
            plan = plan, progress = progress, time = time,
            originName = originCityName, destinationName = destCityName,
            simulation = simulation, isLandscape = isLandscape,
            onBack = { vm.edit() }, onControls = { controlsOpen = true },
            onOrientation = { vm.toggleOrientation() },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 3. Attribution (always above the dock) + discreet floating bottom panel.
        // The pill rides directly on top of the panel, so it stays legible and
        // clear of the dock in portrait/landscape, with the timeline expanded
        // or a city card open - never hidden behind the panel like before.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = if (isLandscape) 18.dp else 10.dp, vertical = 8.dp)
                .widthIn(max = 880.dp),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                color = Color(0x99081218),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
            ) {
                Text(
                    "© OpenStreetMap contributors · OpenFreeMap · OpenMapTiles | Imagery: NASA Visible Earth",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
            DiscreetIfePanel(
            pack = pack,
            corridorCities = corridorCities,
            progress = progress,
            time = time,
            nearest = nearest,
            weather = weather,
            solarIsDay = solarIsDay,
            cameraState = cameraState,
            isLandscape = isLandscape,
            onTogglePreview = { controlsOpen = true },
            modifier = Modifier.fillMaxWidth()
        )
    }
    }
    if (controlsOpen) FlightControlsDialog(vm, onDismiss = { controlsOpen = false }, onSimulation = {
        controlsOpen = false
        simulationOpen = true
    })
    if (simulationOpen) SimulationDialog(vm) { simulationOpen = false }
}

/**
 * Single discreet, low-profile translucent bottom panel that unifies:
 * - Concise structured Wikidata-style card shown ONLY when the user explicitly taps a
 *   city (or peak/pin) marker on the map; dismissible via X, map background tap, or
 *   Route reset (AC #16). Never auto-opened: no nearest-city card on initial load,
 *   timeline scrubbing, Play/Sim, or Plane-follow.
 * - Flight controls open in a dialog, leaving the map free of a time-scrubbing row.
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
    isLandscape: Boolean,
    onTogglePreview: () -> Unit,
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
            // 1. Short structured Wikidata-style card shown ONLY on explicit map-marker tap (no auto-open)
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

            // 3. Discreet status & camera controls bar (1-line in landscape, 2-line in portrait so city name never truncates)
            @Composable
            fun LocationSummaryRow(rowModifier: Modifier) {
                // Tap-only city cards: this status line is display-only and never opens a card.
                // City detail appears only when the user explicitly taps a marker on the map.
                Row(
                    rowModifier,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
            }

            @Composable
            fun ActionControlsRow(rowModifier: Modifier = Modifier) {
                Row(
                    modifier = rowModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        onClick = onTogglePreview,
                        color = Color(0xFF132038),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Flight controls",
                                color = Cream,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (!isLandscape) {
                        Spacer(Modifier.weight(1f))
                    }

                    if (!cameraState.isDefaultOverview) {
                        Surface(
                            onClick = { cameraState.resetToRouteOverview() },
                            color = IfeIndigoBright,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text("Reset", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    DiscreetIconButton(
                        icon = Icons.Outlined.Flight,
                        description = "Plane",
                        active = cameraState.followPlane,
                        onClick = { cameraState.toggleFollowPlane(progress, pack.cities) }
                    )

                    DiscreetTextButton(
                        label = "N ↑",
                        description = "North up; reset rotation and tilt",
                        onClick = {
                            cameraState.rotationDeg = 0.0
                            cameraState.tiltDeg = 0.0
                        }
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
                }
            }

            if (isLandscape) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LocationSummaryRow(Modifier.weight(1f))
                    ActionControlsRow()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LocationSummaryRow(Modifier.fillMaxWidth())
                    ActionControlsRow(Modifier.fillMaxWidth())
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
        if (feature !is SelectedGlobeFeature.CityFeature) Text(
            fact, color = Color(0xFFCBD5E1), fontSize = 11.sp, lineHeight = 14.5.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
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

@Composable
private fun RouteFlightHeader(
    plan: FlightPlan, progress: FlightProgress, time: Instant,
    originName: String, destinationName: String, simulation: Boolean, isLandscape: Boolean,
    onBack: () -> Unit, onControls: () -> Unit, onOrientation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(color = Ink.copy(alpha = .94f), modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Saved flights", tint = Cream) }
            Column(Modifier.weight(1f).clickable(onClick = onControls).padding(horizontal = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${plan.number} · ${if (simulation) "SIMULATION" else "ESTIMATED"}", color = if (simulation) Amber else Mint,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Tune, "Flight controls", tint = Mint, modifier = Modifier.size(20.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.widthIn(max = 112.dp).weight(1f)) {
                        Text(plan.origin.iata, color = Cream, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(originName, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IfeHeaderProgressBar(progress.fraction.toFloat(), Modifier.weight(1.4f).height(24.dp))
                    Column(Modifier.widthIn(max = 112.dp).weight(1f), horizontalAlignment = Alignment.End) {
                        Text(plan.destination.iata, color = Cream, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(destinationName, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(FlightPresentation.status(progress, plan.landedAt != null), color = if (progress.phase == "ARRIVED") Mint else Cream,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${FlightPresentation.altitude(progress)} · ${(progress.fraction * 100).roundToInt()}%", color = Muted, fontSize = 11.sp)
                Text(FlightPresentation.arrival(plan, progress, time), color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onOrientation) {
                Icon(Icons.Outlined.ScreenRotation, if (isLandscape) "Switch to portrait" else "Switch to landscape", tint = Mint)
            }
        }
    }
}

@Composable
private fun FlightControlsDialog(vm: FlightViewModel, onDismiss: () -> Unit, onSimulation: () -> Unit) {
    val plan = vm.pack?.plan ?: return
    val context = LocalContext.current
    val zone = ZoneId.of(plan.origin.timezone)
    var takeoff by remember(plan.actualTakeoff, plan.departure) {
        mutableStateOf((plan.actualTakeoff ?: plan.departure).atZone(zone).toLocalDateTime().withSecond(0).withNano(0).toString())
    }
    var durationText by remember(plan.estimatedAirborneMinutes) { mutableStateOf(plan.estimatedAirborneMinutes?.toString().orEmpty()) }
    var durationError by remember { mutableStateOf<String?>(null) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val notifications = context.getSystemService(android.app.NotificationManager::class.java)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && notifications.areNotificationsEnabled()) vm.enableFlightNotification() else permissionDenied = true
    }
    val monitoring = vm.monitoringPlan?.let { isSameFlight(it, plan) } == true
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${plan.number} · Flight controls") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Position and altitude are estimates. Your saved timing applies to this flight only.", color = Muted)
            OutlinedTextField(takeoff, { takeoff = it; inputError = null }, label = { Text("Actual takeoff · ${plan.origin.timezone}") },
                supportingText = { Text(inputError ?: "Local date and time: 2026-10-20T10:30") },
                isError = inputError != null, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !vm.timingBusy, onClick = {
                    runCatching { FlightPresentation.parseTakeoff(takeoff, plan.origin.timezone) }
                        .onSuccess { vm.setActualTakeoff(it) }.onFailure { inputError = it.message ?: "Check date and time" }
                }) { Text("Save takeoff") }
                OutlinedButton(enabled = !vm.timingBusy, onClick = { vm.delayTakeoff() }) { Text("+30 min delay") }
            }
            TextButton(enabled = !vm.timingBusy, onClick = { vm.setActualTakeoff(Instant.now()) }) { Text("Took off now") }
            Button(enabled = !vm.timingBusy && plan.landedAt == null, onClick = { vm.markLanded() }) {
                Text(if (plan.landedAt != null) "Landed ✓" else "Mark landed now")
            }
            OutlinedTextField(durationText, { durationText = it; durationError = null },
                label = { Text("Estimated airborne duration · minutes") },
                supportingText = { Text(durationError ?: FlightPresentation.durationSource(plan)) },
                isError = durationError != null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !vm.timingBusy, onClick = {
                runCatching { FlightPresentation.parseAirborneMinutes(durationText) }
                    .onSuccess { vm.setEstimatedAirborneMinutes(it) }.onFailure { durationError = it.message }
            }) { Text("Save duration estimate") }
            HorizontalDivider()
            Text("Ongoing flight card", fontWeight = FontWeight.Bold)
            Text("Route, plane progress and estimated arrival continue offline when this app is in the background.", fontSize = 12.sp, color = Muted)
            OutlinedButton(onClick = {
                if (monitoring) vm.stopFlightNotification()
                else if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("ongoing_flight")?.importance == android.app.NotificationManager.IMPORTANCE_NONE) permissionDenied = true
                else vm.enableFlightNotification()
            }) { Text(if (monitoring) "Stop flight card" else "Enable flight card") }
            if (permissionDenied) {
                Text("Allow Flight Map notifications in Android settings to show the flight card.", color = Amber, fontSize = 12.sp)
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
            }
            vm.error?.let { Text(it, color = Amber, fontSize = 12.sp) }
            HorizontalDivider()
            TextButton(onClick = onSimulation) { Text("Open simulation…") }
            if (vm.livePlay != null || vm.previewFraction != null) TextButton(onClick = { vm.stopLivePlay(); onDismiss() }) { Text("Return to real time") }
            Text(FlightPresentation.arrival(plan, FlightTiming.progress(plan, vm.now), vm.now), color = Muted, fontSize = 12.sp)
            Text(plan.effectiveRouteLabel, color = Muted, fontSize = 11.sp)
            plan.historicalTrack?.let { track ->
                Text("Historical source: ${track.source} · ${track.observedDeparture.atZone(ZoneOffset.UTC).toLocalDate()}", color = Muted, fontSize = 11.sp)
                track.coverageNote?.let { Text(it, color = Amber, fontSize = 11.sp) }
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(track.sourceUrl)))
                }) { Text("Historical source details") }
            }
            Text(FlightTiming.progress(plan, vm.now).altitudeSource ?: "No altitude observation available", color = Muted, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun SimulationDialog(vm: FlightViewModel, onDismiss: () -> Unit) {
    val plan = vm.pack?.plan?.copy(actualTakeoff = null, landedAt = null) ?: return
    val progress = FlightTiming.progress(plan, vm.effectiveNow())
    LaunchedEffect(Unit) { if (vm.livePlay == null && vm.previewFraction == null) vm.scrubTo(0f) }
    val playing = vm.livePlay != null && vm.livePlay?.isScrubbing == false
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Simulation") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preview only. Actual takeoff, landing and the ongoing flight card keep their real times.", color = Muted)
            Text("${(progress.fraction * 100).roundToInt()}% · ${FlightPresentation.status(progress)}", color = Amber)
            Slider(value = progress.fraction.toFloat(), onValueChange = { vm.scrubTo(it) },
                modifier = Modifier.semantics { contentDescription = "Simulation journey position" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (playing) vm.pauseLivePlayKeepPosition()
                    else vm.resumeLivePlayFromFraction(progress.fraction.toFloat())
                }) { Text(if (playing) "Pause" else "Play") }
                OutlinedButton(onClick = { vm.setSimSpeed(if (vm.simulationSpeed == 1) 60 else 1) }) { Text("${vm.simulationSpeed}× speed") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("View map") } },
        dismissButton = { TextButton(onClick = { vm.stopLivePlay(); onDismiss() }) { Text("Return to real time") } })
}
