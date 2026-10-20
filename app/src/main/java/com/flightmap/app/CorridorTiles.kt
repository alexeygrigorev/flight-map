package com.flightmap.app

import android.content.Context
import android.graphics.BitmapFactory
import com.flightmap.core.CorridorImagery
import com.flightmap.core.FlightPlan
import com.flightmap.core.OfflinePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * On-disk raster cache for high-detail corridor imagery (NASA GIBS Blue Marble,
 * public domain - see CorridorImagery). Layout per flight:
 *   corridor-tiles/<sanitizedFlightKey>/<z>/<x>/<y>.jpg
 * plus a manifest.json recording the completed selection.
 *
 * [CorridorTileStore] is pure file logic (JVM unit-testable); [CorridorTiles]
 * wires it to Android (OkHttp fetch with retries, BitmapFactory decode).
 * All network happens only during online preparation - the in-flight globe
 * reads exclusively from these files (verified by [coverage] before display).
 */
data class TileCoverage(val present: Int, val total: Int, val bytes: Long) {
    val isComplete: Boolean get() = total > 0 && present >= total
    val fraction: Float get() = if (total == 0) 1f else present.toFloat() / total.toFloat()
}

class CorridorTileStore(val rootDir: File) {

    companion object {
        const val MANIFEST_NAME = "manifest.json"

        /** Sanitizes an OfflinePlan flightKey into a safe single directory name. */
        fun sanitizeKey(flightKey: String): String =
            flightKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(180)
    }

    fun dirFor(flightKey: String): File = File(rootDir, sanitizeKey(flightKey))

    fun tileFile(flightKey: String, z: Int, x: Int, y: Int): File =
        File(dirFor(flightKey), "${z}/${x}/${y}.jpg")

    fun manifestFile(flightKey: String): File = File(dirFor(flightKey), MANIFEST_NAME)

    fun isValidTile(file: File): Boolean {
        if (!file.isFile || file.length() < 512) return false
        return runCatching {
            file.inputStream().use { stream ->
                val magic = ByteArray(2)
                stream.read(magic) == 2 && magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()
            }
        }.getOrDefault(false)
    }

    /** Counts valid cached tiles for a selection; missing/invalid files count as absent (resume). */
    fun coverage(flightKey: String, selection: CorridorImagery.ImagerySelection): TileCoverage {
        var present = 0
        var bytes = 0L
        for (key in selection.tiles) {
            val (z, x, y) = CorridorImagery.decodeKey(key)
            val file = tileFile(flightKey, z, x, y)
            if (isValidTile(file)) {
                present++
                bytes += file.length()
            }
        }
        return TileCoverage(present, selection.tileCount, bytes)
    }

    fun missingTiles(
        flightKey: String,
        selection: CorridorImagery.ImagerySelection
    ): List<Triple<Int, Int, Int>> {
        val missing = ArrayList<Triple<Int, Int, Int>>(selection.tileCount)
        for (key in selection.tiles) {
            val (z, x, y) = CorridorImagery.decodeKey(key)
            if (!isValidTile(tileFile(flightKey, z, x, y))) missing.add(Triple(z, x, y))
        }
        return missing
    }

    fun writeTile(flightKey: String, z: Int, x: Int, y: Int, bytes: ByteArray) {
        val target = tileFile(flightKey, z, x, y)
        target.parentFile?.mkdirs()
        val tmp = File(target.parent, "${y}.tmp-${System.nanoTime()}")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target)) { "Could not persist tile $z/$x/$y" }
        tmp.delete()
    }

    fun writeManifest(flightKey: String, selection: CorridorImagery.ImagerySelection, bytes: Long) {
        val dir = dirFor(flightKey)
        dir.mkdirs()
        val levels = selection.levels.joinToString(",")
        manifestFile(flightKey).writeText(
            "{\"flightKey\":${jsonStr(flightKey)}," +
                "\"levels\":${jsonStr(levels)}," +
                "\"tiles\":${selection.tileCount}," +
                "\"bytes\":$bytes," +
                "\"completedAt\":${System.currentTimeMillis()}}"
        )
    }

    fun readManifest(flightKey: String): String? =
        runCatching { manifestFile(flightKey).takeIf { it.isFile }?.readText() }.getOrNull()

    fun delete(flightKey: String) {
        dirFor(flightKey).deleteRecursively()
    }

    /** Removes cached imagery for flights not in [keepKeys] (already sanitized or raw keys). */
    fun pruneExcept(keepKeys: Set<String>) {
        val keep = keepKeys.map { sanitizeKey(it) }.toSet()
        rootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in keep) {
                dir.deleteRecursively()
            }
        }
    }

    private fun jsonStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Parallel resumable tile downloader with bounded concurrency, retries and
 * cooperative cancellation. Skips already-cached valid tiles so pause/resume
 * and process restarts continue where they left off.
 *
 * @param fetch network fetch for one tile, returning raw bytes or null on failure.
 */
suspend fun downloadTiles(
    store: CorridorTileStore,
    flightKey: String,
    selection: CorridorImagery.ImagerySelection,
    fetch: suspend (z: Int, x: Int, y: Int) -> ByteArray?,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    maxAttempts: Int = 3
): TileCoverage = withContext(Dispatchers.IO) {
    val total = selection.tileCount
    require(total > 0) { "Empty imagery selection" }
    val missing = store.missingTiles(flightKey, selection)
    val done = java.util.concurrent.atomic.AtomicInteger(total - missing.size)
    var lastReportMs = System.currentTimeMillis()
    suspend fun report(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastReportMs >= 500L) {
            lastReportMs = now
            onProgress(done.get(), total)
        }
    }
    report(force = true)
    if (missing.isEmpty()) {
        val coverage = store.coverage(flightKey, selection)
        store.writeManifest(flightKey, selection, coverage.bytes)
        return@withContext coverage
    }
    val semaphore = Semaphore(4)
    coroutineScope {
        missing.map { (z, x, y) ->
            async {
                semaphore.withPermit {
                    var attempt = 0
                    var saved = false
                    while (!saved && attempt < maxAttempts) {
                        attempt++
                        try {
                            val bytes = fetch(z, x, y)
                            if (bytes != null && bytes.size >= 512 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                                store.writeTile(flightKey, z, x, y, bytes)
                                saved = true
                                done.incrementAndGet()
                                report()
                            } else if (attempt >= maxAttempts) {
                                throw IllegalStateException("Tile $z/$x/$y failed ($maxAttempts attempts)")
                            } else {
                                delay(backoffMs(attempt))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (attempt >= maxAttempts) throw e
                            delay(backoffMs(attempt))
                        }
                    }
                }
            }
        }.awaitAll()
    }
    val coverage = store.coverage(flightKey, selection)
    if (coverage.isComplete) {
        store.writeManifest(flightKey, selection, coverage.bytes)
    }
    onProgress(coverage.present, coverage.total)
    coverage
}

private fun backoffMs(attempt: Int): Long = when (attempt) {
    1 -> 400L
    2 -> 1_200L
    else -> 3_000L
}

/**
 * Android wiring: OkHttp tile fetch (unique UA, 4 parallel) + viewport decode.
 * Reads cache only - never touches the network outside preparation.
 */
class CorridorTiles(context: Context) {
    private val appContext = context.applicationContext
    val store = CorridorTileStore(File(appContext.filesDir, "corridor-tiles"))

    private val http: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "FlightMapAndroid/0.2 (offline corridor companion)")
                        .build()
                )
            }
            .build()
    }

    /** Shared flight key for vector regions and raster cache (single on-disk identity). */
    fun keyForPlan(plan: FlightPlan): String = OfflinePlan.create(plan).flightKey

    fun selectionFor(plan: FlightPlan): Pair<String, CorridorImagery.ImagerySelection> =
        CorridorImagery.selectForPlan(plan)

    suspend fun download(plan: FlightPlan, progress: (String) -> Unit): TileCoverage {
        val (flightKey, selection) = selectionFor(plan)
        progress("Imagery ${selection.levels.let { if (it.size == 1) "z${it.first()}" else "z${it.min()}\u2013${it.max()}" }} \u00b7 0 / ${selection.tileCount} tiles")
        return downloadTiles(
            store = store,
            flightKey = flightKey,
            selection = selection,
            fetch = { z, x, y -> fetchTile(z, x, y) },
            onProgress = { done, total ->
                val mb = store.coverage(flightKey, selection).bytes / 1024 / 1024
                progress("Imagery \u00b7 $done / $total tiles \u00b7 $mb MB")
            }
        )
    }

    private suspend fun fetchTile(z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CorridorImagery.tileUrl(z, x, y)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.bytes()
        }
    }

    fun coverage(plan: FlightPlan): Triple<String, CorridorImagery.ImagerySelection, TileCoverage> {
        val (flightKey, selection) = selectionFor(plan)
        return Triple(flightKey, selection, store.coverage(flightKey, selection))
    }

    fun coverageDetail(plan: FlightPlan): String {
        val (_, selection, coverage) = coverage(plan)
        val mb = "%.1f".format(Locale.US, coverage.bytes / 1_000_000.0)
        val levelText = selection.levels.let { if (it.size == 1) "z${it.first()}" else "z${it.min()}\u2013${it.max()}" }
        return if (coverage.isComplete) {
            "Imagery $levelText \u00b7 ${coverage.present} tiles \u00b7 $mb MB"
        } else {
            "Imagery $levelText \u00b7 ${coverage.present}/${coverage.total} tiles (incomplete)"
        }
    }

    /**
     * Decodes cached tiles for the current viewport. Offline file reads only.
     * Returns tileKey -> 256x256 ARGB pixels. Missing tiles are simply absent
     * (renderer falls back to bundled relief there).
     */
    suspend fun loadViewport(
        flightKey: String,
        zoom: Int,
        xRange: IntRange,
        yRange: IntRange,
        maxTiles: Int = CorridorImagery.MAX_VIEWPORT_TILES
    ): Map<Long, IntArray> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Long, IntArray>(maxTiles)
        val n = 1 shl zoom.coerceIn(0, 8)
        outer@ for (x in xRange) {
            for (y in yRange) {
                if (out.size >= maxTiles) break@outer
                val xx = ((x % n) + n) % n
                if (y !in 0 until n) continue
                val file = store.tileFile(flightKey, zoom, xx, y)
                if (!store.isValidTile(file)) continue
                val pixels = runCatching {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                    if (bmp.width != CorridorImagery.TILE_PX || bmp.height != CorridorImagery.TILE_PX) {
                        bmp.recycle()
                        return@runCatching null
                    }
                    val arr = IntArray(CorridorImagery.TILE_PX * CorridorImagery.TILE_PX)
                    bmp.getPixels(arr, 0, CorridorImagery.TILE_PX, 0, 0, CorridorImagery.TILE_PX, CorridorImagery.TILE_PX)
                    bmp.recycle()
                    arr
                }.getOrNull() ?: continue
                out[CorridorImagery.encodeKey(zoom, xx, y)] = pixels
            }
        }
        out
    }

    fun pruneExceptPlans(plans: List<FlightPlan>) {
        store.pruneExcept(plans.map { OfflinePlan.create(it).flightKey }.toSet())
    }

    fun deletePlan(plan: FlightPlan) {
        store.delete(OfflinePlan.create(plan).flightKey)
    }
}
