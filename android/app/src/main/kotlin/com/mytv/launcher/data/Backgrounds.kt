// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class BgInfo(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val source: String,
    val asset: String?,     // assets path, or null for a downloaded or own photo
    val file: File?,        // a downloaded photo of the online set, or an own photo in the app's external files dir
    val title: String = name,
    /** Part of MyTV's online photo set: one of MyTV's photos, like the bundled ones. */
    val online: Boolean = false,
) {
    /** A photo the user added (from the phone). */
    val own get() = file != null && !online
    /** One of MyTV's own photos: bundled or from the online set (with author, licence and source). */
    val mytv get() = !own
    /** Where the photo comes from, for the credit line ("Wikimedia Commons", "Unsplash", …). */
    val site get() = runCatching { java.net.URI(source).host.orEmpty() }.getOrDefault("").let { h ->
        when {
            h.endsWith("wikimedia.org") -> "Wikimedia Commons"
            h.endsWith("unsplash.com") -> "Unsplash"
            h.endsWith("pexels.com") -> "Pexels"
            h.endsWith("flickr.com") -> "Flickr"
            else -> h.removePrefix("www.")
        }
    }
}

/** [full] is drawn centre-cropped through [src] (decoded straight to the GPU at about screen size, never cropped in memory). */
class BgImage(val info: BgInfo, val full: ImageBitmap, val src: androidx.compose.ui.unit.IntRect, val blurred: ImageBitmap, private val lum: FloatArray) {
    /**
     * Perceived brightness (0..1, linear-light luminance) of the photo under a screen rectangle.
     * Mixes mean and 90th percentile so small bright patches (sky, concrete) still count.
     */
    fun brightness(left: Float, top: Float, right: Float, bottom: Float, rootW: Int, rootH: Int): Float {
        if (rootW <= 0 || rootH <= 0) return 0.3f
        val x0 = (left / rootW * GW).toInt().coerceIn(0, GW - 1); val x1 = (right / rootW * GW).toInt().coerceIn(x0 + 1, GW)
        val y0 = (top / rootH * GH).toInt().coerceIn(0, GH - 1); val y1 = (bottom / rootH * GH).toInt().coerceIn(y0 + 1, GH)
        val v = FloatArray((x1 - x0) * (y1 - y0)); var n = 0; var sum = 0f
        for (y in y0 until y1) for (x in x0 until x1) { val l = lum[y * GW + x]; v[n++] = l; sum += l }
        v.sort()
        return 0.5f * (sum / n) + 0.5f * v[(n * 0.9f).toInt().coerceAtMost(n - 1)]
    }

    companion object { const val GW = 96; const val GH = 54 }
}

/**
 * Bundled, licensed photos (assets/backgrounds, credits.json), the downloaded online set ([OnlinePhotos]) and
 * optional own photos sent from the phone to the app's external files dir (no storage permission needed).
 */
class BackgroundRepository(private val context: Context) {

    /**
     * Own photos live in MyTV's private storage. Photos found in the app's folder on shared storage (where they were
     * kept before 2026.11.4, and where `adb push` can still put them) are moved in.
     */
    fun userDir(): File = ownDir(context).also { dir ->
        runCatching {
            context.getExternalFilesDir("backgrounds")?.listFiles { f -> f.isFile && f.extension.lowercase() in PHOTO_TYPES }?.forEach { f ->
                val to = File(dir, f.name)
                if (!f.renameTo(to)) { f.copyTo(to, overwrite = true); f.delete() }
            }
            // A timing log earlier versions kept on shared storage.
            context.getExternalFilesDir(null)?.let { File(it, "power-on.txt").delete() }
        }.onFailure { android.util.Log.w("MyTV", "own photos not moved: $it") }
    }

    fun list(): List<BgInfo> {
        val out = ArrayList<BgInfo>()
        val a = JSONArray(context.assets.open("backgrounds/credits.json").bufferedReader().use { it.readText() })
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out += BgInfo(o.getString("id"), o.getString("name"), o.getString("author"), o.getString("license"),
                o.getString("source"), "backgrounds/" + o.getString("file"), null, title = o.optString("title").ifBlank { o.getString("name") })
        }
        val bundled = out.map { it.id }.toSet()
        OnlinePhotos.infos(context).filter { it.id !in bundled }.forEach { out += it }
        userDir().listFiles { f -> f.isFile && f.extension.lowercase() in PHOTO_TYPES }
            ?.sortedBy { it.name }
            ?.forEach { f -> out += BgInfo("user:" + f.name, f.nameWithoutExtension, "", "", "", null, f) }
        return out
    }

    /**
     * The photo for the home screen. Glass and brightness come from a second, tiny decode; the photo itself is decoded
     * once, scaled while decoding and straight into a hardware bitmap (uploaded to the GPU here, on a background
     * thread, not on the first fade frame). Was: full decode, scaled copy, cropped copy, then a GPU copy (~30 MB peak).
     */
    suspend fun load(info: BgInfo): BgImage = withContext(Dispatchers.Default) {
        last?.takeIf { it.info.id == info.id }?.let { return@withContext it }
        val small = decode(info, 240, 135)
        val lum = luminanceGrid(Bitmap.createScaledBitmap(small, BgImage.GW, BgImage.GH, true))
        val blurred = boxBlur(small, 5, 3)
        val hwBlur = blurred.copy(Bitmap.Config.HARDWARE, false) ?: blurred
        val full = decodeCovering(info, 1920, 1080, Bitmap.Config.HARDWARE)
        // Centre crop to 16:9, applied when drawing.
        val s = maxOf(1920f / full.width, 1080f / full.height)
        val cw = (1920 / s).toInt().coerceAtMost(full.width); val ch = (1080 / s).toInt().coerceAtMost(full.height)
        val x = (full.width - cw) / 2; val y = (full.height - ch) / 2
        BgImage(info, full.asImageBitmap(), androidx.compose.ui.unit.IntRect(x, y, x + cw, y + ch), hwBlur.asImageBitmap(), lum)
            .also { last = it }
    }

    /** Decodes so the picture just covers [w]×[h] (power-of-two sampling plus scaling while decoding: no full-size copy). */
    private fun decodeCovering(info: BgInfo, w: Int, h: Int, config: Bitmap.Config): Bitmap {
        fun open() = info.asset?.let { context.assets.open(it) } ?: info.file!!.inputStream()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("unreadable photo: ${info.id}")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val sampledW = bounds.outWidth / sample
        val targetW = kotlin.math.ceil(bounds.outWidth * maxOf(w / bounds.outWidth.toFloat(), h / bounds.outHeight.toFloat())).toInt()
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = config
            if (targetW < sampledW) { inScaled = true; inDensity = sampledW; inTargetDensity = targetW }
        }
        return open().use { BitmapFactory.decodeStream(it, null, o) } ?: throw java.io.IOException("unreadable photo: ${info.id}")
    }

    /** Thumbnail, cached for the whole run (pages that show them open often); null when the file cannot be read. */
    suspend fun thumb(info: BgInfo): ImageBitmap? = thumbs[info.id] ?: withContext(Dispatchers.Default) {
        runCatching { Bitmap.createScaledBitmap(decode(info, 384, 216), 384, 216, true).asImageBitmap() }
            .onFailure { android.util.Log.w("MyTV", "thumb ${info.id}: $it") }.getOrNull()?.also { thumbs[info.id] = it }
    }

    /**
     * The photos that take part in rotation (and the screensaver), without the ones switched off ([off]); falls back
     * to all when nothing is left.
     */
    fun pool(all: List<BgInfo>, source: String, off: Set<String> = emptySet()): List<BgInfo> = when (source) {
        "own" -> all.filter { it.own }
        "mytv" -> all.filter { it.mytv }
        else -> all
    }.filter { it.id !in off }.ifEmpty { all }

    /** Full-screen photo only (screensaver): no blur or luminance grid. */
    fun photo(info: BgInfo): Bitmap = decode(info, 1920, 1080)

    private fun decode(info: BgInfo, w: Int, h: Int): Bitmap {
        fun open() = info.asset?.let { context.assets.open(it) } ?: info.file!!.inputStream()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val raw = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw java.io.IOException("unreadable photo: ${info.id}")
        // Centre-crop to 16:9 at the target size.
        val scale = maxOf(w / raw.width.toFloat(), h / raw.height.toFloat())
        val sw = (raw.width * scale).toInt().coerceAtLeast(w)
        val sh = (raw.height * scale).toInt().coerceAtLeast(h)
        val scaled = if (sw == raw.width && sh == raw.height) raw else Bitmap.createScaledBitmap(raw, sw, sh, true)
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
    }

    companion object {
        private val PHOTO_TYPES = setOf("jpg", "jpeg", "png", "webp")
        fun ownDir(c: Context): File = File(c.filesDir, "backgrounds").also { it.mkdirs() }

        /** Photos got English ids (2026.11): a photo chosen before keeps being found. */
        val RENAMED = mapOf(
            "kust" to "coast", "kinderdijk" to "windmill", "baybridge" to "bay-bridge", "bergmeer" to "mountain-lake",
            "brug" to "bridge", "burcht" to "castle", "dekerf" to "dutch-dunes", "duin" to "desert-dune", "meer" to "lake",
            "mist" to "misty-valley", "pampus" to "fort-island", "steiger" to "jetty", "vuurtoren" to "lighthouse",
        )

        /** The photo on screen, kept for the whole process: a home screen built anew shows it at once. */
        @Volatile private var last: BgImage? = null

        /** Memory back while another app plays: these are rebuilt when needed. */
        fun trim() { last = null; thumbs.clear() }
        /**
         * The photo thumbnails only (settings and photo sheets closed): about 0.3 MB each, twice (memory and GPU).
         * Returns them, so the caller can recycle them once nothing draws them any more (waiting for the garbage
         * collector keeps the memory for minutes on a TV that is doing nothing).
         */
        fun trimThumbs(): List<ImageBitmap> = thumbs.values.toList().also { thumbs.clear() }
        private val thumbs = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

        private fun lin(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }

        fun luminanceGrid(b: Bitmap): FloatArray {
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            return FloatArray(px.size) { val c = px[it]; 0.2126f * lin((c shr 16) and 255) + 0.7152f * lin((c shr 8) and 255) + 0.0722f * lin(c and 255) }
        }

        /** Separable box blur, repeated passes approximate a Gaussian. Runs on a tiny bitmap. */
        fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
            val w = src.width; val h = src.height
            var a = IntArray(w * h); src.getPixels(a, 0, w, 0, 0, w, h)
            var b = IntArray(w * h)
            repeat(passes) {
                blurLine(a, b, w, h, radius, true); val t = a; a = b; b = t
                blurLine(a, b, w, h, radius, false); val t2 = a; a = b; b = t2
            }
            return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val div = 2 * r + 1
            for (l in 0 until lines) {
                fun idx(p: Int): Int { val q = p.coerceIn(0, len - 1); return if (horizontal) l * w + q else q * w + l }
                var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) { val c = src[idx(k)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
                for (p in 0 until len) {
                    dst[idx(p)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val add = src[idx(p + r + 1)]; val rem = src[idx(p - r)]
                    sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                    sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                    sb += (add and 255) - (rem and 255)
                }
            }
        }
    }
}
