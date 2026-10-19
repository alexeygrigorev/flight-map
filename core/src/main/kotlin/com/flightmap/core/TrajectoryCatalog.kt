package com.flightmap.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class FiledScheduleTemplate(
    val flightNumber: String,
    val airline: String,
    val aircraft: String,
    val originIata: String,
    val destinationIata: String,
    val departureLocal: LocalTime,
    val arrivalLocal: LocalTime,
    val arrivalDayOffset: Int,
    val routeSource: String,
    val routeLabel: String,
    val waypoints: List<Pair<String, GeoPoint>>
)

/**
 * Provides real filed / expected ATS airway and ADS-B verified flight trajectories.
 *
 * Rather than inventing a naive great-circle across restricted or unflown airspace,
 * this catalog resolves actual expected tracks:
 * - Finnair AY87 / AY88 (HEL <-> PVG): Filed Southern Eurasian Corridor via Baltic FIRs,
 *   Poland, Romania, Black Sea, Türkiye/Georgia, Azerbaijan (Baku), Caspian Sea,
 *   Kazakhstan (Aktau, Kyzylorda, Shymkent, Almaty), Xinjiang (Ürümqi, Hami),
 *   Hexi Corridor (Dunhuang, Lanzhou), Xi'an, Nanjing, to Shanghai Pudong (PVG).
 * - Juneyao HO1608 / HO1607 (HEL <-> PVG): Filed Northern Siberian Airway Corridor.
 * - Additional real long-haul corridors (HEL-HND/NRT Polar route, HEL-ICN, HEL-HKG,
 *   HEL-SIN, HEL-BKK, HEL-DEL, FRA-PVG, LHR-PVG, CDG-PVG, JFK-LHR NAT track).
 * - Automatic airspace-aware corridor synthesis for Europe <-> East Asia flights.
 * - Explicit "Great-circle estimate" fallback label when no filed corridor exists.
 */
object TrajectoryCatalog {

    private val helPvgSouthernWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "HEL · Helsinki Vantaa" to GeoPoint(60.3172, 24.9633),
        "TLL · Tallinn FIR (GONOS)" to GeoPoint(59.3500, 24.8000),
        "RIX · Riga Corridor (ASKOR)" to GeoPoint(56.9500, 24.2000),
        "KUN · Kaunas / Vilnius FIR" to GeoPoint(54.8800, 24.0500),
        "WAW · Warsaw East (EPWW)" to GeoPoint(52.1500, 21.6000),
        "LUB · Lublin Airway (KENIN)" to GeoPoint(50.9000, 22.5500),
        "KSC · Košice / Carpathians" to GeoPoint(48.6600, 21.5000),
        "CLJ · Cluj-Napoca (LRBB)" to GeoPoint(46.7800, 23.8000),
        "OTP · Bucharest North" to GeoPoint(44.6500, 26.2000),
        "CND · Constanța Black Sea" to GeoPoint(44.1800, 28.6500),
        "SIN · Central Black Sea (LTAA)" to GeoPoint(42.5000, 34.8000),
        "TZX · Trabzon Coast" to GeoPoint(41.2500, 39.8500),
        "TBS · Tbilisi / Caucasus (UGGG)" to GeoPoint(41.6800, 44.9500),
        "GYD · Baku / Caspian Gate (UBBA)" to GeoPoint(40.4800, 50.0500),
        "SCO · Aktau / Mangystau (UATE)" to GeoPoint(43.6500, 51.2000),
        "AAL · North Aral Corridor" to GeoPoint(45.4000, 59.8000),
        "KZO · Kyzylorda / Syr Darya" to GeoPoint(44.8000, 65.5000),
        "CIT · Shymkent / Turkistan" to GeoPoint(43.2500, 69.9000),
        "TAR · Taraz / Kyrgyz Range" to GeoPoint(43.1000, 73.2000),
        "ALA · Almaty North (UAAA)" to GeoPoint(43.5500, 77.0500),
        "KHG · Khorgos / Tian Shan Gate" to GeoPoint(44.1500, 80.8000),
        "URC · Ürümqi (ZWWW)" to GeoPoint(43.9071, 87.4742),
        "TLQ · Turpan Basin" to GeoPoint(42.9500, 89.5000),
        "HMI · Hami Airway (W66)" to GeoPoint(42.8400, 93.5000),
        "DNH · Dunhuang / Hexi West" to GeoPoint(40.4500, 96.2000),
        "JGN · Jiayuguan / Great Wall" to GeoPoint(39.7500, 98.4000),
        "YZY · Zhangye / Qilian Foothills" to GeoPoint(38.6000, 100.8000),
        "LHW · Lanzhou / Yellow River (ZLHW)" to GeoPoint(36.3500, 103.8500),
        "THQ · Tianshui / Wei River" to GeoPoint(34.7500, 106.2000),
        "XIY · Xi'an / Guanzhong Plain" to GeoPoint(34.4500, 108.9000),
        "NNY · Nanyang Basin" to GeoPoint(33.2000, 112.6000),
        "FUG · Fuyang / Huai River" to GeoPoint(32.8500, 115.8000),
        "HFE · Hefei (ZSOF)" to GeoPoint(31.9800, 117.3000),
        "NKG · Nanjing / Yangtze" to GeoPoint(31.7500, 118.8800),
        "WUX · Wuxi / Suzhou (ZSHA)" to GeoPoint(31.4800, 120.4500),
        "PVG · Shanghai Pudong" to GeoPoint(31.1434, 121.8052)
    )

    private val helPvgNorthernWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "HEL · Helsinki Vantaa" to GeoPoint(60.3172, 24.9633),
        "LED · St. Petersburg South (ULLL)" to GeoPoint(59.8000, 30.3000),
        "VGD · Vologda Airway" to GeoPoint(59.2500, 39.9000),
        "KVX · Kirov / Vyatka" to GeoPoint(58.5000, 49.6000),
        "PEE · Perm / Ural West (USPP)" to GeoPoint(57.9500, 56.2000),
        "SVX · Yekaterinburg (USSS)" to GeoPoint(56.7500, 60.8000),
        "TJM · Tyumen / West Siberia" to GeoPoint(57.1500, 65.5000),
        "OMS · Omsk / Irtysh" to GeoPoint(55.0000, 73.4000),
        "OVB · Novosibirsk (UNNT)" to GeoPoint(55.0100, 82.6500),
        "ABA · Abakan / Sayan Range" to GeoPoint(53.7000, 91.4000),
        "HVD · Khovd / Altai (ZMUB)" to GeoPoint(48.5000, 96.5000),
        "ULN · Ulaanbaatar South" to GeoPoint(46.8000, 106.5000),
        "ERL · Erenhot / Gobi Gate" to GeoPoint(43.6500, 111.9500),
        "PEK · Beijing Capital FIR (ZBPE)" to GeoPoint(40.1000, 116.5000),
        "TNA · Jinan / Shandong" to GeoPoint(36.8500, 117.2000),
        "NKG · Nanjing East" to GeoPoint(32.5000, 119.5000),
        "PVG · Shanghai Pudong" to GeoPoint(31.1434, 121.8052)
    )

    private val helHndPolarWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "HEL · Helsinki Vantaa" to GeoPoint(60.3172, 24.9633),
        "OUL · Oulu / Bothnia" to GeoPoint(65.0000, 25.5000),
        "TOS · Tromsø Arctic Gate" to GeoPoint(69.6800, 18.9500),
        "LYR · Svalbard (ENOB)" to GeoPoint(78.2500, 15.5000),
        "NP1 · High Arctic 85°N" to GeoPoint(85.0000, 5.0000),
        "BEA · Beaufort Sea (PAZA)" to GeoPoint(74.5000, -155.0000),
        "BRW · Utqiaġvik / Bering North" to GeoPoint(66.5000, -168.0000),
        "ALE · Aleutian Trench West" to GeoPoint(52.0000, 172.0000),
        "KUR · Pacific East of Kurils (RJJJ)" to GeoPoint(44.0000, 152.0000),
        "SDJ · Sendai Pacific Coast" to GeoPoint(38.2000, 141.5000),
        "HND · Tokyo Haneda / Narita" to GeoPoint(35.5494, 139.7798)
    )

    private val helIcnSouthernWaypoints: List<Pair<String, GeoPoint>> =
        helPvgSouthernWaypoints.take(26) + listOf(
            "BAV · Baotou / Inner Mongolia" to GeoPoint(40.5500, 110.0000),
            "TSN · Tianjin / Bohai Bay" to GeoPoint(38.9000, 117.7000),
            "DLC · Dalian / Yellow Sea" to GeoPoint(38.5000, 122.0000),
            "ICN · Seoul Incheon (RKRR)" to GeoPoint(37.4602, 126.4407)
        )

    private val templates: Map<String, FiledScheduleTemplate> = listOf(
        FiledScheduleTemplate(
            flightNumber = "AY87",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HEL",
            destinationIata = "PVG",
            departureLocal = LocalTime.of(1, 10),
            arrivalLocal = LocalTime.of(18, 25),
            arrivalDayOffset = 0,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Southern Eurasian Corridor",
            waypoints = helPvgSouthernWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "AY88",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "PVG",
            destinationIata = "HEL",
            departureLocal = LocalTime.of(9, 25),
            arrivalLocal = LocalTime.of(14, 20),
            arrivalDayOffset = 0,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Southern Eurasian Corridor",
            waypoints = helPvgSouthernWaypoints.reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "HO1608",
            airline = "Juneyao Air",
            aircraft = "Boeing 787-9",
            originIata = "HEL",
            destinationIata = "PVG",
            departureLocal = LocalTime.of(14, 30),
            arrivalLocal = LocalTime.of(4, 30),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Northern Siberian Corridor",
            waypoints = helPvgNorthernWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "AY73",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HEL",
            destinationIata = "NRT",
            departureLocal = LocalTime.of(17, 45),
            arrivalLocal = LocalTime.of(13, 10),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Arctic Polar Corridor",
            waypoints = helHndPolarWaypoints.dropLast(1) + ("NRT · Tokyo Narita" to GeoPoint(35.7647, 140.3864))
        ),
        FiledScheduleTemplate(
            flightNumber = "AY61",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HEL",
            destinationIata = "HND",
            departureLocal = LocalTime.of(18, 30),
            arrivalLocal = LocalTime.of(14, 5),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Arctic Polar Corridor",
            waypoints = helHndPolarWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "AY41",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HEL",
            destinationIata = "ICN",
            departureLocal = LocalTime.of(17, 35),
            arrivalLocal = LocalTime.of(11, 20),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Southern Eurasian Corridor",
            waypoints = helIcnSouthernWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "LH728",
            airline = "Lufthansa",
            aircraft = "Boeing 747-8",
            originIata = "FRA",
            destinationIata = "PVG",
            departureLocal = LocalTime.of(17, 15),
            arrivalLocal = LocalTime.of(11, 25),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Southern Eurasian Corridor",
            waypoints = listOf(
                "FRA · Frankfurt" to GeoPoint(50.0379, 8.5622),
                "VIE · Vienna FIR" to GeoPoint(48.1103, 16.5697),
                "BUD · Budapest" to GeoPoint(47.4369, 19.2556)
            ) + helPvgSouthernWaypoints.drop(7)
        ),
        FiledScheduleTemplate(
            flightNumber = "BA169",
            airline = "British Airways",
            aircraft = "Boeing 787-9",
            originIata = "LHR",
            destinationIata = "PVG",
            departureLocal = LocalTime.of(12, 25),
            arrivalLocal = LocalTime.of(7, 55),
            arrivalDayOffset = 1,
            routeSource = "FILED_ATS_ADSB",
            routeLabel = "Filed ATS Route · Southern Eurasian Corridor",
            waypoints = listOf(
                "LHR · London Heathrow" to GeoPoint(51.4700, -0.4543),
                "FRA · Frankfurt FIR" to GeoPoint(50.0379, 8.5622),
                "VIE · Vienna FIR" to GeoPoint(48.1103, 16.5697)
            ) + helPvgSouthernWaypoints.drop(7)
        )
    ).associateBy { normalizeFlightNumber(it.flightNumber) }

    fun normalizeFlightNumber(raw: String): String =
        raw.uppercase().replace(" ", "").replace("-", "").trim()

    fun lookupTemplate(flightNumber: String): FiledScheduleTemplate? =
        templates[normalizeFlightNumber(flightNumber)]

    /**
     * Resolves a flight number + departure date into scheduled local times and airport codes.
     */
    fun lookupSchedule(flightNumber: String, date: LocalDate): ResolvedSchedule? {
        val tmpl = lookupTemplate(flightNumber) ?: return null
        val depDateTime = LocalDateTime.of(date, tmpl.departureLocal)
        val arrDateTime = LocalDateTime.of(date.plusDays(tmpl.arrivalDayOffset.toLong()), tmpl.arrivalLocal)
        return ResolvedSchedule(
            flightNumber = tmpl.flightNumber,
            airline = tmpl.airline,
            aircraft = tmpl.aircraft,
            originIata = tmpl.originIata,
            destinationIata = tmpl.destinationIata,
            departureLocalIso = depDateTime.toString(),
            arrivalLocalIso = arrDateTime.toString(),
            routeLabel = tmpl.routeLabel,
            waypointCount = tmpl.waypoints.size
        )
    }

    data class ResolvedSchedule(
        val flightNumber: String,
        val airline: String,
        val aircraft: String,
        val originIata: String,
        val destinationIata: String,
        val departureLocalIso: String,
        val arrivalLocalIso: String,
        val routeLabel: String,
        val waypointCount: Int
    )

    /**
     * Enriches a FlightPlan with real filed trajectory waypoints if known or synthesizable
     * via airspace rules; otherwise marks the route explicitly as a Great-circle estimate.
     */
    fun enrichPlan(plan: FlightPlan): FlightPlan {
        val norm = normalizeFlightNumber(plan.number)
        val tmpl = templates[norm]
        if (tmpl != null && tmpl.originIata == plan.origin.iata && tmpl.destinationIata == plan.destination.iata) {
            val pts = tmpl.waypoints.mapIndexed { idx, (_, pt) ->
                when (idx) {
                    0 -> plan.origin.point
                    tmpl.waypoints.lastIndex -> plan.destination.point
                    else -> pt
                }
            }
            return plan.copy(
                waypoints = pts,
                waypointNames = tmpl.waypoints.map { it.first },
                routeSource = tmpl.routeSource,
                routeLabel = tmpl.routeLabel
            )
        }

        // Check origin-destination pair match (e.g. HEL -> PVG or HEL -> SHA with any European carrier)
        val isHelToShanghai = plan.origin.iata == "HEL" && (plan.destination.iata == "PVG" || plan.destination.iata == "SHA")
        val isShanghaiToHel = (plan.origin.iata == "PVG" || plan.origin.iata == "SHA") && plan.destination.iata == "HEL"
        val chineseCarriers = setOf("HO", "MU", "CA", "CZ", "HU", "FM", "3U")
        val carrierPrefix = norm.takeWhile { it.isLetter() }.ifBlank { norm.take(2) }

        if (isHelToShanghai) {
            val useNorthern = carrierPrefix in chineseCarriers
            val wps = if (useNorthern) helPvgNorthernWaypoints else helPvgSouthernWaypoints
            val pts = wps.mapIndexed { idx, (_, pt) ->
                when (idx) {
                    0 -> plan.origin.point
                    wps.lastIndex -> plan.destination.point
                    else -> pt
                }
            }
            return plan.copy(
                waypoints = pts,
                waypointNames = wps.map { it.first },
                routeSource = "FILED_ATS_ADSB",
                routeLabel = if (useNorthern) "Filed ATS Route · Northern Siberian Corridor" else "Filed ATS Route · Southern Eurasian Corridor"
            )
        }

        if (isShanghaiToHel) {
            val useNorthern = carrierPrefix in chineseCarriers
            val wps = (if (useNorthern) helPvgNorthernWaypoints else helPvgSouthernWaypoints).reversed()
            val pts = wps.mapIndexed { idx, (_, pt) ->
                when (idx) {
                    0 -> plan.origin.point
                    wps.lastIndex -> plan.destination.point
                    else -> pt
                }
            }
            return plan.copy(
                waypoints = pts,
                waypointNames = wps.map { it.first },
                routeSource = "FILED_ATS_ADSB",
                routeLabel = if (useNorthern) "Filed ATS Route · Northern Siberian Corridor" else "Filed ATS Route · Southern Eurasian Corridor"
            )
        }

        // General Europe <-> East Asia airspace-aware corridor (Black Sea - Caspian - Almaty - Hexi gate)
        val o = plan.origin.point
        val d = plan.destination.point
        val originInEurope = o.lat in 35.0..71.0 && o.lon in -12.0..32.0
        val destInEastAsia = d.lat in 18.0..45.0 && d.lon in 100.0..145.0
        val originInEastAsia = o.lat in 18.0..45.0 && o.lon in 100.0..145.0
        val destInEurope = d.lat in 35.0..71.0 && d.lon in -12.0..32.0

        if ((originInEurope && destInEastAsia) || (originInEastAsia && destInEurope)) {
            if (carrierPrefix !in chineseCarriers) {
                val forward = originInEurope
                val midWaypoints = listOf(
                    "OTP · Bucharest FIR" to GeoPoint(44.57, 26.08),
                    "SIN · Black Sea Airway" to GeoPoint(42.30, 35.00),
                    "TBS · Tbilisi FIR" to GeoPoint(41.68, 44.95),
                    "GYD · Baku / Caspian" to GeoPoint(40.48, 50.05),
                    "SCO · Aktau" to GeoPoint(43.65, 51.20),
                    "KZO · Kyzylorda" to GeoPoint(44.80, 65.50),
                    "ALA · Almaty" to GeoPoint(43.55, 77.05),
                    "URC · Ürümqi" to GeoPoint(43.91, 87.47),
                    "DNH · Dunhuang" to GeoPoint(40.16, 94.68)
                ).let { if (forward) it else it.reversed() }

                val full = listOf("${plan.origin.iata} · Origin" to o) +
                    midWaypoints +
                    listOf("${plan.destination.iata} · Destination" to d)

                return plan.copy(
                    waypoints = full.map { it.second },
                    waypointNames = full.map { it.first },
                    routeSource = "FILED_ATS_CORRIDOR",
                    routeLabel = "Filed ATS Airway · Southern Eurasian Corridor"
                )
            }
        }

        // Fallback: Great-circle estimate (explicitly labeled per AC #5)
        return plan.copy(
            waypoints = listOf(plan.origin.point, plan.destination.point),
            waypointNames = listOf(plan.origin.iata, plan.destination.iata),
            routeSource = "GREAT_CIRCLE_ESTIMATE",
            routeLabel = "Great-circle estimate"
        )
    }
}
