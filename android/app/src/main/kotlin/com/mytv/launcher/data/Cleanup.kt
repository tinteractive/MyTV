// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import com.mytv.launcher.R

/**
 * Pre-installed parts the owner may switch off (never uninstalled; `pm enable` brings each back). Deliberately
 * conservative, for every brand: only what CleanYourTV's per-model lists (505 TVs and boxes, people who lived with it
 * for days) and Universal Android Debloater agree is safe, and nothing needed for inputs, live TV, casting, the remote,
 * Bluetooth, sound, picture, updates, setup, accessibility or the Play Store. Only parts present on this TV are shown.
 * Left out on purpose, among others: org.droidtv.usagelogger and org.droidtv.demome (they hold TV_INPUT_HARDWARE),
 * org.droidtv.ipcontrol (network control, needed for Ambilight), HbbTV, Chromecast, Google Assistant (the microphone
 * button stops working), the TV's own launcher, setup and updaters (TCL ttvs), and anything the sources disagree on.
 */
object Cleanup {
    enum class Category(val label: Int) {
        TELEMETRY(R.string.cat_telemetry), RECOMMENDATIONS(R.string.cat_recs), PROMOTIONS(R.string.cat_store),
        ASSISTANT(R.string.cat_assistant), EXTRAS(R.string.cat_extras), OTHER(R.string.cat_other), CAUTION(R.string.cat_caution),
    }

    /**
     * [slows] = MyTV recommends switching it off: it makes the TV slow (see [SLOW]). [notOn] = never offered on a device
     * whose maker contains this text (lower case).
     */
    data class Item(val pkg: String, val category: Category, val slows: Boolean = false, val notOn: String? = null)

    private val ALL_ITEMS = listOf(
        // Google, on most Android TV and Google TV devices
        Item("com.google.android.feedback", Category.TELEMETRY),
        Item("com.android.tv.feedbackconsent", Category.TELEMETRY),
        Item("com.google.android.tv.bugreportsender", Category.TELEMETRY),
        Item("com.google.android.tv.remotecontrol.logging", Category.TELEMETRY),
        Item("com.google.android.onetimeinitializer", Category.TELEMETRY),
        Item("android.autoinstalls.config.google.gtvpai", Category.PROMOTIONS),
        Item("com.google.android.play.games", Category.EXTRAS),
        Item("com.google.android.videos", Category.EXTRAS),
        Item("com.google.android.music", Category.EXTRAS),
        Item("com.google.android.youtube.tvmusic", Category.EXTRAS),
        // Every 32 s a job of this service keeps Android's own system process busy for 2 s (seen on a Philips): keys
        // wait and then arrive all at once, apps freeze. Only the TV's own Google home uses it, MyTV does not. Never on a
        // Shield: there its phone-remote input stops working without it (CleanYourTV, troubleshooting).
        Item("com.google.android.tvrecommendations", Category.RECOMMENDATIONS, slows = true, notOn = "nvidia"),
        // Philips (TPV)
        Item("org.droidtv.candeebug", Category.TELEMETRY),
        Item("org.droidtv.tv.frameworklogger", Category.TELEMETRY),
        Item("org.droidtv.gel", Category.TELEMETRY),
        Item("org.droidtv.nettvrecommender", Category.RECOMMENDATIONS),
        Item("org.droidtv.recommendation", Category.RECOMMENDATIONS),
        Item("org.droidtv.nettv.market", Category.PROMOTIONS),
        Item("org.droidtv.nettvregistration", Category.PROMOTIONS),
        Item("android.autoinstalls.config.tpv.device", Category.PROMOTIONS),
        Item("org.droidtv.sticker", Category.PROMOTIONS),
        Item("org.droidtv.amazonalexa", Category.ASSISTANT),
        Item("com.phorus.playfi.tv", Category.EXTRAS),
        Item("org.droidtv.dropboxprovider", Category.EXTRAS),
        Item("org.droidtv.astrahdplus", Category.EXTRAS),
        Item("com.tpv.nftgallery", Category.EXTRAS),
        // Samba TV viewing tracking (Philips, Sony and others)
        Item("tv.samba.ssm", Category.TELEMETRY),
        Item("tv.samba.ssm.ui", Category.TELEMETRY),
        // Sony
        Item("com.sony.dtv.da.service", Category.TELEMETRY),
        Item("com.qterics.da.product", Category.TELEMETRY),
        Item("com.sonyericsson.idd.agent", Category.TELEMETRY),
        Item("com.sony.dtv.sonybugreportsys", Category.TELEMETRY),
        Item("com.sony.dtv.usercontext", Category.TELEMETRY),
        Item("com.sony.dtv.sonytab", Category.RECOMMENDATIONS),
        Item("com.sony.dtv.watchtvrecommendation", Category.RECOMMENDATIONS),
        Item("com.sony.dtv.recommendationservice", Category.RECOMMENDATIONS),
        Item("com.sony.dtv.networkrecommendation", Category.RECOMMENDATIONS),
        Item("com.sony.dtv.sonyselect", Category.PROMOTIONS),
        Item("com.sony.dtv.promos", Category.PROMOTIONS),
        Item("android.autoinstalls.config.sony.bravia", Category.PROMOTIONS),
        Item("com.sony.dtv.multiscreendemo", Category.EXTRAS),
        Item("com.sony.dtv.demosupport", Category.EXTRAS),
        Item("com.sony.dtv.demosystemsupport", Category.EXTRAS),
        Item("com.sony.dtv.demomode", Category.EXTRAS),
        Item("com.sony.dtv.smarthelp", Category.EXTRAS),
        Item("com.sony.dtv.customersupport", Category.EXTRAS),
        Item("com.sony.dtv.imanual", Category.EXTRAS),
        Item("com.sony.dtv.helpguidecontents", Category.EXTRAS),
        Item("com.sony.dtv.discovery", Category.EXTRAS),
        Item("com.sony.dtv.bivlinfo", Category.EXTRAS),
        // TCL
        Item("com.tcl.bi", Category.TELEMETRY),
        Item("com.tcl.logkit", Category.TELEMETRY),
        Item("com.tcl.waterfall.overseas", Category.RECOMMENDATIONS),
        Item("com.tcl.tv.tclhome_passive", Category.RECOMMENDATIONS),
        Item("com.tcl.messagebox", Category.PROMOTIONS),
        Item("com.tcl.dashboard", Category.PROMOTIONS),
        Item("com.tcl.overseasappshow", Category.PROMOTIONS),
        Item("com.tcl.appmarket2", Category.PROMOTIONS),
        Item("com.tcl.useragreement", Category.PROMOTIONS),
        Item("com.tcl.smartalexa", Category.ASSISTANT),
        Item("com.tcl.assistant", Category.ASSISTANT),
        Item("com.tcl.esticker", Category.EXTRAS),
        Item("com.tcl.ocean.instructions", Category.EXTRAS),
        Item("com.tcl.repairguide", Category.EXTRAS),
        Item("com.tcl.copydatatotv", Category.EXTRAS),
        Item("com.tcl.tvweishi", Category.EXTRAS),
        // Hisense (Android TV models)
        Item("com.hisense.platformreport", Category.TELEMETRY),
        Item("com.hisense.logupload", Category.TELEMETRY),
        Item("com.hisense.hitv.das", Category.TELEMETRY),
        Item("com.vidaa.oemtabcontentprovider", Category.RECOMMENDATIONS),
        Item("com.hisense.service.ad", Category.PROMOTIONS),
        Item("com.hisense.business.ad", Category.PROMOTIONS),
        Item("com.hisense.service.message", Category.PROMOTIONS),
        Item("net.vidaatv.hisense.android.tv", Category.PROMOTIONS),
        Item("com.vt.alexa", Category.ASSISTANT),
        Item("com.hisense.storemode", Category.EXTRAS),
        Item("com.vt.storemode", Category.EXTRAS),
        Item("com.hisense.demonstrate", Category.EXTRAS),
        Item("fusion.android.tv.demo", Category.EXTRAS),
        Item("com.hisense.electronictutorial", Category.EXTRAS),
        Item("com.hisense.tv.fitmirror", Category.EXTRAS),
        Item("com.hisense.tv.weather", Category.EXTRAS),
        Item("com.vt.hishow", Category.EXTRAS),
        // Xiaomi
        Item("com.miui.tv.analytics", Category.TELEMETRY),
        Item("com.xiaomi.statistic", Category.TELEMETRY),
        Item("com.miui.analytics", Category.TELEMETRY),
        Item("com.mitv.tvhome.michannel", Category.RECOMMENDATIONS),
        Item("com.mitv.tvhome.oemtab", Category.RECOMMENDATIONS),
        Item("com.mitv.tvhome.mitvplus", Category.PROMOTIONS),
        Item("com.xiaomi.floatingframe", Category.PROMOTIONS),
        Item("com.xiaomi.mitv.tvpush.tvpushservice", Category.PROMOTIONS),
        Item("com.duokan.videodaily", Category.PROMOTIONS),
        Item("com.mitv.shoplugin", Category.PROMOTIONS),
        Item("com.xiaomi.mitv.shop", Category.PROMOTIONS),
        Item("android.autoinstalls.config.xiaomi.amelie", Category.PROMOTIONS),
        Item("android.autoinstalls.config.xioami.dangal", Category.PROMOTIONS),
        Item("com.xiaomo.tv.milegal", Category.EXTRAS),
        Item("com.xiaomi.mimusic2", Category.EXTRAS),
        Item("com.xiaomi.mitv.handbook", Category.EXTRAS),
        Item("com.xiaomi.mitv.tvmanager", Category.EXTRAS),
        Item("com.xiaomi.gamecenter.sdk.service.mibox", Category.EXTRAS),
        // Nvidia Shield
        Item("com.nvidia.stats", Category.TELEMETRY),
        Item("com.nvidia.feedback", Category.TELEMETRY),
        Item("com.nvidia.diagtools", Category.TELEMETRY),
        Item("android.autoinstalls.config.nvidia", Category.PROMOTIONS),
        Item("com.nvidia.shield.welcome", Category.PROMOTIONS),
        Item("com.nvidia.factorybundling", Category.PROMOTIONS),
        Item("com.nvidia.shield.registration", Category.PROMOTIONS),
        Item("com.nvidia.tegrazone3", Category.EXTRAS),
        Item("com.nvidia.shield.beta", Category.EXTRAS),
        Item("com.nvidia.shieldbeta", Category.EXTRAS),
        Item("com.nvidia.nvgamecast", Category.EXTRAS),
    )

    /** The list for this device (without the parts that are not safe on its brand). */
    val ITEMS = (android.os.Build.MANUFACTURER ?: "").lowercase().let { maker -> ALL_ITEMS.filter { it.notOn == null || it.notOn !in maker } }

    /** Parts that make the TV slow: MyTV recommends switching them off (System → Clean up says so while one is on). */
    val SLOW = ITEMS.filter { it.slows }.map { it.pkg }.toSet()

    /** Android TV's home, used only when Android reports no home screen at all; others are found by [stocks]. */
    const val STOCK_LAUNCHER = "com.google.android.tvlauncher"
    @Volatile private var stockCache: String? = null

    /**
     * The TV's own home screen(s), as Android reports them, whatever the brand: of the HOME screens other than MyTV,
     * the ones with the highest priority (what Android itself opens on Home when MyTV is not the default). Only
     * screens that are on count, plus a home app the viewer switched off as a whole (Clean up, the home switch): set-up
     * and recovery wizards also say HOME, often with a higher priority, but switch that screen off once done.
     * Settings' fall-back home (a negative priority) never counts.
     */
    fun stocks(c: Context): List<String> {
        val pm = c.packageManager
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        val on = pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName to it.activityInfo.name }.toSet()
        val homes = pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS).filter { ri ->
            val a = ri.activityInfo
            a.packageName != c.packageName && ri.priority >= 0 && ((a.packageName to a.name) in on || switchedOffByUser(pm, a))
        }
        val top = homes.maxOfOrNull { it.priority } ?: return listOf(STOCK_LAUNCHER)
        return homes.filter { it.priority == top }.map { it.activityInfo.packageName }.distinct()
    }

    /** A home screen in an app the viewer switched off as a whole; the screen itself is not switched off. */
    private fun switchedOffByUser(pm: PackageManager, a: android.content.pm.ActivityInfo): Boolean = runCatching {
        pm.getApplicationEnabledSetting(a.packageName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            when (pm.getComponentEnabledSetting(android.content.ComponentName(a.packageName, a.name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> a.enabled
                else -> false
            }
    }.getOrDefault(false)

    fun stock(c: Context): String = stockCache ?: stocks(c).first().also { stockCache = it }

    /** Never offered: MyTV itself, the settings apps (the way back), Google services and search, network control. */
    private val PROTECTED = setOf(
        STOCK_LAUNCHER, "com.google.android.apps.tv.launcherx", "org.droidtv.settings",
        "com.android.tv.settings", "com.google.android.katniss", "com.google.android.gms",
        "org.droidtv.usagelogger", "org.droidtv.ipcontrol", "org.droidtv.hbbtv", "com.google.android.apps.mediashell",
    )

    /** Offered with a warning and a second OK: live TV / guide (also used for inputs), the demo menu, the Play Store. */
    val CAUTION = setOf("org.droidtv.playtv", "org.droidtv.channels", "org.droidtv.demome", "com.android.vending",
        "com.google.android.tv", "com.sony.dtv.tvx", "com.mediatek.wwtv.tvcenter")

    /** Other pre-installed apps with a TV launcher entry (on or off), e.g. operator apps like Canal Digitaal. */
    fun others(c: Context): List<String> {
        val pm = c.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val launchable = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), flags).map { it.activityInfo.packageName }
        val curated = ITEMS.map { it.pkg }.toSet()
        val homes = stocks(c)
        return launchable.distinct().filter { p ->
            p != c.packageName && p !in PROTECTED && p !in curated && p !in homes && runCatching {
                (pm.getApplicationInfo(p, PackageManager.MATCH_DISABLED_COMPONENTS).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }.sortedBy { label(c, it).lowercase() }
    }

    /** true = active, false = switched off, null = not installed on this TV. */
    fun enabled(c: Context, pkg: String): Boolean? = runCatching {
        when (c.packageManager.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> runCatching { c.packageManager.getApplicationInfo(pkg, 0).enabled }.getOrDefault(true)
            else -> true
        }
    }.getOrNull()

    fun label(c: Context, pkg: String): String = runCatching {
        val pm = c.packageManager
        val app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)).toString()
        if (app != pkg) app else {
            // Some Philips apps only name their launcher entry (e.g. the Canal Digitaal operator app).
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS).firstOrNull()?.loadLabel(pm)?.toString() ?: app
        }
    }.getOrDefault(pkg)

    /** Switches a package off or on through the TV's own debug bridge. Throws LocalAdb.NotAvailable/NotApproved. */
    fun set(c: Context, pkg: String, enable: Boolean, onApprovalNeeded: () -> Unit, approvalTimeoutMs: Int = 90_000): String {
        require(pkg == stock(c) || ITEMS.any { it.pkg == pkg } || pkg in others(c)) { "not an allowed package" }
        val cmd = if (enable) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
        return LocalAdb.shell(cmd, onApprovalNeeded, approvalTimeoutMs).trim()
    }

    /**
     * After a boot: some TVs switch pre-installed apps back on (seen after a power cut). Whatever the viewer switched
     * off with Clean up and is on again is switched off again, quietly, when network debugging is on and MyTV was
     * approved before (no prompt at start-up). The TV's own home screen is left alone. Blocking; returns the packages.
     */
    fun reapply(c: Context): List<String> {
        val off = Prefs(c).cleanedUp - stock(c)
        val back = off.filter { enabled(c, it) == true }
        if (back.isEmpty() || !LocalAdb.reachable()) return emptyList()
        return back.filter { pkg ->
            runCatching { set(c, pkg, enable = false, onApprovalNeeded = {}, approvalTimeoutMs = 3_000); enabled(c, pkg) == false }
                .onFailure { android.util.Log.w("MyTV", "clean up again: $pkg: $it") }.getOrDefault(false)
        }.also { if (it.isNotEmpty()) android.util.Log.i("MyTV", "clean up again after boot: ${it.size} switched off again") }
    }
}
