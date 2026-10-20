package com.flightmap.core

import kotlin.math.abs

/** Pure camera updates shared by touch gestures and camera math tests. */
object GlobeCameraTransform {
    fun wrapDegrees(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** Keep a geographic anchor under the fingers after zoom, rotation, tilt or pan.
     * A small Newton solve accounts for perspective, latitude and map rotation together.
     */
    fun anchoredCenter(
        projection: GlobeProjection,
        anchor: GeoPoint,
        screenX: Float,
        screenY: Float
    ): GeoPoint {
        var lat = projection.centerLat
        var lon = projection.centerLon
        fun at(a: Double, b: Double) = GlobeProjection(
            projection.width, projection.height, a, b, projection.zoom,
            projection.headingRotationDeg, projection.tiltDeg
        ).project(anchor)
        repeat(8) {
            val p = at(lat, lon)
            val ex = (screenX - p.x).toDouble()
            val ey = (screenY - p.y).toDouble()
            if (abs(ex) + abs(ey) < 0.05) return GeoPoint(lat, wrapDegrees(lon))
            val da = at(lat + 0.001, lon)
            val db = at(lat, lon + 0.001)
            val ax = (da.x - p.x) / 0.001
            val ay = (da.y - p.y) / 0.001
            val bx = (db.x - p.x) / 0.001
            val by = (db.y - p.y) / 0.001
            val det = ax * by - ay * bx
            if (abs(det) < 0.00001) return GeoPoint(lat, wrapDegrees(lon))
            lat = (lat + ((ex * by - ey * bx) / det).coerceIn(-12.0, 12.0)).coerceIn(-84.9, 84.9)
            lon += ((ey * ax - ex * ay) / det).coerceIn(-24.0, 24.0)
        }
        return GeoPoint(lat, wrapDegrees(lon))
    }
}
