// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(
    val key: String,            // flattened component name
    val component: ComponentName,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val isSystem: Boolean,
    /** The banner extended to a 4:3 dock tile: outer edge rows trimmed, last clean row repeated (seamless). */
    val bannerTall: ImageBitmap?,
    /** "banner" | "icon" | "letter" (chosen in the app menu). */
    val art: String = "banner",
    /** The app's own name (the label may be a custom one). */
    val origLabel: String = label,
)

/** Discovers apps that are actually installed and launchable on this TV. */
class AppRepository(private val context: Context, private val prefs: Prefs) {

    /** Initial dock order for widely used streaming apps (only those installed); everything else follows by name. */
    private val preferred = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        "com.plexapp.android", "com.spotify.tv.android", "com.google.android.tv", "org.droidtv.playtv",
    )

    /** System and utility entries that start outside the dock (still under "Other apps"). */
    private fun hiddenByDefault(pkg: String) =
        (pkg.startsWith("org.droidtv.") && pkg != "org.droidtv.playtv") ||
            pkg in setOf("com.vewd.core.browserui", "io.mimi.tvapp", "com.phorus.playfi.tv")


    /**
     * One load at a time, for the whole process: start-up, resume and a home screen that Android builds anew (language
     * change, restore) each have their own repository but share the caches below.
     */
    fun loadAll(): List<AppEntry> = synchronized(cache) { load() }

    /** The entry with its tall dock banner, built on first use and remembered. */
    private fun withDockArt(e: AppEntry): AppEntry {
        if (e.bannerTall != null) return e
        return bannerBitmaps[e.key]?.let { b -> runCatching { e.copy(bannerTall = tallBanner(b).asImageBitmap()) }.getOrNull() }
            ?.also { t -> cache[e.key]?.let { cache[e.key] = it.first to t } } ?: e
    }

    /** Dock art for apps that are shown in the dock without having been loaded as dock apps (demo mode). */
    fun dockArt(list: List<AppEntry>): List<AppEntry> = synchronized(cache) { list.map { withDockArt(it) } }

    private fun load(): List<AppEntry> {
        val pm = context.packageManager
        sequence = pm.getChangedPackages(sequence.coerceAtLeast(0))?.sequenceNumber ?: sequence.coerceAtLeast(0)
        val main = Intent(Intent.ACTION_MAIN)
        val leanback = pm.queryIntentActivities(Intent(main).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in leanback) {
            val ai = ri.activityInfo
            if (ai.packageName == context.packageName) continue
            if (!seen.add(ai.packageName)) continue
            val cn = ComponentName(ai.packageName, ai.name)
            val updated = runCatching { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            cache[cn.flattenToString()]?.let { (t, e) -> if (t == updated) { out += e; continue } }
            val bannerBmp = runCatching { (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.toBitmap(320, 180) }.getOrNull()
            val banner = bannerBmp?.asImageBitmap()
            // The tall dock version is built later, only for apps that are in the dock.
            bannerBmp?.let { bannerBitmaps[cn.flattenToString()] = it }
            val icon = runCatching { ai.loadIcon(pm).toBitmap(64, 64).asImageBitmap() }.getOrNull()
            val system = (ai.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val e = AppEntry(cn.flattenToString(), cn, ri.loadLabel(pm).toString(), banner, icon, system, null)
            cache[e.key] = updated to e
            out += e
        }
        applyDefaults(out)
        val hidden = prefs.hidden
        val withTall = out.map { e -> if (e.key in hidden) e else withDockArt(e) }
        val present = withTall.map { it.key }.toSet()
        cache.keys.retainAll(present); bannerBitmaps.keys.retainAll(present)
        return sort(withTall)
    }

    private fun applyDefaults(apps: List<AppEntry>) {
        // One-time: the Play Store used to be hidden by default; show it among the active apps.
        if (!prefs.flag("playStoreShown")) {
            apps.firstOrNull { it.component.packageName == "com.android.vending" }?.let { prefs.hidden = prefs.hidden - it.key }
            prefs.setFlag("playStoreShown")
        }
        val known = prefs.known
        // A child profile that was never opened before starts with an empty dock (the parent chooses its apps).
        if (known.isEmpty() && prefs.profiles.firstOrNull { it.id == prefs.profile }?.child == true) {
            prefs.hidden = apps.map { it.key }.toSet(); prefs.known = apps.map { it.key }.toSet(); return
        }
        val newOnes = apps.filter { it.key !in known }
        if (newOnes.isEmpty()) return
        val hidden = prefs.hidden.toMutableSet()
        newOnes.filter { hiddenByDefault(it.component.packageName) }.forEach { hidden += it.key }
        val order = prefs.order.toMutableList()
        val sortedNew = newOnes.sortedWith(compareBy({ preferred.indexOf(it.component.packageName).let { i -> if (i < 0) 999 else i } }, { it.label.lowercase() }))
        sortedNew.forEach { if (it.key !in order) order += it.key }
        prefs.hidden = hidden
        prefs.order = order
        prefs.known = known + newOnes.map { it.key }
    }

    fun sort(apps: List<AppEntry>): List<AppEntry> {
        val order = prefs.order
        return apps.sortedWith(compareBy({ order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
    }

    fun launch(app: AppEntry): Boolean {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(i); true }.getOrElse {
            runCatching {
                context.startActivity(context.packageManager.getLeanbackLaunchIntentForPackage(app.component.packageName)
                    ?: context.packageManager.getLaunchIntentForPackage(app.component.packageName)!!)
                true
            }.getOrDefault(false)
        }
    }

    fun move(key: String, delta: Int, visibleKeys: List<String>) {
        // Reorder among visible apps, then persist full order (hidden keep their relative position at the end).
        val vis = visibleKeys.toMutableList()
        val i = vis.indexOf(key)
        val j = (i + delta).coerceIn(0, vis.lastIndex)
        if (i < 0 || i == j) return
        vis.removeAt(i); vis.add(j, key)
        prefs.order = vis + prefs.order.filter { it !in vis }
    }

    fun setHidden(key: String, hidden: Boolean) {
        prefs.hidden = if (hidden) prefs.hidden + key else prefs.hidden - key
        if (!hidden) prefs.order = prefs.order.filter { it != key } + key
    }

    /**
     * Whether an app was installed, removed, updated, or switched on/off since the last load: one call to Android
     * instead of a lookup per app on every Home (tv-vibehack does the same).
     */
    fun changedSinceLoad(): Boolean = sequence < 0 ||
        context.packageManager.getChangedPackages(sequence)?.packageNames?.any { it != context.packageName } == true

    companion object {
        /**
         * Whether [component] (flattened) is an activity this TV offers in a launcher. Asked before MyTV starts a
         * stored component by itself (the start-up app), so a settings file cannot make it open something else.
         */
        fun isLaunchable(c: Context, component: String): Boolean = runCatching {
            val cn = ComponentName.unflattenFromString(component) ?: return false
            val pm = c.packageManager
            listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).any { cat ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(cn.packageName), 0)
                    .any { it.activityInfo.packageName == cn.packageName && it.activityInfo.name == cn.className }
            }
        }.getOrDefault(false)

        /** Android's package-change counter at the last load (-1 = never loaded in this process). */
        @Volatile private var sequence = -1
        /**
         * Decoded art per app, reused while the app is not updated (onResume used to decode everything again). For
         * the whole process, so a home screen that Android builds anew (language change, memory) needs no decoding.
         */
        private val cache = HashMap<String, Pair<Long, AppEntry>>()
        private val bannerBitmaps = HashMap<String, android.graphics.Bitmap>()

        /**
         * Extends a 320x180 banner to a 320x240 (4:3) tile. Banners often have a darker or semi-transparent outer
         * pixel row; those rows are trimmed and the first/last clean row is repeated upwards/downwards, so the
         * extension continues the banner exactly instead of leaving a seam. Everything is made opaque on the tile colour.
         */
        fun tallBanner(b: android.graphics.Bitmap): android.graphics.Bitmap {
            val w = b.width; val h = b.height
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            fun opaque(c: Int): Int {
                val a = ((c ushr 24) and 255) / 255f
                fun ch(v: Int, bg: Int) = (v * a + bg * (1 - a)).toInt()
                return (0xFF shl 24) or (ch((c shr 16) and 255, 0x1C) shl 16) or (ch((c shr 8) and 255, 0x1F) shl 8) or ch(c and 255, 0x24)
            }
            for (i in px.indices) px[i] = opaque(px[i])
            fun rowDiff(r1: Int, r2: Int): Float {
                var d = 0f
                for (x in 0 until w) { val c1 = px[r1 * w + x]; val c2 = px[r2 * w + x]
                    d += kotlin.math.abs(((c1 shr 16) and 255) - ((c2 shr 16) and 255)) + kotlin.math.abs(((c1 shr 8) and 255) - ((c2 shr 8) and 255)) + kotlin.math.abs((c1 and 255) - (c2 and 255)) }
                return d / w
            }
            // Trim edge rows that differ from their inner neighbour (borders, anti-aliasing), at least 2, at most 10.
            var top = 2; while (top < 10 && rowDiff(top, top + 1) > 6f) top++
            var bot = 2; while (bot < 10 && rowDiff(h - 1 - bot, h - 2 - bot) > 6f) bot++
            val inner = h - top - bot
            val outH = w * 3 / 4
            val offset = (outH - inner) / 2
            val out = IntArray(w * outH)
            for (y in 0 until outH) {
                val src = (y - offset + top).coerceIn(top, h - 1 - bot)
                System.arraycopy(px, src * w, out, y * w, w)
            }
            return android.graphics.Bitmap.createBitmap(out, w, outH, android.graphics.Bitmap.Config.ARGB_8888)
        }

    }

}
