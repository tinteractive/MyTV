// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap

/** One "continue watching" item published by a streaming app through Android TV's TvProvider. */
data class WatchItem(
    val pkg: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val intentUri: String?,
    val progress: Float?,           // 0..1 when the app reports position + duration
    val engagedAt: Long,
    val historyKey: String? = null, // MyTV's own history entry (History), which the viewer may remove
)

/**
 * "Continue watching" from Android TV's TvProvider: the Watch Next row that apps (Netflix, …) publish for the
 * launcher, plus their preview programmes as fallback (needs READ_TV_LISTINGS, a runtime permission the viewer grants
 * on screen; read-only). Apps that publish nothing there (YouTube, Plex) come from MyTV's own [History].
 */
object WatchNext {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
    private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")
    private val COLS = arrayOf("package_name", "title", "episode_title", "season_display_number", "episode_display_number",
        "poster_art_uri", "thumbnail_uri", "intent_uri", "last_engagement_time_utc_millis", "last_playback_position_millis", "duration_millis",
        "browsable", "watch_next_type")
    /** Watch Next types that are not "continue": new episodes and the watch list (Prime puts its watch list there). */
    private val NOT_CONTINUE = setOf(2, 3)

    fun allowed(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Newest first, one card per app and title, at most [perApp] from one app (as Google TV does). */
    fun load(c: Context, limit: Int = 12, perApp: Int = 4): List<WatchItem> {
        val wn = if (allowed(c)) query(c, WATCH_NEXT) else emptyList()
        // Apps that keep their "continue" in a preview channel of their own (no Watch Next rows): those items, with
        // progress, for each such app. Preview items carry no time: "first seen like this" stands in for it.
        val preview = if (allowed(c)) query(c, PREVIEW).filter { it.progress != null && wn.none { w -> w.pkg == it.pkg } } else emptyList()
        val published = wn + seenTimes(c, preview)
        // One card per title: the app's own and MyTV's history merge, the newest wins.
        // MyTV's own history, and the viewer's Plex server when it is linked (Plex publishes nothing to Android).
        val own = History.load(c) + Plex.continueWatching(c)
        val hidden = hidden(c)
        Log.i("MyTV", "watch next: ${wn.size} published, ${preview.size} from channels, ${own.size} from own history")
        val perPkg = HashMap<String, Int>()
        val off = appsOff(c)
        return (published + own).filter { it.pkg !in off }.sortedByDescending { it.engagedAt }.distinctBy { cardKey(it) }
            .filter { (hidden[cardKey(it)] ?: -1L) < it.engagedAt }
            .filter { item -> perPkg.merge(item.pkg, 1, Int::plus)!! <= perApp }.take(limit)
    }

    /** Apps the viewer switched off for Continue watching. */
    fun appsOff(c: Context): Set<String> = prefs(c).getStringSet(Prefs(c).p("cwOff"), emptySet()).orEmpty()
    fun setAppOn(c: Context, pkg: String, on: Boolean) =
        prefs(c).edit().putStringSet(Prefs(c).p("cwOff"), if (on) appsOff(c) - pkg else appsOff(c) + pkg).apply()

    /** The apps that gave something for Continue watching (published, in a channel, or in MyTV's history). Blocking. */
    fun sources(c: Context): List<String> {
        val wn = if (allowed(c)) query(c, WATCH_NEXT) + query(c, PREVIEW).filter { it.progress != null } else emptyList()
        return (wn.map { it.pkg } + History.apps(c)).filter { it.isNotEmpty() && it != Plex.PACKAGE }.distinct()
    }

    /** Same app and the same title, however it is written ("The Office" / "the office"). */
    fun cardKey(i: WatchItem) = i.pkg + "|" + i.title.lowercase().filter { it.isLetterOrDigit() }

    private fun prefs(c: Context) = c.getSharedPreferences("mytv", Context.MODE_PRIVATE)

    /** Cards the viewer removed, with when: one comes back only when the app shows it again later. */
    private fun hidden(c: Context): Map<String, Long> = runCatching {
        val o = org.json.JSONObject(prefs(c).getString(Prefs(c).p("cwHidden"), "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    /** "Remove from this row" for any card: the app's own row is left alone, MyTV just stops showing it. */
    fun hide(c: Context, item: WatchItem) {
        val now = System.currentTimeMillis()
        val keep = hidden(c).filterValues { now - it < 60L * 24 * 3600 * 1000 } + (cardKey(item) to maxOf(now, item.engagedAt))
        prefs(c).edit().putString(Prefs(c).p("cwHidden"), org.json.JSONObject(keep).toString()).apply()
    }

    /** Preview items: the time each was first seen at this position, so one that just moved comes first. */
    private fun seenTimes(c: Context, items: List<WatchItem>): List<WatchItem> {
        if (items.isEmpty()) return items
        val now = System.currentTimeMillis()
        val seen = runCatching { org.json.JSONObject(prefs(c).getString("cwSeen", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        val out = items.map { i ->
            val state = ((i.progress ?: 0f) * 100).toInt().toString()
            val was = seen.optJSONArray(cardKey(i))
            val at = if (was != null && was.optString(0) == state) was.optLong(1) else now
            seen.put(cardKey(i), org.json.JSONArray().put(state).put(at))
            i.copy(engagedAt = at)
        }
        prefs(c).edit().putString("cwSeen", seen.toString()).apply()
        return out
    }

    private fun query(c: Context, uri: Uri): List<WatchItem> = runCatching {
        c.contentResolver.query(uri, COLS, null, null, null)?.use { cur -> generateSequence { cur.takeIf { it.moveToNext() } }.mapNotNull { row(it) }.toList() }
    }.onFailure { Log.w("MyTV", "watch next query $uri: $it") }.getOrNull().orEmpty()

    /** One row, or null when it is not "continue watching" (hidden by the viewer elsewhere, a watch list, new). */
    private fun row(c: Cursor): WatchItem? {
        fun s(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }
        fun l(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
        if (l("browsable") == 0L || l("watch_next_type")?.toInt() in NOT_CONTINUE) return null
        val se = s("season_display_number"); val ep = s("episode_display_number")
        val sub = listOfNotNull(if (se != null && ep != null) "S$se · E$ep" else null, s("episode_title")).joinToString(" · ").ifBlank { null }
        val pos = l("last_playback_position_millis"); val dur = l("duration_millis")
        // The wide picture first: the card is 16:9, and a portrait poster would be cut badly.
        return WatchItem(s("package_name") ?: "", s("title") ?: "", sub, s("thumbnail_uri") ?: s("poster_art_uri"), s("intent_uri"),
            if (pos != null && dur != null && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null, l("last_engagement_time_utc_millis") ?: 0L)
    }

    /** Posters by address; only the last dozen are kept (the row shows a few at a time). */
    private val images = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(12)
    fun trim() = images.evictAll()

    /** Poster/thumbnail (content:, android.resource: or https:), scaled down and cached. */
    fun image(c: Context, uri: String): androidx.compose.ui.graphics.ImageBitmap? = images.get(uri) ?: runCatching {
        val u = Uri.parse(uri)
        val bytes = when (u.scheme) {
            "https" -> Net.get(uri)
            "content", "android.resource" -> c.contentResolver.openInputStream(u)?.use { Net.readCapped(it) }
            "asset" -> c.assets.open(uri.removePrefix("asset:")).use { Net.readCapped(it) }     // demo cards only
            "file" -> if (History.isCover(c, u)) java.io.File(u.path!!).readBytes() else null
            else -> null
        } ?: return null
        Net.decodeSampled(bytes, 400)?.also { images.put(uri, it) }
    }.getOrNull()

    /** Development check only: MyTV publishes / removes one item of its own, to verify reading and the row. */
    fun testItem(c: Context, add: Boolean) {
        val cr = c.contentResolver
        // TvProvider refuses a selection here; without one an app deletes only its own rows.
        runCatching { cr.delete(WATCH_NEXT, null, null) }.onFailure { Log.w("MyTV", "test item delete: $it") }
        if (!add) return
        val v = android.content.ContentValues().apply {
            put("package_name", c.packageName); put("type", 0); put("watch_next_type", 0); put("title", "MyTV test")
            put("episode_title", "Verder kijken werkt"); put("poster_art_uri", "android.resource://${c.packageName}/drawable/banner")
            put("intent_uri", Intent(c, com.mytv.launcher.MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
            put("last_engagement_time_utc_millis", System.currentTimeMillis()); put("last_playback_position_millis", 1_200_000L); put("duration_millis", 3_000_000L)
        }
        runCatching { cr.insert(WATCH_NEXT, v) }.onSuccess { Log.i("MyTV", "test item inserted: $it") }.onFailure { Log.w("MyTV", "test item: $it") }
        Log.i("MyTV", "own rows readable: " + runCatching { cr.query(WATCH_NEXT, arrayOf("_id", "package_name"), null, null, null)?.use { it.count } }.getOrNull())
    }

    /**
     * Opens the item in its own app. The row's intent comes from that app, so it is limited to that app (never MyTV
     * itself or another package), and loses any flags, selector or attached data (no URI permissions handed out).
     */
    fun open(c: Context, item: WatchItem): Boolean = runCatching {
        if (item.pkg == c.packageName) return false
        val direct = item.intentUri?.let { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }?.apply {
            selector = null; clipData = null; flags = 0; setPackage(item.pkg)
            component?.let { if (it.packageName != item.pkg) component = null }
        }?.takeIf { it.resolveActivity(c.packageManager) != null }
        // The app's own link where it still works; otherwise just the app.
        val i = direct ?: c.packageManager.getLeanbackLaunchIntentForPackage(item.pkg) ?: c.packageManager.getLaunchIntentForPackage(item.pkg) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
