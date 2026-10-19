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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = colors) {
                val vm: FlightViewModel = viewModel()
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    if (vm.screen == "flight" && vm.pack != null) FlightScreen(vm) else PrepareScreen(vm)
                }
            }
        }
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

@Composable
private fun PrepareScreen(vm: FlightViewModel) {
    val exampleDate = remember { LocalDate.now(ZoneId.of("Europe/Helsinki")).plusDays(1).toString() }
    var flight by rememberSaveableState("AY87")
    var date by rememberSaveableState(exampleDate)
    var origin by rememberSaveableState("HEL")
    var destination by rememberSaveableState("PVG")
    var departure by rememberSaveableState("${exampleDate}T16:40")
    var arrival by rememberSaveableState("${LocalDate.parse(exampleDate).plusDays(1)}T07:55")
    var lookupInfo by remember {
        mutableStateOf("AY87 filed Southern Eurasian trajectory pre-configured · HEL → PVG (36 waypoints).")
    }
    var about by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FlightPack?>(null) }
    val saved = vm.pack

    fun fillFrom(plan: FlightPlan) {
        flight = plan.number
        origin = plan.origin.iata
        destination = plan.destination.iata
        departure = plan.departure.atZone(ZoneId.of(plan.origin.timezone)).toLocalDateTime().toString()
        arrival = plan.arrival.atZone(ZoneId.of(plan.destination.timezone)).toLocalDateTime().toString()
        date = departure.take(10)
    }

    fun applyScheduleLookup(code: String, dateStr: String): Boolean {
        val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return false
        val sched = TrajectoryCatalog.lookupSchedule(code, parsedDate) ?: return false
        origin = sched.originIata
        destination = sched.destinationIata
        departure = sched.departureLocalIso
        arrival = sched.arrivalLocalIso
        lookupInfo = "${sched.flightNumber} · ${sched.routeLabel} (${sched.waypointCount} waypoints) loaded for offline preload."
        return true
    }

    val resolvedPreview = remember(flight, origin, destination, vm.loaded) {
        val from = vm.repository.airports.firstOrNull { it.iata == origin }
        val to = vm.repository.airports.firstOrNull { it.iata == destination }
        if (from != null && to != null && from.iata != to.iata) {
            TrajectoryCatalog.enrichPlan(FlightPlan(flight, from, to, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))
        } else null
    }

    val mapEstimate = remember(resolvedPreview) {
        resolvedPreview?.let { runCatching { OfflinePlan.summary(it) }.getOrNull() }
    }

    LaunchedEffect(saved?.plan) {
        saved?.plan?.let { fillFrom(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Eyebrow("Flight Map · In-Flight Entertainment", Mint)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { about = true }) {
                Icon(Icons.Outlined.Info, "About & data sources", tint = Muted)
            }
        }
        Text(
            "Prepare for flight",
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp
        )
        Text(
            "Enter your flight to preload the real trajectory, 3D satellite globe, overflown cities, time zones, day/night & weather offline.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
        )
        if (vm.packs.isNotEmpty()) {
            Eyebrow("SAVED FLIGHTS · ${vm.packs.size}", Mint)
            Spacer(Modifier.height(10.dp))
            vm.packs.forEach { flightPack ->
                val enrichedPlan = TrajectoryCatalog.enrichPlan(flightPack.plan)
                Surface(
                    color = Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
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
                                "${flightPack.plan.origin.iata} → ${flightPack.plan.destination.iata} · ${flightPack.plan.number}",
                                color = if (flightPack == saved) Mint else Cream,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                enrichedPlan.effectiveRouteLabel,
                                color = if (enrichedPlan.isRealTrajectory) Mint else Amber,
                                fontSize = 11.sp
                            )
                            Text(
                                "${flightPack.cities.size} cities · " +
                                    flightPack.plan.departure.atZone(ZoneId.of(flightPack.plan.origin.timezone))
                                        .format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")),
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = {
                                vm.selectPack(flightPack)
                                vm.fly()
                            },
                            enabled = !vm.busy
                        ) {
                            Text("Open")
                        }
                        IconButton(onClick = { deleting = flightPack }, enabled = !vm.busy) {
                            Icon(Icons.Outlined.Delete, "Delete ${flightPack.plan.number}", tint = Muted)
                        }
                    }
                }
            }
            Text(
                "Tap Open to launch horizontal landscape IFE mode, or enter a flight below.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                flight,
                {
                    val upper = it.uppercase()
                    flight = upper
                    applyScheduleLookup(upper, date)
                },
                label = { Text("Flight number") },
                singleLine = true,
                enabled = !vm.busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                date,
                {
                    date = it
                    applyScheduleLookup(flight, it)
                },
                label = { Text("Departure date") },
                singleLine = true,
                enabled = !vm.busy,
                modifier = Modifier.weight(1.2f),
                shape = RoundedCornerShape(14.dp)
            )
        }
        TextButton(
            onClick = {
                runCatching { LocalDate.parse(date) }.onSuccess {
                    if (!applyScheduleLookup(flight, date)) {
                        lookupInfo = "No bundled schedule for $flight. Airports & corridor resolver will use $origin → $destination."
                    }
                }.onFailure { lookupInfo = "Use a date in YYYY-MM-DD format." }
            },
            enabled = !vm.busy
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Look up saved schedule")
        }
        Text(lookupInfo, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AirportInput("From", origin, { origin = it }, vm, Modifier.weight(1f))
            AirportInput("To", destination, { destination = it }, vm, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            departure,
            { departure = it },
            label = { Text("Departure · airport local time") },
            supportingText = {
                Text("YYYY-MM-DDTHH:MM · ${vm.repository.airports.firstOrNull { it.iata == origin }?.timezone ?: "airport time zone"}")
            },
            singleLine = true,
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            arrival,
            { arrival = it },
            label = { Text("Arrival · airport local time") },
            supportingText = {
                Text("Include arrival date · ${vm.repository.airports.firstOrNull { it.iata == destination }?.timezone ?: "airport time zone"}")
            },
            singleLine = true,
            enabled = !vm.busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(20.dp))
        Eyebrow(
            vm.preparationPlan?.takeIf { vm.busy || vm.pendingPreparation }?.let {
                "Preparing · ${it.number} ${it.origin.iata} → ${it.destination.iata}"
            } ?: saved?.let {
                "Saved flight · ${it.plan.number} ${it.plan.origin.iata} → ${it.plan.destination.iata}"
            } ?: "Offline Preload Pack"
        )
        Spacer(Modifier.height(12.dp))
        val routeSummaryText = resolvedPreview?.let { res ->
            val wps = res.effectiveWaypoints
            val km = GreatCircle.routeDistanceKm(wps).roundToInt()
            "${res.effectiveRouteLabel} · ${wps.size} waypoints · %,d km".format(km)
        } ?: "Filed ATS corridor or explicitly labeled great-circle estimate"
        DownloadRow(
            "Trajectory & 3D satellite globe",
            routeSummaryText,
            vm.routeReady,
            vm.busy && !vm.routeReady
        )
        DownloadRow(
            "Overflown cities, facts & time zones",
            saved?.let { "${it.cities.size} corridor cities + offline cultural/geographic notes & time zones" }
                ?: "69,822 GeoNames cities · curated landmarks · mountain peaks · route time zones",
            vm.citiesReady,
            vm.busy && vm.routeReady && !vm.citiesReady
        )
        DownloadRow(
            "Hourly route weather",
            vm.weatherDetail.ifBlank { "Open-Meteo corridor forecast · cached offline for in-flight playback" },
            vm.weatherReady,
            vm.busy && vm.citiesReady && !vm.weatherReady && vm.stage.contains("forecast")
        )
        DownloadRow(
            "Offline vector & relief data",
            vm.mapDetail.ifBlank { "Bundled NASA Blue Marble 3D globe + Natural Earth borders + corridor tiles" },
            vm.mapReady,
            vm.busy && vm.stage.contains("vector")
        )
        mapEstimate?.let { estimate ->
            Text(
                "Offline pack coverage: ~${estimate.estimatedTiles} corridor tiles + bundled 3D Earth relief & country boundaries.",
                color = Mint,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (vm.stage.isNotBlank()) {
            Text(
                vm.stage,
                Modifier.padding(top = 12.dp),
                color = if (vm.mapReady && vm.weatherReady) Mint else Muted,
                fontSize = 13.sp
            )
        }
        vm.error?.let {
            Text(it, color = Amber, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                vm.prepare(flight, origin, destination, departure, arrival)
            },
            enabled = vm.loaded && !vm.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Outlined.Download, null, Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (vm.busy) "Preparing your flight…" else "Prepare for flight", fontSize = 16.sp)
        }
        if (vm.busy) {
            TextButton(onClick = { vm.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Pause download")
            }
        }
        if (vm.pendingPreparation && !vm.busy) {
            OutlinedButton(
                onClick = { vm.resume() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text("Resume preparation")
            }
        }
        if (saved != null && !vm.busy) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.fly() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Outlined.FlightTakeoff, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (saved.mapReady && saved.forecastsReady) "Open offline flight" else "Open saved flight · offline ready")
            }
            Text(
                "Saved ${saved.preparedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))} · opens in landscape airline IFE mode",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Text(
            "Filed trajectories reflect real operational corridors (e.g. AY87 Southern Eurasian Corridor Avoiding Russian Airspace). Unverified routes are explicitly labeled as Great-Circle Estimates.",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 28.dp)
        )
    }

    deleting?.let { flightPack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete saved flight?") },
            text = {
                Text("${flightPack.plan.number} · ${flightPack.plan.origin.iata} → ${flightPack.plan.destination.iata}\nIts offline pack and forecasts will be removed from this device.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePack(flightPack)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep flight") }
            }
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Made for the window seat") },
            text = {
                Text(
                    "Flight Map renders an airline IFE 3D satellite globe with filed ATS route corridors, overflown cities, mountain peaks, solar day/night terminator, route time zones, and offline weather.\n\nData: NASA Visible Earth Blue Marble Next Generation (public domain), Natural Earth (public domain), OurAirports (public domain), GeoNames (CC BY 4.0), Open-Meteo (CC BY 4.0).",
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
            textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )
        val selected = vm.repository.airports.firstOrNull { it.iata == value }
        if (selected != null) {
            Text(selected.name, color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        } else if (value.isNotBlank()) {
            vm.repository.airports
                .filter { it.iata.startsWith(value) || it.name.contains(value, ignoreCase = true) }
                .take(3)
                .forEach { airport ->
                    Text(
                        "${airport.iata} · ${airport.name}",
                        color = Mint,
                        fontSize = 11.sp,
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { change(airport.iata) }
                            .padding(vertical = 8.dp)
                    )
                }
        }
    }
}

@Composable
private fun DownloadRow(title: String, detail: String, done: Boolean, active: Boolean) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Mint, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null,
                    tint = if (done) Mint else Muted,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
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

    // AC #1: Primary landscape (horizontal) in-flight mode like airline IFE
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }

    val time = vm.effectiveNow()
    val progress = FlightTiming.progress(plan, time)
    val nearest = remember(pack.cities, progress.point) {
        pack.cities
            .filter { it.population >= 45_000 && GreatCircle.distanceKm(it.point, progress.point) <= 165.0 }
            .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
            ?: pack.cities.minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
    }
    val nearestDistance = nearest?.let { GreatCircle.distanceKm(it.point, progress.point) }
    val weatherLocation = nearest?.takeIf { it.id in pack.weather } ?: pack.cities
        .filter { it.id in pack.weather }
        .minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
    val weather = weatherLocation?.let { WeatherParser.nearest(pack.weather[it.id].orEmpty(), time) }
        ?: SolarCalculator.estimateSurfaceWeather(progress.point, time)
    val solarLabel = remember(progress.point, time.epochSecond / 60) {
        SolarCalculator.dayNightLabel(progress.point, time)
    }
    val solarIsDay = remember(progress.point, time.epochSecond / 60) {
        SolarCalculator.isDaylight(progress.point, time)
    }
    val timeZones = remember(plan, pack.cities, progress.point, time.epochSecond / 60) {
        SolarCalculator.routeTimeZones(plan, pack.cities, progress.point, time)
    }
    val tailWindText = remember(progress.point, progress.heading) {
        SolarCalculator.estimateCruiseWindLabel(progress.point, progress.heading)
    }

    var citiesOpen by remember { mutableStateOf(false) }
    var citySearch by remember { mutableStateOf("") }
    var previewOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            kotlinx.coroutines.delay(120)
            val current = vm.previewFraction ?: progress.fraction.toFloat()
            if (current >= 1.0f) {
                isPlaying = false
            } else {
                vm.previewFraction = (current + 0.004f).coerceAtMost(1.0f)
            }
        }
    }

    BackHandler { vm.edit() }

    val originCityName = remember(plan.origin) { IFE_AIRPORT_CITY_NAMES[plan.origin.iata] ?: plan.origin.name.substringBefore(" ") }
    val destCityName = remember(plan.destination) { IFE_AIRPORT_CITY_NAMES[plan.destination.iata] ?: plan.destination.name.substringBefore(" ") }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // AC #2: Full-bleed 3D Satellite Earth IFE Globe with curved horizon, peaks, cities, countries, landmarks, solar terminator
        IfeGlobeView(
            pack = pack,
            progress = progress,
            countries = vm.repository.countries,
            allCities = vm.repository.allCities,
            onOpenCitiesSheet = { citiesOpen = true },
            modifier = Modifier.fillMaxSize()
        )

        // Top Airline IFE Header Bar (matching flight-map-ref-ife.jpg without overlapping elements)
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xEE040812),
                            Color(0xAA060B18),
                            Color.Transparent
                        )
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Circular indigo back button + flight code badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = { vm.edit() },
                    color = IfeIndigo.copy(alpha = 0.92f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.ArrowBack,
                            contentDescription = "Edit flight",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "${plan.origin.iata} → ${plan.destination.iata}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            plan.number,
                            color = Mint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        if (plan.isRealTrajectory) "FILED ATS CORRIDOR" else "GREAT-CIRCLE ESTIMATE",
                        color = if (plan.isRealTrajectory) Mint else Amber,
                        fontSize = 8.sp,
                        letterSpacing = 0.7.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Center: Helsinki ——✈————○ Shanghai + Tail Wind & Telemetry subtitle (exact match to ref photo)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        originCityName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // IFE Flight Progress Track with Plane Icon & Destination Ring
                    IfeHeaderProgressBar(
                        fraction = progress.fraction.toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                    )
                    Text(
                        destCityName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "$tailWindText  ·  %,d km remaining  ·  %02dh %02dm left  ·  ETA %s".format(
                        progress.remainingKm.roundToInt(),
                        progress.remaining.toHours(),
                        progress.remaining.toMinutes() % 60,
                        plan.arrival.atZone(ZoneId.of(plan.destination.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))
                    ),
                    color = Color(0xFFD6E0EE),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right: Status pill + Play/Preview button (matching ref photo top-right play button)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill("OFFLINE READY", true)
                Surface(
                    onClick = {
                        previewOpen = !previewOpen
                    },
                    color = IfeIndigo.copy(alpha = 0.92f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Preview journey",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // Bottom Translucent Airline IFE Telemetry & Offline Info Strip
        Surface(
            color = Color(0xDD081020),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                // Primary telemetry row: [Where the plane is now + Overflown city & fact] | [Time zones under route] | [Weather & Controls]
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Aircraft Position, Day/Night, Overflown City & Cultural/Geo Fact
                    Column(Modifier.weight(1.3f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val latHem = if (progress.point.lat >= 0) "N" else "S"
                            val lonHem = if (progress.point.lon >= 0) "E" else "W"
                            Text(
                                "NOW %.1f°%s, %.1f°%s".format(abs(progress.point.lat), latHem, abs(progress.point.lon), lonHem),
                                color = Mint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                solarLabel,
                                color = if (solarIsDay) PeakYellow else Color(0xFF9FB8FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${progress.flownKm.roundToInt()} km flown · ${progress.heading.roundToInt()}° hdg",
                                color = Muted,
                                fontSize = 10.sp
                            )
                        }
                        val relativeDir = nearest?.let {
                            GreatCircle.relativeBearingDescription(progress.point, progress.heading, it.point)
                        }.orEmpty()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                nearest?.name ?: "Open skies",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (nearest != null && nearestDistance != null) {
                                val localClock = time.atZone(ZoneId.of(nearest.timezone))
                                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                                Text(
                                    "${nearest.country} · ${nearestDistance.roundToInt()} km (${relativeDir.substringBefore(" ·")}) · Local $localClock",
                                    color = Muted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (nearest != null) {
                            Text(
                                IfeCatalog.cityFact(nearest),
                                color = Color(0xFFD0DCEB),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 2. Time Zones Under the Route + Day/Night per zone
                    Column(Modifier.weight(1.35f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "TIME ZONES UNDER ROUTE · ${plan.effectiveRouteLabel.uppercase()}",
                                color = Muted,
                                fontSize = 9.sp,
                                letterSpacing = 0.7.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(timeZones) { tz ->
                                Surface(
                                    color = if (tz.isCurrent) IfeIndigoBright else Color(0xFF121D33),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (tz.isCurrent) Mint else Color.White.copy(alpha = 0.12f)
                                    )
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            if (tz.isDaylight) "☀" else "☾",
                                            color = if (tz.isDaylight) PeakYellow else Color(0xFF9FB8FF),
                                            fontSize = 10.sp
                                        )
                                        Column {
                                            Text(
                                                "${tz.utcOffsetLabel} · ${tz.localTimeLabel}",
                                                color = if (tz.isCurrent) Mint else Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                tz.representativeCity,
                                                color = Muted,
                                                fontSize = 8.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Weather + Preview & Along the route buttons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${weather.temperature.roundToInt()}°C",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Cream
                            )
                            Text(
                                WeatherParser.conditionText(weather.code),
                                color = Muted,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Surface(
                                onClick = {
                                    previewOpen = !previewOpen
                                    if (!previewOpen) isPlaying = false
                                },
                                color = if (previewOpen) IfeIndigoBright else Color(0xFF162238),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                            ) {
                                Text(
                                    if (previewOpen) "Close preview" else "Preview journey",
                                    color = if (previewOpen) Mint else Cream,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                            Surface(
                                onClick = { citiesOpen = true },
                                color = Color(0xFF162238),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Mint.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Along the route (${pack.cities.size})",
                                        color = Mint,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Icon(Icons.Outlined.ChevronRight, null, tint = Mint, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                // Scrubber bar when Preview journey is open
                if (previewOpen) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Preview · ${
                                time.atZone(ZoneId.of(plan.origin.timezone))
                                    .format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
                            } (${(progress.fraction * 100).roundToInt()}%)",
                            color = Amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                if (vm.previewFraction != null && vm.previewFraction!! >= 1.0f) {
                                    vm.previewFraction = 0f
                                }
                                isPlaying = !isPlaying
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(if (isPlaying) "⏸ Pause" else "▶ Play", fontSize = 11.sp, color = Mint)
                        }
                        if (vm.previewFraction != null) {
                            TextButton(
                                onClick = {
                                    isPlaying = false
                                    vm.previewFraction = null
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Use current time", fontSize = 10.sp, color = Muted)
                            }
                        }
                        // Native Android SeekBar for both smooth scrubbing and UIAutomator compatibility
                        val sliderValue = ((vm.previewFraction ?: progress.fraction.toFloat()) * 1000f)
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
                                                isPlaying = false
                                                vm.previewFraction = (value / 1000f).coerceIn(0f, 1f)
                                            }
                                        }
                                        override fun onStartTrackingTouch(seekBar: SeekBar?) {
                                            isPlaying = false
                                        }
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
                                .height(26.dp)
                        )
                    }
                }
            }
        }
    }

    // AC #4 & #6: Preloaded Offline Cities, Facts, Time Zones & Weather Modal Sheet
    if (citiesOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val orderedSheetCities = remember(pack.cities, plan, citySearch) {
            if (citySearch.isNotBlank()) {
                pack.cities.filter {
                    it.name.contains(citySearch, true) ||
                        it.country.contains(citySearch, true) ||
                        it.timezone.contains(citySearch, true)
                }
            } else {
                val highlights = PlaceLabels.overview(plan, pack.cities, 48)
                val highlightIds = highlights.mapTo(HashSet()) { it.id }
                val major = pack.cities.filter { it.id in highlightIds || it.population >= 120_000 }
                val majorIds = major.mapTo(HashSet()) { it.id }
                major + pack.cities.filter { it.id !in majorIds }
            }
        }
        ModalBottomSheet(
            onDismissRequest = { citiesOpen = false },
            sheetState = sheetState,
            containerColor = Ink
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Along the route", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                "${pack.cities.size} overflown cities · ${plan.effectiveRouteLabel}",
                                color = Mint,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        StatusPill("OFFLINE READY", true)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        citySearch,
                        { citySearch = it },
                        label = { Text("Find an overflown city, country, or time zone") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                items(
                    orderedSheetCities,
                    key = { it.id }
                ) { city ->
                    val forecast = WeatherParser.nearest(pack.weather[city.id].orEmpty(), time)
                        ?: SolarCalculator.estimateSurfaceWeather(city.point, time)
                    val cityDist = GreatCircle.distanceKm(progress.point, city.point).roundToInt()
                    val relativeDir = GreatCircle.relativeBearingDescription(progress.point, progress.heading, city.point)
                    val citySolarLabel = SolarCalculator.dayNightLabel(city.point, time)
                    val cityIsDay = SolarCalculator.isDaylight(city.point, time)
                    val zoned = runCatching { time.atZone(ZoneId.of(city.timezone)) }.getOrNull()
                    val localClock = zoned?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "—"
                    val utcOffset = SolarCalculator.utcOffsetLabel(city.timezone, time)

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(city.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text(
                                        citySolarLabel,
                                        color = if (cityIsDay) PeakYellow else Color(0xFF9FB8FF),
                                        fontSize = 10.sp
                                    )
                                }
                                Text(
                                    "${city.country} · %,d km (${relativeDir.substringBefore(" ·")}) · Pop. %,d".format(
                                        cityDist,
                                        city.population
                                    ),
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "$localClock ($utcOffset)",
                                    color = Mint,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${forecast.temperature.roundToInt()}°C · ${WeatherParser.conditionText(forecast.code)}",
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Text(
                            IfeCatalog.cityFact(city),
                            color = Color(0xFFD2DFEE),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    HorizontalDivider(color = Panel)
                }
            }
        }
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
    "PEK" to "Beijing",
    "PKX" to "Beijing",
    "NRT" to "Tokyo",
    "HND" to "Tokyo",
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
