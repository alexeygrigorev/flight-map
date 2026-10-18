package com.flightmap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelsTest {

    @Test
    fun testGeoPointAndAirportAndCityCreation() {
        val point = GeoPoint(51.4700, -0.4543)
        assertEquals(51.4700, point.lat, 1e-6)
        assertEquals(-0.4543, point.lon, 1e-6)

        val airport = Airport("LHR", "London Heathrow", point, "Europe/London")
        assertEquals("LHR", airport.iata)
        assertEquals("London Heathrow", airport.name)
        assertEquals("Europe/London", airport.timezone)

        val city = City("london", "London", "United Kingdom", point, "Europe/London", 8982000)
        assertEquals("london", city.id)
        assertEquals("London", city.name)
        assertEquals("United Kingdom", city.country)
        assertEquals(8982000, city.population)
    }

    @Test
    fun testFlightPlanValid() {
        val dep = Instant.parse("2026-10-18T10:00:00Z")
        val arr = Instant.parse("2026-10-18T18:00:00Z")
        val origin = Airport("JFK", "John F. Kennedy", GeoPoint(40.6413, -73.7781), "America/New_York")
        val dest = Airport("LHR", "Heathrow", GeoPoint(51.4700, -0.4543), "Europe/London")

        val plan = FlightPlan("BA178", origin, dest, dep, arr)
        assertEquals("BA178", plan.number)
        assertEquals(origin, plan.origin)
        assertEquals(dest, plan.destination)
        assertEquals(dep, plan.departure)
        assertEquals(arr, plan.arrival)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFlightPlanArrivalEqualToDepartureThrows() {
        val t = Instant.parse("2026-10-18T10:00:00Z")
        val origin = Airport("JFK", "JFK", GeoPoint(40.6413, -73.7781), "America/New_York")
        val dest = Airport("LHR", "LHR", GeoPoint(51.4700, -0.4543), "Europe/London")

        FlightPlan("BA178", origin, dest, t, t)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFlightPlanArrivalBeforeDepartureThrows() {
        val dep = Instant.parse("2026-10-18T10:00:00Z")
        val arr = Instant.parse("2026-10-18T09:00:00Z")
        val origin = Airport("JFK", "JFK", GeoPoint(40.6413, -73.7781), "America/New_York")
        val dest = Airport("LHR", "LHR", GeoPoint(51.4700, -0.4543), "Europe/London")

        FlightPlan("BA178", origin, dest, dep, arr)
    }

    @Test
    fun testWeatherHourDefaultIsDay() {
        val now = Instant.now()
        val hour = WeatherHour(now, 15.5, 3)
        assertEquals(now, hour.time)
        assertEquals(15.5, hour.temperature, 1e-6)
        assertEquals(3, hour.code)
        assertTrue(hour.isDay)
    }
}
