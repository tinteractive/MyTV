// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.mytv.launcher.data.AppEntry
import com.mytv.launcher.data.AppRepository
import com.mytv.launcher.data.BackgroundRepository
import com.mytv.launcher.data.BgInfo
import com.mytv.launcher.data.City
import com.mytv.launcher.data.Prefs
import com.mytv.launcher.data.Profile
import com.mytv.launcher.data.WeatherRepository
import com.mytv.launcher.data.WeatherState
import com.mytv.launcher.data.Place
import com.mytv.launcher.ui.BgRender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Overlay {
    data object Weather : Overlay()
    data object Settings : Overlay()
    data object AllApps : Overlay()
    data object Sources : Overlay()
    data object PlexLink : Overlay()
    data class PlexUser(val profile: String) : Overlay()
    data object OwnPhotos : Overlay()
    /** Settings to the phone, or back from it. */
    /** [restore]: only putting settings back (welcome); otherwise one page for saving and restoring. */
    data class Backup(val restore: Boolean = false) : Overlay()
    /** After a restore: the permissions that were on in the backup and are not on here ([rights] = JSON). */
    data class RestoreRights(val rights: String) : Overlay()
    /** Other TVs with MyTV: link once, then take over all settings of a linked TV. */
    data object OtherTvs : Overlay()
    data object Welcome : Overlay()
    /** Profile picker (top right); [ask] = "who is watching?" after the TV was switched on. */
    data class Profiles(val ask: Boolean = false) : Overlay()
    /** Set or change the PIN (Settings / welcome). */
    data object PinSetup : Overlay()
    data class Rename(val key: String) : Overlay()
    data object Bluetooth : Overlay()
    data object Update : Overlay()
    data class AppOptions(val key: String) : Overlay()
    /** Hold OK / Menu on the "now playing" card. */
    data object NowPlayingMenu : Overlay()
    /** Hold OK / Menu on a "continue watching" card ([id] = its focus key). */
    data class WatchMenu(val id: String) : Overlay()
}

data class OverlayEntry(val overlay: Overlay, val opener: String?)

/** The coloured buttons and Android's standard key for each. */
val COLORS = listOf("red" to android.view.KeyEvent.KEYCODE_PROG_RED, "green" to android.view.KeyEvent.KEYCODE_PROG_GREEN,
    "yellow" to android.view.KeyEvent.KEYCODE_PROG_YELLOW, "blue" to android.view.KeyEvent.KEYCODE_PROG_BLUE)

class HomeState(private val context: Context, private val scope: CoroutineScope) {
    val appContext: Context get() = context
    val prefs = Prefs(context).also { it.migrateProfiles() }
    private val appRepo = AppRepository(context, prefs)
    val weatherRepo = WeatherRepository(context, prefs)
    val bgRepo = BackgroundRepository(context)
    val bg = BgRender()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var hidden by mutableStateOf(prefs.hidden); private set
    val visibleApps get() = apps.filter { it.key !in hidden }

    /** Single weather location (city level). */
    var city by mutableStateOf(prefs.cities.firstOrNull()); private set
    var weather by mutableStateOf(WeatherState(null, null, false)); private set

    var searchText by mutableStateOf("")
    var searchResults by mutableStateOf<List<Place>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var searchError by mutableStateOf(false); private set
    private var searchJob: Job? = null

    var autostart by mutableStateOf(prefs.autostart); private set

    /** Moon data for the weather location, computed off the UI thread (next full moon needs a scan). */
    var moon by mutableStateOf<com.mytv.launcher.data.MoonInfo?>(null); private set

    private fun updateMoon() {
        val c = city ?: return
        scope.launch { moon = withContext(Dispatchers.Default) { com.mytv.launcher.data.Moon.info(c.lat, c.lon) } }
    }

    var backgrounds by mutableStateOf<List<BgInfo>>(emptyList()); private set
    var compactClock by mutableStateOf(prefs.compactClock)
    var transitions by mutableStateOf(prefs.transitions)
    var rotateSeconds by mutableIntStateOf(prefs.rotateSeconds)

    // ---------------------------------------------------------------- language and units (Display)
    var language by mutableStateOf(prefs.language); private set
    var tempUnit by mutableStateOf(prefs.tempUnit); private set
    var timeFormat by mutableStateOf(prefs.timeFormat); private set
    /** Set by MainActivity: re-creates the screen in the new language. */
    var onLanguageChanged: (() -> Unit)? = null

    /** Setup from adb: language without reopening the settings page afterwards. */
    fun provisionLanguage(lang: String?) {
        if (lang != null && lang !in com.mytv.launcher.ui.LANGUAGES || lang == language) return
        prefs.language = lang; language = lang
        onLanguageChanged?.invoke()
    }

    fun chooseLanguage(lang: String?) {
        if (lang == language) return
        prefs.language = lang; language = lang
        // After the screen is rebuilt in the new language, come back to where the choice was made.
        if (top == Overlay.Welcome) reopenWelcome = welcomeStep else reopenDisplay = true
        onLanguageChanged?.invoke()
    }

    companion object {
        /** Survives the activity re-creation that applies a new language (same process). */
        @Volatile var reopenDisplay = false
        /** Welcome step to return to after a language change (-1 = none). */
        @Volatile var reopenWelcome = -1
    }
    fun chooseTempUnit(u: String) {
        prefs.tempUnit = u; tempUnit = u
        if (weather.report?.fahrenheit != wantFahrenheit()) refreshWeather(force = true)   // Open-Meteo converts
    }
    fun chooseTimeFormat(f: String) { prefs.timeFormat = f; timeFormat = f }

    fun units() = com.mytv.launcher.ui.Units.of(context, tempUnit, timeFormat, weather.report?.fahrenheit)
    private fun wantFahrenheit() = com.mytv.launcher.ui.Units.wantsFahrenheit(tempUnit)

    /** App key currently in "move" mode in the dock (Left/Right moves it, OK/Back drops it). */
    var moving by mutableStateOf<String?>(null)
    /** On-screen bounds of tiles by focus key, used to anchor the app menu above its tile. */
    val tileRects = HashMap<String, androidx.compose.ui.geometry.Rect>()

    /** Section shown in the settings screen (also remembered between openings). */
    var settingsSection by mutableStateOf("weather")
    /** Lets the top overlay handle Back first (e.g. settings: page -> section list). Returns true if handled. */
    var backHook: (() -> Boolean)? = null

    val overlays = mutableStateListOf<OverlayEntry>()
    val top get() = overlays.lastOrNull()?.overlay

    /** Focus targets by stable key, so focus can return to the element that opened an overlay. */
    val focus = HashMap<String, FocusRequester>()
    var pendingFocus by mutableStateOf<String?>(null)

    private var bgJob: Job? = null

    init {
        bg.blur = prefs.glassBlur
        // Cached weather is parsed in the background: nothing on the main thread before the first frame.
        city?.let { c -> scope.launch { withContext(Dispatchers.Default) { weatherRepo.cached(c) }?.let { if (weather.report == null) weather = WeatherState(it, null, false) } } }
        updateMoon()
    }

    private var appsJob: kotlinx.coroutines.Job? = null
    /** [onlyIfChanged]: coming back to MyTV; skip the full load when no app changed meanwhile. */
    fun refreshApps(onlyIfChanged: Boolean = false) {
        if (appsJob?.isActive == true) {
            appsAgain = appsAgain || !onlyIfChanged
            return
        }
        appsJob = scope.launch {
            if (onlyIfChanged && apps.isNotEmpty() && !withContext(Dispatchers.IO) { appRepo.changedSinceLoad() }) return@launch
            // A home screen without its app list is of little use, but it must stay up: a failed load keeps the old list.
            val list = try {
                withContext(Dispatchers.IO) { appRepo.loadAll() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MyTV", "app list failed", e)
                return@launch
            }
            // Own names and pictures.
            val names = prefs.appNames; val arts = prefs.appArt
            apps = list.map { e ->
                val art = arts[e.key] ?: "banner"
                e.copy(label = names[e.key] ?: e.label, origLabel = e.label, art = art)
            }
            hidden = prefs.hidden
            if (pendingFocus == null && overlays.isEmpty() && apps.isNotEmpty() && firstFocusDone.not()) {
                firstFocusDone = true
                // The TV was just switched on: the first app. Otherwise MyTV restarted while away: back where you were.
                pendingFocus = if (freshStart) { freshStart = false; firstDockFocus() }
                    else prefs.homeFocus?.takeIf { it.startsWith("app:") && visibleApps.any { a -> "app:" + a.key == it } } ?: dockFocus()
            }
        }.also { job -> job.invokeOnCompletion { if (appsAgain) { appsAgain = false; scope.launch { refreshApps() } } } }
    }
    private var firstFocusDone = false
    /** Something changed while a load was running (an app back in the dock, another profile): load once more after it. */
    private var appsAgain = false

    fun launch(app: AppEntry) {
        prefs.lastLaunched = app.key
        if (!appRepo.launch(app)) Log.w("MyTV", "launch failed: ${app.key}")
    }

    fun moveApp(key: String, delta: Int) {
        appRepo.move(key, delta, visibleApps.map { it.key })
        apps = appRepo.sort(apps)
    }

    fun setHidden(key: String, h: Boolean) {
        appRepo.setHidden(key, h)
        hidden = prefs.hidden
        apps = appRepo.sort(apps)
        if (!h) refreshApps()   // builds the dock art for an app that just came back
    }

    // ---------------------------------------------------------------- overlays
    /** uptimeMillis of the last open() (for timing logs). */
    var openedAt = 0L

    fun open(o: Overlay, opener: String?) {
        openedAt = android.os.SystemClock.uptimeMillis()
        if (o == Overlay.Settings) settingsSection = "home"   // settings always start at the first section
        overlays.add(OverlayEntry(o, opener))
        Log.i("MyTV", "overlay: open ${o::class.simpleName} (from $opener), ${overlays.size} open")
    }
    /**
     * "Who is watching?" after power-on, while a parent profile is still the active one and a PIN protects it: Back and
     * Home must not skip the question (that would hand a child the parent profile without the PIN).
     */
    val whoLocked get() = (overlays.lastOrNull()?.overlay as? Overlay.Profiles)?.ask == true && hasPin && !isChild

    /** Where focus goes when nothing else is known: the last opened app, or the first one in the dock. */
    fun firstDockFocus() = "app:" + (visibleApps.firstOrNull()?.key ?: "__other")

    /**
     * True right after the TV was switched on (screen off and on again, or a start soon after booting): the remote
     * starts on the first app in the dock. Otherwise MyTV comes back where it was.
     */
    var freshStart = android.os.SystemClock.elapsedRealtime() < 10 * 60_000L

    /** The home-screen element the remote is on (not inside a menu or settings). */
    var homeFocus: String? = null; private set
    fun noteFocus(key: String) {
        if (overlays.isEmpty()) homeFocus = key
        if (key.startsWith("app:")) lastDockKey = key
    }

    /** The dock tile the remote was on last: Down into the dock goes back there. */
    var lastDockKey: String? = null; private set

    /** MyTV is in front again: after a switch-on, to the first app (Compose keeps the focus where it was otherwise). */
    fun onBackInFront() {
        if (!freshStart || !firstFocusDone) return
        freshStart = false
        if (overlays.isEmpty()) pendingFocus = firstDockFocus()
    }

    fun dockFocus() = "app:" + (prefs.lastLaunched?.takeIf { k -> visibleApps.any { it.key == k } } ?: visibleApps.firstOrNull()?.key ?: "__other")

    fun close() {
        if (whoLocked) return
        val e = overlays.removeLastOrNull() ?: return
        Log.i("MyTV", "overlay: close ${e.overlay::class.simpleName}")
        // A sheet that opened by itself (update, welcome, restored rights) has no opener: back to the dock.
        pendingFocus = e.opener ?: if (overlays.isEmpty()) dockFocus() else null
        if (overlays.isEmpty()) releaseSheetMemory()
    }

    /** Back on the home screen: what only the sheets needed (photo thumbnails) is given back at once. */
    private fun releaseSheetMemory() {
        val old = com.mytv.launcher.data.BackgroundRepository.trimThumbs()
        if (old.isEmpty()) return
        scope.launch {
            // The closed sheets are out of the composition a frame later; a sheet opened meanwhile decodes its own.
            kotlinx.coroutines.delay(1_000)
            old.forEach { runCatching { it.asAndroidBitmap().recycle() } }
        }
    }

    /** [answered]: the "who is watching" question was answered (a profile was chosen, with the PIN where needed). */
    fun closeAll(focusKey: String?, answered: Boolean = false) {
        if (whoLocked && !answered) return
        overlays.clear(); pendingFocus = focusKey
        releaseSheetMemory()
    }

    /** The focused home-screen item went away (playback ended, weather too old, a card left the row): to the dock. */
    fun focusGone() { if (overlays.isEmpty() && pendingFocus == null) pendingFocus = dockFocus() }

    /** Opens the stock Google TV home on purpose; the Home watcher is told to stand aside for a moment. */
    fun openStockHome() {
        HomeWatch.bypassUntil = android.os.SystemClock.elapsedRealtime() + 10_000
        HomeWatch.watchHomeKey()    // a Home press there sends no window event: the key itself brings MyTV back
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(stockLauncher)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAppInfo(app: AppEntry) = startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:" + app.component.packageName)))

    /** Opens the system uninstall confirmation (user-installed apps only). */
    fun uninstall(app: AppEntry) = startSafe(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.component.packageName)))

    private fun startSafe(i: Intent) { com.mytv.launcher.data.Net.startFirst(context, i) }

    // ---------------------------------------------------------------- continue watching
    var continueWatching by mutableStateOf(prefs.continueWatching); private set
    var watchItems by mutableStateOf<List<com.mytv.launcher.data.WatchItem>>(emptyList()); private set
    /** Set by MainActivity: asks the viewer for READ_TV_LISTINGS. */
    var requestTvListings: (() -> Unit)? = null
    /** Whether Android will still show its permission question (false after "don't ask again"). */
    var tvListingsAskable: (() -> Boolean)? = null
    /** Set by MainActivity: shows Android's own question to make MyTV the default home. */
    var requestDefaultHome: ((Intent) -> Unit)? = null
    fun askDefaultHome() { com.mytv.launcher.data.SystemAccess.defaultHomeRequest(context)?.let { i -> requestDefaultHome?.invoke(i) } }

    fun refreshWatchNext() {
        if (demoMode) return
        if (!continueWatching) { watchItems = emptyList(); return }
        scope.launch {
            val all = withContext(Dispatchers.IO) { com.mytv.launcher.data.WatchNext.load(context) }
            // A child profile sees only what comes from its own apps.
            watchItems = if (!isChild) all else visibleApps.map { it.component.packageName }.toSet().let { mine -> all.filter { it.pkg in mine } }
        }
    }

    fun watchItem(id: String) = watchItems.firstOrNull { "cw:${it.pkg}:${it.title}" == id }

    /** "Remove from this row": any card. MyTV's own history forgets it; an app's own card is hidden (until it changes). */
    fun removeWatchItem(item: com.mytv.launcher.data.WatchItem) {
        // Focus moves to the neighbouring card, or to the dock when this was the last one.
        val next = watchItems.filter { it != item }.getOrNull((watchItems.indexOf(item)).coerceAtMost(watchItems.size - 2))
        pendingFocus = next?.let { "cw:${it.pkg}:${it.title}" } ?: ("app:" + (visibleApps.firstOrNull()?.key ?: ""))
        scope.launch {
            withContext(Dispatchers.IO) {
                item.historyKey?.let { com.mytv.launcher.data.History.remove(context, it) }
                com.mytv.launcher.data.WatchNext.hide(context, item)
            }
            refreshWatchNext()
        }
    }

    fun changeContinueWatching(on: Boolean) {
        prefs.continueWatching = on; continueWatching = on
        if (on && !com.mytv.launcher.data.WatchNext.allowed(context)) requestTvListings?.invoke()
        refreshWatchNext()
    }

    fun openWatchItem(item: com.mytv.launcher.data.WatchItem) { com.mytv.launcher.data.WatchNext.open(context, item) }

    // ---------------------------------------------------------------- top-right buttons and app names/pictures
    var button1 by mutableStateOf(prefs.button1); private set
    var button2 by mutableStateOf(prefs.button2); private set
    fun setButton(slot: Int, v: String) { if (slot == 1) { prefs.button1 = v; button1 = v } else { prefs.button2 = v; button2 = v } }
    /** "All apps" must stay reachable: without that button the dock gets an "Other apps" tile. */
    val showOtherTile get() = !isChild && (hidden.any { h -> apps.any { it.key == h } } || (button1 != "allapps" && button2 != "allapps"))

    fun setDockOrder(packages: List<String>) {
        val keys = packages.mapNotNull { p -> apps.firstOrNull { it.component.packageName == p }?.key }
        prefs.order = keys + prefs.order.filter { it !in keys } + apps.map { it.key }.filter { it !in keys && it !in prefs.order }
        apps = appRepo.sort(apps)
    }

    fun renameApp(key: String, name: String?) {
        prefs.appNames = prefs.appNames.toMutableMap().apply { if (name.isNullOrBlank()) remove(key) else put(key, name.trim()) }
        refreshApps()
    }

    fun nextAppArt(key: String) {
        val cur = prefs.appArt[key] ?: "banner"
        val next = listOf("banner", "icon", "letter").let { it[(it.indexOf(cur) + 1) % it.size] }
        prefs.appArt = prefs.appArt.toMutableMap().apply { if (next == "banner") remove(key) else put(key, next) }
        refreshApps()
    }

    fun runButton(v: String, opener: String) {
        when {
            v == "allapps" -> open(Overlay.AllApps, opener)
            v == "sources" -> open(Overlay.Sources, opener)
            v == "bluetooth" -> open(Overlay.Bluetooth, opener)
            v == "android" -> openSystemSettings()
            v == "stock" -> openStockHome()
            // The sleep timer: one step further (off → 15 → 30 → 60 → 90 → 120 → off).
            v == "sleep" -> setSleep(listOf(15, 30, 60, 90, 120).firstOrNull { it > com.mytv.launcher.SleepTimer.minutesLeft() } ?: 0)
            v.startsWith("app:") -> apps.firstOrNull { it.key == v.removePrefix("app:") }?.let { launch(it) }
        }
    }

    // ---------------------------------------------------------------- night mode
    /** "off" | "sunset" | "fixed" */
    var nightMode by mutableStateOf(prefs.nightMode); private set
    fun changeNightMode(v: String) { prefs.nightMode = v; nightMode = v; updateNight() }
    var isNight by mutableStateOf(false); private set
    fun updateNight() {
        val now = java.time.LocalDateTime.now()
        isNight = when (nightMode) {
            "fixed" -> now.hour >= 22 || now.hour < 7
            "sunset" -> {
                val days = weather.report?.days.orEmpty()
                val today = days.firstOrNull { it.date == now.toLocalDate() }
                val set = today?.sunset; val rise = today?.sunrise
                if (set == null || rise == null) now.hour >= 21 || now.hour < 7 else now.isAfter(set) || now.isBefore(rise)
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- sleep timer
    var sleepTick by mutableIntStateOf(0); private set

    // ---------------------------------------------------------------- remote buttons
    var sourcesButton by mutableStateOf(prefs.sourcesButton); private set
    var appsButton by mutableStateOf(prefs.appsButton); private set
    var digitKeys by mutableStateOf(prefs.digitKeys); private set
    /** Bumped when a coloured button's function or a learned button changes (the remote page reads Prefs). */
    var remoteTick by mutableIntStateOf(0); private set
    /** Bumped when Plex is linked or unlinked (the settings line and Continue watching read it). */
    var plexTick by mutableIntStateOf(0); private set
    fun plexChanged() { plexTick++; refreshWatchNext() }

    // ---------------------------------------------------------------- low battery
    /** "Headphones: battery low (12%)", shown for a while on the home screen; null otherwise. */
    var batteryNotice by mutableStateOf<Pair<String, Int>?>(null); private set
    private val batteryWarned = HashSet<String>()

    /** Once per drop below 15 % per device (again after it was charged above 30 %). */
    fun checkBatteries() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { com.mytv.launcher.data.Bluetooth.batteries(context) }
            list.filter { it.third > 30 }.forEach { batteryWarned.remove(it.first) }
            val low = list.firstOrNull { it.third < 15 && it.first !in batteryWarned } ?: return@launch
            batteryWarned += low.first
            batteryNotice = low.second to low.third
            kotlinx.coroutines.delay(15_000L)
            batteryNotice = null
        }
    }

    /** Apps that feed Continue watching (for its settings), and the ones switched off. */
    var cwSources by mutableStateOf<List<String>>(emptyList()); private set
    var cwOff by mutableStateOf(com.mytv.launcher.data.WatchNext.appsOff(context)); private set
    fun loadCwSources() { scope.launch { cwSources = withContext(Dispatchers.IO) { com.mytv.launcher.data.WatchNext.sources(context) } } }
    fun setCwApp(pkg: String, on: Boolean) {
        com.mytv.launcher.data.WatchNext.setAppOn(context, pkg, on); cwOff = com.mytv.launcher.data.WatchNext.appsOff(context); refreshWatchNext()
    }
    /** Which button is being learned ("sources", "apps", "red", …): the Home-button helper reports the next key. */
    var learning by mutableStateOf<String?>(null); private set

    fun changeSourcesButton(on: Boolean) { prefs.sourcesButton = on; sourcesButton = on }
    fun changeAppsButton(on: Boolean) { prefs.appsButton = on; appsButton = on }
    fun changeDigitKeys(on: Boolean) { prefs.digitKeys = on; digitKeys = on }
    fun setColorAction(color: String, v: String) { prefs.setColorAction(color, v); remoteTick++ }

    fun learned(target: String): Boolean = when (target) {
        "sources" -> prefs.sourcesKey != -1
        "apps" -> prefs.appsKey != -1
        else -> prefs.colorCode(target) != -1
    }

    /** Learn [target]'s button from the next key pressed; again (or leaving the page) stops learning. */
    fun learnKey(target: String?) {
        HomeWatch.learnKey = null
        if (target == null || learning == target) { learning = null; return }
        learning = target
        HomeWatch.learnKey = { code ->
            when (target) {
                "sources" -> prefs.sourcesKey = code
                "apps" -> prefs.appsKey = code
                else -> prefs.setColorCode(target, code)
            }
            learning = null; remoteTick++
        }
        scope.launch { kotlinx.coroutines.delay(15_000L); if (learning == target) learnKey(null) }
    }

    /**
     * A key on MyTV's home screen (nothing open): a coloured button's function, or a number button opening that app in
     * the dock. True when it was used.
     */
    fun onRemoteKey(code: Int): Boolean {
        if (overlays.isNotEmpty() || moving != null) return false
        COLORS.forEach { (color, standard) ->
            val mine = prefs.colorCode(color).takeIf { it != -1 } ?: standard
            if (code == mine) {
                val v = prefs.colorAction(color)
                if (v == "none" || (isChild && v != "sources")) return false
                runButton(v, homeFocus ?: dockFocus()); return true
            }
        }
        if (digitKeys && code in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9) {
            visibleApps.getOrNull(code - android.view.KeyEvent.KEYCODE_1)?.let { launch(it); return true }
        }
        return false
    }
    fun setSleep(minutes: Int) { SleepTimer.set(context, minutes); sleepTick++ }
    /** The minutes left (and whether a timer runs at all) are not Compose state: redraw the button's label. */
    fun refreshSleepLabel() {
        if (SleepTimer.active || sleepShown) sleepTick++
        sleepShown = SleepTimer.active
    }
    private var sleepShown = false
    // ---------------------------------------------------------------- network debugging (adb) on the TV
    /**
     * Clean up, the Google TV home switch, "start after" for the screensaver and the sleep timer use the TV's own adb.
     * When network debugging is off they cannot work, so they are not shown.
     */
    var adbReachable by mutableStateOf(false); private set
    /** Functions that used to need network debugging, now also possible without it. */
    val canSleep get() = adbReachable || SleepTimer.canStandby
    val canSetDreamTimeout get() = adbReachable || com.mytv.launcher.data.Screensaver.canSetTimeout(context) ||
        com.mytv.launcher.data.Screensaver.permissionScreen(context) != null
    fun checkAdb() {
        scope.launch {
            adbReachable = withContext(Dispatchers.IO) { com.mytv.launcher.data.LocalAdb.reachable() }
            // The TV switched apps back on that Clean up switched off (after a restart), while the connection was not
            // there yet (Wireless debugging is off after a restart): switch them off again now, once per MyTV start.
            if (adbReachable && !cleanupReapplied) {
                cleanupReapplied = true
                withContext(Dispatchers.IO) { runCatching { com.mytv.launcher.data.Cleanup.reapply(context) } }
            }
        }
    }
    private var cleanupReapplied = false

    // ---------------------------------------------------------------- burn-in protection
    var pixelShift by mutableStateOf(prefs.flagOn("pixelShift")); private set
    fun changePixelShift(on: Boolean) { prefs.setFlagOn("pixelShift", on); pixelShift = on; idle = false }
    private var lastInput = android.os.SystemClock.elapsedRealtime()
    /** No key pressed for a while on the home screen: the UI dims a little (until the next key). */
    var idle by mutableStateOf(false); private set
    fun onUserInput() { lastInput = android.os.SystemClock.elapsedRealtime(); if (idle) idle = false }
    fun checkIdle() { idle = pixelShift && android.os.SystemClock.elapsedRealtime() - lastInput > 10 * 60_000L }

    // ---------------------------------------------------------------- profiles
    var profiles by mutableStateOf(prefs.profiles); private set
    var profileId by mutableStateOf(prefs.profile); private set
    var hasPin by mutableStateOf(prefs.pinHash != null); private set
    var askWho by mutableStateOf(prefs.askWho); private set
    val profile get() = profiles.firstOrNull { it.id == profileId }
    /** Child profile: no settings, no All apps, no app menu. */
    val isChild get() = profile?.child == true
    /** The switch button only makes sense with two or more profiles. */
    val canSwitch get() = profiles.size >= 2

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    /** Before 2026.11.4: one unsalted SHA-256. Still accepted once, then replaced by [hash]. */
    private fun oldHash(pin: String) = hex(java.security.MessageDigest.getInstance("SHA-256").digest(("mytv:" + pin).toByteArray()))
    /** "v2:<salt>:<PBKDF2-HMAC-SHA256>": a random salt and many rounds, so the stored value is not a lookup away from the PIN. */
    private fun hash(pin: String, salt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }): String {
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded
        return "v2:" + hex(salt) + ":" + hex(key)
    }
    private fun pinMatches(pin: String): Boolean {
        val stored = prefs.pinHash ?: return false
        if (!stored.startsWith("v2:")) return (stored == oldHash(pin)).also { if (it) prefs.pinHash = hash(pin) }
        val salt = runCatching { stored.split(':')[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull() ?: return false
        return java.security.MessageDigest.isEqual(stored.toByteArray(), hash(pin, salt).toByteArray())
    }
    /** Wrong tries in a row, kept across restarts (switching the TV off and on must not give five new tries). */
    private var pinFails = prefs.pinFails
    private var pinBlockedUntil = if (pinFails >= 5) android.os.SystemClock.elapsedRealtime() + pinDelay(pinFails) else 0L
    private fun pinDelay(fails: Int) = 30_000L * (1L shl (fails - 5).coerceIn(0, 6))
    /** Seconds before the next try is allowed (after 5 wrong PINs: 30 s, then doubling). */
    fun pinWait() = ((pinBlockedUntil - android.os.SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0).toInt()
    fun checkPin(pin: String): Boolean {
        if (pinWait() > 0) return false
        if (pinMatches(pin)) { pinFails = 0; prefs.pinFails = 0; return true }
        prefs.pinFails = ++pinFails
        if (pinFails >= 5) pinBlockedUntil = android.os.SystemClock.elapsedRealtime() + pinDelay(pinFails)
        return false
    }
    fun setPin(pin: String?) { prefs.pinHash = pin?.let { hash(it) }; hasPin = pin != null; pinFails = 0; prefs.pinFails = 0 }
    /** Going from a child profile (or from the "who is watching" screen) to a parent profile needs the PIN. */
    fun needsPin(target: Profile) = hasPin && !target.child && (isChild || top is Overlay.Profiles && (top as Overlay.Profiles).ask)

    /**
     * The first profile (or no profiles): only there the settings for the whole TV are shown (Home button, Sources and
     * Apps buttons, screensaver, sleep timer, quiet hours, maintenance, profiles and PIN). Other profiles have only their
     * own home-screen settings.
     */
    val isMainProfile get() = profileId.isEmpty()

    fun switchProfile(id: String) {
        if (id == profileId) return
        val lang = language
        prefs.profile = id; profileId = id
        hidden = prefs.hidden
        refreshApps()
        loadBackgrounds()
        loadProfileSettings()
        // Another language for this profile: the screen is rebuilt in it (as when the language is chosen).
        if (prefs.language != lang) { language = prefs.language; onLanguageChanged?.invoke() }
    }

    /** Each profile has its own home-screen settings: read them again after a switch. */
    private fun loadProfileSettings() {
        recentCities = prefs.recentCities
        val c = prefs.cities.firstOrNull()
        if (c?.lat != city?.lat || c?.lon != city?.lon) {
            city = c
            weather = if (c != null) WeatherState(weatherRepo.cached(c), null, false) else WeatherState(null, null, false)
            refreshWeather(force = false)
        }
        autostart = prefs.autostart
        compactClock = prefs.compactClock
        transitions = prefs.transitions
        rotateSeconds = prefs.rotateSeconds
        tempUnit = prefs.tempUnit
        timeFormat = prefs.timeFormat
        bg.blur = prefs.glassBlur
        continueWatching = prefs.continueWatching
        button1 = prefs.button1
        button2 = prefs.button2
        nightMode = prefs.nightMode; updateNight()
        digitKeys = prefs.digitKeys
        pixelShift = prefs.flagOn("pixelShift")
        nowPlayingOn = prefs.nowPlaying
        if (!nowPlayingOn) nowPlaying = null
        npSonos = prefs.flagOn("npSonos"); npCast = prefs.flagOn("npCast"); npApps = prefs.flagOn("npApps"); npOther = prefs.flagOn("npOther")
        photoSource = prefs.photoSource
        photosOff = prefs.photosOff
        remoteTick++
        refreshWatchNext()
    }

    /** Parent (keeps everything as it is now) + child. */
    fun enableProfiles(parent: String, child: String) {
        if (profiles.isNotEmpty()) return
        prefs.copySettings("", "p2", alsoDock = true)
        prefs.profiles = listOf(Profile("", parent, false), Profile("p2", child, true)); profiles = prefs.profiles
        startChild("p2")
    }

    /** What a new child profile hides at first: everything except the apps in the dock it was made from. */
    fun childStartHidden(): Set<String> = apps.map { it.key }.toSet() - visibleApps.map { it.key }.toSet()

    /** A profile that just became a child profile: it starts with the dock apps; the parent can switch them off. */
    private fun startChild(id: String) {
        if (id == profileId || prefs.knownFor(id).isNotEmpty()) return
        prefs.setHiddenFor(id, childStartHidden()); prefs.setKnownFor(id, apps.map { it.key }.toSet())
    }

    /** Adds a profile; the very first one also creates the profile for the current setup ([firstName]). */
    fun addProfile(name: String, firstName: String): String {
        val base = profiles.ifEmpty { listOf(Profile("", firstName, false)) }
        val id = "p" + ((base.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 1) + 1)
        prefs.copySettings(profileId, id, alsoDock = true)
        prefs.profiles = base + Profile(id, name, false); profiles = prefs.profiles
        return id
    }

    fun renameProfile(id: String, name: String) {
        if (name.isBlank()) return
        prefs.profiles = profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }; profiles = prefs.profiles
    }

    /** Returns false when this would leave no parent profile. */
    fun setChild(id: String, child: Boolean): Boolean {
        if (child && profiles.count { !it.child && it.id != id } == 0) return false
        prefs.profiles = profiles.map { if (it.id == id) it.copy(child = child) else it }; profiles = prefs.profiles
        if (child) startChild(id)
        // Child -> parent: back to the normal default apps (the child's selection no longer applies).
        if (!child) {
            prefs.setHiddenFor(id, emptySet()); prefs.setKnownFor(id, emptySet())
            if (id == profileId) { hidden = prefs.hidden; refreshApps() }
        }
        return true
    }

    fun deleteProfile(id: String) {
        if (id.isEmpty()) return
        // Never leave only child profiles (Settings would be unreachable): the first profile becomes a parent again.
        if (profiles.none { !it.child && it.id != id }) setChild("", false)
        if (profileId == id) switchProfile("")
        prefs.forgetProfile(id)
        prefs.profiles = profiles.filter { it.id != id }; profiles = prefs.profiles
        if (profiles.size < 2) disableProfiles()
    }

    fun disableProfiles() {
        switchProfile("")
        profiles.forEach { prefs.forgetProfile(it.id) }
        prefs.profiles = emptyList(); profiles = emptyList()
        setPin(null); changeAskWho(false)
    }

    fun setProfileIcon(id: String, icon: Int) {
        prefs.profiles = profiles.map { if (it.id == id) it.copy(icon = icon) else it }; profiles = prefs.profiles
    }

    fun changeAskWho(on: Boolean) { prefs.askWho = on; askWho = on }

    fun hiddenFor(id: String) = if (id == profileId) hidden else prefs.hiddenFor(id)
    fun setHiddenFor(id: String, key: String, hide: Boolean) {
        if (id == profileId) { setHidden(key, hide); return }
        // A child profile that never ran yet: it starts with the apps of the dock.
        val base = if (prefs.knownFor(id).isEmpty() && profiles.firstOrNull { it.id == id }?.child == true) childStartHidden() else prefs.hiddenFor(id)
        prefs.setKnownFor(id, prefs.knownFor(id) + apps.map { it.key })
        prefs.setHiddenFor(id, if (hide) base + key else base - key)
        profileTick++
    }
    var profileTick by mutableIntStateOf(0); private set

    /** After "who is watching" on power-on: the start-up app follows the choice. */
    fun afterWhoIsWatching() { PowerOn.startApp(context) }

    // ---------------------------------------------------------------- welcome
    var welcomeStep by mutableIntStateOf(0)

    /** Fresh install only: no place chosen and the welcome was never shown. */
    fun shouldWelcome() = !prefs.flag("welcomeShown") && prefs.cities.isEmpty()

    fun openWelcome(step: Int = 0) {
        prefs.setFlag("welcomeShown")
        welcomeStep = step
        overlays.clear()
        open(Overlay.Welcome, null)
    }

    // ---------------------------------------------------------------- screensaver
    var dreamMine by mutableStateOf(false); private set
    var dreamTimeout by mutableIntStateOf(0); private set
    var dreamClock by mutableStateOf(prefs.dreamClock); private set

    fun refreshDream() {
        dreamMine = com.mytv.launcher.data.Screensaver.isMine(context)
        dreamTimeout = com.mytv.launcher.data.Screensaver.timeoutMinutes(context)
    }

    fun toggleDream() {
        if (!com.mytv.launcher.data.SystemAccess.canWriteSecure(context)) { openDreamSettings(); return }
        runCatching { com.mytv.launcher.data.Screensaver.setMine(context, prefs, !dreamMine) }.onFailure { Log.w("MyTV", "screensaver: $it") }
        refreshDream()
    }

    fun cycleDreamTimeout() {
        if (adbBusy != null) return
        // Not allowed yet and no network debugging: Android's own "modify system settings" switch, where the TV has it.
        if (!adbReachable && !com.mytv.launcher.data.Screensaver.canSetTimeout(context)) {
            com.mytv.launcher.data.Screensaver.permissionScreen(context)?.let { startSafe(it) }; return
        }
        val all = com.mytv.launcher.data.Screensaver.CHOICES
        val next = all[(all.indexOf(dreamTimeout).coerceAtLeast(-1) + 1) % all.size]
        runAdb("dream", { com.mytv.launcher.data.Screensaver.setTimeoutMinutes(context, next, it) }) { refreshDream() }
    }

    fun changeDreamClock(on: Boolean) { prefs.dreamClock = on; dreamClock = on }

    fun previewDream() {
        startSafe(Intent(context, DreamPreviewActivity::class.java))
    }

    fun openDreamSettings() {
        // Some TVs send Android's own link to an "ignore" screen: then the screensaver page of the Android TV settings
        // app itself, and only when that is not there its main panel (Device preferences → Screensaver).
        val pm = context.packageManager
        val i = com.mytv.launcher.data.SystemAccess.real(context, Intent(Settings.ACTION_DREAM_SETTINGS))
            ?: Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.display.daydream.DaydreamActivity")
                .takeIf { pm.resolveActivity(it, 0)?.activityInfo?.exported == true }
            ?: com.mytv.launcher.data.SystemAccess.androidTvSettings().takeIf { pm.resolveActivity(it, 0) != null }
            ?: Intent(Settings.ACTION_SETTINGS)
        if (!com.mytv.launcher.data.Net.startFirst(context, i)) openSystemSettings()
    }

    // ---------------------------------------------------------------- now playing on Sonos
    var nowPlayingOn by mutableStateOf(prefs.nowPlaying); private set
    var nowPlaying by mutableStateOf<com.mytv.launcher.data.NowPlaying?>(null); private set

    var npSonos by mutableStateOf(prefs.flagOn("npSonos")); private set
    var npCast by mutableStateOf(prefs.flagOn("npCast")); private set
    var npApps by mutableStateOf(prefs.flagOn("npApps")); private set
    var npOther by mutableStateOf(prefs.flagOn("npOther")); private set
    var npAppsAllowed by mutableStateOf(false); private set
    private val npStarted = HashMap<String, Long>()       // key|title -> first seen playing
    private val npLastPlaying = HashMap<String, Long>()   // key|title -> last seen playing
    private var npAppsBefore = emptySet<String>()         // apps on the TV playing in the previous round
    private var npHidden: String? = null                   // key|title hidden by the viewer until something else plays
    private var pollCount = 0
    private var npEmpty = 0
    /** Every 4 s; after five rounds with nothing playing every 30 s (a new look each time MyTV comes to the front). */
    fun resetNpPolling() { npEmpty = 0 }
    val npPollMs get() = if (nowPlayingOn && npEmpty < 5) 4_000L else 30_000L

    /** Polled by MainActivity while the home screen is in front; local network and this TV only. */
    suspend fun pollSonos() {
        if (demoMode) return                       // the demo keeps its own example (or none)
        if (!nowPlayingOn) { nowPlaying = null; return }
        npAppsAllowed = com.mytv.launcher.data.MediaApps.allowed(context)
        if (npSonos) com.mytv.launcher.data.Sonos.discover(context)
        if (npCast) com.mytv.launcher.data.Cast.discover(context)
        if (npOther) com.mytv.launcher.data.Speakers.discover(context)
        val castNow = pollCount++ % 2 == 0          // Cast needs a TLS connection: every other round is plenty
        val found = withContext(Dispatchers.IO) {
            buildList {
                if (npSonos) addAll(com.mytv.launcher.data.Sonos.nowPlaying())
                if (npCast) addAll(if (castNow) com.mytv.launcher.data.Cast.nowPlaying().also { lastCast = it } else lastCast)
                if (npApps) addAll(com.mytv.launcher.data.MediaApps.nowPlaying(context))
                if (npOther) addAll(com.mytv.launcher.data.Speakers.nowPlaying())
            }
        }
        // The same song through two routes (e.g. Spotify on the TV steering a speaker): keep the speaker.
        // An app on the TV counts as playing only when it still plays a round later: a video app still says
        // "playing" for a moment after Home, until it stops (that is "continue watching", not "now playing").
        // Per app, not per song: the next song of an app that was already playing shows at once.
        val appsPlaying = found.filter { it.source == "app" && it.playing }.map { it.key }.toSet()
        val unique = found.filter { it.source != "app" || !it.playing || it.key in npAppsBefore }
            .sortedBy { if (it.source == "app") 1 else 0 }.distinctBy { it.title.lowercase() }
        npAppsBefore = appsPlaying
        if (npHidden != null && found.none { it.key + "|" + it.title == npHidden }) npHidden = null
        val now = System.currentTimeMillis()
        unique.filter { it.playing }.forEach { npStarted.putIfAbsent(it.key + "|" + it.title, now) }
        npStarted.keys.retainAll(unique.filter { it.playing }.map { it.key + "|" + it.title }.toSet())
        unique.filter { it.playing }.forEach { npLastPlaying[it.key + "|" + it.title] = now }
        npLastPlaying.keys.retainAll(unique.map { it.key + "|" + it.title }.toSet())
        // Paused: a card for 10 minutes after it stopped (apps keep "paused" for hours). When it stopped is unknown,
        // e.g. paused before the TV came out of standby: no card.
        fun recent(n: com.mytv.launcher.data.NowPlaying) = (n.changedAt ?: npLastPlaying[n.key + "|" + n.title])?.let { now - it < 10 * 60_000L } == true
        // Playing before paused; among the playing ones the one that started last.
        nowPlaying = unique.filter { (it.playing || recent(it)) && it.key + "|" + it.title != npHidden }.sortedWith(compareBy<com.mytv.launcher.data.NowPlaying>({ !it.playing }, { -(npStarted[it.key + "|" + it.title] ?: 0L) })).firstOrNull()
        // Slower rounds when nothing is shown (a session that stays "paused" for hours must not keep the fast pace).
        npEmpty = if (nowPlaying == null) npEmpty + 1 else 0
    }
    private var lastCast: List<com.mytv.launcher.data.NowPlaying> = emptyList()

    fun changeNpSource(which: String, on: Boolean) {
        prefs.setFlagOn(which, on)
        when (which) { "npSonos" -> npSonos = on; "npCast" -> npCast = on; "npOther" -> npOther = on; else -> npApps = on }
        if (which == "npApps" && on && !com.mytv.launcher.data.MediaApps.allowed(context)) {
            if (!com.mytv.launcher.data.SystemAccess.canWriteSecure(context) || !com.mytv.launcher.data.MediaApps.enable(context))
                com.mytv.launcher.data.MediaApps.openSettings(context)
        }
        npAppsAllowed = com.mytv.launcher.data.MediaApps.allowed(context)
    }

    fun refreshNpAppsAllowed() { npAppsAllowed = com.mytv.launcher.data.MediaApps.allowed(context) }

    /** From the card's menu: hide this title (optionally pausing it first) until something else plays. */
    fun hideNowPlaying(stop: Boolean) {
        val np = nowPlaying ?: return
        if (stop && np.playing) sonos("Pause")
        npHidden = np.key + "|" + np.title; nowPlaying = null
        pendingFocus = "app:" + (visibleApps.firstOrNull()?.key ?: "")
    }

    fun openNowPlayingApp() {
        nowPlaying?.pkg?.let { p -> context.packageManager.getLeanbackLaunchIntentForPackage(p) ?: context.packageManager.getLaunchIntentForPackage(p) }
            ?.let { startSafe(it) }
    }

    fun changeNowPlaying(on: Boolean) { prefs.nowPlaying = on; nowPlayingOn = on; if (!on) nowPlaying = null }

    fun sonos(action: String) {
        val np = nowPlaying ?: return
        if (action == "Pause" || action == "Play") nowPlaying = np.copy(playing = action == "Play")   // immediate feedback
        scope.launch {
            withContext(Dispatchers.IO) {
                when (np.source) {
                    "cast" -> com.mytv.launcher.data.Cast.command(np.coordinatorIp, np.extra, action)
                    "app" -> np.pkg?.let { com.mytv.launcher.data.MediaApps.command(context, it, action) }
                    "speaker" -> com.mytv.launcher.data.Speakers.command(np, action)
                    else -> com.mytv.launcher.data.Sonos.command(np.coordinatorIp, action)
                }
            }
            kotlinx.coroutines.delay(700); pollSonos()
        }
    }

    // ---------------------------------------------------------------- system clean-up (via the TV's own adb)
    /** Package -> active (true) / off (false); refreshed when the Clean up or System page opens. */
    var pkgStates by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set
    /** null | "waiting" (prompt on screen) | "off" | "refused" | "noanswer" (the TV never answered) | "failed" */
    var adbStatus by mutableStateOf<String?>(null); private set
    var adbBusy by mutableStateOf<String?>(null); private set
    /** A command through the TV's own adb worked in this session (Connection to the TV says "Connected"). */
    var adbVerified by mutableStateOf(false); private set

    var otherPkgs by mutableStateOf<List<String>>(emptyList()); private set
    /** This TV's own home screen app (differs per brand / Android TV vs Google TV). */
    val stockLauncher: String by lazy { com.mytv.launcher.data.Cleanup.stock(context) }

    fun refreshPkgStates() {
        scope.launch {
            // Package queries take a while on a TV: in the background. Only what is on now, or what MyTV switched off
            // (regional apps the TV ships switched off are noise).
            val (others, states) = withContext(Dispatchers.IO) {
                val o = com.mytv.launcher.data.Cleanup.others(context)
                    .filter { com.mytv.launcher.data.Cleanup.enabled(context, it) == true || it in prefs.cleanedUp }
                o to (com.mytv.launcher.data.Cleanup.ITEMS.map { it.pkg } + o + stockLauncher)
                    .mapNotNull { p -> com.mytv.launcher.data.Cleanup.enabled(context, p)?.let { p to it } }.toMap()
            }
            otherPkgs = others; pkgStates = states
        }
    }

    /**
     * Runs one command through the TV's own adb: marks [busy], shows "waiting" while the approval prompt is up,
     * and turns failures into a status line. A cancelled coroutine (screen rebuilt) is not reported as a failure.
     */
    private fun runAdb(busy: String, work: (onApprovalNeeded: () -> Unit) -> Unit, done: (Boolean) -> Unit) {
        if (adbBusy != null) return
        adbBusy = busy; adbStatus = null
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { work { scope.launch { adbStatus = "waiting" } } }; null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e }
            adbStatus = when (r) {
                null -> null
                is com.mytv.launcher.data.LocalAdb.NotAvailable -> "off"
                is com.mytv.launcher.data.LocalAdb.NoAnswer -> "noanswer"
                is com.mytv.launcher.data.LocalAdb.NotApproved -> "refused"
                else -> "failed"
            }
            r?.let { Log.w("MyTV", "adb ($busy): $it") }
            adbVerified = r == null
            adbBusy = null
            done(r == null)
        }
    }

    /**
     * The extra right from setup (WRITE_SECURE_SETTINGS), given by MyTV itself through the TV's own adb: no computer
     * needed (tvOS-Launcher does the same). The TV asks "Allow debugging?" once. GitHub edition only.
     */
    fun grantRights() {
        runAdb("rights", { com.mytv.launcher.data.LocalAdb.shell("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS", it) }) { ok ->
            if (ok) {
                // The button disappears: focus on what takes its place (welcome: next step).
                pendingFocus = if (overlays.any { it.overlay == Overlay.Welcome }) "wel:next" else "conn:adboff"
                com.mytv.launcher.data.SystemAccess.repairHomeWatch(context)
                if (npApps && !com.mytv.launcher.data.MediaApps.allowed(context)) com.mytv.launcher.data.MediaApps.enable(context)
            }
            resumeTick++
        }
    }

    /** "Check the connection": one harmless command; the TV may ask "Allow debugging?" once. */
    fun checkConnection() {
        runAdb("check", { com.mytv.launcher.data.LocalAdb.shell("echo ok", it) }) { checkAdb() }
    }

    /** Network debugging off again once everything is set up (safer): straight when allowed, otherwise through adb. */
    fun closeNetworkDebugging() {
        runAdb("adboff", {
            val direct = runCatching { android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) }.isSuccess
            if (!direct) com.mytv.launcher.data.LocalAdb.shell("settings put global adb_enabled 0", it)
        }) { checkAdb() }
    }

    fun setPackage(pkg: String, enable: Boolean, after: () -> Unit = {}) {
        if (adbBusy != null) return
        runAdb(pkg, { com.mytv.launcher.data.Cleanup.set(context, pkg, enable, it) }) { ok ->
            if (ok) {
                val off = prefs.cleanedUp.toMutableSet(); if (enable) off -= pkg else off += pkg; prefs.cleanedUp = off
            }
            refreshPkgStates()
            if (ok) refreshApps()      // a switched-off app disappears from the dock and All apps at once
            after()
        }
    }

    /** Switches back on everything MyTV switched off (clean-up list only; the stock launcher has its own row). */
    fun restoreAll() {
        val todo = prefs.cleanedUp.filter { it != stockLauncher && pkgStates[it] == false }
        fun next(i: Int) { if (i < todo.size) setPackage(todo[i], true) { next(i + 1) } }
        next(0)
    }

    /** Whether the "MyTV Home button" accessibility service is switched on (force-stopping the app turns it off). */
    fun homeWatchEnabled(): Boolean = com.mytv.launcher.data.SystemAccess.homeWatchOn(context)

    fun openAccessibilitySettings() { com.mytv.launcher.data.SystemAccess.openHomeWatchSettings(context) }

    /** Bumped on every return to MyTV: lets screens re-read system state (e.g. the Home-button switch). */
    var resumeTick by mutableIntStateOf(0)

    /** With the extra system access the Home-button service can be switched on directly. */
    fun enableHomeWatch(): Boolean {
        // The viewer asks for it: an earlier "switched off by the owner" no longer applies.
        context.getSharedPreferences("mytv", Context.MODE_PRIVATE).edit().putBoolean("homeWatchOffByUser", false).commit()
        val done = com.mytv.launcher.data.SystemAccess.repairHomeWatch(context)
        resumeTick++
        return done
    }

    // ---------------------------------------------------------------- approximate location (weather)
    var locating by mutableStateOf(false); private set
    var locateFailed by mutableStateOf(false); private set

    private var approx: com.mytv.launcher.data.City? = null

    /** City-level place from the internet address (ipapi.co); asked once per run of MyTV. */
    private suspend fun approxPlace(): com.mytv.launcher.data.City? = approx ?: withContext(Dispatchers.IO) {
                runCatching {
                    val o = org.json.JSONObject(String(com.mytv.launcher.data.Net.get("https://ipapi.co/json/", headers = mapOf("User-Agent" to "MyTV"))))
                    com.mytv.launcher.data.City(o.getString("city"), o.getDouble("latitude"), o.getDouble("longitude"))
                }.getOrNull()
            }.also { approx = it }

    /** Welcome on a fresh install: the approximate place, chosen. */
    fun detectLocation(after: () -> Unit = {}) {
        if (locating) return
        locating = true; locateFailed = false
        scope.launch {
            val c = approxPlace()
            locating = false
            if (c == null) locateFailed = true else { chooseCity(c); after() }
        }
    }

    /** Weather settings: put the approximate place in the list if it is not there yet; the choice stays as it is. */
    fun addApproxPlace() {
        scope.launch {
            val c = approxPlace() ?: return@launch
            val near = { o: com.mytv.launcher.data.City -> o.name.equals(c.name, true) || (Math.abs(o.lat - c.lat) < 0.05 && Math.abs(o.lon - c.lon) < 0.05) }
            if (prefs.recentCities.any(near) || city?.let(near) == true) return@launch
            prefs.recentCities = (prefs.recentCities.ifEmpty { listOfNotNull(city) } + c).take(8)
            recentCities = prefs.recentCities
        }
    }

    fun openSystemSettings() {
        startSafe(Intent(Settings.ACTION_SETTINGS))
    }

    // ---------------------------------------------------------------- weather
    /** Recently used places (newest first), so switching back needs no typing. */
    var recentCities by mutableStateOf(prefs.recentCities); private set

    fun chooseCity(c: City) {
        prefs.cities = listOf(c)
        prefs.recentCities = (listOf(c) + prefs.recentCities.filterNot { it.lat == c.lat && it.lon == c.lon }).take(8)
        recentCities = prefs.recentCities
        city = c
        weather = WeatherState(weatherRepo.cached(c), null, false)
        refreshWeather(force = true)
    }

    /**
     * Screenshots for the project page (adb setup command, GitHub edition): only these apps in the dock, this city's
     * weather, and either no Now playing and Continue watching, or ([cards]) made-up examples with MyTV's own photos:
     * never the viewer's own history. Nothing is saved: a restart of MyTV brings everything back.
     */
    private var demoMode = false

    fun demo(packages: List<String>, cityName: String?, cards: Boolean = false) {
        val chosen = packages.mapNotNull { p -> apps.firstOrNull { it.component.packageName == p } }
        // An app from "Other apps" has no tall dock banner yet: without it the wide banner would be cropped.
        val keep = appRepo.dockArt(chosen)
        hidden = apps.map { it.key }.toSet() - keep.map { it.key }.toSet()
        apps = keep + apps.filter { it !in chosen }
        demoMode = true; nowPlaying = null; watchItems = emptyList()
        rotateSeconds = 0                     // one photo for the screenshots (the saved setting is left as it is)
        if (cards) {
            val now = System.currentTimeMillis()
            val pkgs = chosen.map { it.component.packageName }
            // Real open-licence films and music (Blender Studio, CC BY; credited in docs/CREDITS.md), pictures from
            // Wikimedia Commons: never the viewer's own history.
            fun commons(file: String) = "https://commons.wikimedia.org/wiki/Special:FilePath/$file?width=640"
            fun pkg(p: String) = p.takeIf { pkgs.contains(it) } ?: pkgs.firstOrNull().orEmpty()
            watchItems = listOf(
                com.mytv.launcher.data.WatchItem(pkg("com.google.android.youtube.tv"), "Big Buck Bunny", "Blender Studio", commons("Big_buck_bunny_poster_big.jpg"), null, 0.42f, now),
                com.mytv.launcher.data.WatchItem(pkg("com.plexapp.android"), "Cosmos Laundromat", null,
                    commons("Cosmos_Laundromat_-_First_Cycle_-_Official_Blender_Foundation_release.webm"), null, 0.7f, now - 1),
                com.mytv.launcher.data.WatchItem(pkg("com.plexapp.android"), "Elephants Dream", null, commons("Elephants_Dream_s5_both.jpg"), null, 0.25f, now - 2),
            )
            scope.launch {
                val art = withContext(Dispatchers.IO) { com.mytv.launcher.data.WatchNext.image(context, commons("Sintel_movie_4K.webm")) }
                nowPlaying = com.mytv.launcher.data.NowPlaying("", "", "Sintel (Main Theme)", "Jan Morgenstern", null, true, source = "app",
                    key = "demo", image = art, pkg = pkg("com.spotify.tv.android"))
            }
        }
        if (cityName != null) scope.launch {
            weatherRepo.geocode(cityName)?.let { c -> city = c; weather = WeatherState(weatherRepo.cached(c), null, false); refreshWeather(force = true) }
        }
    }

    /** Provisioning helper: `--es mtv_cities "Amsterdam"` (geocoded to city level). */
    fun provisionCities(names: List<String>) {
        val n = names.firstOrNull() ?: return
        scope.launch { weatherRepo.geocode(n)?.let { chooseCity(it) } }
    }

    fun refreshWeather(force: Boolean) {
        val c = city ?: return
        if (weather.loading) return
        val age = weather.ageMinutes()
        // Older cached data has no hourly forecast; refresh once to get it.
        val complete = weather.report?.let { r -> r.hours.isNotEmpty() && r.days.firstOrNull()?.moonPhase != null } == true
        if (!force && age != null && age < 25 && complete && weather.report?.fahrenheit == wantFahrenheit()) return
        weather = weather.copy(loading = true)
        scope.launch {
            val r = runCatching { weatherRepo.fetch(c, wantFahrenheit()) }
            if (city != c) return@launch
            updateMoon()
            weather = r.fold(
                { WeatherState(it, null, false) },
                { WeatherState(weather.report ?: weatherRepo.cached(c), "offline", false) },
            )
        }
    }

    /** Debounced free-text place search for the weather location. */
    fun search(text: String) {
        searchText = text
        searchJob?.cancel()
        if (text.trim().length < 2) { searchResults = emptyList(); searching = false; searchError = false; return }
        searchJob = scope.launch {
            kotlinx.coroutines.delay(350)
            searching = true
            val r = runCatching { weatherRepo.search(text) }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()   // overtaken by newer typing: say nothing
            searching = false
            searchResults = r.getOrDefault(emptyList())
            searchError = r.isFailure
        }
    }

    // ---------------------------------------------------------------- start-up app
    fun setAutostartApp(key: String?) { prefs.autostart = key; autostart = key }

    fun setAutostartPackage(pkg: String?) {
        if (pkg == null) { setAutostartApp(null); return }
        scope.launch {
            val list = if (apps.isEmpty()) withContext(Dispatchers.IO) { appRepo.loadAll() } else apps
            list.firstOrNull { it.component.packageName == pkg }?.let { setAutostartApp(it.key) }
        }
    }


    // ---------------------------------------------------------------- backgrounds
    /**
     * Another app is in front and Android asks for memory back (tv-vibehack's MemoryTrimPolicy): thumbnails and
     * posters always; under real pressure also the photo, which is loaded again when MyTV comes back.
     */
    fun trimMemory(deep: Boolean) {
        com.mytv.launcher.data.BackgroundRepository.trim()
        com.mytv.launcher.data.WatchNext.trim()
        if (deep && bg.current != null) { bgJob?.cancel(); bg.current = null; bg.previous = null; Log.i("MyTV", "memory: photo released") }
    }

    /** After [trimMemory]: the photo back. */
    fun restoreAfterTrim() {
        if (bg.current != null) return
        val id = prefs.backgroundId?.takeIf { i -> backgrounds.any { it.id == i } } ?: backgrounds.firstOrNull()?.id ?: return
        setBackground(id, animate = false, remember = false)
    }

    fun loadBackgrounds() {
        scope.launch {
            // Reading the photo list touches storage: in the background.
            backgrounds = withContext(Dispatchers.IO) { bgRepo.list() }
            // The chosen photo may be one of the online set that is not downloaded (yet): show another meanwhile,
            // without forgetting the choice.
            val chosen = prefs.backgroundId?.takeIf { i -> backgrounds.any { it.id == i } }
            val id = chosen ?: backgrounds.firstOrNull()?.id ?: return@launch
            setBackground(id, animate = false, remember = chosen != null || prefs.backgroundId == null)
            syncOnlinePhotos()
        }
    }

    /**
     * MyTV's online photo set: fetched at most once a day (at start and when MyTV comes back to the front);
     * new photos join the list as soon as they are downloaded and checked.
     */
    fun syncOnlinePhotos() {
        scope.launch {
            val changed = withContext(Dispatchers.IO) { com.mytv.launcher.data.OnlinePhotos.sync(context) }
            if (!changed) return@launch
            backgrounds = withContext(Dispatchers.IO) { bgRepo.list() }
            prefs.backgroundId?.takeIf { i -> i != bg.current?.info?.id && backgrounds.any { it.id == i } }?.let { setBackground(it) }
        }
    }

    /** Whether the TV reports inputs (for the Sources button); looked up once, in the background. */
    var hasInputs by mutableStateOf(false); private set
    fun checkInputs() { scope.launch { hasInputs = withContext(Dispatchers.IO) { com.mytv.launcher.data.Sources.list(context).isNotEmpty() } } }

    fun setBackground(id: String, animate: Boolean = transitions, remember: Boolean = true) {
        val info = backgrounds.firstOrNull { it.id == id } ?: return
        if (bg.current?.info?.id == id) return
        bgJob?.cancel()
        bgJob = scope.launch {
            Log.i("MyTV", "background load start: $id")
            val img = runCatching { bgRepo.load(info) }.onFailure { Log.e("MyTV", "background load failed: $id", it) }.getOrNull() ?: return@launch
            Log.i("MyTV", "background loaded: $id ${img.full.width}x${img.full.height}")   // load start → loaded = decode time
            if (remember) prefs.backgroundId = id
            if (animate && bg.current != null) {
                bg.previous = bg.current
                bg.fade = 0f
                bg.current = img
                val start = System.nanoTime()
                while (bg.fade < 1f) {
                    kotlinx.coroutines.delay(16)
                    bg.fade = ((System.nanoTime() - start) / 1.2e9f).coerceAtMost(1f)
                }
                bg.previous = null
            } else {
                bg.current = img; bg.previous = null; bg.fade = 1f
            }
        }
    }

    private val bag = ArrayDeque<String>()

    /** Random order without repeats: every photo once (shuffled) before the next round; never the same twice in a row. */
    fun nextBackground() {
        val pool = bgRepo.pool(backgrounds, photoSource, photosOff).map { it.id }
        if (pool.isEmpty()) return
        bag.retainAll(pool.toSet())
        if (bag.isEmpty()) bag.addAll(pool.shuffled().let { l -> if (l.size > 1 && l.first() == bg.current?.info?.id) l.drop(1) + l.first() else l })
        setBackground(bag.removeFirst())
    }

    // ---------------------------------------------------------------- own photos
    var photoSource by mutableStateOf(prefs.photoSource); private set
    var photosReceived by mutableIntStateOf(0); private set

    fun changePhotoSource(s: String) { prefs.photoSource = s; photoSource = s }

    /** MyTV photos left out of the rotation (they cannot be removed: they come with MyTV or its online set). */
    var photosOff by mutableStateOf(prefs.photosOff); private set
    fun togglePhotoOff(id: String) { photosOff = if (id in photosOff) photosOff - id else photosOff + id; prefs.photosOff = photosOff }

    /** Called from the upload server's thread. */
    /** A backup was put back: MyTV builds itself again from the restored settings (like a language change). */
    fun settingsRestored() { scope.launch { kotlinx.coroutines.delay(1500); onLanguageChanged?.invoke() } }

    /**
     * This TV follows another (Other TVs): its changes are taken over when MyTV comes to the front, only while nothing
     * is open on screen, and MyTV builds itself again right after. Quiet when the other TV cannot be reached.
     */
    fun checkFollow() {
        if (overlays.isNotEmpty()) return
        scope.launch {
            val taken = withContext(Dispatchers.IO) { com.mytv.launcher.data.TvLink.followCheck(context) }
            if (taken) onLanguageChanged?.invoke()
        }
    }

    /** After the restart: ask for the permissions the backup had, when some are missing here. */
    fun askRestoredRights() {
        val r = com.mytv.launcher.data.Backup.takeRightsToAsk(context) ?: return
        if (com.mytv.launcher.ui.missingRights(this, r).isNotEmpty()) { closeAll(null); open(Overlay.RestoreRights(r.toString()), null) }
    }

    fun photoReceived() { scope.launch { photosReceived++; backgrounds = withContext(Dispatchers.IO) { bgRepo.list() }; if (top == Overlay.OwnPhotos) pendingFocus = "own:done" } }

    /**
     * "Continue watching" is on by default: ask once for the TV-listings permission it needs. Never over the welcome
     * screen: that asks on its Continue watching step, where it is clear what the permission is for.
     */
    fun maybeAskTvListings() {
        if (top == Overlay.Welcome) return
        if (continueWatching && !com.mytv.launcher.data.WatchNext.allowed(context) && !prefs.flag("askedTvListings")) {
            prefs.setFlag("askedTvListings"); requestTvListings?.invoke()
        }
    }

    /** Asked for by the viewer (the row in Continue watching): Android's question, or its app page when it no longer asks. */
    fun askTvListings() {
        if (!prefs.flag("askedTvListings") || tvListingsAskable?.invoke() == true) { prefs.setFlag("askedTvListings"); requestTvListings?.invoke() }
        else startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
    }

    fun resetPhotosReceived() { photosReceived = 0 }

    fun deleteOwnPhoto(info: BgInfo) {
        if (!info.own) return
        info.file?.delete()
        backgrounds = bgRepo.list()
        if (bg.current?.info?.id == info.id) backgrounds.firstOrNull()?.let { setBackground(it.id) }
    }

    fun setBlur(on: Boolean) { prefs.glassBlur = on; bg.blur = on }
    fun setCompact(on: Boolean) { prefs.compactClock = on; compactClock = on }
    fun changeTransitions(on: Boolean) { prefs.transitions = on; transitions = on }
    fun setRotate(sec: Int) { prefs.rotateSeconds = sec; rotateSeconds = sec }
}
