// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MyTV's own "continue watching" for apps that publish nothing to Android's Watch Next (YouTube, Plex, …).
 * Follows the media sessions MyTV may already read ("notification access"), like tvOS-Launcher does; the counting
 * rules: a title counts after a minute of playing *and* once the app's own position is past a minute
 * (so previews that start by themselves while browsing do not). One entry per app and title, per profile, at most
 * [MAX], kept [KEEP_DAYS] days, dropped when watched to the end. Stored only on the TV (files/history.json and small
 * covers). Music (a track with an album) and live TV (no duration) are not kept. One thing goes online: for a
 * YouTube video its title and channel are sent to YouTube once, to find the video again ([findYouTubeId]).
 */
object History {
    private const val MAX = 20
    private const val KEEP_DAYS = 30L
    private const val MIN_PLAY_MS = 60_000L
    private val YT_ID = Regex("(?:/vi/|/vi_webp/|v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})")

    private var thread: HandlerThread? = null
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val watched = HashMap<MediaSession.Token, Watch>()

    /** When each app last played (wall clock), seen in the background: a paused card only for a recent pause. */
    private val lastPlaying = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun lastPlaying(pkg: String): Long? = lastPlaying[pkg]

    /** Set while the home screen is in front: the row refreshes as soon as something is kept or removed. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Starts following the media sessions; called by [MediaListener] once Android has connected it. */
    fun start(c: Context) {
        if (thread != null) return
        val app = c.applicationContext
        val t = HandlerThread("mytv-history").apply { start() }; thread = t
        val h = Handler(t.looper)
        val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(app, h, list.orEmpty()) }
        listener = l
        runCatching {
            msm.addOnActiveSessionsChangedListener(l, MediaApps.component(app), h)
            h.post { runCatching { follow(app, h, msm.getActiveSessions(MediaApps.component(app))) } }
        }.onFailure { Log.w("MyTV", "history: $it") }
    }

    fun stop(c: Context) {
        val t = thread ?: return
        listener?.let { l -> runCatching { (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).removeOnActiveSessionsChangedListener(l) } }
        Handler(t.looper).post { watched.values.forEach { it.close() }; watched.clear(); t.quitSafely() }
        thread = null; listener = null
    }

    private fun follow(app: Context, h: Handler, list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { watched.remove(it)?.close() }
        list.filter { it.packageName != app.packageName && it.sessionToken !in watched }.forEach { mc ->
            Log.i("MyTV", "history: following ${mc.packageName}")
            watched[mc.sessionToken] = Watch(app, mc).also { mc.registerCallback(it, h); it.update() }
        }
    }

    /** Follows one session: adds up how long each title plays and saves it once it counts. */
    private class Watch(val app: Context, val mc: MediaController) : MediaController.Callback() {
        private var md: MediaMetadata? = null
        private var title: String? = null
        private var played = 0L
        private var since = 0L          // elapsedRealtime when the current playing stretch began; 0 = not playing
        private var pos = 0L
        private var art: android.graphics.Bitmap? = null   // apps often drop the picture when they stop: keep it

        fun update() = runCatching {
            val now = SystemClock.elapsedRealtime()
            // Playing until now (or still): remember when, for the "now playing" card.
            if (since > 0 || mc.playbackState?.state == PlaybackState.STATE_PLAYING) lastPlaying[mc.packageName] = System.currentTimeMillis()
            if (since > 0) { played += now - since; since = 0 }
            val st = mc.playbackState
            val nextTitle = titleOf(app, mc.packageName, mc.metadata, mc.queueTitle)
            if (nextTitle == title) {
                // Players often report position 0 when they stop, just before the session closes: keep the last one.
                val p = position(st, now)
                val reset = st == null || st.state in setOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR) || p <= 1000
                if (!(pos > 5000 && reset)) pos = p
                mc.metadata?.let { md = it; picture(it)?.let { b -> art = b } }
            }
            val m = md; val t = title
            if (m != null && t != null && played >= MIN_PLAY_MS && pos >= MIN_PLAY_MS) save(app, mc.packageName, t, m, pos, art)
            if (nextTitle != title) { title = nextTitle; md = mc.metadata; art = md?.let { picture(it) }; played = 0; pos = position(st, now) }
            if (st?.state == PlaybackState.STATE_PLAYING) since = now
        }.onFailure { Log.w("MyTV", "history: $it") }

        fun close() { update(); runCatching { mc.unregisterCallback(this) } }

        override fun onPlaybackStateChanged(state: PlaybackState?) { update() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { update() }
        override fun onSessionDestroyed() { update() }
    }

    /** The title the app gives, or its queue's; not when it is just the app's own name ("Prime Video"). */
    private fun titleOf(c: Context, pkg: String, md: MediaMetadata?, queue: CharSequence?): String? {
        val t = (md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: queue?.toString())?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        if (GENERIC.matches(t)) return null
        return t.takeUnless { label != null && it.replace(" ", "").equals(label.replace(" ", ""), ignoreCase = true) }
    }

    /** Not a programme: adverts, trailers, a loading screen, a bare number. */
    private val GENERIC = Regex("""(?i)\s*(advert(isement)?s?|ad|ads|commercial|sponsored|trailer|teaser|preview|loading|laden|reclame|\d+)\s*""")

    private fun position(st: PlaybackState?, now: Long): Long {
        st ?: return 0L
        val moved = if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
        return (st.position + moved).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- storage

    private fun file(c: Context) = File(c.filesDir, "history.json")
    private fun coverDir(c: Context) = File(c.filesDir, "history")
    private fun profile(c: Context) = c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString("profile", "") ?: ""

    @Synchronized private fun read(c: Context): MutableList<JSONObject> = runCatching {
        val a = JSONArray(file(c).readText())
        MutableList(a.length()) { a.getJSONObject(it) }
    }.getOrDefault(mutableListOf())

    /** Written next to the file first, then swapped; a full disk loses this update, never the home screen. */
    private fun store(c: Context, list: List<JSONObject>): Boolean = runCatching {
        val f = file(c); val tmp = File(f.path + ".part")
        tmp.writeText(JSONArray(list).toString()); tmp.renameTo(f)
    }.onFailure { Log.w("MyTV", "history not saved: $it") }.getOrDefault(false)

    @Synchronized private fun write(c: Context, list: List<JSONObject>) {
        if (!store(c, list)) return
        val keep = list.mapNotNull { it.optString("cover").takeIf { s -> s.isNotEmpty() } }.toSet()
        coverDir(c).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        onChange?.invoke()
    }

    private fun picture(md: MediaMetadata) = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        .firstNotNullOfOrNull { md.getBitmap(it) }?.takeIf { it.width >= 64 }

    @Synchronized private fun save(c: Context, pkg: String, title: String, md: MediaMetadata, position: Long, art: android.graphics.Bitmap?) {
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration <= 0 || !md.getString(MediaMetadata.METADATA_KEY_ALBUM).isNullOrBlank()) return   // live TV / music
        val who = profile(c)
        val key = "$who|$pkg|$title"
        val all = read(c)
        val old = all.firstOrNull { it.optString("key") == key }
        val list = all.filter { it.optString("key") != key }.toMutableList()
        if (position < duration * 95 / 100) {       // the last 5 % (credits): watched, no longer "continue"
            val uris = listOf(MediaMetadata.METADATA_KEY_MEDIA_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, MediaMetadata.METADATA_KEY_MEDIA_ID).mapNotNull { md.getString(it) }
            val sub = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "").takeIf { it != title } ?: ""
            val ytId = uris.firstNotNullOfOrNull { u -> YT_ID.find(u)?.groupValues?.get(1) }
                ?: md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) && "youtube" in pkg }
                ?: old?.optString("yt")?.takeIf { it.isNotEmpty() }
                ?: if ("youtube" in pkg && System.currentTimeMillis() >= (old?.optLong("ytNext") ?: 0L)) lookUp(old, title, sub, duration) else null
            list.add(0, JSONObject().put("key", key).put("who", who).put("pkg", pkg).put("title", title)
                .put("sub", sub)
                .put("yt", ytId ?: "").put("ytNext", old?.optLong("ytNext") ?: 0L).put("ytWait", old?.optLong("ytWait") ?: 0L)
                .put("link", link(md) ?: old?.optString("link") ?: "")
                .put("art", listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                    .firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.startsWith("https://") } } ?: "")
                .put("cover", art?.let { saveCover(c, key, it) } ?: old?.optString("cover") ?: "")
                .put("pos", position).put("dur", duration).put("at", System.currentTimeMillis()))
        }
        // What is watched stays out of the log: only that something was kept.
        Log.i("MyTV", "history: kept an item of $pkg (${list.size} in total)")
        write(c, list.take(MAX))
    }

    private const val BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /**
     * YouTube tells Android the title, channel and length of a video but not which video it is. Like tvOS-Launcher,
     * MyTV then searches YouTube for it (title + channel are sent to YouTube, once per video), but only accepts a
     * result whose title matches, or whose channel and length both match: better the app than a wrong video.
     */
    private fun findYouTubeId(title: String, channel: String?, durationMs: Long): Lookup = runCatching {
        val q = java.net.URLEncoder.encode(listOfNotNull(title, channel).joinToString(" "), "UTF-8")
        val html = String(Net.get("https://www.youtube.com/results?search_query=$q&hl=en", 5000, 8000,
            mapOf("User-Agent" to BROWSER, "Cookie" to "SOCS=CAI")), Charsets.UTF_8)
        // A page without results (a consent page, another layout) says nothing: try again later, like a failure.
        if ("\"videoRenderer\"" !in html) Lookup.FAILED else pickYouTubeId(html, title, channel, durationMs)?.let { Lookup(it) } ?: Lookup.UNSURE
    }.onFailure { Log.w("MyTV", "youtube lookup: $it") }.getOrDefault(Lookup.FAILED)

    /** A YouTube lookup: the video, "searched but not sure" or "could not search" (no network). */
    private class Lookup(val id: String?, val sure: Boolean = true) {
        companion object { val UNSURE = Lookup(null, true); val FAILED = Lookup(null, false) }
    }

    /**
     * Looks the video up and notes on [o] when to try again: a failure (offline) after 10 minutes, an unsure answer
     * after 10 minutes doubling up to a day, so an entry kept while offline still finds its video later.
     */
    private fun lookUp(o: JSONObject?, title: String, sub: String, duration: Long): String? {
        val r = findYouTubeId(title, sub.ifEmpty { null }, duration)
        if (r.id == null && o != null) {
            val wait = if (r.sure) (o.optLong("ytWait").takeIf { it > 0 }?.times(2) ?: RETRY_MS).coerceAtMost(24 * 3600_000L) else RETRY_MS
            o.put("ytWait", wait).put("ytNext", System.currentTimeMillis() + wait)
        }
        return r.id
    }
    private const val RETRY_MS = 10 * 60_000L

    /**
     * A link to exactly this programme, when the app gives one with what it plays (any app, nothing app-specific):
     * an address of its own (app scheme or https), not a stream file. Used to continue there instead of just the app.
     */
    private fun link(md: MediaMetadata): String? {
        val u = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.let { Uri.parse(it) } ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme in setOf("content", "file", "android.resource", "http", "rtsp", "data")) return null
        if (scheme == "https" && Regex("""\.(m3u8|mpd|mp4|ts|m4s|mkv|webm)(\?|$)""").containsMatchIn(u.path.orEmpty())) return null
        Log.i("MyTV", "history: the app gives a link ($scheme)")
        return u.toString()
    }

    /** Pure, unit-tested: the best matching video among the first search results. */
    internal fun pickYouTubeId(html: String, title: String, channel: String?, durationMs: Long): String? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        fun text(field: String, p: String) = Regex(""""$field":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""").find(p)?.groupValues?.get(1)
            ?.let { raw -> runCatching { JSONObject("{\"v\":\"$raw\"}").getString("v") }.getOrDefault(raw) }
        return html.split("\"videoRenderer\":{\"videoId\":\"").drop(1).take(8).mapNotNull { p ->
            val id = p.take(11).takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return@mapNotNull null
            val len = Regex("\"lengthText\":.*?\"simpleText\":\"([0-9:]+)\"").find(p.take(8000))?.groupValues?.get(1)
                ?.split(':')?.fold(0L) { a, x -> a * 60 + (x.toLongOrNull() ?: 0L) }
            var score = 0
            if (text("title", p)?.let { norm(it) == norm(title) } == true) score += 2
            if (channel != null && text("ownerText", p)?.let { norm(it) == norm(channel) } == true) score += 1
            if (len != null && durationMs > 0 && kotlin.math.abs(len * 1000 - durationMs) <= 3000) score += 1
            (id to score).takeIf { score >= 2 }
        }.maxByOrNull { it.second }?.first
    }

    /** The session's own picture, made small, as a file of MyTV's own. */
    private fun saveCover(c: Context, key: String, bmp: android.graphics.Bitmap): String? = runCatching {
        val scale = minOf(1f, 400f / bmp.width)
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        val name = Integer.toHexString(key.hashCode()) + ".jpg"
        File(coverDir(c).apply { mkdirs() }, name).outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        name
    }.getOrNull()

    /** This profile's entries, newest first; only apps that are still installed. */
    fun load(c: Context): List<WatchItem> {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        val who = profile(c); val pm = c.packageManager
        lookUpMissing(c)
        return read(c).filter { it.optLong("at") > cutoff && it.optString("who") == who }.mapNotNull { o ->
            val pkg = o.optString("pkg")
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
            val pos = o.optLong("pos"); val dur = o.optLong("dur")
            val cover = o.optString("cover").takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(coverDir(c), it)).toString() }
            val yt = o.optString("yt").ifBlank { null }
            WatchItem(pkg, o.optString("title"), o.optString("sub").ifBlank { null },
                // mqdefault: YouTube's 16:9 picture (hqdefault has black bars).
                cover ?: o.optString("art").ifBlank { null } ?: yt?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" },
                youtube(pkg, o.optString("yt"), pos) ?: linkIntent(pkg, o.optString("link")), if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null, o.optLong("at"),
                historyKey = o.optString("key"))
        }
    }

    /** YouTube entries whose video is not known yet, when their next try is due. Called off the main thread. */
    @Synchronized private fun lookUpMissing(c: Context) {
        val all = read(c)
        val now = System.currentTimeMillis()
        val todo = all.filter { "youtube" in it.optString("pkg") && it.optString("yt").isEmpty() && now >= it.optLong("ytNext") }
        if (todo.isEmpty()) return
        todo.forEach { o -> lookUp(o, o.optString("title"), o.optString("sub"), o.optLong("dur"))?.let { o.put("yt", it) } }
        store(c, all)
    }

    /** YouTube: straight back to the video at the same moment; other apps open normally. */
    private fun youtube(pkg: String, id: String, pos: Long): String? = id.takeIf { it.isNotEmpty() }?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$it" + (if (pos > 5000) "&t=${pos / 1000}s" else "")))
            .setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME)
    }

    /** The app's own link to the programme, opened in that app only (WatchNext.open checks it resolves there). */
    private fun linkIntent(pkg: String, link: String): String? = link.takeIf { it.isNotEmpty() }?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg).toUri(Intent.URI_INTENT_SCHEME)
    }

    /** Apps with something in MyTV's own history (any profile). */
    fun apps(c: Context): List<String> = read(c).map { it.optString("pkg") }.distinct()

    /** "Remove from the row". */
    @Synchronized fun remove(c: Context, key: String) = write(c, read(c).filter { it.optString("key") != key })

    /** Whether [uri] is one of MyTV's own covers (the only local files the row may read). */
    fun isCover(c: Context, uri: Uri) = uri.scheme == "file" && uri.path?.let { File(it).canonicalFile.parentFile == coverDir(c).canonicalFile } == true
}
