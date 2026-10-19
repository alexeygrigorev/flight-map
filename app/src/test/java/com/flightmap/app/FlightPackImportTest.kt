package com.flightmap.app

import com.flightmap.core.Airport
import com.flightmap.core.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FlightPackImportTest {
    private val airports = listOf(
        Airport("PVG", "Shanghai Pudong", GeoPoint(31.1434, 121.805), "Asia/Shanghai"),
        Airport("DYG", "Zhangjiajie", GeoPoint(29.10475, 110.44279), "Asia/Shanghai"),
        Airport("CAN", "Guangzhou", GeoPoint(23.3924, 113.299), "Asia/Shanghai"),
        Airport("HND", "Haneda", GeoPoint(35.54968, 139.78696), "Asia/Tokyo"),
        Airport("NRT", "Narita", GeoPoint(35.76858, 140.38871), "Asia/Tokyo"),
        Airport("AUH", "Zayed", GeoPoint(24.44097, 54.64924), "Asia/Dubai"),
        Airport("BER", "Berlin", GeoPoint(52.36174, 13.50234), "Europe/Berlin")
    ).associateBy { it.iata }

    @Test
    fun parseTripMultipack() {
        val json = """
        {
          "format": "flight-map-multipack",
          "title": "trip",
          "flights": [
            {
              "plan": {
                "number": "MU689",
                "originIata": "PVG",
                "destinationIata": "DYG",
                "departureLocal": "2026-10-20T10:00",
                "arrivalLocal": "2026-10-20T12:10"
              }
            },
            {
              "plan": {
                "number": "DE2553",
                "originIata": "AUH",
                "destinationIata": "BER",
                "departureLocal": "2026-10-31T02:20",
                "arrivalLocal": "2026-10-31T06:20"
              }
            }
          ]
        }
        """.trimIndent()
        val imported = FlightPackImport.parse(json, airports)
        assertEquals(2, imported.packs.size)
        assertEquals("MU689", imported.packs[0].plan.number)
        assertEquals(Instant.parse("2026-10-20T02:00:00Z"), imported.packs[0].plan.departure)
        assertEquals("DE2553", imported.packs[1].plan.number)
        assertEquals(Instant.parse("2026-10-30T22:20:00Z"), imported.packs[1].plan.departure)
    }

    @Test
    fun parseDemoStartsNow() {
        val json = """
        {
          "format": "flight-map-pack",
          "demoStartsNow": true,
          "flight": {
            "plan": {
              "number": "MU689",
              "originIata": "PVG",
              "destinationIata": "DYG",
              "departure": "2026-10-20T02:00:00Z",
              "arrival": "2026-10-20T04:10:00Z"
            }
          }
        }
        """.trimIndent()
        val imported = FlightPackImport.parse(json, airports)
        assertTrue(imported.demoStartsNow)
        assertEquals(1, imported.packs.size)
        assertEquals("MU689", imported.packs[0].plan.number)
    }
}
