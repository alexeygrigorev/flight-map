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
     * Describes the relative bearing of a target point relative to the aircraft's current
     * heading and position, suitable for window-seat passengers (e.g. "Off left wing · 10 o'clock").
     */
    fun relativeBearingDescription(planePoint: GeoPoint, planeHeading: Double, targetPoint: GeoPoint): String {
        val bearing = heading(planePoint, targetPoint)
        val relAngle = ((bearing - planeHeading) % 360.0 + 360.0) % 360.0
        val clock = (((relAngle + 15.0) / 30.0).toInt() % 12).let { if (it == 0) 12 else it }
        return when {
            relAngle in 345.0..360.0 || relAngle in 0.0..15.0 -> "Dead ahead · 12 o'clock"
            relAngle in 165.0..195.0 -> "Directly behind · 6 o'clock"
            relAngle in 15.0..165.0 -> "Off right wing · $clock o'clock"
            else -> "Off left wing · $clock o'clock"
        }
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
     * Computes total distance in kilometers along a multi-waypoint trajectory.
     */
    fun routeDistanceKm(waypoints: List<GeoPoint>): Double {
        if (waypoints.size < 2) return 0.0
        if (waypoints.size == 2) return distanceKm(waypoints[0], waypoints[1])
        var sum = 0.0
        for (i in 0 until waypoints.size - 1) {
            sum += distanceKm(waypoints[i], waypoints[i + 1])
        }
        return sum
    }

    /**
     * Generates a C1-smooth fly-by waypoint chain by replacing sharp interior waypoint corners
     * with quadratic spherical Bezier turn arcs (RNAV fly-by filleting).
     */
    fun smoothWaypoints(waypoints: List<GeoPoint>, arcSteps: Int = 6): List<GeoPoint> {
        if (waypoints.size <= 2) return waypoints
        val out = ArrayList<GeoPoint>(waypoints.size * (arcSteps + 1))
        out.add(waypoints.first())
        for (i in 1 until waypoints.lastIndex) {
            val prev = waypoints[i - 1]
            val curr = waypoints[i]
            val next = waypoints[i + 1]
            val dPrev = distanceKm(prev, curr)
            val dNext = distanceKm(curr, next)
            if (dPrev < 1.0 || dNext < 1.0) {
                out.add(curr)
                continue
            }
            val fPrev = (80.0 / dPrev).coerceIn(0.08, 0.28)
            val fNext = (80.0 / dNext).coerceIn(0.08, 0.28)
            val pIn = interpolate(curr, prev, fPrev)
            val pOut = interpolate(curr, next, fNext)
            for (s in 0..arcSteps) {
                val t = s.toDouble() / arcSteps.toDouble()
                val q0 = interpolate(pIn, curr, t)
                val q1 = interpolate(curr, pOut, t)
                out.add(interpolate(q0, q1, t))
            }
        }
        out.add(waypoints.last())
        return out
    }

    /**
     * Interpolates position and forward heading along a multi-waypoint trajectory at [fraction] in [0.0, 1.0].
     */
    fun interpolateWaypointsWithHeading(waypoints: List<GeoPoint>, fraction: Double): Pair<GeoPoint, Double> {
        if (waypoints.isEmpty()) return GeoPoint(0.0, 0.0) to 0.0
        if (waypoints.size == 1) return waypoints[0] to 0.0
        if (waypoints.size == 2) {
            val a = waypoints[0]
            val b = waypoints[1]
            val totalDist = distanceKm(a, b)
            val pt = interpolate(a, b, fraction)
            val hdg = if (totalDist < 1e-4) {
                0.0
            } else if (fraction >= 0.9999) {
                heading(interpolate(a, b, 0.99), b)
            } else {
                heading(pt, b)
            }
            return pt to hdg
        }

        val pts = smoothWaypoints(waypoints)
        val clamped = fraction.coerceIn(0.0, 1.0)
        val segLengths = DoubleArray(pts.size - 1)
        var totalKm = 0.0
        for (i in 0 until pts.size - 1) {
            val d = distanceKm(pts[i], pts[i + 1])
            segLengths[i] = d
            totalKm += d
        }
        if (totalKm < 1e-4) return pts.first() to 0.0
        if (clamped <= 0.0) {
            return pts.first() to heading(pts[0], pts[1])
        }
        if (clamped >= 1.0) {
            val n = pts.size
            val nearEnd = interpolate(pts[n - 2], pts[n - 1], 0.98)
            return pts.last() to heading(nearEnd, pts.last())
        }

        val targetKm = totalKm * clamped
        var accum = 0.0
        for (i in segLengths.indices) {
            val segLen = segLengths[i]
            if (accum + segLen >= targetKm || i == segLengths.lastIndex) {
                val localFrac = if (segLen > 1e-6) ((targetKm - accum) / segLen).coerceIn(0.0, 1.0) else 0.0
                val pt = interpolate(pts[i], pts[i + 1], localFrac)
                val hdg = if (localFrac >= 0.995) {
                    val prevPt = interpolate(pts[i], pts[i + 1], 0.95)
                    heading(prevPt, pts[i + 1])
                } else {
                    heading(pt, pts[i + 1])
                }
                return pt to hdg
            }
            accum += segLen
        }
        return pts.last() to 0.0
    }

    /**
     * Generates evenly spaced points along a multi-waypoint trajectory.
     */
    fun routeWaypoints(waypoints: List<GeoPoint>, segments: Int = 180): List<GeoPoint> {
        if (waypoints.size < 2) return waypoints
        if (waypoints.size == 2) return route(waypoints[0], waypoints[1], segments)
        if (segments <= 1) return listOf(waypoints.first(), waypoints.last())
        val pts = smoothWaypoints(waypoints)
        val segLengths = DoubleArray(pts.size - 1)
        var totalKm = 0.0
        for (i in 0 until pts.size - 1) {
            val d = distanceKm(pts[i], pts[i + 1])
            segLengths[i] = d
            totalKm += d
        }
        if (totalKm < 1e-4) return listOf(pts.first(), pts.last())
        val result = ArrayList<GeoPoint>(segments + 1)
        var segIdx = 0
        var accum = 0.0
        for (s in 0..segments) {
            val targetKm = totalKm * (s.toDouble() / segments.toDouble())
            while (segIdx < segLengths.lastIndex && accum + segLengths[segIdx] < targetKm) {
                accum += segLengths[segIdx]
                segIdx++
            }
            val segLen = segLengths[segIdx]
            val localFrac = if (segLen > 1e-6) ((targetKm - accum) / segLen).coerceIn(0.0, 1.0) else 0.0
            result.add(interpolate(pts[segIdx], pts[segIdx + 1], localFrac))
        }
        return result
    }

    /**
     * Returns cities located within the specified corridor width along the flight path,
     * sorted in sequence from origin towards destination.
     * Supports both 2-point great-circle plans and multi-waypoint real filed trajectories.
     */
    fun corridorCities(plan: FlightPlan, cities: List<City>, widthKm: Double = 180.0): List<City> {
        val waypoints = plan.effectiveWaypoints
        if (waypoints.size > 2) {
            return corridorCitiesForWaypoints(waypoints, cities, widthKm)
        }
        return corridorCitiesSegment(plan.origin.point, plan.destination.point, cities, widthKm)
    }

    fun corridorCitiesForWaypoints(waypoints: List<GeoPoint>, cities: List<City>, widthKm: Double = 180.0): List<City> {
        if (waypoints.isEmpty()) return emptyList()
        if (waypoints.size == 1) {
            return cities.filter { distanceKm(waypoints[0], it.point) <= widthKm }
        }
        if (waypoints.size == 2) {
            return corridorCitiesSegment(waypoints[0], waypoints[1], cities, widthKm)
        }

        // Precompute segment vectors for fast projection
        data class SegGeometry(
            val pA: GeoPoint,
            val pB: GeoPoint,
            val vA: Vector3D,
            val nVec: Vector3D,
            val tVec: Vector3D,
            val segLenKm: Double,
            val segAngle: Double,
            val cumStartKm: Double
        )

        val segments = mutableListOf<SegGeometry>()
        var cumKm = 0.0
        for (i in 0 until waypoints.size - 1) {
            val pA = waypoints[i]
            val pB = waypoints[i + 1]
            val dist = distanceKm(pA, pB)
            if (dist < 1e-3) continue
            val vA = toVector3D(pA)
            val vB = toVector3D(pB)
            val cross = vA.cross(vB)
            val crossLen = cross.length()
            val nVec = if (crossLen < 1e-12) {
                val tangent = if (abs(vA.z) < 0.9) Vector3D(-vA.y, vA.x, 0.0).normalized()
                else Vector3D(0.0, -vA.z, vA.y).normalized()
                vA.cross(tangent).normalized()
            } else {
                cross.normalized()
            }
            val tVec = nVec.cross(vA).normalized()
            segments.add(
                SegGeometry(
                    pA = pA,
                    pB = pB,
                    vA = vA,
                    nVec = nVec,
                    tVec = tVec,
                    segLenKm = dist,
                    segAngle = dist / EARTH_RADIUS_KM,
                    cumStartKm = cumKm
                )
            )
            cumKm += dist
        }

        if (segments.isEmpty()) {
            return corridorCitiesSegment(waypoints.first(), waypoints.last(), cities, widthKm)
        }

        data class CityCandidate(val city: City, val alongTrackKm: Double)
        val matching = ArrayList<CityCandidate>()

        for (city in cities) {
            val vP = toVector3D(city.point)
            var bestDistKm = Double.MAX_VALUE
            var bestAlongKm = 0.0

            for (seg in segments) {
                val sinDelta = vP.dot(seg.nVec).coerceIn(-1.0, 1.0)
                val crossTrackAngle = abs(asin(sinDelta))
                val crossTrackKm = EARTH_RADIUS_KM * crossTrackAngle

                val proj = vP - (seg.nVec * vP.dot(seg.nVec))
                val projLen = proj.length()
                val alongAngle = if (projLen < 1e-12) {
                    0.0
                } else {
                    val vProj = proj.normalized()
                    val cosTheta = vProj.dot(seg.vA).coerceIn(-1.0, 1.0)
                    val sinTheta = vProj.dot(seg.tVec)
                    atan2(sinTheta, cosTheta)
                }

                val distToSegKm: Double
                val clampedAlongKm: Double
                when {
                    alongAngle < 0.0 -> {
                        distToSegKm = distanceKm(city.point, seg.pA)
                        clampedAlongKm = seg.cumStartKm
                    }
                    alongAngle > seg.segAngle -> {
                        distToSegKm = distanceKm(city.point, seg.pB)
                        clampedAlongKm = seg.cumStartKm + seg.segLenKm
                    }
                    else -> {
                        distToSegKm = crossTrackKm
                        clampedAlongKm = seg.cumStartKm + alongAngle * EARTH_RADIUS_KM
                    }
                }

                if (distToSegKm < bestDistKm) {
                    bestDistKm = distToSegKm
                    bestAlongKm = clampedAlongKm
                }
            }

            if (bestDistKm <= widthKm) {
                matching.add(CityCandidate(city, bestAlongKm))
            }
        }

        matching.sortBy { it.alongTrackKm }
        return matching.map { it.city }
    }

    private fun corridorCitiesSegment(pA: GeoPoint, pB: GeoPoint, cities: List<City>, widthKm: Double): List<City> {
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
