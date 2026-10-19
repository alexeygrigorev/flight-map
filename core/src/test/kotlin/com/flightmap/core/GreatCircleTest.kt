package com.flightmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class GreatCircleTest {

    @Test
    fun antipodalCorridorFollowsInterpolatedArc() {
        val a = Airport("AAA", "A", GeoPoint(0.0, 0.0), "UTC")
        val b = Airport("BBB", "B", GeoPoint(0.0, 180.0), "UTC")
        val plan = FlightPlan("TEST", a, b, Instant.EPOCH, Instant.EPOCH.plusSeconds(3600))
        val midpoint = GreatCircle.interpolate(a.point, b.point, 0.5)
        val city = City("mid", "Midpoint", "", midpoint, "UTC", 1)
        assertEquals(listOf(city), GreatCircle.corridorCities(plan, listOf(city), 1.0))
    }

    private val originJFK = Airport("JFK", "New York JFK", GeoPoint(40.6413, -73.7781), "America/New_York")
    private val destLHR = Airport("LHR", "London Heathrow", GeoPoint(51.4700, -0.4543), "Europe/London")
    private val destCDG = Airport("CDG", "Paris Charles de Gaulle", GeoPoint(49.0097, 2.5479), "Europe/Paris")

    @Test
    fun testDistanceKmCoincidentPoints() {
        val p = GeoPoint(45.0, 10.0)
        assertEquals(0.0, GreatCircle.distanceKm(p, p), 1e-6)
    }

    @Test
    fun testDistanceKmLHRtoCDG() {
        val dist = GreatCircle.distanceKm(destLHR.point, destCDG.point)
        // Expected distance between LHR and CDG is ~344 km
        assertTrue("Distance should be ~344 km, got $dist", dist in 340.0..350.0)
    }

    @Test
    fun testDistanceKmJFKtoLHR() {
        val dist = GreatCircle.distanceKm(originJFK.point, destLHR.point)
        // Expected distance between JFK and LHR is ~5550-5560 km
        assertTrue("Distance should be ~5555 km, got $dist", dist in 5500.0..5600.0)
    }

    @Test
    fun testDistanceKmAntipodal() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 180.0)
        val expected = Math.PI * GreatCircle.EARTH_RADIUS_KM
        val dist = GreatCircle.distanceKm(p1, p2)
        assertEquals(expected, dist, 1.0)
    }

    @Test
    fun testDistanceKmPoles() {
        val northPole = GeoPoint(90.0, 0.0)
        val southPole = GeoPoint(-90.0, 0.0)
        val expected = Math.PI * GreatCircle.EARTH_RADIUS_KM
        val dist = GreatCircle.distanceKm(northPole, southPole)
        assertEquals(expected, dist, 1.0)
    }

    @Test
    fun testHeadingCardinalDirections() {
        val origin = GeoPoint(0.0, 0.0)
        assertEquals(0.0, GreatCircle.heading(origin, GeoPoint(10.0, 0.0)), 0.1)   // North
        assertEquals(90.0, GreatCircle.heading(origin, GeoPoint(0.0, 10.0)), 0.1)  // East
        assertEquals(180.0, GreatCircle.heading(origin, GeoPoint(-10.0, 0.0)), 0.1)// South
        assertEquals(270.0, GreatCircle.heading(origin, GeoPoint(0.0, -10.0)), 0.1)// West
    }

    @Test
    fun testHeadingCoincidentAndPoles() {
        val p = GeoPoint(45.0, 20.0)
        assertEquals(0.0, GreatCircle.heading(p, p), 1e-6)

        val northPole = GeoPoint(90.0, 0.0)
        assertEquals(180.0, GreatCircle.heading(northPole, GeoPoint(50.0, 10.0)), 1e-6)

        val southPole = GeoPoint(-90.0, 0.0)
        assertEquals(0.0, GreatCircle.heading(southPole, GeoPoint(-50.0, 10.0)), 1e-6)
    }

    @Test
    fun testHeadingDateLineCrossing() {
        val p1 = GeoPoint(0.0, 179.0)
        val p2 = GeoPoint(0.0, -179.0)
        // Heading East across the 180th meridian
        assertEquals(90.0, GreatCircle.heading(p1, p2), 0.1)
    }

    @Test
    fun testInterpolateEndpointsAndMidpoint() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 20.0)

        val start = GreatCircle.interpolate(p1, p2, 0.0)
        assertEquals(p1.lat, start.lat, 1e-6)
        assertEquals(p1.lon, start.lon, 1e-6)

        val end = GreatCircle.interpolate(p1, p2, 1.0)
        assertEquals(p2.lat, end.lat, 1e-6)
        assertEquals(p2.lon, end.lon, 1e-6)

        val mid = GreatCircle.interpolate(p1, p2, 0.5)
        assertEquals(0.0, mid.lat, 1e-5)
        assertEquals(10.0, mid.lon, 1e-5)
    }

    @Test
    fun testInterpolateDateLineCrossing() {
        val p1 = GeoPoint(0.0, 179.0)
        val p2 = GeoPoint(0.0, -179.0)
        val mid = GreatCircle.interpolate(p1, p2, 0.5)
        assertEquals(0.0, mid.lat, 1e-5)
        assertTrue("Longitude should be 180 or -180, got ${mid.lon}", abs(abs(mid.lon) - 180.0) < 1e-5)
    }

    @Test
    fun testInterpolatePolarCrossing() {
        val p1 = GeoPoint(80.0, 0.0)
        val p2 = GeoPoint(80.0, 180.0)
        val mid = GreatCircle.interpolate(p1, p2, 0.5)
        assertEquals(90.0, mid.lat, 1e-5)
    }

    @Test
    fun testInterpolateCoincidentAndAntipodalRobustness() {
        val p = GeoPoint(35.0, 139.0)
        val interpCoincident = GreatCircle.interpolate(p, p, 0.5)
        assertEquals(p.lat, interpCoincident.lat, 1e-5)
        assertEquals(p.lon, interpCoincident.lon, 1e-5)

        val antipodal = GeoPoint(-35.0, -41.0)
        val interpAntipodal = GreatCircle.interpolate(p, antipodal, 0.5)
        assertFalse(interpAntipodal.lat.isNaN())
        assertFalse(interpAntipodal.lon.isNaN())
    }

    @Test
    fun testRouteDefaultSegments() {
        val p1 = GeoPoint(40.0, -74.0)
        val p2 = GeoPoint(51.0, 0.0)
        val points = GreatCircle.route(p1, p2)
        assertEquals(129, points.size)
        assertEquals(p1.lat, points.first().lat, 1e-6)
        assertEquals(p1.lon, points.first().lon, 1e-6)
        assertEquals(p2.lat, points.last().lat, 1e-6)
        assertEquals(p2.lon, points.last().lon, 1e-6)
    }

    @Test
    fun testRouteCustomSegments() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(10.0, 10.0)
        val points = GreatCircle.route(p1, p2, segments = 4)
        assertEquals(5, points.size)
    }

    @Test
    fun testCorridorCitiesFilteringAndSorting() {
        val dep = Instant.parse("2026-10-18T10:00:00Z")
        val arr = Instant.parse("2026-10-18T18:00:00Z")
        val plan = FlightPlan("BA178", originJFK, destLHR, dep, arr)

        // Boston is near the JFK-LHR track (~300 km from JFK, close to track)
        val boston = City("bos", "Boston", "USA", GeoPoint(42.3601, -71.0589), "America/New_York", 675000)
        // Halifax is further along the track in Nova Scotia (~1000 km along track)
        val halifax = City("yhz", "Halifax", "Canada", GeoPoint(44.6488, -63.5752), "America/Halifax", 440000)
        // St. John's is near Newfoundland (~1800 km along track)
        val stJohns = City("yyt", "St. John's", "Canada", GeoPoint(47.5615, -52.7126), "America/St_Johns", 110000)
        // Tokyo is far off route
        val tokyo = City("tyo", "Tokyo", "Japan", GeoPoint(35.6762, 139.6503), "Asia/Tokyo", 14000000)
        // Sydney is far off route
        val sydney = City("syd", "Sydney", "Australia", GeoPoint(-33.8688, 151.2093), "Australia/Sydney", 5300000)

        val candidateCities = listOf(sydney, stJohns, tokyo, boston, halifax)
        val corridor = GreatCircle.corridorCities(plan, candidateCities, widthKm = 250.0)

        // Tokyo and Sydney must be excluded
        assertFalse(corridor.contains(tokyo))
        assertFalse(corridor.contains(sydney))

        // Boston, Halifax, and St. John's should be included
        assertTrue(corridor.contains(boston))
        assertTrue(corridor.contains(halifax))
        assertTrue(corridor.contains(stJohns))

        // Must be sorted along route: Boston first, then Halifax, then St. John's
        val bosIdx = corridor.indexOf(boston)
        val yhzIdx = corridor.indexOf(halifax)
        val yytIdx = corridor.indexOf(stJohns)
        assertTrue("Boston should come before Halifax", bosIdx < yhzIdx)
        assertTrue("Halifax should come before St. John's", yhzIdx < yytIdx)
    }

    @Test
    fun testCorridorCitiesDateLineCrossing() {
        val dep = Instant.parse("2026-10-18T00:00:00Z")
        val arr = Instant.parse("2026-10-18T10:00:00Z")
        val nrt = Airport("NRT", "Tokyo Narita", GeoPoint(35.7647, 140.3863), "Asia/Tokyo")
        val hnl = Airport("HNL", "Honolulu", GeoPoint(21.3187, -157.9225), "Pacific/Honolulu")
        val plan = FlightPlan("NH182", nrt, hnl, dep, arr)

        // Midway Island is right near the great circle path across the date line
        val midway = City("mid", "Midway", "US", GeoPoint(28.21, -177.37), "Pacific/Midway", 50)
        val london = City("lon", "London", "UK", GeoPoint(51.5074, -0.1278), "Europe/London", 9000000)

        val result = GreatCircle.corridorCities(plan, listOf(london, midway), widthKm = 300.0)
        assertTrue(result.contains(midway))
        assertFalse(result.contains(london))
    }

    @Test
    fun testCorridorCitiesCoincidentEndpoints() {
        val dep = Instant.parse("2026-10-18T10:00:00Z")
        val arr = Instant.parse("2026-10-18T11:00:00Z")
        val plan = FlightPlan("CIR1", originJFK, originJFK.copy(name = "JFK Clone"), dep, arr)

        val nearCity = City("nyc", "New York", "USA", GeoPoint(40.7128, -74.0060), "America/New_York", 8400000)
        val farCity = City("lon", "London", "UK", GeoPoint(51.5074, -0.1278), "Europe/London", 9000000)

        val result = GreatCircle.corridorCities(plan, listOf(farCity, nearCity), widthKm = 100.0)
        assertTrue(result.contains(nearCity))
        assertFalse(result.contains(farCity))
    }

    @Test
    fun testRelativeBearingDescription() {
        val plane = GeoPoint(0.0, 0.0)
        val planeHeadingEast = 90.0

        val targetAhead = GeoPoint(0.0, 5.0) // East -> ahead
        val aheadDesc = GreatCircle.relativeBearingDescription(plane, planeHeadingEast, targetAhead)
        assertTrue(aheadDesc.contains("Dead ahead"))

        val targetRight = GeoPoint(-5.0, 0.0) // South -> 90 deg right
        val rightDesc = GreatCircle.relativeBearingDescription(plane, planeHeadingEast, targetRight)
        assertTrue(rightDesc.contains("Off right wing") && rightDesc.contains("3 o'clock"))

        val targetLeft = GeoPoint(5.0, 0.0) // North -> 90 deg left
        val leftDesc = GreatCircle.relativeBearingDescription(plane, planeHeadingEast, targetLeft)
        assertTrue(leftDesc.contains("Off left wing") && leftDesc.contains("9 o'clock"))

        val targetBehind = GeoPoint(0.0, -5.0) // West -> directly behind
        val behindDesc = GreatCircle.relativeBearingDescription(plane, planeHeadingEast, targetBehind)
        assertTrue(behindDesc.contains("Directly behind"))
    }
}
