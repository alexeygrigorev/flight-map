package com.flightmap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flightmap.core.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

private fun GeoPoint.mapPoint() = Point.fromLngLat(lon, lat)

@Composable
fun RouteMap(pack: FlightPack, progress: FlightProgress, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val view = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true).compassEnabled(false).logoEnabled(false).attributionEnabled(false)
        .minZoomPreference(1.0).maxZoomPreference(10.0).tiltGesturesEnabled(false)
        .foregroundLoadColor(android.graphics.Color.rgb(11, 28, 43))) }
    DisposableEffect(owner, view) {
        view.onCreate(null)
        view.onStart(); view.onResume()
        view.getMapAsync { m ->
            map = m
            m.setStyle(Style.Builder().fromJson(OfflineMaps.styleJson(context))) { style ->
                val route = GreatCircle.route(pack.plan.origin.point, pack.plan.destination.point)
                // Break lines crossing the date line to avoid a false line across the world.
                val parts = mutableListOf<MutableList<Point>>(mutableListOf())
                route.forEachIndexed { i, p ->
                    if (i > 0 && kotlin.math.abs(p.lon - route[i - 1].lon) > 180) parts.add(mutableListOf())
                    parts.last().add(p.mapPoint())
                }
                style.addSource(GeoJsonSource("route", MultiLineString.fromLngLats(parts.filter { it.size > 1 })))
                style.addLayer(LineLayer("route-shadow", "route").withProperties(lineColor("#8DE0C6"), lineWidth(8f), lineOpacity(0.09f)))
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#8DE0C6"), lineWidth(2f), lineOpacity(0.9f)))
                style.addSource(GeoJsonSource("ends", FeatureCollection.fromFeatures(listOf(
                    Feature.fromGeometry(pack.plan.origin.point.mapPoint()), Feature.fromGeometry(pack.plan.destination.point.mapPoint())))))
                style.addLayer(CircleLayer("ends", "ends").withProperties(circleColor("#8DE0C6"), circleRadius(5f), circleStrokeColor("#0B1420"), circleStrokeWidth(2f)))
                val overview = PlaceLabels.overview(pack.plan, pack.cities)
                val detail = pack.cities.sortedByDescending { it.population }
                    .distinctBy { kotlin.math.floor(it.point.lat * 2) to kotlin.math.floor(it.point.lon * 2) }
                    .filter { city -> overview.none { it.id == city.id } }.take(450)
                val selected = (overview + listOf(
                    City("origin", pack.plan.origin.iata, "", pack.plan.origin.point, "UTC", 0),
                    City("destination", pack.plan.destination.iata, "", pack.plan.destination.point, "UTC", 0)) + detail)
                selected.forEachIndexed { index, city ->
                    val bitmap = labelBitmap(city.name)
                    style.addImage("city-$index", bitmap)
                    style.addSource(GeoJsonSource("city-$index", city.point.mapPoint()))
                    style.addLayer(SymbolLayer("city-$index", "city-$index").apply {
                        minZoom = if (index < overview.size + 2) 0f else 5.5f
                    }.withProperties(iconImage("city-$index"), iconSize(0.6f), iconAnchor(Property.ICON_ANCHOR_TOP), iconOffset(arrayOf(0f, 12f))))
                }
                style.addImage("plane", planeBitmap())
                style.addSource(GeoJsonSource("plane", progress.point.mapPoint()))
                style.addLayer(SymbolLayer("plane", "plane").withProperties(iconImage("plane"), iconSize(0.7f), iconAllowOverlap(true), iconIgnorePlacement(true), iconRotate(progress.heading.toFloat())))
                styleReady = true
                val points = route.map { LatLng(it.lat.coerceIn(-84.0, 84.0), it.lon) }
                if (points.maxOf { it.longitude } - points.minOf { it.longitude } < 180) {
                    view.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 40, 50, 40, 50)) }
                } else m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(progress.point.lat, progress.point.lon), 2.0))
            }
        }
        val observer = LifecycleEventObserver { _, event -> when(event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(styleReady, progress.point) {
        if (styleReady) map?.style?.let { style ->
            style.getSourceAs<GeoJsonSource>("plane")?.setGeoJson(progress.point.mapPoint())
            style.getLayerAs<SymbolLayer>("plane")?.setProperties(iconRotate(progress.heading.toFloat()))
        }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.CenterEnd).padding(8.dp)) {
            FilledTonalButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp).semantics { contentDescription = "Zoom in" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp).semantics { contentDescription = "Zoom out" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(progress.point.lat.coerceIn(-84.0, 84.0), progress.point.lon), 7.0)) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(66.dp)) { Text("Plane") }
            FilledTonalButton(onClick = {
                val points = GreatCircle.route(pack.plan.origin.point, pack.plan.destination.point).map { LatLng(it.lat.coerceIn(-84.0, 84.0), it.lon) }
                if (points.maxOf { it.longitude } - points.minOf { it.longitude } < 180)
                    map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 45))
                else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(progress.point.lat.coerceIn(-84.0, 84.0), progress.point.lon), 2.0))
            }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(66.dp)) { Text("Route") }
        }
    }
}

private fun labelBitmap(text: String): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(207, 221, 226); textSize = 27f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    val bitmap = Bitmap.createBitmap((paint.measureText(text) + 18).toInt(), 48, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply {
        paint.setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
        drawText(text, 9f, 32f, paint)
    }
    return bitmap
}
private fun planeBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888)
    val path = Path().apply {
        moveTo(44f, 4f); lineTo(50f, 12f); lineTo(51f, 34f); lineTo(80f, 55f); lineTo(80f, 63f)
        lineTo(51f, 50f); lineTo(50f, 71f); lineTo(60f, 79f); lineTo(60f, 84f); lineTo(44f, 79f)
        lineTo(28f, 84f); lineTo(28f, 79f); lineTo(38f, 71f); lineTo(37f, 50f); lineTo(8f, 63f)
        lineTo(8f, 55f); lineTo(37f, 34f); lineTo(38f, 12f); close()
    }
    Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(246, 246, 240); setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK) })
    return bitmap
}
