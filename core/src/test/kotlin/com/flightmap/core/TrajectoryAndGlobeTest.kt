package com.flightmap.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TrajectoryAndGlobeTest {

    private val hel = Airport("HEL", "Helsinki Vantaa", GeoPoint(60.3172, 24.9633), "Europe/Helsinki")
    private val pvg = Airport("PVG", "Shanghai Pudong", GeoPoint(31.1434, 121.8052), "Asia/Shanghai")
    private val dep = Instant.parse("2026-10-17T22:10:00Z")
    private val arr = Instant.parse("2026-10-18T10:25:00Z")

    @Test
    fun testAy87FiledSouthernCorridorTrajectory() {
        val rawPlan = FlightPlan("AY87", hel, pvg, dep, arr)
        val enriched = TrajectoryCatalog.enrichPlan(rawPlan)

        assertTrue("AY87 must resolve to a real filed trajectory", enriched.isRealTrajectory)
        assertEquals("FILED_ATS_ADSB", enriched.routeSource)
        assertTrue(enriched.effectiveRouteLabel.contains("Southern Eurasian Corridor"))
        assertTrue("AY87 should have >= 25 filed waypoints", enriched.effectiveWaypoints.size >= 25)

        // Great circle distance is ~7394 km; real Southern Corridor is ~9100-9600 km
        val realDistKm = GreatCircle.routeDistanceKm(enriched.effectiveWaypoints)
        val gcDistKm = GreatCircle.distanceKm(hel.point, pvg.point)
        assertTrue("Real trajectory ($realDistKm km) must be longer than naive great circle ($gcDistKm km)", realDistKm > gcDistKm + 1200.0)

        // Midpoint of AY87 must pass through the Caucasus / Caspian / Kazakhstan corridor (lat 40..46),
        // NOT northern Siberia (lat 56..62)
        val midProgress = FlightTiming.progress(enriched, dep.plusSeconds((arr.epochSecond - dep.epochSecond) / 2))
        assertTrue("Midpoint lat (${midProgress.point.lat}) should be in southern corridor [38..47]", midProgress.point.lat in 38.0..47.0)
        assertTrue("Midpoint lon (${midProgress.point.lon}) should be in Central Asia [50..88]", midProgress.point.lon in 50.0..88.0)
    }

    @Test
    fun testFallbackGreatCircleExplicitlyLabeledAsEstimate() {
        val syd = Airport("SYD", "Sydney", GeoPoint(-33.9461, 151.1772), "Australia/Sydney")
        val akl = Airport("AKL", "Auckland", GeoPoint(-37.0082, 174.7850), "Pacific/Auckland")
        val rawPlan = FlightPlan("QF141", syd, akl, dep, arr)
        val enriched = TrajectoryCatalog.enrichPlan(rawPlan)

        assertFalse("Unfiled arbitrary pair must not claim real trajectory", enriched.isRealTrajectory)
        assertEquals("GREAT_CIRCLE_ESTIMATE", enriched.routeSource)
        assertEquals("Great-circle estimate", enriched.effectiveRouteLabel)
        assertEquals(2, enriched.effectiveWaypoints.size)
    }

    @Test
    fun testCorridorCitiesFollowRealFiledPathNotSiberia() {
        val enriched = TrajectoryCatalog.enrichPlan(FlightPlan("AY87", hel, pvg, dep, arr))

        val tallinn = City("tll", "Tallinn", "EE", GeoPoint(59.4370, 24.7536), "Europe/Tallinn", 430000)
        val warsaw = City("waw", "Warsaw", "PL", GeoPoint(52.2297, 21.0122), "Europe/Warsaw", 1790000)
        val bucharest = City("otp", "Bucharest", "RO", GeoPoint(44.4268, 26.1025), "Europe/Bucharest", 1830000)
        val tbilisi = City("tbs", "Tbilisi", "GE", GeoPoint(41.7151, 44.8271), "Asia/Tbilisi", 1110000)
        val baku = City("gyd", "Baku", "AZ", GeoPoint(40.4093, 49.8671), "Asia/Baku", 2300000)
        val almaty = City("ala", "Almaty", "KZ", GeoPoint(43.2220, 76.8512), "Asia/Almaty", 1970000)
        val urumqi = City("urc", "Ürümqi", "CN", GeoPoint(43.8256, 87.6168), "Asia/Urumqi", 3500000)
        val xian = City("xiy", "Xi'an", "CN", GeoPoint(34.3416, 108.9398), "Asia/Shanghai", 12000000)
        val nanjing = City("nkg", "Nanjing", "CN", GeoPoint(32.0603, 118.7969), "Asia/Shanghai", 8500000)
        val khantyMansiysk = City("khm", "Khanty-Mansiysk", "RU", GeoPoint(61.0042, 69.0019), "Asia/Yekaterinburg", 100000)

        val all = listOf(nanjing, khantyMansiysk, urumqi, tallinn, baku, warsaw, xian, bucharest, tbilisi, almaty)
        val corridor = GreatCircle.corridorCities(enriched, all, widthKm = 180.0)

        assertFalse("Siberian great-circle city Khanty-Mansiysk must NOT be in AY87 Southern Corridor", corridor.contains(khantyMansiysk))
        assertEquals(
            listOf(tallinn, warsaw, bucharest, tbilisi, baku, almaty, urumqi, xian, nanjing),
            corridor
        )
    }

    @Test
    fun testScheduleLookupAndOfflineFacts() {
        val resolved = TrajectoryCatalog.lookupSchedule("ay 87", LocalDate.of(2026, 10, 18))
        assertNotNull(resolved)
        assertEquals("HEL", resolved!!.originIata)
        assertEquals("PVG", resolved.destinationIata)
        assertEquals("2026-10-18T01:10", resolved.departureLocalIso)
        assertEquals("2026-10-18T18:25", resolved.arrivalLocalIso)

        val urumqi = City("urc", "Ürümqi", "CN", GeoPoint(43.8256, 87.6168), "Asia/Urumqi", 3500000)
        val fact = IfeCatalog.cityFact(urumqi)
        assertTrue(fact.contains("Tian Shan") || fact.contains("Xinjiang"))

        val peakNames = IfeCatalog.mountainPeaks.map { it.name }
        assertTrue(peakNames.containsAll(listOf("Mount Narodnaya", "Kebnekaise", "Mont Blanc", "Mount Ararat", "K2")))
    }

    @Test
    fun testSolarAndRouteTimeZones() {
        val enriched = TrajectoryCatalog.enrichPlan(FlightPlan("AY87", hel, pvg, dep, arr))
        val cities = listOf(
            City("waw", "Warsaw", "PL", GeoPoint(52.2297, 21.0122), "Europe/Warsaw", 1790000),
            City("tbs", "Tbilisi", "GE", GeoPoint(41.7151, 44.8271), "Asia/Tbilisi", 1110000),
            City("ala", "Almaty", "KZ", GeoPoint(43.2220, 76.8512), "Asia/Almaty", 1970000),
            City("xiy", "Xi'an", "CN", GeoPoint(34.3416, 108.9398), "Asia/Shanghai", 12000000)
        )
        val midNow = Instant.parse("2026-10-18T05:00:00Z")
        val progress = FlightTiming.progress(enriched, midNow)
        val bands = SolarCalculator.routeTimeZones(enriched, cities, progress.point, midNow)

        assertTrue("Should produce multiple distinct timezone bands", bands.size >= 4)
        assertEquals(1, bands.count { it.isCurrent })
        val subsolar = SolarCalculator.subsolarPoint(midNow)
        assertTrue(subsolar.lat in -24.0..24.0)
        assertTrue(subsolar.lon in -180.0..180.0)
    }

    @Test
    fun testGlobeProjectionAndInverse() {
        val proj = GlobeProjection(
            width = 1920f,
            height = 1080f,
            centerLat = 48.0,
            centerLon = 52.0,
            zoom = 1.0
        )

        val centerProj = proj.project(GeoPoint(48.0, 52.0))
        assertTrue(centerProj.visible)
        assertEquals(proj.sphereCenterX, centerProj.x, 1.5f)
        assertEquals(proj.focusScreenY, centerProj.y, 1.5f)

        val unproj = proj.unproject(centerProj.x, centerProj.y)
        assertNotNull(unproj)
        assertEquals(48.0, unproj!!.first, 0.05)
        assertEquals(52.0, unproj.second, 0.05)

        // Point above the curved top horizon (y = 10) must be in outer space (null)
        assertNull(proj.unproject(960f, 10f))
    }

    @Test
    fun testGlobeLabelPlannerDecluttersAndPreventsOverlaps() {
        val enriched = TrajectoryCatalog.enrichPlan(FlightPlan("AY87", hel, pvg, dep, arr))
        val midNow = Instant.parse("2026-10-18T02:00:00Z")
        val progress = FlightTiming.progress(enriched, midNow)
        val proj = GlobeProjection(
            width = 1920f,
            height = 1080f,
            centerLat = 46.5,
            centerLon = 42.0,
            zoom = 1.0
        )

        val candidateCities = listOf(
            City("hel", "Helsinki", "FI", GeoPoint(60.1699, 24.9384), "Europe/Helsinki", 650_000),
            City("sto", "Stockholm", "SE", GeoPoint(59.3293, 18.0686), "Europe/Stockholm", 975_000),
            City("waw", "Warsaw", "PL", GeoPoint(52.2297, 21.0122), "Europe/Warsaw", 1_790_000),
            City("min", "Minsk", "BY", GeoPoint(53.9006, 27.5590), "Europe/Minsk", 2_000_000),
            City("kyi", "Kyiv", "UA", GeoPoint(50.4501, 30.5234), "Europe/Kyiv", 2_950_000),
            City("otp", "Bucharest", "RO", GeoPoint(44.4268, 26.1025), "Europe/Bucharest", 1_830_000),
            City("tbs", "Tbilisi", "GE", GeoPoint(41.7151, 44.8271), "Asia/Tbilisi", 1_110_000),
            City("gyd", "Baku", "AZ", GeoPoint(40.4093, 49.8671), "Asia/Baku", 2_300_000),
            City("ala", "Almaty", "KZ", GeoPoint(43.2220, 76.8512), "Asia/Almaty", 1_970_000),
            City("urc", "Ürümqi", "CN", GeoPoint(43.8256, 87.6168), "Asia/Urumqi", 3_500_000),
            City("stropkov", "Stropkov", "SK", GeoPoint(49.2022, 21.6522), "Europe/Bratislava", 10_000),
            City("jibou", "Jibou", "RO", GeoPoint(47.2583, 23.2583), "Europe/Bucharest", 10_400),
            City("ayancik", "Ayancık", "TR", GeoPoint(41.9447, 34.5861), "Europe/Istanbul", 23_000)
        )

        val candidateCountries = listOf(
            CountryBoundary("Finland", "FI", GeoPoint(61.9, 25.7), 5_500_000L),
            CountryBoundary("Sweden", "SE", GeoPoint(60.1, 18.6), 10_400_000L),
            CountryBoundary("Poland", "PL", GeoPoint(52.2, 21.0), 38_000_000L),
            CountryBoundary("Belarus", "BY", GeoPoint(53.9, 27.6), 9_400_000L),
            CountryBoundary("Ukraine", "UA", GeoPoint(50.4, 30.5), 41_000_000L),
            CountryBoundary("Georgia", "GE", GeoPoint(41.7, 44.8), 3_700_000L),
            CountryBoundary("Kazakhstan", "KZ", GeoPoint(48.0, 66.9), 19_000_000L)
        )

        val layout = GlobeLabelPlanner.plan(
            proj = proj,
            plan = enriched,
            progress = progress,
            peaks = IfeCatalog.mountainPeaks,
            pins = IfeCatalog.landmarkPins,
            cities = candidateCities,
            countries = candidateCountries,
            topSafePx = 68f,
            bottomSafePx = 88f
        )

        val placedCityNames = layout.cities.map { it.city.name }.toSet()
        assertFalse("Small town Stropkov must not appear at overview zoom", "Stropkov" in placedCityNames)
        assertFalse("Small town Jibou must not appear at overview zoom", "Jibou" in placedCityNames)
        assertFalse("Small town Ayancık must not appear at overview zoom", "Ayancık" in placedCityNames)
        assertFalse("Duplicate Helsinki city dot must be suppressed near HEL endpoint at overview zoom", "Helsinki" in placedCityNames)

        // Verify strict upper bounds at overview zoom
        assertTrue("Overview pins should be <= 6", layout.pins.size <= 6)
        assertTrue("Overview peaks should be <= 5", layout.peaks.size <= 5)
        assertTrue("Overview cities should be <= 14", layout.cities.size <= 14)
        assertTrue("Overview countries should be <= 9", layout.countries.size <= 9)

        // Verify zero pairwise intersections among all placed label/marker boxes
        val boxes = layout.occupiedBoxes
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                assertFalse(
                    "Occupied boxes $i (${boxes[i]}) and $j (${boxes[j]}) must not intersect",
                    boxes[i].intersects(boxes[j])
                )
            }
        }
    }
}

