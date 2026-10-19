package com.flightmap.core

/**
 * Offline database of iconic mountain peaks, featured IFE landmark pins,
 * and historical/geographic city facts for in-flight exploration.
 *
 * Matches the visual vocabulary of the Finnair A350 Flight Explorer IFE
 * (yellow triangle mountain peaks with elevation in feet, indigo landmark pins,
 * and rich city descriptions).
 */
object IfeCatalog {

    val mountainPeaks: List<MountainPeak> = listOf(
        MountainPeak(
            id = "narodnaya",
            name = "Mount Narodnaya",
            elevationFt = 6214,
            elevationM = 1895,
            point = GeoPoint(65.0358, 60.1125),
            region = "Subpolar Urals",
            fact = "Highest peak of the Ural mountain chain dividing European and Asian Russia."
        ),
        MountainPeak(
            id = "kebnekaise",
            name = "Kebnekaise",
            elevationFt = 6886,
            elevationM = 2099,
            point = GeoPoint(67.9010, 18.5170),
            region = "Scandinavian Mountains, Sweden",
            fact = "Highest mountain in Sweden, located in Lapland 150 km north of the Arctic Circle."
        ),
        MountainPeak(
            id = "mont-blanc",
            name = "Mont Blanc",
            elevationFt = 15774,
            elevationM = 4808,
            point = GeoPoint(45.8325, 6.8650),
            region = "Graian Alps, France / Italy",
            fact = "Highest mountain in the Alps and Western Europe, crowned by perennial glaciers."
        ),
        MountainPeak(
            id = "ararat",
            name = "Mount Ararat",
            elevationFt = 16854,
            elevationM = 5137,
            point = GeoPoint(39.7022, 44.2981),
            region = "Eastern Anatolia, Türkiye",
            fact = "Snow-capped dormant compound volcano near the borders of Armenia, Iran, and Azerbaijan."
        ),
        MountainPeak(
            id = "k2",
            name = "K2",
            elevationFt = 28251,
            elevationM = 8611,
            point = GeoPoint(35.8814, 76.5133),
            region = "Karakoram, Pakistan / China",
            fact = "Earth's second-highest mountain, rising along the China–Pakistan border south of Xinjiang."
        ),
        MountainPeak(
            id = "elbrus",
            name = "Mount Elbrus",
            elevationFt = 18510,
            elevationM = 5642,
            point = GeoPoint(43.3499, 42.4453),
            region = "Greater Caucasus",
            fact = "Twin-coned dormant volcano and the highest peak of the Caucasus and Europe."
        ),
        MountainPeak(
            id = "tomur",
            name = "Jengish Chokusu",
            elevationFt = 24406,
            elevationM = 7439,
            point = GeoPoint(42.0378, 80.1289),
            region = "Central Tian Shan",
            fact = "Also known as Tomur Peak; the highest summit of the Tian Shan mountain system."
        ),
        MountainPeak(
            id = "bogda",
            name = "Bogda Peak",
            elevationFt = 17864,
            elevationM = 5445,
            point = GeoPoint(43.7989, 88.3264),
            region = "Eastern Tian Shan, Xinjiang",
            fact = "Dramatic glaciated peak rising directly east of Ürümqi above Heavenly Lake (Tianchi)."
        ),
        MountainPeak(
            id = "qilian",
            name = "Qilian Peak",
            elevationFt = 19055,
            elevationM = 5808,
            point = GeoPoint(38.5100, 97.7200),
            region = "Qilian Mountains, Gansu",
            fact = "Snowy wall flanking the southern edge of the ancient Silk Road Hexi Corridor."
        ),
        MountainPeak(
            id = "huashan",
            name = "Mount Hua",
            elevationFt = 7070,
            elevationM = 2155,
            point = GeoPoint(34.4781, 110.0842),
            region = "Qinling Range, Shaanxi",
            fact = "Western Great Mountain of China, famous for sheer granite cliffs east of Xi'an."
        ),
        MountainPeak(
            id = "everest",
            name = "Mount Everest",
            elevationFt = 29032,
            elevationM = 8849,
            point = GeoPoint(27.9881, 86.9250),
            region = "Himalayas, Nepal / Tibet",
            fact = "Earth's highest mountain above sea level on the crest of the Great Himalayas."
        )
    )

    val landmarkPins: List<LandmarkPin> = listOf(
        LandmarkPin("pin-hel", "Helsinki Cathedral & Senate Square", "Finland", GeoPoint(60.1704, 24.9522), "Europe/Helsinki", "Neoclassical heart of Helsinki overlooking the Gulf of Finland."),
        LandmarkPin("pin-tll", "Tallinn Old Town", "Estonia", GeoPoint(59.4370, 24.7536), "Europe/Tallinn", "UNESCO-listed medieval Hanseatic merchant port across the gulf from Helsinki."),
        LandmarkPin("pin-waw", "Warsaw Historic Center", "Poland", GeoPoint(52.2497, 21.0122), "Europe/Warsaw", "Capital of Poland along the Vistula River on the central European airway."),
        LandmarkPin("pin-otp", "Carpathian Arc & Bucharest", "Romania", GeoPoint(44.4268, 26.1025), "Europe/Bucharest", "Gateway between the Southern Carpathians, Danube plain, and the Black Sea."),
        LandmarkPin("pin-tbs", "Old Tbilisi & Kura Valley", "Georgia", GeoPoint(41.6938, 44.8015), "Asia/Tbilisi", "Ancient Silk Road crossroads nestled between the Greater and Lesser Caucasus."),
        LandmarkPin("pin-gyd", "Icherisheher & Flame Towers, Baku", "Azerbaijan", GeoPoint(40.3660, 49.8372), "Asia/Baku", "Caspian Sea capital sitting 28 meters below sea level on the Absheron Peninsula."),
        LandmarkPin("pin-ala", "Ile-Alatau & Almaty", "Kazakhstan", GeoPoint(43.2389, 76.8897), "Asia/Almaty", "Largest city in Kazakhstan at the northern foot of the snow-capped Tian Shan."),
        LandmarkPin("pin-urc", "Ürümqi & Heavenly Lake", "China", GeoPoint(43.8256, 87.6168), "Asia/Urumqi", "Inland Silk Road metropolis, closer to the center of Eurasia than any other major city."),
        LandmarkPin("pin-dnh", "Mogao Caves & Singing Sand Dunes, Dunhuang", "China", GeoPoint(40.1421, 94.6620), "Asia/Shanghai", "Legendary oasis at the edge of the Taklamakan and Gobi deserts along the Hexi Corridor."),
        LandmarkPin("pin-xiy", "Terracotta Army & Ancient Wall, Xi'an", "China", GeoPoint(34.3416, 108.9398), "Asia/Shanghai", "Eastern terminus of the Silk Road and ancient imperial capital of 13 dynasties."),
        LandmarkPin("pin-nkg", "Ming City Wall & Yangtze Bridge, Nanjing", "China", GeoPoint(32.0603, 118.7969), "Asia/Shanghai", "Historic southern capital on the lower Yangtze River approaching the delta."),
        LandmarkPin("pin-pvg", "The Bund & Lujiazui Skyline, Shanghai", "China", GeoPoint(31.2397, 121.4998), "Asia/Shanghai", "Global financial hub where the Huangpu River meets the Yangtze Estuary."),
        LandmarkPin("pin-sto", "Gamla Stan, Stockholm", "Sweden", GeoPoint(59.3251, 18.0711), "Europe/Stockholm", "Nordic capital built across 14 islands where Lake Mälaren meets the Baltic Sea."),
        LandmarkPin("pin-ber", "Brandenburg Gate, Berlin", "Germany", GeoPoint(52.5163, 13.3777), "Europe/Berlin", "Historic cultural and political heart of Germany on the Spree River."),
        LandmarkPin("pin-lon", "Westminster & Thames, London", "United Kingdom", GeoPoint(51.5007, -0.1246), "Europe/London", "Tidal Thames metropolis and prime meridian reference at Greenwich."),
        LandmarkPin("pin-par", "Île de la Cité & Eiffel Tower, Paris", "France", GeoPoint(48.8584, 2.2945), "Europe/Paris", "Capital of France in the Seine basin."),
        LandmarkPin("pin-rom", "Colosseum & Tiber, Rome", "Italy", GeoPoint(41.8902, 12.4922), "Europe/Rome", "Eternal City on the central Italian peninsula.")
    )

    private val curatedCityFacts: Map<String, String> = mapOf(
        "helsinki" to "Nordic maritime capital spread across a granite peninsula and over 300 islands on the Gulf of Finland.",
        "vantaa" to "Aviation hub of Finland and home to Helsinki Airport, linking Northern Europe with Asia.",
        "tallinn" to "Estonia's capital on the southern shore of the Gulf of Finland, famed for its medieval walled Old Town.",
        "tartu" to "Estonia's intellectual capital and home to its oldest university, founded in 1632.",
        "pärnu" to "Historic Baltic coastal resort city on Pärnu Bay in southwestern Estonia.",
        "riga" to "Capital of Latvia at the mouth of the Daugava River, renowned for Art Nouveau architecture.",
        "daugavpils" to "Second-largest city in Latvia, situated on the Daugava River near the Lithuanian border.",
        "panevėžys" to "Crossroads city of northern Lithuania along the Via Baltica corridor.",
        "kaunas" to "Historic interwar capital of Lithuania at the confluence of the Nemunas and Neris rivers.",
        "vilnius" to "Baroque capital of Lithuania on the Neris River, one of Europe's largest surviving medieval old towns.",
        "białystok" to "Largest city in northeastern Poland, near the primeval Białowieża Forest.",
        "warsaw" to "Capital of Poland on the Vistula River; meticulously reconstructed UNESCO historic center.",
        "lublin" to "Historic eastern Polish cultural hub where the Union of Lublin was signed in 1569.",
        "rzeszów" to "Aviation Valley hub in southeastern Poland at the foothills of the Carpathians.",
        "košice" to "Largest city in eastern Slovakia, famed for the Gothic Cathedral of St. Elizabeth.",
        "debrecen" to "Second-largest city of Hungary, set on the Great Hungarian Plain (Alföld).",
        "oradea" to "Art Nouveau gateway city of northwestern Romania along the Crișul Repede River.",
        "cluj-napoca" to "Unofficial capital of historic Transylvania in the Someșul Mic valley.",
        "târgu mureș" to "Transylvanian cultural center on the Mureș River inside the Carpathian arc.",
        "brașov" to "Medieval Saxon city ringed by the Southern Carpathian peaks of Transylvania.",
        "ploiești" to "Historic energy and industrial crossroads 56 km north of Bucharest.",
        "bucharest" to "Capital of Romania on the Dâmbovița River in the Wallachian plain.",
        "constanța" to "Ancient Greek port of Tomis on the western Black Sea coast of Romania.",
        "sinop" to "Natural harbor on the northernmost cape of Türkiye's Black Sea coast.",
        "samsun" to "Major Black Sea port of northern Türkiye between the Kızılırmak and Yeşilırmak deltas.",
        "trabzon" to "Historic Silk Road port on the Black Sea coast beneath the Pontic Mountains.",
        "batumi" to "Subtropical Black Sea port and capital of Georgia's Adjara region.",
        "kutaisi" to "One of the world's oldest continuously inhabited cities, in western Georgia's Rioni plain.",
        "gori" to "Fortress town in central Georgia at the confluence of the Mtkvari and Liakhvi rivers.",
        "tbilisi" to "Capital of Georgia straddling the Kura (Mtkvari) River gorge beneath Mtatsminda.",
        "rustavi" to "Ancient Silk Road settlement and modern industrial city southeast of Tbilisi.",
        "ganja" to "Historic Azerbaijan city at the northeastern foothills of the Lesser Caucasus.",
        "sumqayit" to "Caspian coast city 30 km northwest of Baku on the Absheron Peninsula.",
        "baku" to "Capital of Azerbaijan on the Caspian Sea; world's lowest-lying national capital (-28 m).",
        "aktau" to "Caspian port on Kazakhstan's Mangystau Peninsula, surrounded by dramatic chalk canyons.",
        "aralsk" to "Former northern port of the Aral Sea in western Kazakhstan, restored by the Kok-Aral dam.",
        "baikonur" to "World's first operational spaceport on the Syr Darya steppe, launch site of Sputnik and Gagarin.",
        "kyzylorda" to "Oasis capital along the lower Syr Darya River on the ancient northern Silk Road.",
        "turkistan" to "Ancient spiritual heart of Kazakhstan, home to the 14th-century UNESCO Mausoleum of Khoja Ahmed Yasawi.",
        "shymkent" to "Vibrant southern Kazakh metropolis in the fertile valleys north of Tashkent.",
        "taraz" to "2,000-year-old Silk Road trading city on the Talas River near the Kyrgyz range.",
        "bishkek" to "Capital of Kyrgyzstan at the northern foot of the snow-clad Kyrgyz Ala-Too range.",
        "tashkent" to "Largest city in Central Asia and capital of Uzbekistan, fed by the Chirchiq River.",
        "almaty" to "Former capital of Kazakhstan, framed by 4,000-meter glaciated peaks of the Zailiyskiy Alatau.",
        "astana" to "Modern capital of Kazakhstan on the Ishim River in the northern Eurasian steppe.",
        "yining" to "Garden city of the Ili River Valley in northwestern Xinjiang near Khorgos.",
        "shihezi" to "Oasis city on the northern rim of the Junggar Basin beneath the Tian Shan.",
        "ürümqi" to "Capital of Xinjiang at the northern foot of the Tian Shan; the most inland major city on Earth.",
        "urumqi" to "Capital of Xinjiang at the northern foot of the Tian Shan; the most inland major city on Earth.",
        "turpan" to "Silk Road oasis in the Turpan Depression (-154 m), famous for Karez underground canals and Flaming Mountains.",
        "hami" to "Eastern gateway oasis of Xinjiang between the Tian Shan and the Gobi Desert.",
        "dunhuang" to "Historic Silk Road junction famous for the UNESCO Mogao Caves and Crescent Lake.",
        "jiayuguan" to "Guarded pass at the western end of the Ming Dynasty Great Wall in the Hexi Corridor.",
        "jiuquan" to "Ancient Hexi Corridor oasis and neighbor to China's Jiuquan Satellite Launch Center.",
        "zhangye" to "Silk Road oasis celebrated for its rainbow Danxia landform cliffs and Giant Buddha Temple.",
        "wuwei" to "Eastern gateway of the Hexi Corridor where the Bronze Galloping Horse of Han was discovered.",
        "lanzhou" to "Capital of Gansu Province, the only major city bisected directly by the upper Yellow River.",
        "tianshui" to "Ancient city on the Wei River, famed for the cliffside Maijishan Grottoes.",
        "bao-ji" to "Gateway across the Qinling Mountains at the western end of the Guanzhong Plain.",
        "baoji" to "Gateway across the Qinling Mountains at the western end of the Guanzhong Plain.",
        "xianyang" to "First imperial capital of unified China under the Qin Dynasty on the Wei River.",
        "xi'an" to "Ancient Chang'an: eastern Silk Road terminus and home of the Terracotta Warriors.",
        "xian" to "Ancient Chang'an: eastern Silk Road terminus and home of the Terracotta Warriors.",
        "nanyang" to "Historic basin city of southwestern Henan, nestled south of the Funiu Mountains.",
        "zhengzhou" to "Capital of Henan Province on the southern bank of the Yellow River.",
        "fuyang" to "Populous agricultural and railway hub on the Huai River plain in northwestern Anhui.",
        "huainan" to "Huai River city known as the historic birthplace of tofu during the Han Dynasty.",
        "hefei" to "Capital of Anhui Province north of Chao Lake, a major science and technology hub.",
        "chuzhou" to "Eastern Anhui city famed for Langya Mountain and the Northern Song pavilion Zuiwengting.",
        "nanjing" to "Six Dynasties and Ming capital on the lower Yangtze River, ringed by Purple Mountain.",
        "zhenjiang" to "Historic Yangtze port at the intersection of the Yangtze River and the Grand Canal.",
        "changzhou" to "Water-town metropolis on the Grand Canal between Nanjing and Lake Tai.",
        "wuxi" to "Pearl of Lake Tai (Taihu), bisected by the ancient Beijing–Hangzhou Grand Canal.",
        "suzhou" to "Renowned for classical UNESCO gardens, silk craft, and canals; 'Venice of the East'.",
        "kunshan" to "Water-town hub between Suzhou and Shanghai, birthplace of Kunqu Opera.",
        "shanghai" to "China's largest metropolis at the mouth of the Yangtze River on the East China Sea.",
        "stockholm" to "Capital of Sweden built across 14 islands where Lake Mälaren meets the Baltic.",
        "oslo" to "Capital of Norway at the head of the 100-km Oslofjord.",
        "moscow" to "Capital of Russia on the Moskva River in the central European plain.",
        "st. petersburg" to "Imperial Baltic port founded on the Neva River delta in 1703.",
        "kazan" to "Millennium-old capital of Tatarstan at the confluence of the Volga and Kazanka rivers.",
        "yekaterinburg" to "Fourth-largest city in Russia on the eastern slope of the Ural Mountains.",
        "novosibirsk" to "Largest city of Siberia, built where the Trans-Siberian Railway crosses the Ob River.",
        "ulaanbaatar" to "Capital of Mongolia in the Tuul River valley at 1,350 m elevation.",
        "beijing" to "Capital of China at the northern tip of the North China Plain, framed by the Yan Mountains.",
        "seoul" to "Capital of South Korea along the Han River basin.",
        "tokyo" to "Capital of Japan fronting Tokyo Bay on the Kanto Plain."
    )

    private val countryNames: Map<String, String> = mapOf(
        "FI" to "Finland",
        "EE" to "Estonia",
        "LV" to "Latvia",
        "LT" to "Lithuania",
        "PL" to "Poland",
        "BY" to "Belarus",
        "UA" to "Ukraine",
        "SK" to "Slovakia",
        "HU" to "Hungary",
        "RO" to "Romania",
        "MD" to "Moldova",
        "BG" to "Bulgaria",
        "TR" to "Türkiye",
        "GE" to "Georgia",
        "AM" to "Armenia",
        "AZ" to "Azerbaijan",
        "KZ" to "Kazakhstan",
        "UZ" to "Uzbekistan",
        "TM" to "Turkmenistan",
        "KG" to "Kyrgyzstan",
        "TJ" to "Tajikistan",
        "CN" to "China",
        "MN" to "Mongolia",
        "RU" to "Russia",
        "SE" to "Sweden",
        "NO" to "Norway",
        "DK" to "Denmark",
        "DE" to "Germany",
        "GB" to "United Kingdom",
        "FR" to "France",
        "IT" to "Italy",
        "GR" to "Greece",
        "CY" to "Cyprus",
        "IR" to "Iran",
        "IQ" to "Iraq",
        "AF" to "Afghanistan",
        "PK" to "Pakistan",
        "IN" to "India",
        "KR" to "South Korea",
        "JP" to "Japan",
        "US" to "United States",
        "CA" to "Canada"
    )

    fun countryDisplayName(isoOrName: String): String =
        countryNames[isoOrName.uppercase().trim()] ?: isoOrName

    /**
     * Returns an offline cultural/geographic fact for any overflown city.
     * Uses curated historical notes for major hubs and synthesizes geographic context
     * for regional towns so every single city in the 69,822-city database has offline info.
     */
    fun cityFact(city: City): String {
        val key = city.name.lowercase().trim()
        curatedCityFacts[key]?.let { return it }

        val countryFull = countryDisplayName(city.country)
        val regionName = city.timezone.substringAfter("/", city.timezone).replace("_", " ")
        val popDesc = when {
            city.population >= 5_000_000 -> "Major metropolis (pop. %,d)".format(city.population)
            city.population >= 1_000_000 -> "Metropolitan center (pop. %,d)".format(city.population)
            city.population >= 250_000 -> "Regional city (pop. %,d)".format(city.population)
            city.population >= 50_000 -> "Urban center (pop. %,d)".format(city.population)
            city.population > 0 -> "Town (pop. %,d)".format(city.population)
            else -> "Settlement"
        }
        val latDir = if (city.point.lat >= 0) "N" else "S"
        val lonDir = if (city.point.lon >= 0) "E" else "W"
        val coords = "%.1f°%s, %.1f°%s".format(
            kotlin.math.abs(city.point.lat), latDir,
            kotlin.math.abs(city.point.lon), lonDir
        )
        return "$popDesc in $countryFull ($regionName time zone · $coords)."
    }
}
