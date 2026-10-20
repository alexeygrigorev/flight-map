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

/** Advisory schedules and hand-authored geographic corridors, not filed plans or ADS-B observations.
 * Genuine dated observations are supplied separately through FlightPlan.historicalTrack.
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

    private val icaoToIataAirline = mapOf(
        "FIN" to "AY",
        "DLH" to "LH",
        "BAW" to "BA",
        "AFR" to "AF",
        "KLM" to "KL",
        "DKH" to "HO",
        "CCA" to "CA",
        "CES" to "MU",
        "CSN" to "CZ",
        "JAL" to "JL",
        "ANA" to "NH",
        "KAL" to "KE",
        "AAR" to "OZ",
        "SIA" to "SQ",
        "CPA" to "CX",
        "THY" to "TK",
        "UAE" to "EK",
        "QTR" to "QR",
        "SAS" to "SK",
        "AAL" to "AA",
        "UAL" to "UA",
        "DAL" to "DL",
        "QFA" to "QF",
        "ETD" to "EY",
        "CFG" to "DE"
    )

    private val pvgDygWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "PVG · Shanghai Pudong" to GeoPoint(31.1434, 121.8050),
        "HGH · Hangzhou / Lake Tai" to GeoPoint(30.3500, 120.1500),
        "JDZ · Huangshan / Jingdezhen" to GeoPoint(29.6500, 117.2000),
        "KHN · Nanchang / Poyang Lake" to GeoPoint(28.8500, 115.9000),
        "CSX · Changsha / Xiang River" to GeoPoint(28.3500, 113.2000),
        "CGD · Changde / Dongting Lake" to GeoPoint(29.0200, 111.6800),
        "DYG · Zhangjiajie Hehua" to GeoPoint(29.1048, 110.4428)
    )

    private val dygCanWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "DYG · Zhangjiajie Hehua T2" to GeoPoint(29.1048, 110.4428),
        "HJJ · Huaihua / Xuefeng" to GeoPoint(27.5500, 110.9500),
        "HNY · Hengyang / Mount Heng" to GeoPoint(26.8800, 112.5500),
        "HSC · Shaoguan / Nanling Gate" to GeoPoint(24.8000, 113.5500),
        "CAN · Guangzhou Baiyun T2" to GeoPoint(23.3924, 113.2990)
    )

    private val canHndWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "CAN · Guangzhou Baiyun" to GeoPoint(23.3924, 113.2990),
        "SWA · Shantou / East Guangdong" to GeoPoint(23.5500, 116.5000),
        "XMN · Xiamen / Taiwan Strait" to GeoPoint(24.8000, 118.8000),
        "TPE · East China Sea Airway" to GeoPoint(26.2000, 123.5000),
        "OKA · Okinawa / Ryukyu Arc" to GeoPoint(28.2000, 128.5000),
        "KMI · Miyazaki / Kyushu Coast" to GeoPoint(31.8500, 131.6000),
        "SHM · Kii Peninsula / Wakayama" to GeoPoint(33.6500, 135.8000),
        "FSZ · Shizuoka / Mount Fuji" to GeoPoint(34.8000, 138.6000),
        "HND · Tokyo Haneda" to GeoPoint(35.5497, 139.7870)
    )

    private val nrtAuhWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "NRT · Tokyo Narita T1" to GeoPoint(35.7686, 140.3887),
        "YNG · Yonago / Sea of Japan" to GeoPoint(35.6000, 133.3000),
        "ICN · Seoul / Yellow Sea FIR" to GeoPoint(37.4000, 126.2000),
        "TAO · Bohai / Shandong" to GeoPoint(37.8000, 119.0000),
        "TYN · Taiyuan / Loess Plateau" to GeoPoint(37.7500, 112.5000),
        "LHW · Lanzhou / Hexi Corridor" to GeoPoint(36.3500, 103.8500),
        "DNH · Dunhuang / Silk Road" to GeoPoint(40.1500, 94.7000),
        "URC · Ürümqi / Tian Shan" to GeoPoint(43.8000, 87.5000),
        "ALA · Almaty / Jetisu" to GeoPoint(43.3000, 76.9000),
        "TAS · Tashkent / Syr Darya" to GeoPoint(41.2500, 69.2500),
        "ASB · Ashgabat / Kopet Dag" to GeoPoint(37.9500, 58.3800),
        "THR · Tehran / Alborz South" to GeoPoint(35.5000, 51.4000),
        "SYZ · Shiraz / Zagros" to GeoPoint(29.5500, 52.5800),
        "AUH · Abu Dhabi Zayed TA" to GeoPoint(24.4410, 54.6492)
    )

    private val auhBerWaypoints: List<Pair<String, GeoPoint>> = listOf(
        "AUH · Abu Dhabi Zayed" to GeoPoint(24.4410, 54.6492),
        "BAH · Arabian Gulf North" to GeoPoint(26.3000, 50.6000),
        "KWI · Kuwait / Mesopotamia Gate" to GeoPoint(29.2200, 47.9600),
        "EBL · Erbil / Upper Tigris" to GeoPoint(36.2000, 44.0000),
        "ERZ · Erzurum / Eastern Anatolia" to GeoPoint(39.9500, 41.2500),
        "SIN · Black Sea South Coast" to GeoPoint(41.8000, 35.1500),
        "IST · Istanbul / Thrace" to GeoPoint(41.2800, 28.7500),
        "OTP · Bucharest / Carpathians" to GeoPoint(44.5700, 26.0800),
        "BUD · Budapest / Danube Bend" to GeoPoint(47.4400, 19.2600),
        "PRG · Prague / Bohemia" to GeoPoint(50.1000, 14.2600),
        "BER · Berlin Brandenburg T1" to GeoPoint(52.3617, 13.5023)
    )

    private val templates: Map<String, FiledScheduleTemplate> = listOf(
        FiledScheduleTemplate(
            flightNumber = "MU689",
            airline = "China Eastern",
            aircraft = "Airbus A320",
            originIata = "PVG",
            destinationIata = "DYG",
            departureLocal = LocalTime.of(10, 0),
            arrivalLocal = LocalTime.of(12, 10),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Yangtze–Wulingyuan Corridor",
            waypoints = pvgDygWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "CZ3382",
            airline = "China Southern (T2→T2)",
            aircraft = "Boeing 737-800",
            originIata = "DYG",
            destinationIata = "CAN",
            departureLocal = LocalTime.of(21, 40),
            arrivalLocal = LocalTime.of(23, 25),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Hunan–Pearl River Corridor",
            waypoints = dygCanWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "CZ3085",
            airline = "China Southern (QF3MDK)",
            aircraft = "Airbus A330-300",
            originIata = "CAN",
            destinationIata = "HND",
            departureLocal = LocalTime.of(15, 10),
            arrivalLocal = LocalTime.of(20, 0),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Ryukyu–Kuroshio Pacific Corridor",
            waypoints = canHndWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "EY801",
            airline = "Etihad Airways (T1→TA · 9GVDH4)",
            aircraft = "Boeing 787-9",
            originIata = "NRT",
            destinationIata = "AUH",
            departureLocal = LocalTime.of(17, 20),
            arrivalLocal = LocalTime.of(0, 40),
            arrivalDayOffset = 1,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Trans-Asian Silk Road Corridor",
            waypoints = nrtAuhWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "DE2553",
            airline = "Condor / Etihad EY1042 (→T1)",
            aircraft = "Airbus A330-900neo",
            originIata = "AUH",
            destinationIata = "BER",
            departureLocal = LocalTime.of(2, 20),
            arrivalLocal = LocalTime.of(6, 20),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Gulf–Anatolia–Central Europe Corridor",
            waypoints = auhBerWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "EY1042",
            airline = "Etihad / Condor DE2553 (→T1)",
            aircraft = "Airbus A330-900neo",
            originIata = "AUH",
            destinationIata = "BER",
            departureLocal = LocalTime.of(2, 20),
            arrivalLocal = LocalTime.of(6, 20),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Gulf–Anatolia–Central Europe Corridor",
            waypoints = auhBerWaypoints
        ),
        FiledScheduleTemplate(
            flightNumber = "AY87",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HEL",
            destinationIata = "PVG",
            departureLocal = LocalTime.of(1, 10),
            arrivalLocal = LocalTime.of(18, 25),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Northern Siberian Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Arctic Polar Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Arctic Polar Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
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
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
            waypoints = listOf(
                "LHR · London Heathrow" to GeoPoint(51.4700, -0.4543),
                "FRA · Frankfurt FIR" to GeoPoint(50.0379, 8.5622),
                "VIE · Vienna FIR" to GeoPoint(48.1103, 16.5697)
            ) + helPvgSouthernWaypoints.drop(7)
        ),
        FiledScheduleTemplate(
            flightNumber = "HO1607",
            airline = "Juneyao Air",
            aircraft = "Boeing 787-9",
            originIata = "PVG",
            destinationIata = "HEL",
            departureLocal = LocalTime.of(0, 55),
            arrivalLocal = LocalTime.of(6, 20),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Northern Siberian Corridor",
            waypoints = helPvgNorthernWaypoints.reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "AY74",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "NRT",
            destinationIata = "HEL",
            departureLocal = LocalTime.of(23, 5),
            arrivalLocal = LocalTime.of(5, 55),
            arrivalDayOffset = 1,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Arctic Polar Corridor",
            waypoints = (helHndPolarWaypoints.dropLast(1) + ("NRT · Tokyo Narita" to GeoPoint(35.7647, 140.3864))).reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "AY62",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "HND",
            destinationIata = "HEL",
            departureLocal = LocalTime.of(21, 50),
            arrivalLocal = LocalTime.of(4, 40),
            arrivalDayOffset = 1,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Arctic Polar Corridor",
            waypoints = helHndPolarWaypoints.reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "AY42",
            airline = "Finnair",
            aircraft = "Airbus A350-900",
            originIata = "ICN",
            destinationIata = "HEL",
            departureLocal = LocalTime.of(21, 50),
            arrivalLocal = LocalTime.of(5, 30),
            arrivalDayOffset = 1,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
            waypoints = helIcnSouthernWaypoints.reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "LH729",
            airline = "Lufthansa",
            aircraft = "Boeing 747-8",
            originIata = "PVG",
            destinationIata = "FRA",
            departureLocal = LocalTime.of(13, 45),
            arrivalLocal = LocalTime.of(19, 50),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
            waypoints = (listOf(
                "FRA · Frankfurt" to GeoPoint(50.0379, 8.5622),
                "VIE · Vienna FIR" to GeoPoint(48.1103, 16.5697),
                "BUD · Budapest" to GeoPoint(47.4369, 19.2556)
            ) + helPvgSouthernWaypoints.drop(7)).reversed()
        ),
        FiledScheduleTemplate(
            flightNumber = "BA168",
            airline = "British Airways",
            aircraft = "Boeing 787-9",
            originIata = "PVG",
            destinationIata = "LHR",
            departureLocal = LocalTime.of(11, 0),
            arrivalLocal = LocalTime.of(16, 55),
            arrivalDayOffset = 0,
            routeSource = "ESTIMATED_CORRIDOR",
            routeLabel = "Estimated corridor · Southern Eurasian Corridor",
            waypoints = (listOf(
                "LHR · London Heathrow" to GeoPoint(51.4700, -0.4543),
                "FRA · Frankfurt FIR" to GeoPoint(50.0379, 8.5622),
                "VIE · Vienna FIR" to GeoPoint(48.1103, 16.5697)
            ) + helPvgSouthernWaypoints.drop(7)).reversed()
        )
    ).associateBy { normalizeFlightNumber(it.flightNumber) }

    fun normalizeFlightNumber(raw: String): String {
        val cleaned = raw.uppercase().replace(" ", "").replace("-", "").trim()
        if (cleaned.isEmpty()) return ""
        val prefix = cleaned.takeWhile { it.isLetter() }
        val rest = cleaned.drop(prefix.length)
        if (prefix.isNotEmpty() && rest.isNotEmpty() && rest.all { it.isDigit() }) {
            val mappedPrefix = icaoToIataAirline[prefix] ?: prefix
            val numberPart = rest.trimStart('0').ifEmpty { "0" }
            return "$mappedPrefix$numberPart"
        }
        return cleaned
    }

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

    /**
     * Estimates scheduled block duration from route distance (cruise ~835 km/h + 38m climb/descent/taxi).
     */
    fun estimateBlockDuration(routeDistanceKm: Double): java.time.Duration {
        val minutes = kotlin.math.round(routeDistanceKm / 835.0 * 60.0 + 38.0).toLong().coerceIn(45L, 1140L)
        return java.time.Duration.ofMinutes(minutes)
    }

    /**
     * Synthesizes a complete ResolvedSchedule for any origin/destination pair and date
     * (e.g. when origin/destination come from an online flight lookup or user selection),
     * automatically computing the arrival local date/time from trajectory distance and time zones.
     */
    fun buildScheduleForRoute(
        flightNumber: String,
        origin: Airport,
        destination: Airport,
        date: LocalDate,
        departureTime: LocalTime = LocalTime.of(9, 0),
        airline: String = ""
    ): ResolvedSchedule {
        val norm = normalizeFlightNumber(flightNumber).ifBlank { "FLIGHT" }
        val tmpl = lookupTemplate(norm)
        if (tmpl != null && tmpl.originIata == origin.iata && tmpl.destinationIata == destination.iata && departureTime == tmpl.departureLocal) {
            return lookupSchedule(norm, date)!!
        }
        val depLocal = LocalDateTime.of(date, departureTime)
        val originZone = ZoneId.of(origin.timezone)
        val destZone = ZoneId.of(destination.timezone)
        val depInstant = localDateTimeToInstant(depLocal, origin)
        val tempEnriched = enrichPlan(
            FlightPlan(norm, origin, destination, depInstant, depInstant.plusSeconds(3600))
        )
        val blockDuration = if (tmpl != null && tmpl.originIata == origin.iata && tmpl.destinationIata == destination.iata) {
            val tmplDep = localDateTimeToInstant(LocalDateTime.of(date, tmpl.departureLocal), origin)
            val tmplArr = localDateTimeToInstant(
                LocalDateTime.of(date.plusDays(tmpl.arrivalDayOffset.toLong()), tmpl.arrivalLocal),
                destination
            )
            java.time.Duration.between(tmplDep, tmplArr)
        } else {
            val km = GreatCircle.routeDistanceKm(tempEnriched.effectiveWaypoints)
            estimateBlockDuration(km)
        }
        val arrInstant = depInstant.plus(blockDuration)
        val arrLocal = arrInstant.atZone(destZone).toLocalDateTime().withSecond(0).withNano(0)
        return ResolvedSchedule(
            flightNumber = tmpl?.flightNumber ?: norm,
            airline = airline.ifBlank { tmpl?.airline ?: "" },
            aircraft = tmpl?.aircraft ?: "",
            originIata = origin.iata,
            destinationIata = destination.iata,
            departureLocalIso = depLocal.withSecond(0).withNano(0).toString(),
            arrivalLocalIso = arrLocal.toString(),
            routeLabel = tempEnriched.effectiveRouteLabel,
            waypointCount = tempEnriched.effectiveWaypoints.size
        )
    }

    /**
     * Resolves a complete enriched FlightPlan primarily from flightNumber + date,
     * with optional overrides for airports or local times (accepting either "HH:MM" or full ISO "YYYY-MM-DDTHH:MM").
     */
    fun resolvePlan(
        flightNumber: String,
        dateStr: String,
        airports: List<Airport>,
        originOverride: String? = null,
        destinationOverride: String? = null,
        departureOverride: String? = null,
        arrivalOverride: String? = null
    ): FlightPlan {
        val norm = normalizeFlightNumber(flightNumber)
        val tmpl = lookupTemplate(norm)
        val date = runCatching { LocalDate.parse(dateStr.trim()) }.getOrElse {
            val extracted = departureOverride?.trim()?.take(10)?.let { d ->
                runCatching { LocalDate.parse(d) }.getOrNull()
            }
            extracted ?: throw IllegalArgumentException("Enter a valid departure date (YYYY-MM-DD)")
        }

        val originCode = originOverride?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: tmpl?.originIata
            ?: throw IllegalArgumentException("No schedule found for ${norm.ifBlank { "flight" }}. Tap 'Customize route / times' to select airports.")
        val destCode = destinationOverride?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: tmpl?.destinationIata
            ?: throw IllegalArgumentException("Destination airport is required.")

        val from = airports.firstOrNull { it.iata == originCode }
            ?: throw IllegalArgumentException("Origin airport '$originCode' not found")
        val to = airports.firstOrNull { it.iata == destCode }
            ?: throw IllegalArgumentException("Destination airport '$destCode' not found")
        require(from.iata != to.iata) { "Choose two different airports" }

        val depLocalDateTime = parseFlexibleLocalDateTime(
            text = departureOverride,
            defaultDate = date,
            defaultTime = if (tmpl != null && tmpl.originIata == from.iata && tmpl.destinationIata == to.iata) {
                tmpl.departureLocal
            } else {
                LocalTime.of(9, 0)
            }
        )
        val depInstant = localDateTimeToInstant(depLocalDateTime, from)

        val arrInstant = if (!arrivalOverride.isNullOrBlank()) {
            val rawArrLocal = parseFlexibleLocalDateTime(
                text = arrivalOverride,
                defaultDate = depLocalDateTime.toLocalDate(),
                defaultTime = tmpl?.arrivalLocal ?: LocalTime.of(17, 0)
            )
            var candidate = localDateTimeToInstant(rawArrLocal, to)
            // If user only typed "HH:MM" for an overnight flight and it landed on the same date before departure, advance +1 day
            if (!arrivalOverride.trim().contains("T") && !arrivalOverride.trim().contains("-") && candidate <= depInstant) {
                candidate = localDateTimeToInstant(rawArrLocal.plusDays(1), to)
            }
            candidate
        } else {
            val schedule = buildScheduleForRoute(
                flightNumber = norm.ifBlank { "MANUAL" },
                origin = from,
                destination = to,
                date = depLocalDateTime.toLocalDate(),
                departureTime = depLocalDateTime.toLocalTime()
            )
            localDateTimeToInstant(LocalDateTime.parse(schedule.arrivalLocalIso), to)
        }

        val hours = java.time.Duration.between(depInstant, arrInstant).toHours()
        require(arrInstant > depInstant && hours in 0..30) {
            "Arrival must be after departure, within 30 hours"
        }

        return enrichPlan(
            FlightPlan(
                number = tmpl?.flightNumber ?: norm.ifBlank { "MANUAL" },
                origin = from,
                destination = to,
                departure = depInstant,
                arrival = arrInstant
            )
        )
    }

    private fun parseFlexibleLocalDateTime(
        text: String?,
        defaultDate: LocalDate,
        defaultTime: LocalTime
    ): LocalDateTime {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return LocalDateTime.of(defaultDate, defaultTime)
        val normalized = raw.replace(" ", "T")
        if (normalized.contains("T")) {
            return runCatching { LocalDateTime.parse(normalized) }.getOrElse {
                throw IllegalArgumentException("Invalid date/time '$raw'. Use HH:MM or YYYY-MM-DDTHH:MM.")
            }
        }
        val parsedTime = runCatching {
            val parts = raw.split(":")
            if (parts.size >= 2) {
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            } else {
                LocalTime.parse(raw)
            }
        }.getOrElse {
            throw IllegalArgumentException("Invalid time '$raw'. Use HH:MM (e.g. 01:10 or 16:40).")
        }
        return LocalDateTime.of(defaultDate, parsedTime)
    }

    private fun localDateTimeToInstant(dateTime: LocalDateTime, airport: Airport): java.time.Instant {
        val zone = ZoneId.of(airport.timezone)
        val offsets = zone.rules.getValidOffsets(dateTime)
        require(offsets.isNotEmpty()) {
            "${airport.iata}: local time $dateTime is skipped by daylight saving transition."
        }
        return dateTime.toInstant(offsets.first())
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
     * Enriches a FlightPlan with advisory corridor waypoints if known or synthesizable
     * via airspace rules; otherwise marks the route explicitly as a Great-circle estimate.
     */
    fun enrichPlan(plan: FlightPlan): FlightPlan {
        if (plan.effectiveHistoricalTrack != null) return plan
        if (plan.routeSource == "USER_SUPPLIED_ROUTE" && !plan.waypoints.isNullOrEmpty()) return plan
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
                routeSource = "ESTIMATED_CORRIDOR",
                routeLabel = if (useNorthern) "Estimated corridor · Northern Siberian Corridor" else "Estimated corridor · Southern Eurasian Corridor"
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
                routeSource = "ESTIMATED_CORRIDOR",
                routeLabel = if (useNorthern) "Estimated corridor · Northern Siberian Corridor" else "Estimated corridor · Southern Eurasian Corridor"
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
                    routeSource = "ESTIMATED_CORRIDOR",
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
