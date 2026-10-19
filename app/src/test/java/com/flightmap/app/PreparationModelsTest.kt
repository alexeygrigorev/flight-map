package com.flightmap.app

import com.flightmap.core.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PreparationModelsTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, JsonSerializer<Instant> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(Instant::class.java, JsonDeserializer { json, _, _ -> Instant.parse(json.asString) })
        .create()

    private val airportHel = Airport("HEL", "Helsinki Airport", GeoPoint(60.3172, 24.9633), "Europe/Helsinki")
    private val airportPvg = Airport("PVG", "Shanghai Pudong", GeoPoint(31.1434, 121.8052), "Asia/Shanghai")
    private val dep = Instant.parse("2026-10-18T01:10:00Z")
    private val arr = Instant.parse("2026-10-18T18:25:00Z")
    private val plan = FlightPlan("AY87", airportHel, airportPvg, dep, arr)

    @Test
    fun testFlightIdentityMatching() {
        val samePlan = FlightPlan("AY87", airportHel, airportPvg, dep, arr)
        assertTrue(isSameFlight(plan, samePlan))

        val diffArrival = FlightPlan("AY87", airportHel, airportPvg, dep, arr.plusSeconds(3600))
        assertFalse(isSameFlight(plan, diffArrival))

        val returnLeg = FlightPlan("AY87", airportPvg, airportHel, dep, arr)
        assertFalse(isSameFlight(plan, returnLeg))

        val diffFlightNum = FlightPlan("AY88", airportHel, airportPvg, dep, arr)
        assertFalse(isSameFlight(plan, diffFlightNum))
    }

    @Test
    fun testFlightPackSerializationAndDefaults() {
        val city = City("hel-1", "Helsinki", "FI", GeoPoint(60.1699, 24.9384), "Europe/Helsinki", 650000)
        val weatherHours = listOf(
            WeatherHour(dep, 10.0, 1, true),
            WeatherHour(arr, 12.0, 1, true)
        )
        val pack = FlightPack(
            plan = plan,
            cities = listOf(city),
            weather = mapOf("hel-1" to weatherHours),
            preparedAt = dep,
            mapReady = true,
            mapRegionIds = listOf(42L, 99L),
            mapDetailSummary = "Corridor saved · zoom 0–8",
            sampledWeatherIds = listOf("hel-1")
        )

        val json = gson.toJson(pack)
        val deserialized = gson.fromJson(json, FlightPack::class.java)

        assertEquals(pack.plan.number, deserialized.plan.number)
        assertEquals(pack.effectiveMapDetailSummary, deserialized.effectiveMapDetailSummary)
        assertEquals(listOf(42L, 99L), deserialized.mapRegionIds)
        assertEquals(listOf("hel-1"), deserialized.effectiveSampledWeatherIds)
        assertTrue(deserialized.forecastsReady)
        assertTrue(deserialized.isWeatherReady())
    }

    @Test
    fun testLegacyPackMigrationDefaults() {
        val legacyJson = """
            {
              "plan": {
                "number": "AY87",
                "origin": {"iata": "HEL", "name": "Helsinki", "point": {"lat": 60.3, "lon": 24.9}, "timezone": "Europe/Helsinki"},
                "destination": {"iata": "PVG", "name": "Shanghai", "point": {"lat": 31.1, "lon": 121.8}, "timezone": "Asia/Shanghai"},
                "departure": "2026-10-18T01:10:00Z",
                "arrival": "2026-10-18T18:25:00Z"
              },
              "cities": [
                {"id": "c1", "name": "City1", "country": "FI", "point": {"lat": 60.0, "lon": 25.0}, "timezone": "Europe/Helsinki", "population": 100000}
              ],
              "weather": {
                "c1": [
                  {"time": "2026-10-18T01:10:00Z", "temperature": 12.0, "code": 1, "isDay": true},
                  {"time": "2026-10-18T18:25:00Z", "temperature": 15.0, "code": 1, "isDay": true}
                ]
              },
              "preparedAt": "2026-10-18T00:00:00Z",
              "mapReady": true,
              "mapRegionIds": [101]
            }
        """.trimIndent()

        val pack = gson.fromJson(legacyJson, FlightPack::class.java)
        assertEquals("AY87", pack.plan.number)
        assertTrue(pack.mapReady)
        assertEquals(listOf(101L), pack.mapRegionIds)
        assertEquals("Corridor saved · zoom 0–5", pack.effectiveMapDetailSummary)
        assertTrue(pack.forecastsReady)
    }

    @Test
    fun testForecastsReadyOceanRoute() {
        // Ocean route with 0 cities in corridor is valid and forecastsReady = true
        val oceanPack = FlightPack(
            plan = plan,
            cities = emptyList(),
            weather = emptyMap(),
            preparedAt = dep,
            mapReady = true
        )
        assertTrue(oceanPack.forecastsReady)
    }

    @Test
    fun testForecastsReadyMissingSampledCity() {
        val c1 = City("c1", "City1", "FI", GeoPoint(60.0, 25.0), "Europe/Helsinki", 500000)
        val c2 = City("c2", "City2", "FI", GeoPoint(61.0, 26.0), "Europe/Helsinki", 20000)
        val weatherHours = listOf(
            WeatherHour(dep, 10.0, 1, true),
            WeatherHour(arr, 12.0, 1, true)
        )

        // c1 and c2 were sampled, but only c1 has weather in map
        val packMissingC2 = FlightPack(
            plan = plan,
            cities = listOf(c1, c2),
            weather = mapOf("c1" to weatherHours),
            preparedAt = dep,
            mapReady = true,
            sampledWeatherIds = listOf("c1", "c2")
        )
        assertFalse(packMissingC2.forecastsReady)
    }

    @Test
    fun testForecastsReadyEmptyHours() {
        val c1 = City("c1", "City1", "FI", GeoPoint(60.0, 25.0), "Europe/Helsinki", 500000)
        val packEmptyHours = FlightPack(
            plan = plan,
            cities = listOf(c1),
            weather = mapOf("c1" to emptyList()),
            preparedAt = dep,
            mapReady = true,
            sampledWeatherIds = listOf("c1")
        )
        assertFalse(packEmptyHours.forecastsReady)
    }

    @Test
    fun testForecastsReadyIncompleteCoverage() {
        val c1 = City("c1", "City1", "FI", GeoPoint(60.0, 25.0), "Europe/Helsinki", 500000)
        // Weather only covers departure, missing arrival
        val hoursOnlyDep = listOf(WeatherHour(dep, 10.0, 1, true))
        val packIncomplete = FlightPack(
            plan = plan,
            cities = listOf(c1),
            weather = mapOf("c1" to hoursOnlyDep),
            preparedAt = dep,
            mapReady = true,
            sampledWeatherIds = listOf("c1")
        )
        assertFalse(packIncomplete.forecastsReady)
    }

    @Test
    fun testForecastsReadyCompleteCoverage() {
        val c1 = City("c1", "City1", "FI", GeoPoint(60.0, 25.0), "Europe/Helsinki", 500000)
        val fullHours = listOf(
            WeatherHour(dep, 10.0, 1, true),
            WeatherHour(arr, 15.0, 1, true)
        )
        val packComplete = FlightPack(
            plan = plan,
            cities = listOf(c1),
            weather = mapOf("c1" to fullHours),
            preparedAt = dep,
            mapReady = true,
            sampledWeatherIds = listOf("c1")
        )
        assertTrue(packComplete.forecastsReady)
    }

    @Test
    fun testPreparationCheckpointSerialization() {
        val checkpoint = PreparationCheckpoint(
            plan = plan,
            status = PreparationStatus.IN_PROGRESS,
            stage = "Downloading city forecasts",
            weatherDone = 15,
            weatherTotal = 30,
            weatherDetail = "15 / 30 forecasts",
            mapRegionIds = listOf(1L, 2L, 3L),
            mapReady = false,
            mapDetail = "Downloading layer corridor",
            routeReady = true,
            citiesReady = true,
            weatherReady = false,
            error = null,
            sampledWeatherIds = listOf("c1", "c2")
        )

        val json = gson.toJson(checkpoint)
        val restored = gson.fromJson(json, PreparationCheckpoint::class.java)

        assertEquals(checkpoint.plan.number, restored.plan.number)
        assertEquals(PreparationStatus.IN_PROGRESS, restored.status)
        assertEquals(15, restored.weatherDone)
        assertEquals(30, restored.weatherTotal)
        assertEquals(listOf(1L, 2L, 3L), restored.mapRegionIds)
        assertEquals(listOf("c1", "c2"), restored.effectiveSampledWeatherIds)
    }

    @Test
    fun testMultiPackListSerialization() {
        val pack1 = FlightPack(plan, emptyList(), emptyMap(), dep, true, listOf(1L))
        val plan2 = FlightPlan("LH400", airportHel, airportPvg, dep.plusSeconds(86400), arr.plusSeconds(86400))
        val pack2 = FlightPack(plan2, emptyList(), emptyMap(), dep.plusSeconds(86400), false, listOf(2L))

        val list = listOf(pack1, pack2)
        val json = gson.toJson(list)

        val type = object : com.google.gson.reflect.TypeToken<List<FlightPack>>() {}.type
        val restoredList: List<FlightPack> = gson.fromJson(json, type)

        assertEquals(2, restoredList.size)
        assertEquals("AY87", restoredList[0].plan.number)
        assertEquals("LH400", restoredList[1].plan.number)
    }

    @Test
    fun testDenseCorridorPackSummarySerialization() {
        val detailSummary = "Saved · z0–6 / z7–9 / z9–10 · 974 tiles · 32.1 MB"
        val pack = FlightPack(
            plan = plan,
            cities = emptyList(),
            weather = emptyMap(),
            preparedAt = dep,
            mapReady = true,
            mapRegionIds = listOf(10L, 20L, 30L),
            mapDetailSummary = detailSummary
        )

        val json = gson.toJson(pack)
        val restored = gson.fromJson(json, FlightPack::class.java)

        assertEquals(detailSummary, restored.effectiveMapDetailSummary)
        assertEquals(listOf(10L, 20L, 30L), restored.mapRegionIds)
        assertTrue(restored.mapReady)
    }

    @Test
    fun testCheckpointRestartStatePreservation() {
        val checkpoint = PreparationCheckpoint(
            plan = plan,
            status = PreparationStatus.PAUSED,
            stage = "Preparation paused",
            mapRegionIds = listOf(101L, 102L, 103L),
            mapReady = true,
            mapDetail = "Saved · z0–6 / z7–9 / z9–10 · 974 tiles · 32.1 MB",
            weatherReady = true,
            weatherDetail = "600 city forecasts saved",
            sampledWeatherIds = listOf("c1", "c2")
        )

        val json = gson.toJson(checkpoint)
        val restored = gson.fromJson(json, PreparationCheckpoint::class.java)

        assertEquals(PreparationStatus.PAUSED, restored.status)
        assertEquals(listOf(101L, 102L, 103L), restored.mapRegionIds)
        assertTrue(restored.mapReady)
        assertTrue(restored.weatherReady)
        assertEquals("Saved · z0–6 / z7–9 / z9–10 · 974 tiles · 32.1 MB", restored.mapDetail)
    }

    @Test
    fun testEnrichedTrajectoryPackSerialization() {
        val enriched = TrajectoryCatalog.enrichPlan(plan)
        assertTrue(enriched.isRealTrajectory)
        assertTrue(enriched.effectiveWaypoints.size >= 30)

        val pack = FlightPack(
            plan = enriched,
            cities = emptyList(),
            weather = emptyMap(),
            preparedAt = dep,
            mapReady = true
        )
        val json = gson.toJson(pack)
        val restored = gson.fromJson(json, FlightPack::class.java)

        assertTrue(restored.plan.isRealTrajectory)
        assertEquals(enriched.effectiveWaypoints.size, restored.plan.effectiveWaypoints.size)
        assertTrue(restored.plan.effectiveRouteLabel.contains("Southern Eurasian Corridor"))
    }
}

