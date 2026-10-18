package com.flightmap.app

import android.content.Context
import com.google.gson.JsonParser
import com.flightmap.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.maplibre.android.offline.*
import org.maplibre.geojson.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos

/** Coarse vector corridor, zoom 0–5; avoids an enormous rectangular continent download. */
class OfflineMaps(context: Context) {
    private val manager = OfflineManager.getInstance(context)
    companion object {
        // OfflineManager's downloader handles HTTP resources, not Android asset URIs.
        // Seed our local style into its native resource cache under a stable URL.
        const val STYLE = "https://flight-map.invalid/bundled-style-v1.json"
        fun styleJson(context: Context): String {
            val style = JsonParser.parseString(context.assets.open("map-style.json").bufferedReader().use { it.readText() }).asJsonObject
            style.getAsJsonObject("sources").getAsJsonObject("earth").add("data",
                JsonParser.parseString(context.assets.open("data/land.geojson").bufferedReader().use { it.readText() }))
            return style.toString()
        }
    }
    init {
        manager.putResourceWithUrl(STYLE, styleJson(context).toByteArray(), java.time.Instant.now().epochSecond,
            java.time.Instant.parse("2099-01-01T00:00:00Z").epochSecond, null, false)
    }

    suspend fun download(plan: FlightPlan, progress: (String) -> Unit): List<Long> = withTimeout(300_000) {
        // Small overlapping boxes are split at the date line. 3° latitude provides >180km margin.
        val boxes = GreatCircle.route(plan.origin.point, plan.destination.point, 80).flatMap { p ->
            val west = p.lon - (3.0 / cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)).coerceAtMost(45.0)
            val east = p.lon + (3.0 / cos(Math.toRadians(p.lat)).coerceAtLeast(0.1)).coerceAtMost(45.0)
            val centerLat = p.lat.coerceIn(-82.0, 82.0)
            val south = centerLat - 3
            val north = centerLat + 3
            fun box(w: Double, e: Double) = listOf(listOf(Point.fromLngLat(w, south), Point.fromLngLat(e, south), Point.fromLngLat(e, north), Point.fromLngLat(w, north), Point.fromLngLat(w, south)))
            when {
                west < -180 -> listOf(box(west + 360, 180.0), box(-180.0, east))
                east > 180 -> listOf(box(west, 180.0), box(-180.0, east - 360))
                else -> listOf(box(west, east))
            }
        }
        val definition = OfflineGeometryRegionDefinition(STYLE, MultiPolygon.fromLngLats(boxes), 0.0, 5.0, 1f)
        suspendCancellableCoroutine { continuation ->
            var region: OfflineRegion? = null
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { region?.let { discard(listOf(it.id)) } } }
            manager.setOfflineMapboxTileCountLimit(3000)
            manager.createOfflineRegion(definition, "${plan.number}:${plan.departure}".toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onError(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error)) }
                override fun onCreate(offlineRegion: OfflineRegion) {
                    region = offlineRegion
                    if (!continuation.isActive) { discard(listOf(offlineRegion.id)); return }
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            progress("${status.completedResourceCount} / ${status.requiredResourceCount} resources · ${status.completedResourceSize / 1024 / 1024} MB")
                            if (status.completedResourceSize > 49_000_000 || status.completedTileCount > 2900) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                discard(listOf(offlineRegion.id))
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Map exceeds the 50 MB download limit. Base map is still available."))
                            } else if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                if (continuation.isActive) continuation.resume(listOf(offlineRegion.id))
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            discard(listOf(offlineRegion.id))
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Map download: ${error.message}. Retry on Wi-Fi."))
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            discard(listOf(offlineRegion.id))
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Offline tile limit reached"))
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
            })
        }
    }
    suspend fun verify(ids: List<Long>): Boolean = suspendCancellableCoroutine { continuation ->
        if (ids.isEmpty()) { continuation.resume(false); return@suspendCancellableCoroutine }
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onError(error: String) { if (continuation.isActive) continuation.resume(false) }
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty().filter { it.id in ids }
                if (regions.size != ids.size) { if (continuation.isActive) continuation.resume(false); return }
                var pending = regions.size
                var complete = true
                regions.forEach { region -> region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onError(error: String?) { complete = false; finish() }
                    override fun onStatus(status: OfflineRegionStatus?) { complete = complete && status?.isComplete == true; finish() }
                    fun finish() { pending--; if (pending == 0 && continuation.isActive) continuation.resume(complete) }
                }) }
            }
        })
    }
    fun discard(ids: List<Long>) {
        if (ids.isEmpty()) return
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onError(error: String) = Unit
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().filter { it.id in ids }.forEach { region ->
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = Unit
                        override fun onError(error: String) = Unit
                    })
                }
            }
        })
    }
    fun discardUnreferenced(keep: List<Long>) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onError(error: String) = Unit
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                discard(offlineRegions.orEmpty().map { it.id }.filter { it !in keep })
            }
        })
    }
}
