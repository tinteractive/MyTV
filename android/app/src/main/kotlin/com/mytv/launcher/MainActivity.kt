// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mytv.launcher.ui.HomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        // Android 11 has no per-app language: apply MyTV's language (or the TV's, or English) here.
        super.attachBaseContext(com.mytv.launcher.ui.withMyTvLanguage(newBase))
    }

    private lateinit var state: HomeState
    private var resumedAt = 0L
    private var pausedAt = 0L
    /**
     * A TV that follows another (Other TVs) takes over its changes only at a start (MyTV started, or the TV switched on)
     * or when the remote was not touched for 2 minutes: never while someone is using MyTV.
     */
    private var followAtStart = true
    private var lastKeyAt = 0L
    /** Switched off (standby or screen off): when it comes back, the remote starts on the first app in the dock. */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { state.freshStart = true; followAtStart = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        CrashGuard.starting(this)
        state = HomeState(applicationContext, lifecycleScope)
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff,
            android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        state.onLanguageChanged = { recreate() }
        state.requestTvListings = { requestPermissions(arrayOf(com.mytv.launcher.data.WatchNext.PERMISSION), 41) }
        state.tvListingsAskable = { shouldShowRequestPermissionRationale(com.mytv.launcher.data.WatchNext.PERMISSION) }
        @Suppress("DEPRECATION")
        state.requestDefaultHome = { i -> runCatching { startActivityForResult(i, 43) }.onFailure { Log.w("MyTV", "default home: $it") } }
        if (HomeState.reopenWelcome >= 0) {
            state.openWelcome(HomeState.reopenWelcome)
            HomeState.reopenWelcome = -1
        } else if (state.shouldWelcome()) state.openWelcome()
        if (HomeState.reopenDisplay) {
            HomeState.reopenDisplay = false
            state.open(Overlay.Settings, "btn:settings")
            state.settingsSection = "language"
            state.pendingFocus = "disp:lang"
        }
        com.mytv.launcher.data.Updates.init(this)
        com.mytv.launcher.data.LocalAdb.init(this)
        // The watch history normally starts when Android connects MyTV's notification access; after an update that can
        // come late or not at all. With the access on, MyTV can follow the media sessions anyway (starting twice is a no-op).
        if (com.mytv.launcher.data.MediaApps.allowed(this)) com.mytv.launcher.data.History.start(this)
        state.loadBackgrounds()
        state.checkInputs()
        state.refreshApps()      // onResume asks again; refreshApps skips while one load runs
        setContent { HomeScreen(state) }
        state.askRestoredRights()      // only right after a settings restore
        handlePowerOn()                // "who is watching?" also when the power-on start created this screen
        Log.i("MyTV", "onCreate ${SystemClock.uptimeMillis() - t0} ms")
        // Not needed for the first frame: weather icons for the forecast page, a moment after MyTV is on screen.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) { delay(3000); com.mytv.launcher.ui.WxCache.preload(applicationContext) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    state.refreshWeather(force = false)
                    state.checkBatteries()
                    state.refreshSleepLabel()
                    // Asked every minute; it only fetches when the forecast is older than 25 minutes or the last
                    // try failed (e.g. right after a cold start, before the network is up).
                    delay(60_000L)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // "Now playing": every few seconds while the home screen is in front; first look once MyTV is drawn.
                delay(1200)
                state.resetNpPolling()
                while (true) {
                    state.pollSonos()
                    delay(state.npPollMs)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // A TV that follows another (Other TVs): every 5 minutes while the home screen stays in front, when the
                // remote was left alone for 2 minutes. Nothing happens when this TV follows none.
                while (true) {
                    delay(5 * 60_000L)
                    if (SystemClock.elapsedRealtime() - lastKeyAt >= 2 * 60_000L) state.checkFollow()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Restarts whenever the interval changes, so 30 min -> 10 s takes effect immediately.
                snapshotFlow { state.rotateSeconds }.collectLatest { sec ->
                    if (sec <= 0) return@collectLatest
                    while (true) {
                        delay(sec * 1000L)
                        if (state.overlays.isEmpty()) state.nextBackground()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        HomeWatch.stopWatchingHome()
        window.decorView.postDelayed({ CrashGuard.settled(this) }, 3_000)   // drawn and alive: this start counts as good
        resumedAt = SystemClock.uptimeMillis()
        lastKeyAt = SystemClock.elapsedRealtime()     // coming back to MyTV is someone using the TV too
        // Android resets the app's default language when any of its screens starts: dates and weekdays would
        // fall back to the TV's language. Apply MyTV's choice again (cheap: no new context).
        com.mytv.launcher.ui.applyMyTvLocale(this)
        state.refreshApps(onlyIfChanged = true)   // installs/uninstalls while we were away (one check, in the background)
        state.restoreAfterTrim()
        state.onBackInFront()
        openSourcesIfAsked()
        state.resumeTick++
        state.refreshSleepLabel()      // the timer may have run out (standby) or been extended from its warning meanwhile
        // Everything else waits until MyTV is on screen, so Home and start-up stay instant.
        lifecycleScope.launch {
            delay(800)
            com.mytv.launcher.data.SystemAccess.repairHomeWatch(this@MainActivity)
            HomeWatchGuard.schedule(this@MainActivity)
            com.mytv.launcher.data.TvLink.start(this@MainActivity)   // linked TVs can take over this TV's settings (new address: new listener)
            // Only at a start (MyTV started, TV switched on); a Home press is someone using the TV.
            if (followAtStart) { followAtStart = false; state.checkFollow() }
            state.checkAdb()
            state.refreshWatchNext()
            // Android's own question only while MyTV is still in front: it would otherwise pull MyTV over the app just opened.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) state.maybeAskTvListings()
            com.mytv.launcher.data.Updates.onResume(this@MainActivity)
            state.syncOnlinePhotos()
        }
    }


    /**
     * Out of sight for a while (watching TV or another app): let go of the photo and the picture caches, so other apps
     * have the memory. Android rarely asks for that itself on TVs. Coming back loads the photo again (onResume).
     */
    private val releaseWhileHidden = Runnable {
        if (!inFront) { state.trimMemory(deep = true); Log.i("MyTV", "memory: released while out of sight") }
    }

    override fun onStart() {
        super.onStart()
        window.decorView.removeCallbacks(releaseWhileHidden)
        // Pressing Home stops a video a moment after MyTV is back: show it as soon as the history has it.
        com.mytv.launcher.data.History.onChange = { runOnUiThread { state.refreshWatchNext() } }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(releaseWhileHidden)
        runCatching { unregisterReceiver(screenOff) }
        CrashGuard.settled(this)      // closed normally (language change, Android tidying up): not a failed start
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (inFront) return
        @Suppress("DEPRECATION") val critical = level == TRIM_MEMORY_RUNNING_CRITICAL
        if (level >= TRIM_MEMORY_UI_HIDDEN || critical) state.trimMemory(deep = level >= TRIM_MEMORY_MODERATE || critical)
    }

    override fun onPause() {
        super.onPause()
        inFront = false
        pausedAt = SystemClock.uptimeMillis()
        state.homeFocus?.let { state.prefs.homeFocus = it }   // where to come back to, also if Android ends MyTV meanwhile
    }

    override fun onStop() {
        super.onStop()
        com.mytv.launcher.data.History.onChange = null
        com.mytv.launcher.data.Lan.stopAll(this)      // no network discovery while other apps play
        window.decorView.postDelayed(releaseWhileHidden, 30_000)
    }

    @android.annotation.SuppressLint("RestrictedApi")   // the platform's own Activity method; androidx marks its override as internal
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        state.onUserInput()
        lastKeyAt = SystemClock.elapsedRealtime()
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) PowerOn.cancel()   // the viewer is busy here: no start-up app on top of it
        // Coloured and number buttons with a function of their own (Settings → System → Remote control): act on the
        // press, and swallow its release.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && state.onRemoteKey(event.keyCode)) {
            usedKey = event.keyCode; return true
        }
        if (event.action == android.view.KeyEvent.ACTION_UP && event.keyCode == usedKey) { usedKey = -1; return true }
        return super.dispatchKeyEvent(event)
    }
    private var usedKey = -1

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleProvisioning()) return
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            // Home while MyTV was in front (on this TV the stock home flashes in between, so MyTV paused just before):
            // close menus and go to the start of the dock. Back from another app: close menus, stay where you were.
            val homeInMyTv = inFront || SystemClock.uptimeMillis() - pausedAt < 2_000
            state.closeAll(if (homeInMyTv) state.firstDockFocus() else state.homeFocus ?: state.dockFocus())
        }
        openSourcesIfAsked()
    }

    /** The remote's Sources or Apps button (seen by the Home-button helper): MyTV's own Sources or All apps. */
    private fun openSourcesIfAsked() {
        val what = openOnArrival ?: return
        openOnArrival = null
        if (state.whoLocked) return
        state.closeAll(null)
        state.open(if (what == "allapps") Overlay.AllApps else Overlay.Sources, state.homeFocus ?: state.dockFocus())
    }

    /** Setup commands from a computer (GitHub edition only, see [Provision]); otherwise the power-on question. */
    private fun handleProvisioning(): Boolean = Provision.handle(applicationContext, lifecycleScope, state) ?: handlePowerOn()

    /** "Who is watching?" after power-on (from MyTV's own Home-button service). */
    private fun handlePowerOn(): Boolean {
        if (!PowerOn.askWho) return false
        PowerOn.askWho = false
        if (!state.canSwitch) return false
        if (!state.whoLocked) { state.closeAll(null); state.open(Overlay.Profiles(ask = true), null) }
        return true
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) state.refreshWatchNext()
    }

    companion object {
        /** Whether MyTV's home screen is the resumed activity (read by the Home-button helper). */
        @Volatile var inFront = false
        /** Set by the Home-button helper for the remote's Sources ("sources") or Apps ("allapps") button. */
        @Volatile var openOnArrival: String? = null
    }
}
