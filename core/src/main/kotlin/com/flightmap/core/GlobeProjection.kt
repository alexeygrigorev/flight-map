package com.flightmap.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val z: Double,
    val visible: Boolean
)

/**
 * 3D tilted satellite globe camera matching the airline IFE Flight Explorer perspective
 * (reference: ~/tmp/agent-reports/flight-map-ref-ife.jpg).
 *
 * Uses exact 3D orthogonal sphere rotations so that:
 * 1. The upper limb of the Earth arcs across the top of the landscape frame with deep space
 *    and a luminous blue atmospheric halo above the curved horizon.
 * 2. The target point (centerLat, centerLon) lands cleanly at the visual focus of the viewport.
 * 3. Every point on the front hemisphere (z > 0) maps inside the horizon circle, and
 *    [unproject] is the exact analytical inverse of [project].
 */
class GlobeProjection(
    val width: Float,
    val height: Float,
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double = 1.0,
    val headingRotationDeg: Double = 0.0
) {
    val isPortrait: Boolean = height > width
    // At overview zoom (1.0), radius is large enough that the curved northern horizon arcs
    // gracefully across the top of the frame while filling the bottom width.
    val radius: Float = (maxOf(width, height) * (if (isPortrait) 0.56 else 0.68) * zoom).toFloat()
    // Strictly proportional to height (never pixel-clamped) so raster and vector projections match 1:1 at any resolution.
    val horizonTopY: Float = height * (if (isPortrait) 0.165f else 0.138f)
    val sphereCenterX: Float = width * 0.50f
    val sphereCenterY: Float = run {
        val overviewCy = horizonTopY + radius
        if (zoom <= 1.25) {
            overviewCy
        } else {
            val blend = ((zoom - 1.25) / 1.75).coerceIn(0.0, 1.0).toFloat()
            overviewCy * (1f - blend) + (height * 0.72f + radius * 0.55f) * blend
        }
    }

    val focusScreenY: Float = height * (if (isPortrait) 0.50f else 0.54f)

    val lat0: Double = Math.toRadians(centerLat.coerceIn(-85.0, 85.0))
    val lon0: Double = Math.toRadians(centerLon)
    val rotRad: Double = Math.toRadians(headingRotationDeg)

    // Pitch rotation around camera X-axis so (centerLat, centerLon) projects to focusScreenY
    val pitchRad: Double = asin(((sphereCenterY - focusScreenY) / radius).toDouble().coerceIn(-0.82, 0.82))

    val sinLat0: Double = sin(lat0)
    val cosLat0: Double = cos(lat0)
    val sinRot: Double = sin(rotRad)
    val cosRot: Double = cos(rotRad)
    val sinPitch: Double = sin(pitchRad)
    val cosPitch: Double = cos(pitchRad)

    /**
     * Projects a geographic (lat, lon) coordinate onto 2D screen coordinates + visibility.
     */
    fun project(point: GeoPoint): ProjectedPoint {
        val phi = Math.toRadians(point.lat)
        val lam = Math.toRadians(point.lon)
        val dLon = lam - lon0

        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val cosDLon = cos(dLon)
        val sinDLon = sin(dLon)

        // 1. Camera-centered orthographic coordinates for (centerLat, centerLon)
        val xEast = cosPhi * sinDLon
        val yNorth = cosLat0 * sinPhi - sinLat0 * cosPhi * cosDLon
        val zOut = sinLat0 * sinPhi + cosLat0 * cosPhi * cosDLon

        // 2. Heading rotation around Z-axis
        val x1 = xEast * cosRot - yNorth * sinRot
        val y1 = xEast * sinRot + yNorth * cosRot
        val z1 = zOut

        // 3. Orbital pitch tilt around X-axis
        val x2 = x1
        val y2 = y1 * cosPitch + z1 * sinPitch
        val z2 = -y1 * sinPitch + z1 * cosPitch

        val sx = sphereCenterX + (x2 * radius).toFloat()
        val sy = sphereCenterY - (y2 * radius).toFloat()

        return ProjectedPoint(
            x = sx,
            y = sy,
            z = z2,
            visible = z2 > 0.015
        )
    }

    /**
     * Analytical inverse projection: maps screen coordinates (sx, sy) back to geographic
     * (lat, lon, zDepth) if inside the visible globe disc, or null if in outer space.
     */
    fun unproject(sx: Float, sy: Float): Triple<Double, Double, Double>? {
        val x2 = ((sx - sphereCenterX) / radius).toDouble()
        val y2 = ((sphereCenterY - sy) / radius).toDouble()

        val rSq = x2 * x2 + y2 * y2
        if (rSq > 1.0) return null

        val z2 = sqrt((1.0 - rSq).coerceAtLeast(0.0))

        // Undo X-axis pitch rotation
        val x1 = x2
        val y1 = y2 * cosPitch - z2 * sinPitch
        val z1 = y2 * sinPitch + z2 * cosPitch

        // Undo Z-axis heading rotation
        val xEast = x1 * cosRot + y1 * sinRot
        val yNorth = -x1 * sinRot + y1 * cosRot
        val zOut = z1

        // Convert unit vector back to (lat, lon)
        val sinPhi = (zOut * sinLat0 + yNorth * cosLat0).coerceIn(-1.0, 1.0)
        val lat = Math.toDegrees(asin(sinPhi))
        val lon = Math.toDegrees(
            lon0 + atan2(xEast, zOut * cosLat0 - yNorth * sinLat0)
        )
        val normLon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return Triple(lat, normLon, z2)
    }

    /**
     * Computes the 2D screen rotation angle (in degrees, where 0 = pointing right, -90 = pointing up)
     * for an aircraft at [point] moving with geographic bearing [headingDeg].
     */
    fun screenHeadingDegrees(point: GeoPoint, headingDeg: Double): Float {
        val p0 = project(point)
        val stepPt = stepAlongBearing(point, headingDeg, 1.2)
        val p1 = project(stepPt)
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        if (hypot(dx.toDouble(), dy.toDouble()) < 1e-3) {
            return (headingDeg - 90.0).toFloat()
        }
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun stepAlongBearing(origin: GeoPoint, bearingDeg: Double, distDeg: Double): GeoPoint {
        val phi1 = Math.toRadians(origin.lat)
        val lam1 = Math.toRadians(origin.lon)
        val brg = Math.toRadians(bearingDeg)
        val d = Math.toRadians(distDeg)

        val sinPhi2 = sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(brg)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val y = sin(brg) * sin(d) * cos(phi1)
        val x = cos(d) - sin(phi1) * sinPhi2
        val lam2 = lam1 + atan2(y, x)
        var lon2 = Math.toDegrees(lam2)
        lon2 = ((lon2 + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(phi2), lon2)
    }
}
