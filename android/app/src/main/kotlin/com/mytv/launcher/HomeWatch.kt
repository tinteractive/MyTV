// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import com.mytv.launcher.data.Cleanup

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional "Home button" helper. On this TV the system starts the stock launcher by explicit component
 * when Home is pressed, so the default-home role is ignored. This service is configured (res/xml/home_watch.xml)
 * to receive only window-state events, and only from the TV's own home screen (whatever brand: [Cleanup.stocks] asks
 * Android at connect time; the XML names Android TV's as a start); when that home screen comes to the front it opens
 * MyTV instead. Disable it in Settings > Accessibility to get stock behaviour back.
 *
 * Because the service lives as long as the TV is on, it also marks "power-on" moments (first connect after boot,
 * screen on after standby) so MyTV can open the configured start-up app exactly once.
 */
class HomeWatch : AccessibilityService() {
    private var last = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** A Home press inside the debounce time: look once more when it ends (MyTV may not have come to the front). */
    private val recheck = Runnable { if (!MainActivity.inFront && SystemClock.elapsedRealtime() >= bypassUntil) bringBack("re-check") }
    private var stocks: Set<String> = setOf(STOCK)
    /**
     * Screens of the TV's own home app that are not its home screen (on Android TV: apps overview, inputs and
     * notifications panels, home-screen settings, account choice). Opened on purpose, so MyTV leaves them alone.
     */
    private var otherScreens: Set<String> = emptySet()

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) {
                PowerOn.mark(c, "screen on")
                main.postDelayed({ com.mytv.launcher.data.Screensaver.keepChoice(this@HomeWatch) }, 30_000)
            }
            // Switched off by hand: a running sleep timer ends here (it must not fire in the next session).
            if (i.action == Intent.ACTION_SCREEN_OFF) { SleepTimer.setMs(c, 0); PowerOn.cancel(); stopWatchingHome(); SourcesOverlay.hide() }
        }
    }

    private val keepDream = Runnable { com.mytv.launcher.data.Screensaver.keepChoice(this) }
    /** A change of the screensaver's start time: checked a few seconds later, once the TV is done writing. */
    private val dreamTime = object : android.database.ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(keepDream); main.postDelayed(keepDream, 5_000) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        com.mytv.launcher.data.LocalAdb.init(this)
        CrashGuard.install(this)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        getSharedPreferences("mytv", MODE_PRIVATE).edit().putBoolean("homeWatchOffByUser", false).apply()
        SleepTimer.service = this
        QuietHours.start(this)
        // The TV may put the screensaver's start time back to "never" by itself: MyTV puts the viewer's choice back.
        runCatching { contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, dreamTime) }
        main.postDelayed({ com.mytv.launcher.data.Screensaver.keepChoice(this) }, 30_000)
        // Listen to this TV's own home screen, whatever brand (res/xml lists only Android TV's as a start).
        stocks = Cleanup.stocks(this).toSet()
        runCatching { serviceInfo = serviceInfo.apply { packageNames = stocks.toTypedArray() } }
        // Keys: only the Sources and Apps buttons (and Home after the TV's own home was opened from MyTV, and one key
        // while MyTV is learning a button); every other key is passed on unchanged and not looked at.
        setKeyFilter(true)
        otherScreens = runCatching { otherScreensOf(stocks) }.onFailure { Log.w("MyTV", "home watch: screens: $it") }.getOrDefault(emptySet())
        Log.i("MyTV", "home watch: ${otherScreens.size} other screens of the TV's own home are left alone")
        // First connection in this boot (service is bound shortly after boot): treat as power-on, once per boot.
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val sp = getSharedPreferences("mytv", MODE_PRIVATE)
        if (boot != -1 && sp.getInt("autostartBoot", -2) != boot && SystemClock.elapsedRealtime() < 10 * 60_000) {
            sp.edit().putInt("autostartBoot", boot).apply()
            PowerOn.mark(this, "boot")
            // The TV may have switched apps back on that Clean up switched off: again off, once the TV has settled.
            val app = applicationContext
            Thread({ runCatching { Thread.sleep(90_000); Cleanup.reapply(app) } }, "mytv-cleanup").start()
            // A home screen that was already showing before this service ran sends no event: an invisible activity
            // that opens and closes at once makes whatever is in front report itself (tv-vibehack's boot probe).
            runCatching { startActivity(Intent(this, Probe::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        // Just updated (by adb or by MyTV's own updater)? Android stopped MyTV, so the stock home is showing and
        // this service reconnects too late to have seen it appear: bring MyTV back once. Only within two minutes of
        // the install, so MyTV never jumps over a film after an ordinary reconnect.
        val updated = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (System.currentTimeMillis() - updated in 0..120_000 && sp.getLong("reopenedAfter", 0L) != updated) {
            sp.edit().putLong("reopenedAfter", updated).apply()
            Log.i("MyTV", "reconnected after update -> opening MyTV")
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** Unbound while MyTV keeps running = switched off in the TV settings (a crash or update kills the process instead). */
    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        getSharedPreferences("mytv", MODE_PRIVATE).edit().putBoolean("homeWatchOffByUser", true).apply()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        if (instance === this) instance = null
        main.removeCallbacks(recheck)
        runCatching { unregisterReceiver(screenOn) }
        runCatching { contentResolver.unregisterContentObserver(dreamTime) }
        main.removeCallbacks(keepDream)
        SleepTimer.hideWarning(); SleepTimer.service = null
        PairPad.hide()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in stocks) return
        // Only the home screen itself; anything not known as another screen (a panel, a view) still counts as home.
        if (event.className?.toString() in otherScreens) return
        val now = SystemClock.elapsedRealtime()
        if (now < bypassUntil) return            // user deliberately opened the stock home from MyTV
        if (now - last < 800) {                  // debounce bursts of window events, but check again when it ends
            main.removeCallbacks(recheck); main.postDelayed(recheck, 800 - (now - last)); return
        }
        last = now
        bringBack("stock launcher in front (${event.className})")
    }

    private fun otherScreensOf(pkgs: Set<String>): Set<String> {
        val pm = packageManager
        val all = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), all)
            .map { it.activityInfo.name }.toSet()
        // No home screen found in these packages: then nothing is skipped (better too often MyTV than never).
        if (homes.none { h -> pkgs.any { p -> h.startsWith(p) } }) return emptySet()
        return pkgs.flatMap { p ->
            runCatching { pm.getPackageInfo(p, android.content.pm.PackageManager.GET_ACTIVITIES or all).activities?.map { it.name }.orEmpty() }
                .getOrDefault(emptyList())
        }.filter { it !in homes }.toSet()
    }

    private fun bringBack(why: String) {
        // MyTV keeps crashing: leave the TV's own home alone for a while instead of looping.
        if (CrashGuard.standingAside(this)) { Log.w("MyTV", "$why: crash guard, not opening MyTV"); return }
        Log.i("MyTV", "$why -> opening MyTV")
        // Android 8-10 hold back starting an activity for about five seconds after Home; moving MyTV's own task to
        // the front is not held back (seen on Xiaomi, Fire TV and Hisense). Start it when that did not work.
        if (android.os.Build.VERSION.SDK_INT <= 29 && moveToFront()) {
            main.postDelayed({ if (!MainActivity.inFront) startMyTv() }, 800)
            return
        }
        startMyTv()
    }

    private fun moveToFront(): Boolean = runCatching {
        val task = getSystemService(android.app.ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo?.baseActivity?.className == MainActivity::class.java.name } ?: return false
        task.moveToFront(); true
    }.getOrDefault(false)

    private fun startMyTv() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun onInterrupt() {}

    /**
     * Three keys only (plus one key while MyTV learns a button); every other key is passed on unchanged and not looked at.
     * - Sources (input) button (Android's standard key, or one the viewer taught MyTV): opens MyTV's own Sources (the TV's own source menus open through its home screen, which
     *   the Home-button helper would cover, or close by themselves). The key does not reach the TV then.
     * - Apps button (Android's standard key, or one the viewer taught MyTV): opens MyTV's All apps.
     * - Home, only while the viewer opened the TV's own home screen from MyTV: a Home press there sends no window
     *   event, so the key itself brings MyTV back.
     */
    // KEYCODE_ALL_APPS is a constant compiled in; on Android 8 (API 26/27) that key simply never arrives.
    @android.annotation.SuppressLint("InlinedApi")
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The pairing-code box (Wireless debugging) is on screen: its keys go to it.
        if (PairPad.onKey(event)) return true
        val code = event.keyCode
        // MyTV's settings are learning the Sources button: the next key that is not for moving around is the one.
        learnKey?.let { report ->
            if (code !in NOT_LEARNED) {
                if (event.action == KeyEvent.ACTION_UP) { learnKey = null; main.post { report(code) } }
                return true
            }
        }
        val sp = getSharedPreferences("mytv", MODE_PRIVATE)
        // Apps button: MyTV's All apps (in MyTV; from another app MyTV comes to the front with it).
        val apps = code == KeyEvent.KEYCODE_ALL_APPS || code == sp.getInt("appsKey", -1)
        if (apps && sp.getBoolean("appsButton", true) && !CrashGuard.standingAside(this)) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                Log.i("MyTV", "Apps button -> MyTV's All apps")
                SourcesOverlay.hide()
                MainActivity.openOnArrival = "allapps"
                bypassUntil = 0L
                main.post { startMyTv() }
            }
            return true
        }
        val sources = code == KeyEvent.KEYCODE_TV_INPUT || code == sp.getInt("sourcesKey", -1)
        if (sources && sp.getBoolean("sourcesButton", true) && !CrashGuard.standingAside(this)) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                // MyTV in front: its Sources sheet. Another app playing: only the Sources, over that app.
                if (MainActivity.inFront) {
                    Log.i("MyTV", "Sources button -> MyTV's Sources")
                    MainActivity.openOnArrival = "sources"
                    main.post { startMyTv() }
                } else {
                    Log.i("MyTV", "Sources button -> Sources over the app in front")
                    // Drawn by this helper itself, so the app underneath keeps playing; a screen of its own otherwise.
                    main.post {
                        if (SourcesOverlay.showing) SourcesOverlay.hide()
                        else if (!SourcesOverlay.show(this)) runCatching { startActivity(Intent(this, SourcesPopup::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                bypassUntil = 0L
            }
            return true
        }
        if (!watchingHome) return false
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_UP) {
            watchingHome = false
            bypassUntil = 0L
            main.post { bringBack("Home on the TV's own home screen") }
        }
        return false
    }

    private fun setKeyWatch(on: Boolean) { watchingHome = on }

    private fun setKeyFilter(on: Boolean) {
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = if (on) flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                else flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            }
        }.onFailure { Log.w("MyTV", "home key watch: $it") }
    }

    companion object {
        const val STOCK = "com.google.android.tvlauncher"
        /** Set by MyTV right before it opens the stock home on purpose. */
        @Volatile var bypassUntil = 0L
        /** Whether Android has this service bound right now (in MyTV's own process). */
        @Volatile var connected = false
        @Volatile private var instance: HomeWatch? = null
        /** The bound helper, for windows it draws over other apps (the pairing-code box). */
        val service: android.accessibilityservice.AccessibilityService? get() = instance
        @Volatile private var watchingHome = false
        /** Set by MyTV's settings while learning the Sources button: gets the code of the next key pressed. */
        @Volatile var learnKey: ((Int) -> Unit)? = null
        /** Keys never taken as the Sources button: moving around, OK, Back, Home, volume and power. */
        private val NOT_LEARNED = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        )

        /** The viewer opens the TV's own home screen from MyTV: watch for the next Home press (and nothing else). */
        fun watchHomeKey() { instance?.let { s -> s.main.post { s.setKeyWatch(true) } } }

        /** MyTV is back in front, or the screen went off: stop looking at keys. */
        fun stopWatchingHome() { if (watchingHome) instance?.let { s -> s.main.post { s.setKeyWatch(false) } } }
    }
}

/** Opens and closes at once (invisible): see HomeWatch's boot probe. */
class Probe : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) { super.onCreate(savedInstanceState); finish() }
}

/** After a boot or an update: make sure the Home-button helper is on (when MyTV may switch it on itself). */
class Restore : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            com.mytv.launcher.data.SystemAccess.repairHomeWatch(c)
            HomeWatchGuard.schedule(c)
        }
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) com.mytv.launcher.data.Updates.afterUpdate(c)
    }
}

/**
 * Start-up app: opened directly by the (always running) Home watcher shortly after the TV is switched on.
 * It does not wait for MyTV to be in front (after boot Philips may show live TV instead) and it is never
 * triggered by the Home button. After a boot it re-asserts once, in case the TV puts its own source on top.
 */
object PowerOn {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun mark(c: Context, why: String) {
        val app = c.applicationContext
        val sp = app.getSharedPreferences("mytv", Context.MODE_PRIVATE)
        val key = sp.getString(com.mytv.launcher.data.Prefs(app).p("autostart"), null)
        // Profiles with "ask who is watching": MyTV asks first; the start-up app follows the choice.
        val profiles = runCatching { org.json.JSONArray(sp.getString("profiles", "[]")).length() }.getOrDefault(0)
        // After a boot the TV may put its own source on top a few seconds later: act twice. A streaming app waits for
        // the internet (up to a limit), so it does not open on an error screen (tv-vibehack does the same).
        fun schedule(needsNet: Boolean = false, action: () -> Unit) {
            main.removeCallbacksAndMessages(null)
            val boot = why == "boot"
            val deadline = SystemClock.elapsedRealtime() + if (boot) 25_000L else 12_000L
            val attempt = object : Runnable {
                override fun run() {
                    if (needsNet && !online(app) && SystemClock.elapsedRealtime() < deadline) { main.postDelayed(this, 500L); return }
                    action()
                    if (boot) main.postDelayed(action, 8_000L)
                }
            }
            main.postDelayed(attempt, if (boot) 6_000L else 1_500L)
        }
        if (sp.getBoolean("askWho", false) && profiles >= 2) { schedule { openMyTv(app, who = true) }; return }
        Log.i("MyTV", "power-on ($why): start-up app " + if (key == null) "none" else "set")
        if (key == null) {
            // The Philips only goes "home" through the Google TV launcher. When that is switched off, MyTV brings
            // itself to the front after power-on, so the TV still starts on MyTV (not on live TV or the last app).
            if (Cleanup.enabled(app, Cleanup.stock(app)) == false) schedule { openMyTv(app) }
            return
        }
        schedule(needsNet = true) { launch(app, key, why) }
    }

    /** Internet that Android has checked actually works (not just Wi-Fi joined). */
    private fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

    /** Set before MyTV is opened for "who is watching?"; read once by MainActivity (in-process, not an intent extra). */
    @Volatile var askWho = false

    private fun openMyTv(c: Context, who: Boolean = false) {
        if (who) askWho = true
        Log.i("MyTV", "power-on: opening MyTV" + if (who) " (who is watching)" else " (stock launcher is off)")
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /** The configured start-up app, now (after the profile was chosen). */
    fun startApp(c: Context) {
        val key = c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getString(com.mytv.launcher.data.Prefs(c).p("autostart"), null) ?: return
        launch(c.applicationContext, key, "after profile")
    }

    /** Cancel pending start-up launches (e.g. the user pressed Home in the meantime). */
    fun cancel() = main.removeCallbacksAndMessages(null)

    private fun launch(c: Context, key: String, why: String) {
        val cn = android.content.ComponentName.unflattenFromString(key) ?: return
        // Only something a launcher could open (the stored value may come from a restored settings file).
        if (!com.mytv.launcher.data.AppRepository.isLaunchable(c, key)) { Log.w("MyTV", "start-up app: not a launchable app, skipped"); return }
        Log.i("MyTV", "start-up app ($why)")
        runCatching {
            c.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.onFailure { Log.w("MyTV", "start-up app failed: $it") }
    }
}
