package com.flightmap.app

import com.flightmap.core.City
import com.flightmap.core.GeoPoint
import com.flightmap.core.WeatherSampling
import org.junit.Assert.*
import org.junit.Test

class WeatherSamplingTest {

    private fun generateCities(total: Int, majorCount: Int): List<City> {
        val list = mutableListOf<City>()
        for (i in 0 until total) {
            val isMajor = i < majorCount
            val pop = if (isMajor) 20000 + i * 1000 else 1000 + (i % 5000)
            list.add(
                City(
                    id = "city-$i",
                    name = "City $i",
                    country = "XX",
                    point = GeoPoint(10.0 + (i * 0.05), 20.0 + (i * 0.05)),
                    timezone = "UTC",
                    population = pop
                )
            )
        }
        return list
    }


    @Test
    fun testUnderLimitReturnsAll() {
        val cities = generateCities(350, 50)
        val sampled = WeatherSampling.selectCities(cities, 600)
        assertEquals(350, sampled.size)
        assertEquals(cities, sampled)
    }

    @Test
    fun testExactlyAtLimit() {
        val cities = generateCities(600, 100)
        val sampled = WeatherSampling.selectCities(cities, 600)
        assertEquals(600, sampled.size)
    }

    @Test
    fun testOverLimitWithFewerThan600MajorCities() {
        // 2000 total cities, 200 major (pop >= 15000)
        val cities = generateCities(2000, 200)
        val sampled = WeatherSampling.selectCities(cities, 600)

        assertTrue(sampled.size <= 600)
        assertTrue(sampled.size >= 590) // Allows small rounding

        // All 200 major cities must be included
        val majorCities = cities.filter { it.population >= 15000 }
        assertTrue(sampled.containsAll(majorCities))

        // Remaining quota filled by minor cities
        val minorSampled = sampled.filter { it.population < 15000 }
        assertEquals(sampled.size - 200, minorSampled.size)
    }

    @Test
    fun testOverLimitWithMoreThan600MajorCities() {
        // 1200 major cities
        val cities = generateCities(1500, 1200)
        val sampled = WeatherSampling.selectCities(cities, 600)

        assertTrue(sampled.size <= 600)
        assertTrue(sampled.size >= 595)
        // All sampled cities should be major since there are >= 600 major cities
        assertTrue(sampled.all { it.population >= 15000 })
    }

    @Test
    fun testEmptyCities() {
        val sampled = WeatherSampling.selectCities(emptyList(), 600)
        assertTrue(sampled.isEmpty())
    }
}
