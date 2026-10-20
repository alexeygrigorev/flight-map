package com.flightmap.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GlobeCameraTransformTest {
    @Test fun perspectiveRoundTripsAtAllCameraExtremes() {
        for (zoom in listOf(0.75, 1.0, 12.0, 64.0)) {
            for (tilt in listOf(0.0, 35.0, 65.0)) {
                for (rotation in listOf(-170.0, 0.0, 93.0)) {
                    val p = GlobeProjection(1920f, 1080f, 45.0, 179.8, zoom, rotation, tilt)
                    val focus = p.project(GeoPoint(45.0, 179.8))
                    assertTrue(focus.visible)
                    assertEquals(p.focusScreenY, focus.y, 0.02f)
                    for ((x, y) in listOf(960f to 580f, 800f to 700f, 1100f to 450f)) {
                        val geo = p.unproject(x, y) ?: continue
                        val projected = p.project(GeoPoint(geo.first, geo.second))
                        assertTrue(projected.visible)
                        assertEquals(x, projected.x, 0.05f)
                        assertEquals(y, projected.y, 0.05f)
                    }
                }
            }
        }
    }

    @Test fun simultaneousPinchTwistTiltAndPanKeepsFingerAnchor() {
        for (zoom in listOf(1.5, 12.0, 50.0)) {
            val old = GlobeProjection(1920f, 1080f, 52.0, 179.95, zoom, 70.0, 35.0)
            val anchor = old.unproject(1020f, 620f)!!
            val next = GlobeProjection(1920f, 1080f, 52.0, 179.95, zoom * 1.15, 82.0, 40.0)
            val center = GlobeCameraTransform.anchoredCenter(next, GeoPoint(anchor.first, anchor.second), 1040f, 650f)
            val actual = GlobeProjection(1920f, 1080f, center.lat, center.lon, zoom * 1.15, 82.0, 40.0)
                .project(GeoPoint(anchor.first, anchor.second))
            assertEquals(1040f, actual.x, 0.5f)
            assertEquals(650f, actual.y, 0.5f)
            assertTrue(center.lon in -180.0..180.0)
        }
    }

    @Test fun perspectiveRejectsOccludedHemisphereAndSpace() {
        val p = GlobeProjection(1920f, 1080f, 0.0, 0.0, 1.0, 0.0, 65.0)
        assertFalse(p.project(GeoPoint(0.0, 180.0)).visible)
        assertNull(p.unproject(p.sphereCenterX + p.silhouetteRadius + 10, p.sphereCenterY))
        assertTrue(abs(GlobeCameraTransform.wrapDegrees(359.0) + 1) < 0.001)
    }

    @Test fun tiltedProjectionMatchesRasterAtDifferentResolutions() {
        val p = GlobeProjection(1920f, 1080f, 40.0, 25.0, 32.0, 47.0, 60.0)
        val small = GlobeProjection(960f, 540f, 40.0, 25.0, 32.0, 47.0, 60.0)
        val geo = p.unproject(1200f, 700f)!!
        val hit = small.project(GeoPoint(geo.first, geo.second))
        assertEquals(600f, hit.x, 0.02f)
        assertEquals(350f, hit.y, 0.02f)
    }
}
