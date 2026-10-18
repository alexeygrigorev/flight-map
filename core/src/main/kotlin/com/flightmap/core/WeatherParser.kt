package com.flightmap.core

import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

object WeatherParser {
    /**
     * Parses an Open-Meteo JSON string with hourly forecast data.
     * Expects hourly arrays: time (epoch seconds via timeformat=unixtime),
     * temperature_2m, weather_code, and optional is_day.
     * Rejects malformed JSON or arrays with unequal lengths.
     */
    fun parse(json: String): List<WeatherHour> {
        if (json.isBlank()) {
            throw IllegalArgumentException("JSON string cannot be blank")
        }

        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed JSON", e)
        }

        if (!root.isJsonObject) {
            throw IllegalArgumentException("Root must be a JSON object")
        }

        val rootObj = root.asJsonObject
        if (!rootObj.has("hourly") || !rootObj.get("hourly").isJsonObject) {
            throw IllegalArgumentException("Missing or invalid 'hourly' object")
        }

        val hourly = rootObj.getAsJsonObject("hourly")

        if (!hourly.has("time") || !hourly.get("time").isJsonArray) {
            throw IllegalArgumentException("Missing or invalid 'time' array in hourly")
        }
        if (!hourly.has("temperature_2m") || !hourly.get("temperature_2m").isJsonArray) {
            throw IllegalArgumentException("Missing or invalid 'temperature_2m' array in hourly")
        }
        if (!hourly.has("weather_code") || !hourly.get("weather_code").isJsonArray) {
            throw IllegalArgumentException("Missing or invalid 'weather_code' array in hourly")
        }

        val timeArray = hourly.getAsJsonArray("time")
        val tempArray = hourly.getAsJsonArray("temperature_2m")
        val codeArray = hourly.getAsJsonArray("weather_code")

        val size = timeArray.size()
        if (tempArray.size() != size || codeArray.size() != size) {
            throw IllegalArgumentException(
                "Hourly arrays must have equal lengths: time=$size, temperature_2m=${tempArray.size()}, weather_code=${codeArray.size()}"
            )
        }

        val isDayArray = if (hourly.has("is_day") && !hourly.get("is_day").isJsonNull) {
            if (!hourly.get("is_day").isJsonArray) {
                throw IllegalArgumentException("'is_day' must be a JSON array if provided")
            }
            val arr = hourly.getAsJsonArray("is_day")
            if (arr.size() != size) {
                throw IllegalArgumentException(
                    "'is_day' array length (${arr.size()}) does not match time array length ($size)"
                )
            }
            arr
        } else {
            null
        }

        val result = ArrayList<WeatherHour>(size)
        for (i in 0 until size) {
            val timeElem = timeArray[i]
            val tempElem = tempArray[i]
            val codeElem = codeArray[i]

            val epochSeconds = try {
                timeElem.asLong
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid time value at index $i: $timeElem", e)
            }

            val temp = try {
                tempElem.asDouble
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid temperature_2m value at index $i: $tempElem", e)
            }

            val code = try {
                codeElem.asInt
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid weather_code value at index $i: $codeElem", e)
            }

            val isDay = if (isDayArray != null) {
                val dayElem = isDayArray[i]
                try {
                    if (dayElem.isJsonPrimitive && dayElem.asJsonPrimitive.isBoolean) {
                        dayElem.asBoolean
                    } else {
                        dayElem.asInt == 1
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid is_day value at index $i: $dayElem", e)
                }
            } else {
                true
            }

            result.add(
                WeatherHour(
                    time = Instant.ofEpochSecond(epochSeconds),
                    temperature = temp,
                    code = code,
                    isDay = isDay
                )
            )
        }

        return result
    }

    /**
     * Finds the WeatherHour closest to the requested instant.
     * Returns null if the list is empty or if the closest hour is beyond 90 minutes.
     */
    fun nearest(hours: List<WeatherHour>, instant: Instant): WeatherHour? {
        if (hours.isEmpty()) return null
        val maxDiffMillis = 90L * 60L * 1000L // 90 minutes in milliseconds

        val closest = hours.minByOrNull {
            abs(Duration.between(it.time, instant).toMillis())
        } ?: return null

        val diff = abs(Duration.between(closest.time, instant).toMillis())
        return if (diff > maxDiffMillis) null else closest
    }
}
