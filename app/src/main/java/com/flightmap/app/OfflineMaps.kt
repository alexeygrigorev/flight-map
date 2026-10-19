package com.flightmap.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonParser
import com.flightmap.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.offline.*
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineBudgetExceededException(message: String) : Exception(message)

/**
 * Bounded higher-detail resumable offline vector map manager.
 * Downloads multi-layer coverage (broad overview z0-6, narrow continuous corridor z7-8,
 * and airport close-ups z9-10) adapted to OpenFreeMap provider limits (max 2,900 tiles / 49 MB per pack).
 *
 * Supports pause/cancellation and resumption using stable native region metadata:
 *   "${plan.number}:${plan.origin.iata}-${plan.destination.iata}:${plan.departure}:${plan.arrival}:v2:${layer.id}"
 * On cancellation or failure, regions are paused rather than deleted, allowing resume.
 */
class OfflineMaps(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager: OfflineManager

    companion object {
        private var networkConfigured = false
        // OfflineManager's downloader handles HTTP resources, not Android asset URIs.
        // Seed our local style into its native resource cache under a stable URL.
        const val STYLE = "https://flight-map.invalid/bundled-style-v1.json"

        fun styleJson(context: Context): String {
            val style = JsonParser.parseString(
                context.assets.open("map-style.json").bufferedReader().use { it.readText() }
            ).asJsonObject
            style.getAsJsonObject("sources").getAsJsonObject("earth").add(
                "data",
                JsonParser.parseString(context.assets.open("data/land.geojson").bufferedReader().use { it.readText() })
            )
            return style.toString()
        }

        fun summary(plan: FlightPlan): OfflinePlanSummary = OfflinePlan.summary(plan)
    }

    init {
        MapLibre.getInstance(appContext)
        if (!networkConfigured) {
            val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
            HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "FlightMapAndroid/0.2 (offline corridor companion)").build())
            }.build())
            networkConfigured = true
        }
        manager = OfflineManager.getInstance(appContext)
        manager.putResourceWithUrl(
                STYLE,
                styleJson(appContext).toByteArray(),
                java.time.Instant.now().epochSecond,
                java.time.Instant.parse("2099-01-01T00:00:00Z").epochSecond,
                null,
                false
        )
    }

    /** Exposes summary API for UI estimate/budget. */
    fun summary(plan: FlightPlan): OfflinePlanSummary = OfflinePlan.summary(plan)

    /**
     * Looks up native region IDs for all existing layers matching this flight plan.
     * Searches for both v2 metadata keys and legacy flight keys to preserve pending and completed
     * native regions on app reopen or restart.
     */
    suspend fun regionsFor(plan: FlightPlan): List<Long> = withContext(Dispatchers.Main.immediate) {
        val offlinePlan = OfflinePlan.create(plan)
        val v2Prefix = "${offlinePlan.flightKey}:"
        val legacyPrefix = "${plan.number}:${plan.departure}"
        val existing = listRegions()
        existing.filter { region ->
            val tag = region.metadata?.decodeToString() ?: ""
            tag.startsWith(v2Prefix) || tag == legacyPrefix || tag.startsWith("$legacyPrefix:")
        }.map { it.id }
    }

    /** Report the actual saved levels and resource sizes, including a budget-reduced result. */
    suspend fun detailSummary(ids: List<Long>): String = withContext(Dispatchers.Main.immediate) {
        val regions = listRegions().filter { it.id in ids }
        var bytes = 0L
        var tiles = 0L
        val levels = regions.map { region ->
            getRegionStatus(region)?.let { bytes += it.completedResourceSize; tiles += it.completedTileCount }
            val definition = region.definition
            "z${definition.minZoom.toInt()}–${definition.maxZoom.toInt()}"
        }
        val reduced = if (regions.size < 3) "Reduced detail · " else "Saved · "
        reduced + levels.joinToString(" / ") + " · $tiles tiles · %.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
    }

    /**
     * Downloads or resumes offline map regions for the given flight plan.
     * Enforces the 2,900 tile and 49 MB per-pack download limit. If a higher-detail layer
     * exceeds the byte budget at runtime, completed lower tiers are retained with an explicit
     * reduced-detail summary, without initiating another download beyond the budget.
     *
     * Returns the list of native offline region IDs.
     */
    suspend fun download(plan: FlightPlan, progress: (String) -> Unit): List<Long> = withTimeout(300_000) {
        withContext(Dispatchers.Main.immediate) {
            // Global device-wide SQLite cache limit: 100,000 tiles so multiple packs do not conflict.
            manager.setOfflineMapboxTileCountLimit(100_000L)

            val fullPlan = OfflinePlan.create(plan)
            downloadLayers(fullPlan.layers, fullPlan.flightKey, progress)
        }
    }

    private suspend fun downloadLayers(
        layers: List<OfflineLayerPlan>,
        flightKey: String,
        progress: (String) -> Unit
    ): List<Long> {
        val existingRegions = listRegions()
        val regionIds = mutableListOf<Long>()

        var cumulativeBytes = 0L
        var cumulativeTiles = 0L

        for (layer in layers) {
            val metaTag = "$flightKey:${layer.id}"
            val matchingRegion = existingRegions.firstOrNull { it.metadata?.decodeToString() == metaTag }

            val initialStatus = matchingRegion?.let { getRegionStatus(it) }
            val isAlreadyComplete = initialStatus?.isComplete == true

            val regionId: Long
            if (isAlreadyComplete) {
                cumulativeBytes += initialStatus!!.completedResourceSize
                cumulativeTiles += initialStatus.completedTileCount
                progress("${layer.id.replaceFirstChar { it.uppercase() }} · already saved")
                regionId = matchingRegion!!.id
            } else {
                val regionToDownload = matchingRegion ?: run {
                    val definition = createLayerDefinition(layer)
                    createRegion(definition, metaTag.toByteArray(Charsets.UTF_8))
                }
                try {
                    regionId = downloadOrResumeRegion(regionToDownload, cumulativeBytes, cumulativeTiles, progress)
                    val completedStatus = getRegionStatus(regionToDownload)
                    if (completedStatus != null) {
                        cumulativeBytes += completedStatus.completedResourceSize
                        cumulativeTiles += completedStatus.completedTileCount
                    }
                } catch (e: OfflineBudgetExceededException) {
                    // Discard the overflowing layer so it doesn't leave an unresumable partial
                    discard(listOf(regionToDownload.id))
                    if (regionIds.isNotEmpty()) {
                        // We already have lower layers (e.g. broad overview) saved and verified!
                    progress("Reduced detail · ${regionIds.size} layers saved within budget")
                        return regionIds
                    }
                    throw e
                }
            }

            regionIds.add(regionId)
        }

        return regionIds
    }

    private fun createLayerDefinition(layer: OfflineLayerPlan): OfflineGeometryRegionDefinition {
        val polygons = layer.boxes.map { box ->
            listOf(
                listOf(
                    Point.fromLngLat(box.west, box.south),
                    Point.fromLngLat(box.east, box.south),
                    Point.fromLngLat(box.east, box.north),
                    Point.fromLngLat(box.west, box.north),
                    Point.fromLngLat(box.west, box.south)
                )
            )
        }
        return OfflineGeometryRegionDefinition(
            STYLE,
            MultiPolygon.fromLngLats(polygons),
            layer.minZoom,
            layer.maxZoom,
            1f
        )
    }

    private suspend fun downloadOrResumeRegion(
        region: OfflineRegion,
        baseBytes: Long,
        baseTiles: Long,
        progress: (String) -> Unit
    ): Long = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            mainHandler.post {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                // IMPORTANT: Never delete resumable regions on cancellation
            }
        }

        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val totalBytes = baseBytes + status.completedResourceSize
                val totalTiles = baseTiles + status.completedTileCount
                val mb = totalBytes / 1024 / 1024
                progress("${status.completedResourceCount} / ${status.requiredResourceCount} resources · $mb MB")

                if (totalBytes > 49_000_000L || totalTiles > 2900L) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            OfflineBudgetExceededException("Map exceeds the 49 MB / 2900 tile download limit.")
                        )
                    }
                } else if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    if (continuation.isActive) {
                        continuation.resume(region.id)
                    }
                }
            }

            override fun onError(error: OfflineRegionError) {
                // Pause on error without deleting so download can resume later
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Map download: ${error.message}. Retry on Wi-Fi."))
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                region.setObserver(null)
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Offline tile limit reached"))
                }
            }
        })

        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private suspend fun createRegion(definition: OfflineRegionDefinition, metadata: ByteArray): OfflineRegion =
        suspendCancellableCoroutine { continuation ->
            manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (continuation.isActive) continuation.resume(offlineRegion)
                }
            })
        }

    private suspend fun listRegions(): Array<OfflineRegion> = suspendCancellableCoroutine { continuation ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onError(error: String) {
                if (continuation.isActive) continuation.resume(emptyArray())
            }
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (continuation.isActive) continuation.resume(offlineRegions ?: emptyArray())
            }
        })
    }

    private suspend fun getRegionStatus(region: OfflineRegion): OfflineRegionStatus? =
        suspendCancellableCoroutine { continuation ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onError(error: String?) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (continuation.isActive) continuation.resume(status)
                }
            })
        }

    suspend fun verify(ids: List<Long>): Boolean = withContext(Dispatchers.Main.immediate) {
        if (ids.isEmpty()) return@withContext false
        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { continuation ->
                manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onError(error: String) { if (continuation.isActive) continuation.resume(false) }
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val regions = offlineRegions.orEmpty().filter { it.id in ids }
                        if (regions.size != ids.size) { if (continuation.isActive) continuation.resume(false); return }
                        var pending = regions.size
                        var complete = true
                        regions.forEach { region ->
                            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                                override fun onError(error: String?) { complete = false; finish() }
                                override fun onStatus(status: OfflineRegionStatus?) {
                                    complete = complete && status?.isComplete == true
                                    finish()
                                }
                                fun finish() {
                                    pending--
                                    if (pending == 0 && continuation.isActive) continuation.resume(complete)
                                }
                            })
                        }
                    }
                })
            }
        } ?: false
    }

    fun discard(ids: List<Long>) {
        if (ids.isEmpty()) return
        mainHandler.post {
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
    }

    fun discardUnreferenced(keep: List<Long>) {
        mainHandler.post {
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onError(error: String) = Unit
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    discard(offlineRegions.orEmpty().map { it.id }.filter { it !in keep })
                }
            })
        }
    }
}
