// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A profile; the first one (id "") uses the settings MyTV had before profiles, so nothing is lost. */
data class Profile(val id: String, val name: String, val child: Boolean, val icon: Int = -1) {     // icon -1 = initial letter
    fun toJson() = JSONObject().put("id", id).put("name", name).put("child", child).put("icon", icon)
    companion object { fun fromJson(o: JSONObject) = Profile(o.getString("id"), o.getString("name").take(40), o.optBoolean("child"), o.optInt("icon", -1)) }
}

data class City(val name: String, val lat: Double, val lon: Double) {
    fun toJson() = JSONObject().put("name", name).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = City(o.getString("name").take(80), o.getDouble("lat").also { require(it in -90.0..90.0) }, o.getDouble("lon").also { require(it in -180.0..180.0) })
    }
}

/** Small typed wrapper around SharedPreferences. Everything stays on the TV. */
class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("mytv", Context.MODE_PRIVATE)

    // Readers that fall back to the default when a stored value has another type or cannot be parsed: a damaged or
    // hand-edited settings file must never keep the home screen from starting.
    private fun str(key: String, def: String?): String? = runCatching { sp.getString(p(key), def) }.getOrDefault(def)
    private fun bool(key: String, def: Boolean): Boolean = runCatching { sp.getBoolean(p(key), def) }.getOrDefault(def)
    private fun int(key: String, def: Int): Int = runCatching { sp.getInt(p(key), def) }.getOrDefault(def)
    private fun strings(key: String): Set<String> = runCatching { sp.getStringSet(p(key), emptySet())!!.toSet() }.getOrDefault(emptySet())
    private fun <T : Any> list(key: String, item: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(str(key, "[]"))
        (0 until a.length()).mapNotNull { i -> runCatching { item(a.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    // ---- profiles: dock order, hidden apps, known apps and background are per profile
    var profile: String
        get() = str("profile", "") ?: ""
        set(v) = sp.edit().putString(p("profile"), v).apply()
    private fun k(name: String, id: String = profile) = if (id.isEmpty()) name else "$name@$id"

    /**
     * Settings of the home screen itself are per profile: a new profile starts with a copy, after that each lives its
     * own life. Settings for the whole TV (the Home-button helper and the Sources/Apps buttons, the screensaver, quiet
     * hours, the PIN, Clean up, updates, learned key codes) are shared and only shown for the first profile.
     */
    fun p(name: String): String = if (name in PER_PROFILE || name.startsWith("key_")) k(name) else name

    /** Copies the per-profile settings of profile [from] to [to] (a new profile starts as a copy). */
    fun copySettings(from: String, to: String, alsoDock: Boolean) {
        val all = sp.all
        val e = sp.edit()
        val names = PER_PROFILE + COLORS.map { "key_$it" } + if (alsoDock) listOf("order", "bg") else emptyList()
        for (n in names) {
            val dst = k(n, to)
            when (val v = all[k(n, from)]) {
                null -> e.remove(dst)
                is String -> e.putString(dst, v)
                is Int -> e.putInt(dst, v)
                is Boolean -> e.putBoolean(dst, v)
                is Long -> e.putLong(dst, v)
                is Set<*> -> e.putStringSet(dst, v.filterIsInstance<String>().toSet())
            }
        }
        e.putBoolean("copied@$to", true).apply()
    }

    /** Profiles made before settings were per profile: each gets a copy of the shared settings once. */
    fun migrateProfiles() {
        for (pr in profiles) if (pr.id.isNotEmpty() && !sp.getBoolean("copied@${pr.id}", false)) {
            copySettings("", pr.id, alsoDock = false)
            sp.edit().putBoolean("copied@${pr.id}", true).apply()
        }
    }

    var profiles: List<Profile>
        get() = list("profiles", Profile::fromJson).distinctBy { it.id }
        set(v) = sp.edit().putString(p("profiles"), JSONArray(v.map { it.toJson() }).toString()).apply()

    fun hiddenFor(id: String): Set<String> = strings(k("hidden", id))
    fun setHiddenFor(id: String, v: Set<String>) = sp.edit().putStringSet(k("hidden", id), v).apply()
    fun knownFor(id: String): Set<String> = strings(k("known", id))
    fun setKnownFor(id: String, v: Set<String>) = sp.edit().putStringSet(k("known", id), v).apply()
    fun forgetProfile(id: String) {
        if (id.isEmpty()) return
        val e = sp.edit().remove(k("hidden", id)).remove(k("order", id)).remove(k("known", id)).remove(k("bg", id)).remove("copied@$id")
        (PER_PROFILE + COLORS.map { "key_$it" }).forEach { e.remove(k(it, id)) }
        e.apply()
    }

    /** A salted, slow hash of the PIN (never the PIN itself). */
    var pinHash: String?
        get() = str("pinHash", null)
        set(v) = sp.edit().putString(p("pinHash"), v).apply()
    /** Wrong PIN tries in a row (the wait after five grows with it). */
    var pinFails: Int
        get() = int("pinFails", 0).coerceIn(0, 1000)
        set(v) = sp.edit().putInt(p("pinFails"), v).apply()
    var askWho: Boolean
        get() = bool("askWho", false)
        set(v) = sp.edit().putBoolean(p("askWho"), v).apply()

    var order: List<String>
        get() = str(k("order"), "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(k("order"), v.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = strings(k("hidden"))
        set(v) = sp.edit().putStringSet(k("hidden"), v).apply()

    /** Apps we have seen before, so default-hidden rules only apply to new ones. */
    var known: Set<String>
        get() = strings(k("known"))
        set(v) = sp.edit().putStringSet(k("known"), v).apply()

    var cities: List<City>
        get() = list("cities", City::fromJson)
        set(v) = sp.edit().putString(p("cities"), JSONArray(v.map { it.toJson() }).toString()).apply()

    var recentCities: List<City>
        get() = list("recentCities", City::fromJson)
        set(v) = sp.edit().putString(p("recentCities"), JSONArray(v.map { it.toJson() }).toString()).apply()

    fun flag(name: String) = bool("flag:$name", false)
    fun setFlag(name: String) = sp.edit().putBoolean(p("flag:$name"), true).apply()

    /** Packages MyTV switched off (so "switch everything back on" restores only those). */
    var cleanedUp: Set<String>
        get() = strings("cleanedUp")
        set(v) = sp.edit().putStringSet(p("cleanedUp"), v).apply()

    /** Which photos rotate: "all", "own" or "mytv". */
    var photoSource: String
        get() = str("photoSource", "all")!!
        set(v) = sp.edit().putString(p("photoSource"), v).apply()

    var dreamClock: Boolean
        get() = bool("dreamClock", true)
        set(v) = sp.edit().putBoolean(p("dreamClock"), v).apply()

    var previousDream: String?
        get() = str("previousDream", null)
        set(v) = sp.edit().putString(p("previousDream"), v).apply()

    /** A switch that is on unless switched off. */
    fun flagOn(name: String) = bool(name, true)
    fun setFlagOn(name: String, on: Boolean) = sp.edit().putBoolean(p(name), on).apply()

    /** MyTV photos left out of the rotation and the screensaver (photo ids). */
    var photosOff: Set<String>
        get() = strings("photosOff")
        set(v) = sp.edit().putStringSet(p("photosOff"), v).apply()

    /** Top-right buttons: allapps | sources | android | stock | none | app:<key>. */
    var button1: String
        get() = str("btn1", "allapps")!!
        set(v) = sp.edit().putString(p("btn1"), v).apply()
    var button2: String
        get() = str("btn2", "sources")!!
        set(v) = sp.edit().putString(p("btn2"), v).apply()

    private fun map(name: String): MutableMap<String, String> = runCatching {
        JSONObject(str(name, "{}")!!).let { o -> o.keys().asSequence().associateWith { o.optString(it) }.toMutableMap() }
    }.getOrDefault(mutableMapOf())
    private fun putMap(name: String, m: Map<String, String>) = sp.edit().putString(p(name), JSONObject(m as Map<*, *>).toString()).apply()
    /** Custom app names and pictures (key -> value). */
    var appNames: Map<String, String>
        get() = map("appNames")
        set(v) = putMap("appNames", v)
    var appArt: Map<String, String>
        get() = map("appArt")
        set(v) = putMap("appArt", v)

    var nightMode: String
        get() = str("nightMode", "off")!!
        set(v) = sp.edit().putString(p("nightMode"), v).apply()

    var nowPlaying: Boolean
        get() = bool("nowPlaying", true)
        set(v) = sp.edit().putBoolean(p("nowPlaying"), v).apply()

    var continueWatching: Boolean
        get() = bool("continueWatching", true)
        set(v) = sp.edit().putBoolean(p("continueWatching"), v).apply()

    var compactClock: Boolean
        get() = bool("compactClock", false)
        set(v) = sp.edit().putBoolean(p("compactClock"), v).apply()

    var backgroundId: String?
        get() = str(k("bg"), null)?.let { BackgroundRepository.RENAMED[it] ?: it }
        set(v) = sp.edit().putString(k("bg"), v).apply()

    /** Seconds between automatic background changes; 0 = off. Migrates the old minutes setting. */
    var rotateSeconds: Int
        get() = (if (sp.contains(p("rotateSec"))) int("rotateSec", 1800) else int("rotate", 30) * 60).coerceIn(0, 24 * 3600)
        set(v) = sp.edit().putInt(p("rotateSec"), v).apply()

    /** Component (flattened) of an app to open automatically when the TV is switched on; null = none. */
    var autostart: String?
        get() = str("autostart", null)
        set(v) = sp.edit().putString(p("autostart"), v).apply()

    /** MyTV language: null = follow the TV (English when the TV language is not supported). */
    var language: String?
        get() = str("language", null)
        set(v) = sp.edit().putString(p("language"), v).apply()

    /** "auto", "c" or "f". */
    var tempUnit: String
        get() = str("tempUnit", "auto")!!
        set(v) = sp.edit().putString(p("tempUnit"), v).apply()

    /** "auto", "24" or "12". */
    var timeFormat: String
        get() = str("timeFormat", "auto")!!
        set(v) = sp.edit().putString(p("timeFormat"), v).apply()

    var transitions: Boolean
        get() = bool("transitions", true)
        set(v) = sp.edit().putBoolean(p("transitions"), v).apply()

    /**
     * Frosted glass, or plain dark glass. Until the viewer chooses: plain on a slow TV (Android's "low RAM" flag, or
     * 1.5 GB of memory or less), where blurring panels over the photo costs frames.
     */
    var glassBlur: Boolean
        get() = bool("glassBlur", !slowDevice(context))
        set(v) = sp.edit().putBoolean(p("glassBlur"), v).apply()

    var lastLaunched: String?
        get() = str("lastLaunched", null)
        set(v) = sp.edit().putString(p("lastLaunched"), v).apply()

    /** The remote's Sources button opens MyTV's Sources (off: the TV's own). */
    var sourcesButton: Boolean
        get() = bool("sourcesButton", true)
        set(v) = sp.edit().putBoolean(p("sourcesButton"), v).apply()

    /** A Sources button the viewer taught MyTV (TVs that use their own key code); -1 = only Android's standard key. */
    var sourcesKey: Int
        get() = int("sourcesKey", -1)
        set(v) = sp.edit().putInt(p("sourcesKey"), v).apply()

    /** The remote's Apps button opens MyTV's All apps (off: the TV's own). */
    var appsButton: Boolean
        get() = bool("appsButton", true)
        set(v) = sp.edit().putBoolean(p("appsButton"), v).apply()

    /** An Apps button the viewer taught MyTV; -1 = only Android's standard key. */
    var appsKey: Int
        get() = int("appsKey", -1)
        set(v) = sp.edit().putInt(p("appsKey"), v).apply()

    /** What a coloured button ("red", "green", "yellow", "blue") does on MyTV's home screen: a button choice, or "none". */
    fun colorAction(color: String): String = str("key_$color", null) ?: "none"
    fun setColorAction(color: String, v: String) = sp.edit().putString(p("key_$color"), v).apply()

    /** A coloured button the viewer taught MyTV; -1 = Android's standard key for that colour. */
    fun colorCode(color: String): Int = int("keycode_$color", -1)
    fun setColorCode(color: String, code: Int) = sp.edit().putInt(p("keycode_$color"), code).apply()

    /** Number buttons 1-9 open the apps in the dock, in order. */
    var digitKeys: Boolean
        get() = bool("digitKeys", false)
        set(v) = sp.edit().putBoolean(p("digitKeys"), v).apply()

    /** The home-screen element the remote was on when MyTV went to the background (restored when it comes back). */
    var homeFocus: String?
        get() = str("homeFocus", null)
        set(v) = sp.edit().putString(p("homeFocus"), v).apply()

    /** Before 2026-11: forecasts were kept here per place; read once more, then removed (they made this file grow). */
    fun oldWeatherCache(city: City): String? = str("wx:${city.name}", null)
    fun dropOldWeatherCache() { sp.all.keys.filter { it.startsWith("wx:") }.takeIf { it.isNotEmpty() }?.let { ks -> sp.edit().apply { ks.forEach { remove(it) } }.apply() } }

    private fun slowDevice(c: Context): Boolean = runCatching {
        val am = c.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        am.isLowRamDevice || mem.totalMem <= 1_610_612_736L          // 1.5 GB
    }.getOrDefault(false)

    companion object {
        /** Settings that belong to a profile (see [p]); the coloured buttons ("key_<colour>") too. */
        val PER_PROFILE = listOf(
            "cities", "recentCities", "photoSource", "rotateSec", "rotate", "transitions", "glassBlur", "compactClock",
            "nightMode", "pixelShift", "nowPlaying", "npSonos", "npCast", "npApps", "npOther", "continueWatching", "cwOff",
            "cwHidden", "btn1", "btn2", "digitKeys", "language", "tempUnit", "timeFormat", "autostart", "appNames", "appArt",
            "homeFocus", "lastLaunched", "photosOff",
        )
        private val COLORS = listOf("red", "green", "yellow", "blue")
    }
}
