// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.mytv.launcher.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * MyTV's online photo set. Only a few photos ship inside the APK; the rest are listed in photos.json in the public
 * MyTV repository and downloaded once into MyTV's own storage. Every photo has an open licence (author, licence and
 * source are in the list and shown in About MyTV). A photo is used only after it was downloaded completely and checked
 * (size and SHA-256 as listed, a landscape picture of at least 1280×720). The list is checked at most once a day.
 */
object OnlinePhotos {
    const val LIST_URL = "https://raw.githubusercontent.com/tinteractive/MyTV/main/photos/photos.json"
    const val FORMAT = 1
    /** Upper bounds, whatever the list says: 80 photos of at most 1.5 MB (about 20 MB with the usual ~250 KB each). */
    const val MAX_PHOTOS = 80
    const val MAX_PHOTO_BYTES = 1_500_000
    private const val DAY_MS = 24 * 60 * 60_000L

    data class Entry(
        val id: String, val name: String, val title: String, val author: String, val license: String,
        val source: String, val url: String, val bytes: Int, val sha256: String,
    )

    private val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")
    private val HEX = Regex("[0-9a-f]{64}")

    /** Pure, unit-tested: the list → valid entries (https only, sane sizes, unique ids). Relative URLs are next to the list. */
    fun parse(json: String, listUrl: String = LIST_URL): List<Entry> {
        val o = JSONObject(json)
        if (o.optInt("format") != FORMAT) return emptyList()
        val a = o.optJSONArray("photos") ?: return emptyList()
        val base = java.net.URI(listUrl)
        val out = LinkedHashMap<String, Entry>()
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val id = p.optString("id"); val file = p.optString("file"); val bytes = p.optInt("bytes")
            val sha = p.optString("sha256").lowercase()
            val url = runCatching { base.resolve(file).toString() }.getOrNull() ?: continue
            if (!ID.matches(id) || id in out || !url.startsWith("https://") || bytes !in 1..MAX_PHOTO_BYTES || !HEX.matches(sha)) continue
            val author = p.optString("author"); val license = p.optString("license"); val source = p.optString("source")
            if (author.isBlank() || license.isBlank() || !source.startsWith("https://")) continue   // no credit, no photo
            out[id] = Entry(id, p.optString("name").ifBlank { id }, p.optString("title").ifBlank { p.optString("name") }, author, license, source, url, bytes, sha)
            if (out.size >= MAX_PHOTOS) break
        }
        return out.values.toList()
    }

    private fun dir(c: Context) = File(c.filesDir, "online-photos").also { it.mkdirs() }
    private fun listFile(c: Context) = File(dir(c), "photos.json")
    private fun photoFile(c: Context, id: String) = File(dir(c), "$id.img")

    /** The downloaded, checked photos (for the photo list): only entries whose file is complete. */
    fun infos(c: Context): List<BgInfo> = runCatching {
        parse(listFile(c).readText()).mapNotNull { e ->
            val f = photoFile(c, e.id)
            if (f.length() != e.bytes.toLong()) null
            else BgInfo(e.id, e.name, e.author, e.license, e.source, null, f, title = e.title, online = true)
        }
    }.getOrDefault(emptyList())

    @Volatile private var busy = false

    /**
     * Fetches the list (at most once a day, unless [force]) and downloads what is missing; removes photos that left the
     * list. Returns true when the set of usable photos changed. Never throws; a failed attempt is retried next time.
     */
    fun sync(c: Context, force: Boolean = false): Boolean {
        if (busy) return false
        busy = true
        try {
            val sp = c.getSharedPreferences("mytv", Context.MODE_PRIVATE)
            val last = sp.getLong("photosChecked", 0L)
            val now = System.currentTimeMillis()
            val haveList = listFile(c).exists()
            // After an update of MyTV always look again (the set may have changed with it).
            val installed = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            if (!force && haveList && now - last in 0 until DAY_MS && installed < last) return false
            val text = String(Net.get(LIST_URL, 8000, 10000, headers()))
            val entries = parse(text)
            if (entries.isEmpty() && JSONObject(text).optInt("format") != FORMAT) {
                Log.w("MyTV", "online photos: list format not supported"); return false
            }
            val before = infos(c).map { it.id }.toSet()
            listFile(c).writeText(text)
            // Gone from the list (or no longer matching it): delete.
            val keep = entries.map { "${it.id}.img" }.toSet()
            dir(c).listFiles()?.forEach { f -> if (f.name != "photos.json" && f.name !in keep) f.delete() }
            var failed = 0
            for (e in entries) {
                val f = photoFile(c, e.id)
                if (f.length() == e.bytes.toLong()) continue
                runCatching { download(e, f) }.onFailure { failed++; Log.w("MyTV", "online photo ${e.id}: $it") }
            }
            // Only a complete pass counts as today's check: otherwise the next start tries again.
            if (failed == 0) sp.edit().putLong("photosChecked", now).apply()
            val after = infos(c).map { it.id }.toSet()
            Log.i("MyTV", "online photos: ${after.size} of ${entries.size} ready" + if (failed > 0) ", $failed failed" else "")
            return after != before
        } catch (t: Throwable) {
            Log.w("MyTV", "online photos: $t"); return false
        } finally { busy = false }
    }

    private fun headers() = mapOf("User-Agent" to "MyTV/" + BuildConfig.VERSION_NAME + " (TV home screen; github.com/tinteractive/MyTV)")

    private fun download(e: Entry, f: File) {
        val bytes = Net.get(e.url, 8000, 20000, headers())
        check(bytes.size == e.bytes) { "size ${bytes.size}, expected ${e.bytes}" }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == e.sha256) { "checksum does not match" }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        check(o.outWidth >= 1280 && o.outHeight >= 720 && o.outWidth > o.outHeight) { "not a landscape photo of 1280×720 or more (${o.outWidth}×${o.outHeight})" }
        // Written next to it first: a half-written file is never taken for a photo.
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { tmp.delete(); error("could not store") }
    }
}
