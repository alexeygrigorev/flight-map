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

        assertFalse("Advisory corridor must not claim an observed trajectory", enriched.isRealTrajectory)
        assertEquals("ESTIMATED_CORRIDOR", enriched.routeSource)
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

    @Test
    fun testSimplifiedResolveFromFlightNumberAndDateOnly() {
        val airports = listOf(hel, pvg)
        val plan = TrajectoryCatalog.resolvePlan(
            flightNumber = "ay 087",
            dateStr = "2026-10-20",
            airports = airports
        )

        assertEquals("AY87", plan.number)
        assertEquals("HEL", plan.origin.iata)
        assertEquals("PVG", plan.destination.iata)
        // 2026-10-20T01:10 Europe/Helsinki (UTC+3) = 2026-10-19T22:10:00Z
        assertEquals(Instant.parse("2026-10-19T22:10:00Z"), plan.departure)
        // 2026-10-20T18:25 Asia/Shanghai (UTC+8) = 2026-10-20T10:25:00Z
        assertEquals(Instant.parse("2026-10-20T10:25:00Z"), plan.arrival)
        assertFalse(plan.isRealTrajectory)
        assertEquals(36, plan.effectiveWaypoints.size)
        assertTrue(plan.effectiveRouteLabel.contains("Southern Eurasian Corridor"))
    }

    @Test
    fun testSimplifiedResolveWithIcaoCallsignAndCustomDepartureTimeOnly() {
        val airports = listOf(hel, pvg)
        // User enters ICAO callsign FIN87, date 2026-10-20, and only HH:MM departure "16:40" (no arrival time or ISO string)
        val plan = TrajectoryCatalog.resolvePlan(
            flightNumber = "FIN87",
            dateStr = "2026-10-20",
            airports = airports,
            departureOverride = "16:40"
        )

        assertEquals("AY87", plan.number)
        assertEquals("HEL", plan.origin.iata)
        assertEquals("PVG", plan.destination.iata)
        // 2026-10-20T16:40 Europe/Helsinki (UTC+3) = 2026-10-20T13:40:00Z
        assertEquals(Instant.parse("2026-10-20T13:40:00Z"), plan.departure)
        // Block duration is 12h 15m -> 2026-10-21T01:55:00Z (09:55 next day in Shanghai)
        assertEquals(Instant.parse("2026-10-21T01:55:00Z"), plan.arrival)
        assertFalse(plan.isRealTrajectory)
    }

    @Test
    fun testBuildScheduleForCustomRouteAutoCalculatesArrival() {
        val fra = Airport("FRA", "Frankfurt", GeoPoint(50.0379, 8.5622), "Europe/Berlin")
        val hnd = Airport("HND", "Tokyo Haneda", GeoPoint(35.5494, 139.7798), "Asia/Tokyo")
        val schedule = TrajectoryCatalog.buildScheduleForRoute(
            flightNumber = "LH716",
            origin = fra,
            destination = hnd,
            date = LocalDate.of(2026, 10, 20),
            departureTime = java.time.LocalTime.of(14, 0)
        )

        assertEquals("LH716", schedule.flightNumber)
        assertEquals("FRA", schedule.originIata)
        assertEquals("HND", schedule.destinationIata)
        assertEquals("2026-10-20T14:00", schedule.departureLocalIso)
        assertTrue("Arrival should be on the next day in Tokyo", schedule.arrivalLocalIso.startsWith("2026-10-21T"))
    }

    @Test
    fun testTripFlightsCatalogSchedulesAndTrajectories() {
        val dyg = Airport("DYG", "Zhangjiajie Hehua", GeoPoint(29.1028, 110.4431), "Asia/Shanghai")
        val can = Airport("CAN", "Guangzhou Baiyun", GeoPoint(23.3924, 113.2988), "Asia/Shanghai")
        val hnd = Airport("HND", "Tokyo Haneda", GeoPoint(35.5494, 139.7798), "Asia/Tokyo")
        val nrt = Airport("NRT", "Tokyo Narita", GeoPoint(35.7647, 140.3864), "Asia/Tokyo")
        val auh = Airport("AUH", "Abu Dhabi Zayed", GeoPoint(24.4330, 54.6511), "Asia/Dubai")
        val ber = Airport("BER", "Berlin Brandenburg", GeoPoint(52.3667, 13.5033), "Europe/Berlin")
        val airports = listOf(hel, pvg, dyg, can, hnd, nrt, auh, ber)

        // 1. MU689 PVG -> DYG 2026-10-20 10:00 -> 12:10 Asia/Shanghai
        val mu689 = TrajectoryCatalog.resolvePlan("MU689", "2026-10-20", airports)
        assertEquals("MU689", mu689.number)
        assertEquals("PVG", mu689.origin.iata)
        assertEquals("DYG", mu689.destination.iata)
        assertEquals(Instant.parse("2026-10-20T02:00:00Z"), mu689.departure)
        assertEquals(Instant.parse("2026-10-20T04:10:00Z"), mu689.arrival)
        assertFalse(mu689.isRealTrajectory)

        // 2. CZ3382 DYG -> CAN 2026-10-23 21:40 -> 23:25 Asia/Shanghai
        val cz3382 = TrajectoryCatalog.resolvePlan("CZ3382", "2026-10-23", airports)
        assertEquals("CZ3382", cz3382.number)
        assertEquals("DYG", cz3382.origin.iata)
        assertEquals("CAN", cz3382.destination.iata)
        assertEquals(Instant.parse("2026-10-23T13:40:00Z"), cz3382.departure)
        assertEquals(Instant.parse("2026-10-23T15:25:00Z"), cz3382.arrival)
        assertFalse(cz3382.isRealTrajectory)

        // 3. CZ3085 CAN -> HND 2026-10-25 15:10 CST -> 20:00 JST
        val cz3085 = TrajectoryCatalog.resolvePlan("CZ3085", "2026-10-25", airports)
        assertEquals("CZ3085", cz3085.number)
        assertEquals("CAN", cz3085.origin.iata)
        assertEquals("HND", cz3085.destination.iata)
        assertEquals(Instant.parse("2026-10-25T07:10:00Z"), cz3085.departure)
        assertEquals(Instant.parse("2026-10-25T11:00:00Z"), cz3085.arrival)
        assertFalse(cz3085.isRealTrajectory)

        // 4. EY801 NRT -> AUH 2026-10-30 17:20 JST -> 2026-10-31 00:40 GST
        val ey801 = TrajectoryCatalog.resolvePlan("EY801", "2026-10-30", airports)
        assertEquals("EY801", ey801.number)
        assertEquals("NRT", ey801.origin.iata)
        assertEquals("AUH", ey801.destination.iata)
        assertEquals(Instant.parse("2026-10-30T08:20:00Z"), ey801.departure)
        assertEquals(Instant.parse("2026-10-30T20:40:00Z"), ey801.arrival)
        assertFalse(ey801.isRealTrajectory)

        // 5. DE2553 & EY1042 AUH -> BER 2026-10-31 02:20 GST -> 06:20 CET
        val de2553 = TrajectoryCatalog.resolvePlan("DE2553", "2026-10-31", airports)
        assertEquals("DE2553", de2553.number)
        assertEquals("AUH", de2553.origin.iata)
        assertEquals("BER", de2553.destination.iata)
        assertEquals(Instant.parse("2026-10-30T22:20:00Z"), de2553.departure)
        assertEquals(Instant.parse("2026-10-31T05:20:00Z"), de2553.arrival)
        assertFalse(de2553.isRealTrajectory)

        val ey1042 = TrajectoryCatalog.resolvePlan("EY1042", "2026-10-31", airports)
        assertEquals("EY1042", ey1042.number)
        assertEquals("AUH", ey1042.origin.iata)
        assertEquals("BER", ey1042.destination.iata)
        assertEquals(de2553.departure, ey1042.departure)
        assertEquals(de2553.arrival, ey1042.arrival)
    }

    @Test
    fun testGlobeProjectionScaleInvarianceAndPortraitSupport() {
        // Verify that a low-res raster projection (520x234) and high-res canvas projection (2400x1080)
        // have identical normalized coordinates, eliminating overlay misalignment ("поле полезло")
        val lowRes = GlobeProjection(width = 520f, height = 234f, centerLat = 35.0, centerLon = 115.0, zoom = 1.2)
        val highRes = GlobeProjection(width = 2400f, height = 1080f, centerLat = 35.0, centerLon = 115.0, zoom = 1.2)

        assertEquals(lowRes.horizonTopY / lowRes.height, highRes.horizonTopY / highRes.height, 1e-4f)
        assertEquals(lowRes.radius / lowRes.height, highRes.radius / highRes.height, 1e-4f)

        val shanghai = GeoPoint(31.2304, 121.4737)
        val pLow = lowRes.project(shanghai)
        val pHigh = highRes.project(shanghai)
        assertEquals(pLow.x / lowRes.width, pHigh.x / highRes.width, 1e-4f)
        assertEquals(pLow.y / lowRes.height, pHigh.y / highRes.height, 1e-4f)

        // Verify portrait orientation geometry frames the globe properly
        val portrait = GlobeProjection(width = 1080f, height = 2400f, centerLat = 35.0, centerLon = 115.0, zoom = 1.0)
        assertTrue(portrait.isPortrait)
        val pPortrait = portrait.project(GeoPoint(35.0, 115.0))
        assertTrue(pPortrait.visible)
        assertTrue(pPortrait.y in portrait.horizonTopY..portrait.height)
    }

    @Test
    fun testWikidataCityCardAndApproximateWeatherSeries() {
        val zhangjiajie = City("dyg", "Zhangjiajie", "CN", GeoPoint(29.1170, 110.4792), "Asia/Shanghai", 1_517_000)
        val card = IfeCatalog.wikidataCard(zhangjiajie)
        assertEquals("Zhangjiajie", card.name)
        assertEquals("China", card.country)
        assertEquals("1,517,000", card.populationLabel)
        assertTrue(card.description.contains("Hunan") || card.description.contains("Wulingyuan"))
        assertTrue(card.summaryFact.contains("Wulingyuan") || card.summaryFact.contains("quartz"))

        val series = SolarCalculator.estimateHourlySeries(zhangjiajie.point, dep, arr)
        assertTrue("Approximate hourly series must cover flight duration", series.size >= 12)
        assertTrue(series.first().time <= dep)
        assertTrue(series.last().time >= arr)
    }

    @Test
    fun testHighZoomGlobeProjectionFillsViewportAndFlattensTilt() {
        val overview = GlobeProjection(width = 1080f, height = 2400f, centerLat = 30.5, centerLon = 114.3, zoom = 1.0)
        val zoomed = GlobeProjection(width = 1080f, height = 2400f, centerLat = 30.5, centerLon = 114.3, zoom = 4.5)
        val deepZoom = GlobeProjection(width = 1080f, height = 2400f, centerLat = 30.5, centerLon = 114.3, zoom = 10.0)

        // Camera tilt must flatten smoothly as zoom increases so zoomed-in map isn't foreshortened
        assertTrue("Zoomed pitch (${zoomed.pitchRad}) must be flatter than overview (${overview.pitchRad})", zoomed.pitchRad < overview.pitchRad * 0.2)
        assertTrue("Deep zoom pitch (${deepZoom.pitchRad}) must be near-nadir (< 0.02 rad)", deepZoom.pitchRad < 0.02)

        // Focus point (30.5, 114.3) must stay anchored at (sphereCenterX, focusScreenY) across all zoom levels
        for (proj in listOf(overview, zoomed, deepZoom)) {
            val p = proj.project(GeoPoint(30.5, 114.3))
            assertTrue(p.visible)
            assertEquals(proj.sphereCenterX, p.x, 1.5f)
            assertEquals(proj.focusScreenY, p.y, 1.5f)

            val inv = proj.unproject(p.x, p.y)
            assertNotNull(inv)
            assertEquals(30.5, inv!!.first, 0.03)
            assertEquals(114.3, inv.second, 0.03)
        }

        // At zoom = 4.5, the top of the screen (y = 140f) is covered by the Earth, not outer space
        assertNotNull("Upper viewport should project onto Earth at zoom 4.5", zoomed.unproject(540f, 140f))
    }

    @Test
    fun testSmoothWaypointsAndHighZoomLabelPlanner() {
        val dyg = Airport("DYG", "Zhangjiajie Hehua", GeoPoint(29.1028, 110.4431), "Asia/Shanghai")
        val mu689 = TrajectoryCatalog.resolvePlan("MU689", "2026-10-20", listOf(hel, pvg, dyg))
        val rawWps = mu689.effectiveWaypoints
        val smoothed = GreatCircle.smoothWaypoints(rawWps)
        assertTrue("Smoothed trajectory should expand interior corners into smooth arcs", smoothed.size > rawWps.size * 3)
        assertEquals(rawWps.first(), smoothed.first())
        assertEquals(rawWps.last(), smoothed.last())

        val zoomedProj = GlobeProjection(width = 1080f, height = 2400f, centerLat = 30.2, centerLon = 114.5, zoom = 4.2)
        val midNow = Instant.parse("2026-10-20T03:00:00Z")
        val progress = FlightTiming.progress(mu689, midNow)
        val regionalCities = listOf(
            City("wuh", "Wuhan", "CN", GeoPoint(30.5928, 114.3055), "Asia/Shanghai", 11_000_000),
            City("ezh", "Ezhou", "CN", GeoPoint(30.3908, 114.8950), "Asia/Shanghai", 1_079_000),
            City("hgn", "Huanggang", "CN", GeoPoint(30.4539, 114.8722), "Asia/Shanghai", 360_000),
            City("xnt", "Xiantao", "CN", GeoPoint(30.3625, 113.4539), "Asia/Shanghai", 1_100_000),
            City("jzn", "Jingzhou", "CN", GeoPoint(30.3340, 112.2390), "Asia/Shanghai", 5_230_000),
            City("chn", "Changde", "CN", GeoPoint(29.0317, 111.6985), "Asia/Shanghai", 5_270_000)
        )
        val countriesWithLakes = listOf(
            CountryBoundary("China", "CN", GeoPoint(35.0, 105.0), 1_400_000_000L),
            CountryBoundary("Lake Dongting", "LAKE", GeoPoint(29.3, 112.9), 0L),
            CountryBoundary("Yangtze", "RIVER", GeoPoint(30.5, 114.3), 0L)
        )

        val layout = GlobeLabelPlanner.plan(
            proj = zoomedProj,
            plan = mu689,
            progress = progress,
            peaks = IfeCatalog.mountainPeaks,
            pins = IfeCatalog.landmarkPins,
            cities = regionalCities,
            countries = countriesWithLakes
        )

        assertTrue("High-zoom planner should place multiple regional cities", layout.cities.size >= 3)
        assertFalse("LAKE entries must not be placed as sovereign country labels", layout.countries.any { it.name == "Lake Dongting" })
        assertFalse("RIVER entries must not be placed as sovereign country labels", layout.countries.any { it.name == "Yangtze" })
    }

    @Test
    fun testGeographicNorthSouthEastWestOrderingOnScreen() {
        // Verify North-Up projection maps northern cities (Harbin, Beijing, Seoul) strictly above (smaller screen Y)
        // central cities (Wuhan, Shanghai) and southern cities (Hanoi), and eastern cities strictly to the right (larger screen X).
        val harbin = GeoPoint(45.75, 126.65)
        val beijing = GeoPoint(39.9042, 116.4074)
        val seoul = GeoPoint(37.5665, 126.9780)
        val shanghai = GeoPoint(31.2304, 121.4737)
        val wuhan = GeoPoint(30.5928, 114.3055)
        val hanoi = GeoPoint(21.0285, 105.8542)

        for (zoom in listOf(1.0, 2.2, 4.5)) {
            val proj = GlobeProjection(
                width = 1080f,
                height = 2400f,
                centerLat = 30.5928,
                centerLon = 114.3055,
                zoom = zoom,
                headingRotationDeg = 0.0
            )
            val pHarbin = proj.project(harbin)
            val pBeijing = proj.project(beijing)
            val pSeoul = proj.project(seoul)
            val pShanghai = proj.project(shanghai)
            val pWuhan = proj.project(wuhan)
            val pHanoi = proj.project(hanoi)

            // North -> South must have strictly increasing screen Y (top of screen = smaller Y = North)
            assertTrue("Harbin (${pHarbin.y}) must be north of (above) Beijing (${pBeijing.y}) at zoom=$zoom", pHarbin.y < pBeijing.y)
            assertTrue("Beijing (${pBeijing.y}) must be north of (above) Wuhan (${pWuhan.y}) at zoom=$zoom", pBeijing.y < pWuhan.y)
            assertTrue("Seoul (${pSeoul.y}) must be north of (above) Shanghai (${pShanghai.y}) at zoom=$zoom", pSeoul.y < pShanghai.y)
            assertTrue("Wuhan (${pWuhan.y}) must be north of (above) Hanoi (${pHanoi.y}) at zoom=$zoom", pWuhan.y < pHanoi.y)

            // West -> East must have strictly increasing screen X (left = West, right = East)
            assertTrue("Hanoi (${pHanoi.x}) must be west of (left of) Wuhan (${pWuhan.x}) at zoom=$zoom", pHanoi.x < pWuhan.x)
            assertTrue("Wuhan (${pWuhan.x}) must be west of (left of) Shanghai (${pShanghai.x}) at zoom=$zoom", pWuhan.x < pShanghai.x)
            assertTrue("Beijing (${pBeijing.x}) must be west of (left of) Seoul (${pSeoul.x}) at zoom=$zoom", pBeijing.x < pSeoul.x)
        }
    }
}


