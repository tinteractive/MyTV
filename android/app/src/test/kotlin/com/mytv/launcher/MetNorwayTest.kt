// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.MetNorway
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class MetNorwayTest {
    private fun step(time: String, temp: Double, symbol: String, rain: Double, hourly: Boolean = true): JSONObject {
        val next = JSONObject().put("summary", JSONObject().put("symbol_code", symbol))
            .put("details", JSONObject().put("precipitation_amount", rain).put("probability_of_precipitation", 40.0))
        val data = JSONObject().put("instant", JSONObject().put("details", JSONObject()
            .put("air_temperature", temp).put("apparent_air_temperature", temp - 2).put("relative_humidity", 80.0)
            .put("wind_speed", 5.0).put("wind_from_direction", 270.0)))
            .put(if (hourly) "next_1_hours" else "next_6_hours", next)
        return JSONObject().put("time", time).put("data", data)
    }

    private val met = JSONObject().put("geometry", JSONObject().put("coordinates", JSONArray().put(4.90).put(52.37).put(2))).put("properties", JSONObject().put("timeseries", JSONArray()
        .put(step("2026-11-14T22:00:00Z", 1.0, "fair_night", 0.0))
        .put(step("2026-11-14T23:00:00Z", 2.0, "lightrain_night", 0.5))
        .put(step("2026-11-15T11:00:00Z", 8.0, "partlycloudy_day", 0.0))
        .put(step("2026-11-15T18:00:00Z", 4.0, "heavyrainshowers_night", 3.0, hourly = false))))

    @Test fun currentHoursAndDays() {
        val o = MetNorway.toOpenMeteo(met, fahrenheit = false, zone = ZoneId.of("Europe/Amsterdam"))
        val cur = o.getJSONObject("current")
        assertEquals(1.0, cur.getDouble("temperature_2m"), 0.01)
        assertEquals(-1.0, cur.getDouble("apparent_temperature"), 0.01)
        assertEquals(1, cur.getInt("weather_code")); assertEquals(0, cur.getInt("is_day"))
        assertEquals(18.0, cur.getDouble("wind_speed_10m"), 0.01)                    // 5 m/s = 18 km/h
        val h = o.getJSONObject("hourly")
        assertEquals("2026-11-14T23:00", h.getJSONArray("time").getString(0))          // UTC → local time
        assertEquals(3, h.getJSONArray("time").length())                              // only the hourly steps
        val d = o.getJSONObject("daily")
        assertEquals("2026-11-15", d.getJSONArray("time").getString(1))
        assertEquals(8.0, d.getJSONArray("temperature_2m_max").getDouble(1), 0.01)
        assertEquals(2, d.getJSONArray("weather_code").getInt(1))                     // the step nearest noon
        assertEquals(3.5, d.getJSONArray("precipitation_sum").getDouble(1), 0.01)   // 23:00 UTC is 00:00 local: counts for the 15th
    }

    @Test fun sunTimesForAmsterdam() {
        // Amsterdam, 15 Nov 2026: sunrise about 08:01, sunset about 16:47 (CET).
        val (rise, set) = com.mytv.launcher.data.Moon.sunTimes(java.time.LocalDate.of(2026, 11, 15), 52.37, 4.90, ZoneId.of("Europe/Amsterdam"))
        assertEquals(8 * 60 + 1.0, rise!!.hour * 60 + rise.minute.toDouble(), 4.0)   // SunCalc: within a few minutes
        assertEquals(16 * 60 + 47.0, set!!.hour * 60 + set.minute.toDouble(), 4.0)
        val o = MetNorway.toOpenMeteo(met, fahrenheit = false, zone = ZoneId.of("Europe/Amsterdam"))
        assertEquals(true, o.getJSONObject("daily").getJSONArray("sunrise").getString(1).startsWith("2026-11-15T08:0"))
    }

    @Test fun fahrenheitAndCodes() {
        val o = MetNorway.toOpenMeteo(met, fahrenheit = true, zone = ZoneId.of("UTC"))
        assertEquals(33.8, o.getJSONObject("current").getDouble("temperature_2m"), 0.01)
        assertEquals(95, MetNorway.code("rainandthunder_day"))
        assertEquals(82, MetNorway.code("heavyrainshowers_night"))
    }
}
