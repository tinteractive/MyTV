// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import com.mytv.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings backup: every MyTV setting and profile as one JSON file that the viewer keeps on a phone (through
 * the same QR-code page as own photos) and puts back after a new installation, or on another TV.
 *
 * Compatible in both directions: each value is stored with its type under its own key, so an older backup works in
 * a newer MyTV (keys it does not have keep their default) and a newer backup in an older MyTV (keys it does not know
 * are simply not used). Renamed keys are translated in [RENAMED].
 *
 * Not in the file: the PIN (four digits are guessed from a hash in seconds; a new one is asked for after restoring)
 * and things that only belong to this TV. Which permissions were on is noted, so MyTV can ask for them again.
 */
object Backup {
    const val FORMAT = 1
    const val FILE_NAME = "mytv-settings.json"
    private const val PREFS = "mytv"
    /** Old key → new key, for backups made before a rename (none yet). */
    private val RENAMED = mapOf<String, String>()
    private val THIS_TV_ONLY = setOf("pinHash", "pinFails", "pinBlockedUntil", "homeWatchOffByUser", "autostartBoot", "reopenedAfter",
        "flag:askedTvListings", "restoreAsk", "previousDream", "photosChecked",
        // What Clean up switched off belongs to this TV: on another TV MyTV would switch those apps off again after a
        // boot, without the viewer choosing them there (and without the second OK for the careful ones).
        "cleanedUp",
        // Buttons taught to MyTV: the codes belong to this TV's remote. Where the focus was, and what Continue watching saw.
        "appsKey", "sourcesKey", "homeFocus", "cwSeen")

    private fun skip(k: String) = k.substringBefore('@').let { it in THIS_TV_ONLY || it.startsWith("keycode_") } || k.startsWith("wx:")

    /**
     * Every setting a backup may put back, with its type: s(tring), b(oolean), i(nt) or set. Anything else in a file
     * is ignored, so a damaged or crafted file cannot store values MyTV does not expect. Keys that exist per profile
     * ("order@<id>") are listed by their base name.
     */
    private val SCHEMA = mapOf(
        "profile" to 's', "profiles" to 's', "order" to 's', "bg" to 's', "cities" to 's', "recentCities" to 's',
        "photoSource" to 's', "btn1" to 's', "btn2" to 's', "appNames" to 's', "appArt" to 's', "nightMode" to 's',
        "autostart" to 's', "language" to 's', "tempUnit" to 's', "timeFormat" to 's', "lastLaunched" to 's', "quietSonos" to 's',
        "askWho" to 'b', "dreamClock" to 'b', "nowPlaying" to 'b', "continueWatching" to 'b', "compactClock" to 'b',
        "transitions" to 'b', "glassBlur" to 'b', "quietOn" to 'b', "npApps" to 'b', "npCast" to 'b', "npOther" to 'b',
        "npSonos" to 'b', "pixelShift" to 'b', "flag:welcomeShown" to 'b', "flag:playStoreShown" to 'b',
        "rotateSec" to 'i', "rotate" to 'i', "quietFrom" to 'i', "quietTo" to 'i', "quietMax" to 'i',
        "hidden" to 'S', "known" to 'S',
        "digitKeys" to 'b', "key_red" to 's', "key_green" to 's', "key_yellow" to 's', "key_blue" to 's',
        "cwOff" to 'S', "cwHidden" to 's', "copied" to 'b', "photosOff" to 'S',
        "appsButton" to 'b', "sourcesButton" to 'b', "dreamMinutesChosen" to 'i',
    )
    /** Settings that exist per profile ("<name>@<profile id>"): the dock and background, and every home-screen setting. */
    private val PER_PROFILE = setOf("order", "bg", "hidden", "known", "copied", "key_red", "key_green", "key_yellow", "key_blue") +
        Prefs.PER_PROFILE
    private val PROFILE_ID = Regex("[A-Za-z0-9_-]{1,40}")
    private const val MAX_TEXT = 200_000      // the longest real value (a dock order with hundreds of apps) is far below this
    private const val MAX_SET = 2_000
    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /**
     * Pure, unit-tested: one entry of a backup → the value to store (String, Boolean, Int or Set<String>), or null
     * when the key is unknown, the type is not the one MyTV uses for it, or the value is out of range.
     */
    internal fun accept(key: String, v: JSONObject): Any? {
        val base = key.substringBefore('@')
        if (key != base && (base !in PER_PROFILE || !PROFILE_ID.matches(key.substringAfter('@')))) return null
        return when (SCHEMA[base]) {
            's' -> (v.opt("s") as? String)?.takeIf { it.length <= MAX_TEXT }?.takeIf { text ->
                when (base) {
                    "profiles", "cities", "recentCities" -> runCatching { JSONArray(text) }.isSuccess
                    "appNames", "appArt", "cwHidden" -> runCatching { JSONObject(text) }.isSuccess
                    // "address|room|model" of a soundbar on the home network (quiet hours send it volume commands).
                    "quietSonos" -> text.split('|').let { it.size == 3 && IPV4.matches(it[0]) && Net.isLocal(it[0]) }
                    "autostart" -> COMPONENT.matches(text)       // "package/activity"; checked against the installed apps on restore
                    else -> true
                }
            }
            'b' -> v.opt("b") as? Boolean
            'i' -> (v.opt("i") as? Int)?.takeIf {
                when (base) { "quietFrom", "quietTo" -> it in 0..23; "quietMax" -> it in 1..100; "dreamMinutesChosen" -> it in 0..24 * 60
                    else -> it in 0..24 * 3600 }
            }
            'S' -> v.optJSONArray("set")?.takeIf { it.length() <= MAX_SET }?.let { a ->
                (0 until a.length()).map { a.opt(it) as? String ?: return null }.toSet()
            }
            else -> null
        }
    }

    fun export(c: Context): String {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = JSONObject()
        for ((k, v) in sp.all) {
            if (skip(k)) continue
            val typed = when (v) {
                is String -> JSONObject().put("s", v)
                is Boolean -> JSONObject().put("b", v)
                is Int -> JSONObject().put("i", v)
                is Long -> JSONObject().put("l", v)
                is Float -> JSONObject().put("f", v.toDouble())
                is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            settings.put(k, typed)
        }
        val rights = JSONObject()
            .put("homeButton", SystemAccess.homeWatchOn(c))
            .put("notifications", MediaApps.allowed(c))
            .put("tvListings", WatchNext.allowed(c))
            .put("extraRights", SystemAccess.canWriteSecure(c))
            .put("defaultHome", SystemAccess.holdsDefaultHome(c))
            .put("screensaver", Screensaver.isMine(c))
            .put("pin", sp.getString("pinHash", null) != null)
        return JSONObject().put("app", "MyTV").put("format", FORMAT).put("version", BuildConfig.VERSION_NAME)
            .put("created", java.time.Instant.now().toString())
            .put("updatesAuto", c.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("auto", false))
            // The screensaver's start time as the TV has it now (also when set in the TV's own settings, not in MyTV).
            .put("screensaverMinutes", Screensaver.timeoutMinutes(c))
            .put("settings", settings).put("rights", rights).toString(1)
    }

    /**
     * Puts a backup back: MyTV's own settings are replaced (things of this TV stay). Returns false when [json] is not
     * a MyTV settings file. The rights that were on are remembered ("restoreAsk") for the screen after the restart.
     */
    fun restore(c: Context, json: String, launchable: (String) -> Boolean = { AppRepository.isLaunchable(c, it) }): Boolean {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (o.optString("app") != "MyTV" || o.optInt("format", 0) < 1) return false
        val settings = o.optJSONObject("settings") ?: return false
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = sp.edit()
        sp.all.keys.filterNot(::skip).forEach { e.remove(it) }
        for (old in settings.keys()) {
            val k = RENAMED[old] ?: old
            if (skip(k)) continue
            val v = settings.optJSONObject(old) ?: continue
            when (val value = accept(k, v)) {
                // A start-up app must be an app this TV can open from a launcher (it is started at every power-on).
                is String -> if (k != "autostart" || launchable(value)) e.putString(k, value)
                is Boolean -> e.putBoolean(k, value)
                is Int -> e.putInt(k, value)
                is Set<*> -> e.putStringSet(k, value.filterIsInstance<String>().toSet())
                else -> android.util.Log.w("MyTV", "restore: skipped $k")
            }
        }
        o.optJSONObject("rights")?.let { e.putString("restoreAsk", it.toString().take(2_000)) }
        o.optInt("screensaverMinutes", -1).takeIf { it in 0..24 * 60 }?.let { e.putInt("dreamMinutesChosen", it) }
        // Written before returning: MyTV rebuilds itself from these settings right after.
        @Suppress("ApplySharedPref") e.commit()
        if (o.has("updatesAuto")) c.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("auto", o.optBoolean("updatesAuto")).apply()
        applySystem(c, o.optJSONObject("rights"))
        return true
    }

    /**
     * Android's own settings that the backup had, put in place where MyTV may do that itself: MyTV as screensaver, and
     * its start time. The rest of the rights stays a question after the restart ([takeRightsToAsk]).
     */
    private fun applySystem(c: Context, rights: JSONObject?) {
        if (rights?.optBoolean("screensaver") == true && SystemAccess.canWriteSecure(c) && !Screensaver.isMine(c))
            runCatching { Screensaver.setMine(c, Prefs(c), true) }.onFailure { android.util.Log.w("MyTV", "restore: screensaver: $it") }
        Screensaver.applyChosen(c)
    }

    /** The rights that were on in the restored backup, read once after the restart. */
    fun takeRightsToAsk(c: Context): JSONObject? {
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = sp.getString("restoreAsk", null) ?: return null
        sp.edit().remove("restoreAsk").apply()
        return runCatching { JSONObject(s) }.getOrNull()
    }
}
