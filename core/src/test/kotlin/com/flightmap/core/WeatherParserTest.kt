package com.flightmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherParserTest {

    private val validJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "hourly": {
            "time": [1700000000, 1700003600, 1700007200],
            "temperature_2m": [12.5, 13.1, 10.8],
            "weather_code": [0, 1, 3],
            "is_day": [1, 1, 0]
          }
        }
    """.trimIndent()

    @Test
    fun testParseValidJsonWithIsDay() {
        val hours = WeatherParser.parse(validJson)
        assertEquals(3, hours.size)

        assertEquals(Instant.ofEpochSecond(1700000000), hours[0].time)
        assertEquals(12.5, hours[0].temperature, 1e-4)
        assertEquals(0, hours[0].code)
        assertTrue(hours[0].isDay)

        assertEquals(Instant.ofEpochSecond(1700003600), hours[1].time)
        assertEquals(13.1, hours[1].temperature, 1e-4)
        assertEquals(1, hours[1].code)
        assertTrue(hours[1].isDay)

        assertEquals(Instant.ofEpochSecond(1700007200), hours[2].time)
        assertEquals(10.8, hours[2].temperature, 1e-4)
        assertEquals(3, hours[2].code)
        assertFalse(hours[2].isDay)
    }

    @Test
    fun testParseValidJsonWithoutIsDay() {
        val jsonNoIsDay = """
            {
              "hourly": {
                "time": [1700000000],
                "temperature_2m": [15.0],
                "weather_code": [2]
              }
            }
        """.trimIndent()

        val hours = WeatherParser.parse(jsonNoIsDay)
        assertEquals(1, hours.size)
        assertEquals(15.0, hours[0].temperature, 1e-4)
        assertEquals(2, hours[0].code)
        assertTrue(hours[0].isDay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseEmptyJsonThrows() {
        WeatherParser.parse("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseMalformedJsonThrows() {
        WeatherParser.parse("{ invalid json }")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseMissingHourlyThrows() {
        WeatherParser.parse("""{"latitude": 52.52}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseUnequalArraysThrows() {
        val jsonUnequal = """
            {
              "hourly": {
                "time": [1700000000, 1700003600],
                "temperature_2m": [15.0],
                "weather_code": [0, 1]
              }
            }
        """.trimIndent()
        WeatherParser.parse(jsonUnequal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testParseUnequalIsDayThrows() {
        val jsonUnequalIsDay = """
            {
              "hourly": {
                "time": [1700000000, 1700003600],
                "temperature_2m": [15.0, 16.0],
                "weather_code": [0, 1],
                "is_day": [1]
              }
            }
        """.trimIndent()
        WeatherParser.parse(jsonUnequalIsDay)
    }

    @Test
    fun testNearestEmptyListReturnsNull() {
        val res = WeatherParser.nearest(emptyList(), Instant.now())
        assertNull(res)
    }

    @Test
    fun testNearestWithin90Minutes() {
        val t0 = Instant.ofEpochSecond(1700000000)
        val h0 = WeatherHour(t0, 10.0, 1)
        val h1 = WeatherHour(t0.plusSeconds(3600), 12.0, 2)
        val hours = listOf(h0, h1)

        // Query 20 minutes after t0
        val nearest = WeatherParser.nearest(hours, t0.plusSeconds(1200))
        assertNotNull(nearest)
        assertEquals(h0, nearest)

        // Query exactly at t0 + 90 minutes (5400 seconds)
        val atBoundary = WeatherParser.nearest(hours, t0.plusSeconds(5400))
        assertNotNull(atBoundary)
        // t0+5400 is 1800s (30m) away from h1 (t0+3600), so closest is h1
        assertEquals(h1, atBoundary)
    }

    @Test
    fun testNearestBeyond90MinutesReturnsNull() {
        val t0 = Instant.ofEpochSecond(1700000000)
        val h0 = WeatherHour(t0, 10.0, 1)
        val hours = listOf(h0)

        // 91 minutes away (5460 seconds)
        val target = t0.plusSeconds(5460)
        val res = WeatherParser.nearest(hours, target)
        assertNull("Should return null when beyond 90 minutes", res)

        // 90 minutes + 1 second away
        val resJustBeyond = WeatherParser.nearest(hours, t0.plusSeconds(5401))
        assertNull(resJustBeyond)
    }

    @Test
    fun testConditionTextAndSymbol() {
        assertEquals("Clear", WeatherParser.conditionText(0))
        assertEquals("Partly cloudy", WeatherParser.conditionText(1))
        assertEquals("Overcast", WeatherParser.conditionText(3))
        assertEquals("Rain", WeatherParser.conditionText(63))
        assertEquals("Snow", WeatherParser.conditionText(73))
        assertEquals("Thunderstorm", WeatherParser.conditionText(95))

        assertEquals("☀", WeatherParser.conditionSymbol(0, isDay = true))
        assertEquals("☾", WeatherParser.conditionSymbol(0, isDay = false))
        assertEquals("🌧", WeatherParser.conditionSymbol(63))
        assertEquals("❄", WeatherParser.conditionSymbol(73))
    }
}
