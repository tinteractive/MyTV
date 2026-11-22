// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import com.mytv.launcher.BuildConfig
import com.mytv.launcher.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime

data class DayForecast(
    val date: LocalDate, val code: Int, val max: Double, val min: Double,
    val precipProb: Int?, val precipSum: Double?, val sunrise: LocalDateTime?, val sunset: LocalDateTime?,
    val moonrise: LocalDateTime? = null, val moonset: LocalDateTime? = null, val moonPhase: Double? = null,
)

data class HourForecast(val time: LocalDateTime, val code: Int, val temp: Double, val precipProb: Int?, val isDay: Boolean)

data class WeatherReport(
    val city: City,
    val temp: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    val humidity: Int?,
    val windKmh: Double?,
    val windDir: Int?,
    val precipMm: Double?,
    val hours: List<HourForecast>,
    val days: List<DayForecast>,
    val fetchedAt: Long,          // System.currentTimeMillis() at fetch
    /** Units Open-Meteo returned this report in: °F, mph and inch when true; °C, km/h and mm otherwise. */
    val fahrenheit: Boolean = false,
)

data class WeatherState(val report: WeatherReport?, val error: String?, val loading: Boolean) {
    /** Null when unknown, also when the TV's clock is not set yet after a cold start (it then lies in the past). */
    fun ageMinutes(now: Long = System.currentTimeMillis()) = report?.let { (now - it.fetchedAt) / 60000 }?.takeIf { it >= 0 }
    /**
     * Worth showing: a forecast at most 3 hours old. Otherwise (no network for a long time, never loaded, the
     * clock not set yet) the weather is simply not shown, without a message; it comes back once a fetch succeeds.
     */
    fun usable(now: Long = System.currentTimeMillis()) = (ageMinutes(now) ?: Long.MAX_VALUE) < 180

    /** Older than two refresh periods is shown as stale. */
    fun isStale(now: Long = System.currentTimeMillis()) = (ageMinutes(now) ?: Long.MAX_VALUE) > 75
}

/** A geocoding hit shown in the city search list. */
data class Place(val city: City, val region: String?, val country: String?) {
    val subtitle get() = listOfNotNull(region, country).joinToString(", ")
}

object Wmo {
    /** String resource for a WMO weather code (localized via res/values-xx). */
    fun res(code: Int): Int = when (code) {
        0 -> R.string.wmo_clear; 1 -> R.string.wmo_mainly_clear; 2 -> R.string.wmo_partly; 3 -> R.string.wmo_overcast
        45, 48 -> R.string.wmo_fog
        51, 53, 55 -> R.string.wmo_drizzle; 56, 57 -> R.string.wmo_freezing_drizzle
        61 -> R.string.wmo_light_rain; 63 -> R.string.wmo_rain; 65 -> R.string.wmo_heavy_rain; 66, 67 -> R.string.wmo_freezing_rain
        71 -> R.string.wmo_light_snow; 73 -> R.string.wmo_snow; 75 -> R.string.wmo_heavy_snow; 77 -> R.string.wmo_snow_grains
        80 -> R.string.wmo_light_showers; 81 -> R.string.wmo_showers; 82 -> R.string.wmo_heavy_showers; 85, 86 -> R.string.wmo_snow_showers
        95 -> R.string.wmo_thunder; 96, 99 -> R.string.wmo_thunder_hail
        else -> R.string.wmo_unknown
    }

    /** Index 0..7 (N, NE, E, ...) into R.array.wind_dirs. */
    fun windIndex(deg: Int) = ((deg % 360 + 22) / 45) % 8
}

/**
 * Open-Meteo (free tier, non-commercial/personal home automation, CC BY 4.0 attribution shown in UI), with MET Norway
 * (CC BY 4.0) as second source when Open-Meteo cannot be reached.
 * City-level coordinates only. Responses are cached so the screen works offline and shows age honestly.
 */
class WeatherRepository(context: Context, private val prefs: Prefs) {
    /** The last forecast of the current place only (was: one per place ever chosen, inside the settings file). */
    private val file = java.io.File(context.filesDir, "weather.json")

    fun cached(city: City): WeatherReport? = runCatching {
        val o = JSONObject(file.readText())
        if (o.optString("_place") != city.name) null else parse(city, o)
    }.getOrNull() ?: prefs.oldWeatherCache(city)?.let { runCatching { parse(city, JSONObject(it)) }.getOrNull() }

    suspend fun fetch(city: City, fahrenheit: Boolean): WeatherReport = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m,wind_direction_10m,precipitation" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&forecast_hours=24" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,sunrise,sunset,moonrise,moonset,moon_phase" +
            "&timezone=" + URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8") + "&forecast_days=8" +
            // Open-Meteo converts itself (no rounding differences): °F + mph + inch, or the metric defaults.
            (if (fahrenheit) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else "")
        // Open-Meteo first; when it cannot be reached, MET Norway.
        val o = runCatching { JSONObject(get(url)) }.getOrElse { first ->
            android.util.Log.w("MyTV", "weather: Open-Meteo failed ($first), trying MET Norway")
            MetNorway.toOpenMeteo(JSONObject(get(MetNorway.url(city))), fahrenheit, java.time.ZoneId.systemDefault())
        }.put("_fetchedAt", System.currentTimeMillis()).put("_fahrenheit", fahrenheit)
        val tmp = java.io.File(file.path + ".part")
        tmp.writeText(o.put("_place", city.name).toString()); tmp.renameTo(file)
        prefs.dropOldWeatherCache()
        parse(city, o)
    }

    /** Free-text place search (city level). Open-Meteo geocoding, Dutch names where available. */
    suspend fun search(text: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(text.trim(), "UTF-8")
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=12&language=${java.util.Locale.getDefault().toLanguageTag().substringBefore('-')}&format=json"))
        val a = o.optJSONArray("results") ?: return@withContext emptyList()
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            Place(
                City(r.getString("name"), Math.round(r.getDouble("latitude") * 100) / 100.0, Math.round(r.getDouble("longitude") * 100) / 100.0),
                r.optString("admin1").ifBlank { null }, r.optString("country").ifBlank { null },
            )
        }.distinctBy { "${it.city.name}|${it.subtitle}" }
    }

    suspend fun geocode(name: String): City? = runCatching { search(name).firstOrNull()?.city }.getOrNull()

    // MET Norway asks for an identifying User-Agent with a way to reach the maker: the public project page.
    private fun get(url: String): String =
        String(Net.get(url, 8000, 8000, mapOf("User-Agent" to "MyTV/" + BuildConfig.VERSION_NAME + " (TV home screen; github.com/tinteractive/MyTV)")))

    private fun parse(city: City, o: JSONObject): WeatherReport {
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val t = d.getJSONArray("time")
        fun JSONArray?.intOrNull(i: Int) = if (this == null || isNull(i)) null else getInt(i)
        fun JSONArray?.dblOrNull(i: Int) = if (this == null || isNull(i)) null else getDouble(i)
        fun JSONArray?.timeOrNull(i: Int) = if (this == null || isNull(i)) null else runCatching { LocalDateTime.parse(getString(i)) }.getOrNull()
        val days = (0 until t.length()).map {
            DayForecast(
                LocalDate.parse(t.getString(it)), d.getJSONArray("weather_code").getInt(it),
                d.getJSONArray("temperature_2m_max").getDouble(it), d.getJSONArray("temperature_2m_min").getDouble(it),
                d.optJSONArray("precipitation_probability_max").intOrNull(it), d.optJSONArray("precipitation_sum").dblOrNull(it),
                d.optJSONArray("sunrise").timeOrNull(it), d.optJSONArray("sunset").timeOrNull(it),
                d.optJSONArray("moonrise").timeOrNull(it), d.optJSONArray("moonset").timeOrNull(it), d.optJSONArray("moon_phase").dblOrNull(it),
            )
        }
        val hours = o.optJSONObject("hourly")?.let { h ->
            val ht = h.getJSONArray("time")
            (0 until ht.length()).map {
                HourForecast(
                    LocalDateTime.parse(ht.getString(it)), h.getJSONArray("weather_code").getInt(it),
                    h.getJSONArray("temperature_2m").getDouble(it), h.optJSONArray("precipitation_probability").intOrNull(it),
                    h.optJSONArray("is_day").intOrNull(it) != 0,
                )
            }
        } ?: emptyList()
        return WeatherReport(
            city, cur.getDouble("temperature_2m"), cur.getDouble("apparent_temperature"),
            cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1,
            if (cur.has("relative_humidity_2m")) cur.optInt("relative_humidity_2m") else null,
            if (cur.has("wind_speed_10m")) cur.optDouble("wind_speed_10m") else null,
            if (cur.has("wind_direction_10m")) cur.optInt("wind_direction_10m") else null,
            if (cur.has("precipitation")) cur.optDouble("precipitation") else null,
            hours, days, o.optLong("_fetchedAt", 0), o.optBoolean("_fahrenheit", false),
        )
    }
}
