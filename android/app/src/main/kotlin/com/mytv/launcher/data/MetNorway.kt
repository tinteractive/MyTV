// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Second weather source, used only when Open-Meteo cannot be reached (as tvOS-Launcher does): MET Norway's
 * locationforecast (free, CC BY 4.0, credited under About). Its answer is turned into Open-Meteo's shape, so the
 * cache, the screens and the icons stay as they are. Sunrise/sunset and the moon are calculated locally.
 */
internal object MetNorway {
    fun url(city: City) = "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=%.2f&lon=%.2f".format(java.util.Locale.ROOT, city.lat, city.lon)

    /** MET symbol ("lightrainshowers_day") → WMO weather code as Open-Meteo uses it. */
    fun code(symbol: String?): Int = when (symbol.orEmpty().substringBefore('_')) {
        "clearsky" -> 0; "fair" -> 1; "partlycloudy" -> 2; "cloudy" -> 3; "fog" -> 45
        "lightrain" -> 61; "rain" -> 63; "heavyrain" -> 65
        "lightrainshowers" -> 80; "rainshowers" -> 81; "heavyrainshowers" -> 82
        "lightsleet", "sleet" -> 66; "heavysleet" -> 67
        "lightsleetshowers", "sleetshowers", "heavysleetshowers" -> 85
        "lightsnow" -> 71; "snow" -> 73; "heavysnow" -> 75
        "lightsnowshowers", "snowshowers" -> 85; "heavysnowshowers" -> 86
        else -> if ("thunder" in symbol.orEmpty()) 95 else 3
    }

    /** Pure, unit-tested: MET's JSON → Open-Meteo's (current, 24 hours, 8 days), in °F/mph/inch when asked. */
    fun toOpenMeteo(met: JSONObject, fahrenheit: Boolean, zone: ZoneId): JSONObject {
        val ts = met.getJSONObject("properties").getJSONArray("timeseries")
        fun t(c: Double) = if (fahrenheit) c * 9 / 5 + 32 else c
        fun wind(ms: Double) = if (fahrenheit) ms * 2.236936 else ms * 3.6
        fun mm(v: Double) = if (fahrenheit) v / 25.4 else v
        fun local(i: Int) = Instant.parse(ts.getJSONObject(i).getString("time")).atZone(zone).toLocalDateTime()
        fun data(i: Int) = ts.getJSONObject(i).getJSONObject("data")
        fun details(i: Int) = data(i).getJSONObject("instant").getJSONObject("details")
        fun next(i: Int, h: Int) = data(i).optJSONObject("next_${h}_hours")
        fun symbol(i: Int) = (next(i, 1) ?: next(i, 6) ?: next(i, 12))?.optJSONObject("summary")?.optString("symbol_code")
        fun prob(i: Int) = listOf(1, 6, 12).mapNotNull { h -> next(i, h)?.optJSONObject("details")?.takeIf { it.has("probability_of_precipitation") }?.getDouble("probability_of_precipitation") }.firstOrNull()

        val d0 = details(0)
        val s0 = symbol(0)
        val current = JSONObject()
            .put("temperature_2m", t(d0.getDouble("air_temperature")))
            .put("apparent_temperature", t(d0.optDouble("apparent_air_temperature", d0.getDouble("air_temperature"))))
            .put("weather_code", code(s0)).put("is_day", if (s0?.endsWith("_night") == true) 0 else 1)
            .put("relative_humidity_2m", Math.round(d0.optDouble("relative_humidity", 0.0)).toInt())
            .put("wind_speed_10m", wind(d0.optDouble("wind_speed", 0.0)))
            .put("wind_direction_10m", Math.round(d0.optDouble("wind_from_direction", 0.0)).toInt())
            .put("precipitation", mm(next(0, 1)?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0))

        val hourly = JSONObject(); val ht = JSONArray(); val hc = JSONArray(); val htemp = JSONArray(); val hp = JSONArray(); val hd = JSONArray()
        for (i in 0 until ts.length()) {
            if (ht.length() >= 24) break
            if (next(i, 1) == null) continue
            ht.put(local(i).toString()); hc.put(code(symbol(i))); htemp.put(t(details(i).getDouble("air_temperature")))
            prob(i)?.let { hp.put(Math.round(it).toInt()) } ?: hp.put(JSONObject.NULL)
            hd.put(if (symbol(i)?.endsWith("_night") == true) 0 else 1)
        }
        hourly.put("time", ht).put("weather_code", hc).put("temperature_2m", htemp).put("precipitation_probability", hp).put("is_day", hd)

        // Days: the steps of each local date (hourly first, six-hourly later).
        val byDay = LinkedHashMap<LocalDate, MutableList<Int>>()
        for (i in 0 until ts.length()) byDay.getOrPut(local(i).toLocalDate()) { mutableListOf() } += i
        val daily = JSONObject(); val dt = JSONArray(); val dc = JSONArray(); val dmax = JSONArray(); val dmin = JSONArray(); val dp = JSONArray(); val ds = JSONArray()
        // MET has no sun times: calculated here for the place it answered for ([lon, lat, height]).
        val at = met.optJSONObject("geometry")?.optJSONArray("coordinates")
        val rise = JSONArray(); val set = JSONArray()
        byDay.entries.take(8).forEach { (date, steps) ->
            val temps = steps.map { details(it).getDouble("air_temperature") }
            val noon = steps.minByOrNull { kotlin.math.abs(local(it).hour - 12) }!!
            val sum = steps.sumOf { i ->
                // Hourly steps count their own hour; the later six-hourly steps their six hours.
                (next(i, 1) ?: next(i, 6))?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
            }
            dt.put(date.toString()); dc.put(code(symbol(noon))); dmax.put(t(temps.max())); dmin.put(t(temps.min()))
            steps.mapNotNull { prob(it) }.maxOrNull()?.let { dp.put(Math.round(it).toInt()) } ?: dp.put(JSONObject.NULL)
            ds.put(mm(sum))
            val sun = if (at != null) Moon.sunTimes(date, at.getDouble(1), at.getDouble(0), zone) else null to null
            rise.put(sun.first?.toString() ?: JSONObject.NULL); set.put(sun.second?.toString() ?: JSONObject.NULL)
        }
        daily.put("time", dt).put("weather_code", dc).put("temperature_2m_max", dmax).put("temperature_2m_min", dmin)
            .put("precipitation_probability_max", dp).put("precipitation_sum", ds).put("sunrise", rise).put("sunset", set)
        return JSONObject().put("current", current).put("hourly", hourly).put("daily", daily).put("_source", "met")
    }
}
