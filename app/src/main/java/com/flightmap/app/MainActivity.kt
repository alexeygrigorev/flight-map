package com.flightmap.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flightmap.core.*
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val Ink = Color(0xFF0B1420)
val Panel = Color(0xFF142330)
val Mint = Color(0xFF8DE0C6)
val Muted = Color(0xFFA6B9C8)
val Cream = Color(0xFFF2F3EE)
val Amber = Color(0xFFFFCE8B)
private val colors = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, onBackground = Cream,
    surface = Panel, onSurface = Cream, onSurfaceVariant = Muted, outline = Color(0xFF344857))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "FlightMapAndroid/0.1 (offline corridor companion)").build())
        }.build())
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
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
private fun Eyebrow(text: String, color: Color = Muted) { Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 2.sp, color = color, fontWeight = FontWeight.Medium) }
@Composable
private fun StatusPill(text: String, ready: Boolean = true) {
    Surface(color = Color.Transparent, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, if (ready) Mint.copy(alpha = .5f) else Amber.copy(alpha = .5f))) {
        Text("●  $text", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp, letterSpacing = 1.sp, color = if (ready) Mint else Amber)
    }
}

@Composable
private fun PrepareScreen(vm: FlightViewModel) {
    var flight by rememberSaveableState("AY87")
    var date by rememberSaveableState("2026-10-18")
    var origin by rememberSaveableState("HEL")
    var destination by rememberSaveableState("PVG")
    var departure by rememberSaveableState("2026-10-18T01:10")
    var arrival by rememberSaveableState("2026-10-18T18:25")
    var lookupInfo by remember { mutableStateOf("AY87 example schedule · confirm your ticket before preparing.") }
    var about by remember { mutableStateOf(false) }
    val saved = vm.pack
    LaunchedEffect(saved?.plan) {
        saved?.plan?.let {
            flight = it.number; origin = it.origin.iata; destination = it.destination.iata
            departure = it.departure.atZone(ZoneId.of(it.origin.timezone)).toLocalDateTime().toString()
            arrival = it.arrival.atZone(ZoneId.of(it.destination.timezone)).toLocalDateTime().toString()
            date = departure.take(10)
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Flight Map", Mint)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { about = true }) { Icon(Icons.Outlined.Info, "About & data sources", tint = Muted) }
        }
        Text("Prepare for flight", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        Text("A window seat, wherever you sit.", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(flight, { flight = it.uppercase() }, label = { Text("Flight number") }, singleLine = true, enabled = !vm.busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(date, { date = it }, label = { Text("Departure date") }, singleLine = true, enabled = !vm.busy, modifier = Modifier.weight(1.2f), shape = RoundedCornerShape(14.dp))
        }
        TextButton(onClick = {
            runCatching { LocalDate.parse(date) }.onSuccess {
                if (flight.replace(" ", "").uppercase() == "AY87") {
                    origin = "HEL"; destination = "PVG"; departure = "${date}T01:10"; arrival = "${date}T18:25"
                    lookupInfo = "AY87 schedule fallback loaded. Times are an example, not a date-verified booking. Confirm against your ticket."
                } else lookupInfo = "No bundled schedule for $flight. Enter the airports and ticket times below. No API key required."
            }.onFailure { lookupInfo = "Use a date in YYYY-MM-DD format." }
        }, enabled = !vm.busy) { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Look up saved schedule") }
        Text(lookupInfo, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AirportInput("From", origin, { origin = it }, vm, Modifier.weight(1f))
            AirportInput("To", destination, { destination = it }, vm, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(departure, { departure = it }, label = { Text("Departure · airport local time") }, supportingText = { Text("YYYY-MM-DDTHH:MM · ${vm.repository.airports.firstOrNull { it.iata == origin }?.timezone ?: "airport time zone"}") }, singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(arrival, { arrival = it }, label = { Text("Arrival · airport local time") }, supportingText = { Text("Include arrival date · ${vm.repository.airports.firstOrNull { it.iata == destination }?.timezone ?: "airport time zone"}") }, singleLine = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(22.dp))
        Eyebrow(if (saved != null && !vm.busy) "Saved flight · ${saved.plan.number} ${saved.plan.origin.iata} → ${saved.plan.destination.iata}" else "Download for offline use")
        Spacer(Modifier.height(12.dp))
        DownloadRow("Route and schedule", "Great-circle path · estimated position", vm.routeReady, vm.busy && !vm.routeReady)
        DownloadRow("Cities and time zones", saved?.let { "${it.cities.size} cities within 180 km of the route" } ?: "Bundled GeoNames city database", vm.citiesReady, vm.busy && vm.routeReady && !vm.citiesReady)
        DownloadRow("Hourly weather", vm.weatherDetail.ifBlank { "Open-Meteo · download before departure" }, vm.weatherReady, vm.busy && vm.citiesReady && !vm.weatherReady && vm.stage.contains("forecast"))
        DownloadRow("Offline vector map", vm.mapDetail.ifBlank { "Route corridor · zoom 0–5 · up to 50 MB" }, vm.mapReady, vm.busy && vm.stage.contains("vector"))
        if (vm.stage.isNotBlank()) Text(vm.stage, Modifier.padding(top = 12.dp), color = if(vm.mapReady && vm.weatherReady) Mint else Muted, fontSize = 13.sp)
        vm.error?.let { Text(it, color = Amber, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.prepare(flight, origin, destination, departure, arrival) }, enabled = vm.loaded && !vm.busy, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Outlined.Download, null, Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text(if(vm.busy) "Preparing your flight…" else "Prepare for flight", fontSize = 16.sp)
        }
        if (vm.busy) TextButton(onClick = { vm.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel download") }
        if (saved != null && !vm.busy) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.fly() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.FlightTakeoff, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(if (vm.mapReady && vm.weatherReady) "Open offline flight" else "Open saved flight · limited data")
            }
            Text("Saved ${saved.preparedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))} · works after closing the app", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Text("Position follows scheduled block time along a great circle. Actual flight paths, delays and winds can differ substantially.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 18.dp, bottom = 28.dp))
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Made for the window seat") }, text = { Text("Flight Map estimates a journey from your ticket times. It does not track an aircraft or request GPS.\n\nData: OurAirports (public domain), GeoNames (CC BY 4.0), Natural Earth (public domain), Open-Meteo (CC BY 4.0). Vector tiles: © OpenStreetMap contributors · OpenFreeMap · OpenMapTiles.\n\nPreparation sends corridor city coordinates to Open-Meteo and map tile requests to OpenFreeMap. In-flight mode disables map networking and reads saved forecasts only. No account or API keys.", fontSize = 13.sp) }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
}

@Composable
private fun rememberSaveableState(initial: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun AirportInput(label: String, value: String, change: (String) -> Unit, vm: FlightViewModel, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(value, { change(it.uppercase()) }, label = { Text(label) }, singleLine = true, enabled = !vm.busy,
            textStyle = LocalTextStyle.current.copy(fontSize = 25.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        val selected = vm.repository.airports.firstOrNull { it.iata == value }
        if (selected != null) Text(selected.name, color = Muted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        else if (value.isNotBlank()) {
            vm.repository.airports.filter { it.iata.startsWith(value) || it.name.contains(value, ignoreCase = true) }.take(3).forEach { airport ->
                Text("${airport.iata} · ${airport.name}", color = Mint, fontSize = 11.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { change(airport.iata) }.padding(vertical = 8.dp))
            }
        }
    }
}
@Composable
private fun DownloadRow(title: String, detail: String, done: Boolean, active: Boolean) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) CircularProgressIndicator(Modifier.size(26.dp), color = Mint, strokeWidth = 2.dp)
            else Icon(if(done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if(done) Mint else Muted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlightScreen(vm: FlightViewModel) {
    val pack = vm.pack ?: return
    val plan = pack.plan
    val time = vm.effectiveNow()
    val progress = FlightTiming.progress(plan, time)
    val nearest = pack.cities.minByOrNull { GreatCircle.distanceKm(it.point, progress.point) }
    val nearestDistance = nearest?.let { GreatCircle.distanceKm(it.point, progress.point) }
    val weather = nearest?.let { WeatherParser.nearest(pack.weather[it.id].orEmpty(), time) }
    var citiesOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    BackHandler { vm.edit() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Flight Map")
            Spacer(Modifier.weight(1f))
            StatusPill(if(vm.previewFraction != null) "PREVIEW" else "ESTIMATED", vm.previewFraction == null)
            IconButton(onClick = { vm.edit() }) { Icon(Icons.Outlined.Tune, "Edit flight", tint = Muted, modifier = Modifier.size(21.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(plan.origin.iata, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp); Text(plan.origin.name.substringBefore(" International").substringBefore(" Airport").take(20), color = Muted, fontSize = 12.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.East, null, tint = Mint, modifier = Modifier.size(30.dp)); Text(plan.number, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Column(horizontalAlignment = Alignment.End) { Text(plan.destination.iata, fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp); Text(plan.destination.name.substringBefore(" International").substringBefore(" Airport").take(20), color = Muted, fontSize = 12.sp) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RouteMap(pack, progress, Modifier.fillMaxSize())
            Surface(color = Ink.copy(alpha = .8f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 12.dp)) {
                Text(if (progress.fraction >= 1.0) "Scheduled arrival reached" else if(time < plan.departure) "Before scheduled departure" else "Great-circle estimate", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            Text("© OpenStreetMap · OpenFreeMap · Natural Earth", color = Muted, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 5.dp))
        }
        Surface(color = Panel, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 18.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("%,d km".format(progress.remainingKm.roundToInt()), fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp); Text("remaining", color = Muted, fontSize = 12.sp) }
                    Column(horizontalAlignment = Alignment.End) { Text("%02dh %02dm".format(progress.remaining.toHours(), progress.remaining.toMinutes() % 60), fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp); Text("to scheduled arrival", color = Muted, fontSize = 12.sp) }
                }
                LinearProgressIndicator(progress = { progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(3.dp), color = Mint, trackColor = Color(0xFF3A4A57), drawStopIndicator = {})
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${progress.flownKm.roundToInt()} km flown", color = Muted, fontSize = 10.sp)
                    Text("${progress.heading.roundToInt()}° heading", color = Muted, fontSize = 10.sp)
                    Text("ETA ${plan.arrival.atZone(ZoneId.of(plan.destination.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))}", color = Muted, fontSize = 10.sp)
                }
                HorizontalDivider(color = Color(0xFF30424F))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(nearestDistance != null && nearestDistance <= 80) "Passing near · ${nearestDistance.roundToInt()} km" else "Nearest corridor city${nearestDistance?.let { " · ${it.roundToInt()} km" } ?: ""}", color = Muted, fontSize = 11.sp)
                        Text(nearest?.name ?: "Open skies", fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(nearest?.let { "LOCAL TIME  ${time.atZone(ZoneId.of(it.timezone)).format(DateTimeFormatter.ofPattern("HH:mm"))}" } ?: "No cities in this corridor", color = Muted, letterSpacing = 1.sp, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(weather?.let { "${it.temperature.roundToInt()}°" } ?: "—", fontSize = 30.sp, color = Cream)
                        Text(weather?.let { weatherText(it.code) } ?: "No saved forecast", color = Muted, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(if(pack.mapReady && pack.cities.all { !pack.weather[it.id].isNullOrEmpty() } && pack.cities.isNotEmpty()) "OFFLINE READY" else "PARTIAL OFFLINE", pack.mapReady && pack.weather.isNotEmpty())
                    TextButton(onClick = { citiesOpen = true }) { Text("Along the route", fontSize = 12.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { previewOpen = !previewOpen; if(!previewOpen) vm.previewFraction = null }) { Text(if(previewOpen) "Close preview" else "Preview journey", fontSize = 11.sp, color = Muted) }
                    Spacer(Modifier.weight(1f))
                    if(vm.previewFraction != null) TextButton(onClick = { vm.previewFraction = null }) { Text("Use current time", fontSize = 11.sp) }
                }
                if (previewOpen) {
                    Text("Preview · simulated time ${time.atZone(ZoneId.of(plan.origin.timezone)).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))}", color = Amber, fontSize = 11.sp)
                    Slider(value = vm.previewFraction ?: progress.fraction.toFloat(), onValueChange = { vm.previewFraction = it }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (citiesOpen) ModalBottomSheet(onDismissRequest = { citiesOpen = false }, containerColor = Ink) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Along the route", fontSize = 27.sp, fontWeight = FontWeight.Medium)
                Text("${pack.cities.size} cities · within 180 km of the great circle\nTimes and forecasts for ${if(vm.previewFraction != null) "preview time" else "current time"}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            items(pack.cities, key = { it.id }) { city ->
                val forecast = WeatherParser.nearest(pack.weather[city.id].orEmpty(), time)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(city.name, fontSize = 16.sp); Text("${city.country} · ${city.timezone}", color = Muted, fontSize = 11.sp) }
                    Column(horizontalAlignment = Alignment.End) { Text(time.atZone(ZoneId.of(city.timezone)).format(DateTimeFormatter.ofPattern("HH:mm")), color = Mint); Text(forecast?.let { "${it.temperature.roundToInt()}° · ${weatherText(it.code)}" } ?: "Forecast unavailable", color = Muted, fontSize = 11.sp) }
                }
                HorizontalDivider(color = Panel)
            }
        }
    }
}
private fun weatherText(code: Int) = when(code) { 0 -> "Clear"; 1,2 -> "Partly cloudy"; 3 -> "Overcast"; 45,48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"; in 80..82 -> "Showers"; 85,86 -> "Snow showers"; in 95..99 -> "Thunderstorm"; else -> "Forecast" }
