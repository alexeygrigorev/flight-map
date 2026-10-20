package com.flightmap.app

import com.flightmap.core.Airport
import com.flightmap.core.CorridorImagery
import com.flightmap.core.FlightPlan
import com.flightmap.core.GeoBox
import com.flightmap.core.GeoPoint
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CorridorTileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = "AY87:HEL-PVG:2026-10-20T10:00:00Z:2026-10-20T22:00:00Z:v2"

    private val boxes = listOf(
        GeoBox(50.0, 62.0, 20.0, 40.0),
        GeoBox(28.0, 38.0, 110.0, 125.0)
    )

    private fun jpegBytes(seed: Int, size: Int = 2048): ByteArray {
        // Minimal fake JPEG: magic + deterministic payload (validity = magic + size).
        val out = ByteArray(size)
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte()
        for (i in 2 until size) out[i] = ((seed * 31 + i) and 0xFF).toByte()
        return out
    }

    @Test
    fun sanitizeKeyIsFilesystemSafeAndStable() {
        val safe = CorridorTileStore.sanitizeKey(key)
        assertTrue(safe.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(safe, CorridorTileStore.sanitizeKey(key))
        assertTrue(safe.length <= 180)
    }

    @Test
    fun tileFileLayoutAndValidity() {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val file = store.tileFile(key, 8, 170, 80)
        assertTrue(file.absolutePath.endsWith("8/170/80.jpg"))
        assertFalse(store.isValidTile(file))
        file.parentFile!!.mkdirs()
        file.writeBytes("not-a-tile".toByteArray())
        assertFalse("Short non-JPEG must be invalid", store.isValidTile(file))
        file.writeBytes(jpegBytes(7))
        assertTrue(store.isValidTile(file))
    }

    @Test
    fun downloadSkipsCachedTilesAndResumes() = runBlocking {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val selection = CorridorImagery.selectImagery(boxes, maxTiles = 10_000, maxBytes = 500_000_000L)
        val fetches = AtomicInteger(0)
        val coverage = downloadTiles(store, key, selection, fetch = { z, x, y ->
            fetches.incrementAndGet()
            jpegBytes(z * 1000 + x + y)
        })
        assertTrue("Expected complete coverage, got $coverage", coverage.isComplete)
        assertEquals(selection.tileCount, coverage.present)
        val firstFetches = fetches.get()

        // Second run must not hit the network at all (resume = all cached).
        val again = downloadTiles(store, key, selection, fetch = { _, _, _ ->
            fetches.incrementAndGet()
            jpegBytes(1)
        })
        assertTrue(again.isComplete)
        assertEquals("Resume must skip all cached tiles", firstFetches, fetches.get())
        assertNotNull(store.readManifest(key))
    }

    @Test
    fun partialCacheResumesOnlyMissing() = runBlocking {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val selection = CorridorImagery.selectImagery(boxes, maxTiles = 10_000, maxBytes = 500_000_000L)
        // Pre-seed half the tiles.
        val keys = selection.tiles.toList()
        keys.take(keys.size / 2).forEach { k ->
            val (z, x, y) = CorridorImagery.decodeKey(k)
            val f = store.tileFile(key, z, x, y)
            f.parentFile!!.mkdirs()
            f.writeBytes(jpegBytes(x + y))
        }
        val fetches = AtomicInteger(0)
        val coverage = downloadTiles(store, key, selection, fetch = { z, x, y ->
            fetches.incrementAndGet()
            jpegBytes(z + x + y)
        })
        assertTrue(coverage.isComplete)
        assertEquals("Only missing tiles may be fetched", keys.size - keys.size / 2, fetches.get())
    }

    @Test
    fun failedTilesThrowAndKeepPartialForResume() {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val selection = CorridorImagery.selectImagery(boxes, maxTiles = 10_000, maxBytes = 500_000_000L)
        val attempts = AtomicInteger(0)
        try {
            runBlocking {
                downloadTiles(store, key, selection, fetch = { _, _, _ ->
                    attempts.incrementAndGet()
                    null // persistent network failure
                }, maxAttempts = 2)
            }
            fail("Expected download failure to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("failed"))
        }
        assertTrue("Retries must happen (2 attempts/tile minimum observed)", attempts.get() >= 2)
        val partial = store.coverage(key, selection)
        assertFalse("Failed download must not claim complete", partial.isComplete)
        // Partial files (none saved on total failure) leave valid state for resume.
        assertEquals(0, partial.present)
    }

    @Test
    fun corruptCachedTileIsRefetched() = runBlocking {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val selection = CorridorImagery.selectImagery(boxes, maxTiles = 10_000, maxBytes = 500_000_000L)
        val first = selection.tiles.first()
        val (z, x, y) = CorridorImagery.decodeKey(first)
        val f = store.tileFile(key, z, x, y)
        f.parentFile!!.mkdirs()
        f.writeBytes("corrupt".toByteArray())
        val fetches = AtomicInteger(0)
        val coverage = downloadTiles(store, key, selection, fetch = { zz, xx, yy ->
            fetches.incrementAndGet()
            jpegBytes(zz + xx + yy)
        })
        assertTrue(coverage.isComplete)
        assertEquals(selection.tileCount, fetches.get())
    }

    @Test
    fun pruneKeepsOnlyReferencedFlights() {
        val store = CorridorTileStore(temp.newFolder("tiles"))
        val other = "SQ22:SIN-EWR:2026-10-20T10:00:00Z:2026-10-21T02:00:00Z:v2"
        store.tileFile(key, 7, 1, 1).parentFile!!.mkdirs()
        store.tileFile(key, 7, 1, 1).writeBytes(jpegBytes(1))
        store.tileFile(other, 7, 2, 2).parentFile!!.mkdirs()
        store.tileFile(other, 7, 2, 2).writeBytes(jpegBytes(2))
        store.pruneExcept(setOf(key))
        assertTrue(store.dirFor(key).exists())
        assertFalse(store.dirFor(other).exists())
    }

    @Test
    fun planBasedSelectionIsAlignedWithVectorCorridor() {
        val o = Airport("HEL", "Helsinki Airport", GeoPoint(60.3172, 24.9633), "UTC")
        val d = Airport("PVG", "Shanghai Pudong", GeoPoint(31.1443, 121.8052), "UTC")
        val plan = FlightPlan("AY87", o, d, Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T22:00:00Z"))
        val (flightKey, selection) = CorridorImagery.selectForPlan(plan)
        assertTrue(flightKey.contains("AY87"))
        assertTrue(selection.tileCount in 50..CorridorImagery.MAX_TILES)
    }
}
