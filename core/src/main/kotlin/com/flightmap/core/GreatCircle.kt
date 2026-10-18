package com.flightmap.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GreatCircle {
    const val EARTH_RADIUS_KM: Double = 6371.0

    /**
     * Calculates the great-circle distance between two points on Earth in kilometers
     * using the Vincenty spherical formula for numerical stability across all distances.
     */
    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        if (a.lat == b.lat && a.lon == b.lon) return 0.0

        val phi1 = Math.toRadians(a.lat)
        val lambda1 = Math.toRadians(a.lon)
        val phi2 = Math.toRadians(b.lat)
        val lambda2 = Math.toRadians(b.lon)
        val deltaLambda = lambda2 - lambda1

        val cosPhi2 = cos(phi2)
        val sinDeltaLambda = sin(deltaLambda)
        val cosDeltaLambda = cos(deltaLambda)
        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val sinPhi2 = sin(phi2)

        val numerator = sqrt(
            (cosPhi2 * sinDeltaLambda).pow(2) +
                (cosPhi1 * sinPhi2 - sinPhi1 * cosPhi2 * cosDeltaLambda).pow(2)
        )
        val denominator = sinPhi1 * sinPhi2 + cosPhi1 * cosPhi2 * cosDeltaLambda
        val deltaSigma = atan2(numerator, denominator)
        return EARTH_RADIUS_KM * deltaSigma
    }

    /**
     * Computes the initial bearing (forward azimuth) from point a to point b in degrees [0.0, 360.0).
     * Handles poles, coincident, and antipodal points robustly.
     */
    fun heading(a: GeoPoint, b: GeoPoint): Double {
        if (a.lat >= 89.999999) return 180.0 // At North Pole, all directions point South
        if (a.lat <= -89.999999) return 0.0  // At South Pole, all directions point North
        if (distanceKm(a, b) < 1e-7) return 0.0

        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val deltaLambda = Math.toRadians(b.lon - a.lon)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)

        if (hypot(x, y) < 1e-12) {
            return 0.0 // Antipodal or undefined
        }

        val initialBearing = Math.toDegrees(atan2(y, x))
        val normalized = (initialBearing % 360.0 + 360.0) % 360.0
        return if (normalized == 360.0) 0.0 else normalized
    }

    /**
     * Interpolates along the great-circle arc from a to b by fraction [0.0, 1.0] using spherical
     * linear interpolation (slerp) on 3D Cartesian unit vectors.
     * Robust against date-line crossing, polar crossing, coincident, and antipodal endpoints.
     */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        if (fraction <= 0.0) return a
        if (fraction >= 1.0) return b

        val v1 = toVector3D(a)
        val v2 = toVector3D(b)

        val dot = v1.dot(v2).coerceIn(-1.0, 1.0)
        val omega = acos(dot)
        val sinOmega = sin(omega)

        val vResult: Vector3D = if (sinOmega < 1e-12) {
            if (dot > 0.0) {
                v1 // Coincident or near-coincident
            } else {
                // Antipodal points: pick an orthogonal unit vector to rotate around
                val u = if (abs(v1.z) < 0.9) {
                    Vector3D(-v1.y, v1.x, 0.0).normalized()
                } else {
                    Vector3D(0.0, -v1.z, v1.y).normalized()
                }
                val angle = Math.PI * fraction
                (v1 * cos(angle)) + (u * sin(angle))
            }
        } else {
            val fA = sin((1.0 - fraction) * omega) / sinOmega
            val fB = sin(fraction * omega) / sinOmega
            (v1 * fA) + (v2 * fB)
        }

        return fromVector3D(vResult.normalized())
    }

    /**
     * Generates a sequence of points along the great-circle path from a to b with specified segment count.
     */
    fun route(a: GeoPoint, b: GeoPoint, segments: Int = 128): List<GeoPoint> {
        if (segments <= 1) return listOf(a, b)
        val result = ArrayList<GeoPoint>(segments + 1)
        for (i in 0..segments) {
            val fraction = i.toDouble() / segments.toDouble()
            result.add(interpolate(a, b, fraction))
        }
        return result
    }

    /**
     * Returns cities located within the specified corridor width along the flight path,
     * sorted in sequence from origin towards destination.
     * Robust against date-line crossing, poles, near-coincident, and antipodal paths.
     */
    fun corridorCities(plan: FlightPlan, cities: List<City>, widthKm: Double = 180.0): List<City> {
        val pA = plan.origin.point
        val pB = plan.destination.point
        val vA = toVector3D(pA)
        val vB = toVector3D(pB)

        val totalDist = distanceKm(pA, pB)

        // Handle coincident origin and destination
        if (totalDist < 1e-3) {
            return cities.mapNotNull { city ->
                val dist = distanceKm(pA, city.point)
                if (dist <= widthKm) city to dist else null
            }.sortedBy { it.second }.map { it.first }
        }

        val cross = vA.cross(vB)
        val crossLen = cross.length()
        val nVec = if (crossLen < 1e-12) {
            // Antipodal points
            val tangent = if (abs(vA.z) < 0.9) Vector3D(-vA.y, vA.x, 0.0).normalized()
            else Vector3D(0.0, -vA.z, vA.y).normalized()
            vA.cross(tangent).normalized()
        } else {
            cross.normalized()
        }

        // Tangent vector T at A pointing towards B in the plane of the great circle
        val tVec = nVec.cross(vA).normalized()
        val totalAngle = totalDist / EARTH_RADIUS_KM

        data class CityCandidate(val city: City, val alongTrackKm: Double)

        val matching = mutableListOf<CityCandidate>()

        for (city in cities) {
            val vP = toVector3D(city.point)

            // Cross-track angular distance to great circle
            val sinDelta = vP.dot(nVec).coerceIn(-1.0, 1.0)
            val crossTrackAngle = abs(asin(sinDelta))
            val crossTrackKm = EARTH_RADIUS_KM * crossTrackAngle

            // Projection of vP onto the great-circle plane
            val proj = vP - (nVec * vP.dot(nVec))
            val projLen = proj.length()
            val alongTrackAngle = if (projLen < 1e-12) {
                0.0
            } else {
                val vProj = proj.normalized()
                val cosTheta = vProj.dot(vA).coerceIn(-1.0, 1.0)
                val sinTheta = vProj.dot(tVec)
                atan2(sinTheta, cosTheta)
            }

            // Determine shortest distance to the segment AB
            val distToSegmentKm = when {
                alongTrackAngle < 0.0 -> distanceKm(city.point, pA)
                alongTrackAngle > totalAngle -> distanceKm(city.point, pB)
                else -> crossTrackKm
            }

            if (distToSegmentKm <= widthKm) {
                matching.add(CityCandidate(city, alongTrackAngle * EARTH_RADIUS_KM))
            }
        }

        matching.sortBy { it.alongTrackKm }
        return matching.map { it.city }
    }

    private data class Vector3D(val x: Double, val y: Double, val z: Double) {
        fun dot(other: Vector3D): Double = x * other.x + y * other.y + z * other.z
        fun cross(other: Vector3D): Vector3D = Vector3D(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )
        fun length(): Double = sqrt(x * x + y * y + z * z)
        fun normalized(): Vector3D {
            val len = length()
            return if (len > 0.0) Vector3D(x / len, y / len, z / len) else this
        }
        operator fun plus(other: Vector3D): Vector3D = Vector3D(x + other.x, y + other.y, z + other.z)
        operator fun minus(other: Vector3D): Vector3D = Vector3D(x - other.x, y - other.y, z - other.z)
        operator fun times(scalar: Double): Vector3D = Vector3D(x * scalar, y * scalar, z * scalar)
    }

    private fun toVector3D(point: GeoPoint): Vector3D {
        val phi = Math.toRadians(point.lat)
        val lambda = Math.toRadians(point.lon)
        val cosPhi = cos(phi)
        return Vector3D(
            cosPhi * cos(lambda),
            cosPhi * sin(lambda),
            sin(phi)
        )
    }

    private fun fromVector3D(v: Vector3D): GeoPoint {
        val lat = Math.toDegrees(asin(v.z.coerceIn(-1.0, 1.0)))
        var lon = Math.toDegrees(atan2(v.y, v.x))
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return GeoPoint(lat, lon)
    }
}
