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
 * Rotated, pitched sphere viewed through a pinhole camera. The geographic focus stays at
 * (sphereCenterX, focusScreenY), while pitch changes depth and foreshortening. Raster sampling,
 * overlays, aircraft heading and hit testing must all use this projection or its exact inverse.
 * The overview is nearly orthographic; user tilt brings the camera closer for perspective.
 */
class GlobeProjection(
    val width: Float,
    val height: Float,
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double = 1.0,
    val headingRotationDeg: Double = 0.0,
    val tiltDeg: Double = 0.0
) {
    val isPortrait: Boolean = height > width
    // At overview zoom (1.0), radius is large enough that the curved northern horizon arcs
    // gracefully across the top of the frame while filling the bottom width.
    private val baseRadius: Float = (maxOf(width, height) * (if (isPortrait) 0.56 else 0.68)).toFloat()
    val radius: Float = (baseRadius * zoom).toFloat()
    // Strictly proportional to height (never pixel-clamped) so raster and vector projections match 1:1 at any resolution.
    val horizonTopY: Float = height * (if (isPortrait) 0.165f else 0.138f)
    val sphereCenterX: Float = width * 0.50f
    val focusScreenY: Float = height * (if (isPortrait) 0.50f else 0.54f)

    val lat0: Double = Math.toRadians(centerLat.coerceIn(-85.0, 85.0))
    val lon0: Double = Math.toRadians(centerLon)
    val rotRad: Double = Math.toRadians(headingRotationDeg)

    // At overview (zoom <= 1.0), the northern limb sits at horizonTopY.
    // As zoom increases above 1.0, the oblique tilt smoothly flattens toward nadir (0 degrees)
    // so zoomed-in regions are centered, undistorted, and fill the full screen.
    private val overviewSinPitch: Double =
        ((horizonTopY + baseRadius - focusScreenY) / baseRadius).toDouble().coerceIn(-0.80, 0.80)
    private val tiltFactor: Double = if (zoom <= 1.0) 1.0 else (1.0 / (zoom * zoom)).coerceIn(0.0, 1.0)

    // Automatic orbital pitch (auto-framing) plus the user's two-finger tilt, in radians.
    // Total pitch is clamped so the camera never flips over the pole or through the globe.
    val pitchRad: Double = (
        asin((overviewSinPitch * tiltFactor).coerceIn(-0.80, 0.80)) +
            Math.toRadians(tiltDeg.coerceIn(0.0, MAX_TILT_DEG))
        ).coerceIn(-0.95, 1.18)

    val sinLat0: Double = sin(lat0)
    val cosLat0: Double = cos(lat0)
    val sinRot: Double = sin(rotRad)
    val cosRot: Double = cos(rotRad)
    val sinPitch: Double = sin(pitchRad)
    val cosPitch: Double = cos(pitchRad)

    // Pinhole camera on the view axis: distance in globe radii and focal length in pixels.
    // Near-orthographic (D = 60) until the user tilts; very deep zoom adds a mild pinch.
    val cameraDistance: Double = run {
        val tiltFrac = (tiltDeg.coerceIn(0.0, MAX_TILT_DEG) / MAX_TILT_DEG).coerceIn(0.0, 1.0)
        val deepZoomFrac = ((zoom - 16.0) / (MAX_ZOOM - 16.0)).coerceIn(0.0, 1.0)
        val t = (0.85 * tiltFrac + 0.15 * deepZoomFrac).coerceIn(0.0, 1.0)
        60.0 - 56.8 * t
    }
    val focalPx: Double = radius.toDouble() * cameraDistance
    val silhouetteRadius: Float = (focalPx / sqrt(cameraDistance * cameraDistance - 1.0)).toFloat()

    // The focus point sits at (sphereCenterX, focusScreenY); the silhouette circle is shifted
    // down from the focus point by exactly the focus point's projected y-offset.
    val sphereCenterY: Float = (
        focusScreenY.toDouble() +
            focalPx * sinPitch / (cameraDistance - cosPitch)
        ).toFloat()

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

        // 1. Camera-centered coordinates for (centerLat, centerLon)
        val xEast = cosPhi * sinDLon
        val yNorth = cosLat0 * sinPhi - sinLat0 * cosPhi * cosDLon
        val zOut = sinLat0 * sinPhi + cosLat0 * cosPhi * cosDLon

        // 2. Heading rotation around Z-axis (view axis)
        val x1 = xEast * cosRot - yNorth * sinRot
        val y1 = xEast * sinRot + yNorth * cosRot
        val z1 = zOut

        // 3. Pitch tilt around X-axis
        val x2 = x1
        val y2 = y1 * cosPitch + z1 * sinPitch
        val z2 = -y1 * sinPitch + z1 * cosPitch

        // 4. Pinhole perspective divide (camera at (0, 0, D), depth = D - z2 > 0)
        val depth = cameraDistance - z2
        val sx = sphereCenterX + (focalPx * x2 / depth).toFloat()
        val sy = sphereCenterY - (focalPx * y2 / depth).toFloat()

        return ProjectedPoint(
            x = sx,
            y = sy,
            z = z2,
            visible = z2 > 1.0 / cameraDistance + 0.0001
        )
    }

    /**
     * Analytical inverse projection: casts a ray from the camera through the screen point,
     * intersects the unit sphere, and maps the hit back to geographic (lat, lon, zDepth).
     * Returns null for screen points in outer space (outside the silhouette circle).
     */
    fun unproject(sx: Float, sy: Float): Triple<Double, Double, Double>? {
        val rx = (sx - sphereCenterX).toDouble()
        val ry = (sphereCenterY - sy).toDouble()

        val pxSq = rx * rx + ry * ry
        val silSq = silhouetteRadius.toDouble() * silhouetteRadius.toDouble()
        if (pxSq > silSq) return null

        // Ray from camera (0, 0, D) through the pixel on the focal plane (rx, ry, -f)
        val invLen = 1.0 / sqrt(pxSq + focalPx * focalPx)
        val dx = rx * invLen
        val dy = ry * invLen
        val dz = -focalPx * invLen

        // |O + t*d|^2 = 1  ->  t^2 + 2*t*(O.d) + (D^2 - 1) = 0, near root
        val b = cameraDistance * dz
        val disc = b * b - (cameraDistance * cameraDistance - 1.0)
        if (disc < 0.0) return null
        val t = -b - sqrt(disc)

        val x2 = t * dx
        val y2 = t * dy
        val z2 = cameraDistance + t * dz

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
        val stepPt = stepAlongBearing(point, headingDeg, 0.01)
        val p1 = project(stepPt)
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        if (hypot(dx.toDouble(), dy.toDouble()) < 1e-3) {
            return (headingDeg - 90.0 - headingRotationDeg).toFloat()
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

    companion object {
        const val MAX_TILT_DEG = 65.0
        const val MAX_ZOOM = 64.0
    }
}
