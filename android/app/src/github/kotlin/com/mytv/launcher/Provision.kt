// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point for setup commands from a computer (GitHub edition only):
 *   adb shell am start -n com.tinteractive.mytv/com.mytv.launcher.Provision --es mtv_bg kust
 * Protected by the DUMP permission (adb's shell has it, normal apps cannot get it), so no app on the TV can use it.
 * Hands the extras to MainActivity inside the process and closes; it never shows anything itself.
 * MyTV's own extras on the exported MainActivity are ignored, so no other app on the TV can use them.
 */
class Provision : Activity() {
    // Every activity start resets the app's default language; keep MyTV's choice.
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(com.mytv.launcher.ui.withMyTvLanguage(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = Intent(intent)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    companion object {
        @Volatile private var pending: Intent? = null

        /** Applies a pending setup command; null when there was none. */
        fun handle(c: Context, scope: CoroutineScope, state: HomeState): Boolean? {
            val i = pending ?: return null
            pending = null
            var handled = false
            fun on(key: String, act: () -> Unit) { if (i.hasExtra(key)) { act(); handled = true } }
            on("mtv_cities") { state.provisionCities(i.getStringExtra("mtv_cities").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }) }
            // Development check: add (true) or remove a test item in "Continue watching".
            on("mtv_test_watchnext") {
                scope.launch {
                    withContext(Dispatchers.IO) { com.mytv.launcher.data.WatchNext.testItem(c, i.getBooleanExtra("mtv_test_watchnext", false)) }
                    state.refreshWatchNext()
                }
            }
            // Development check: a speaker at a fixed address, "kind|ip|port|name" (e.g. a simulator via adb reverse).
            on("mtv_fake_speaker") {
                i.getStringExtra("mtv_fake_speaker")?.split('|')?.takeIf { it.size == 4 }?.let { p ->
                    p[2].toIntOrNull()?.let { port -> com.mytv.launcher.data.Speakers.addForTest(com.mytv.launcher.data.Speakers.Dev(p[1], p[3], p[0], port)) }
                }
            }
            // Dock order by package name, "pkg1,pkg2,…" (the rest keeps its order behind them).
            on("mtv_dock_order") { state.setDockOrder(i.getStringExtra("mtv_dock_order").orEmpty().split(',').map { it.trim() }) }
            // Development check: the next N starts of MyTV crash on purpose (crash guard).
            on("mtv_test_crashes") { CrashGuard.testCrashes(c, i.getIntExtra("mtv_test_crashes", 0)) }
            // Development check: sleep timer in seconds (0 = off).
            on("mtv_sleep_seconds") { SleepTimer.setMs(c, i.getIntExtra("mtv_sleep_seconds", 0) * 1000L) }
            if (i.getBooleanExtra("mtv_welcome", false)) { state.openWelcome(i.getIntExtra("mtv_welcome_step", 0)); handled = true }
            // Screenshots: "pkg1,pkg2,…|City|cards" (cards: example Continue watching; in memory only, restart MyTV to end it).
            on("mtv_demo") { i.getStringExtra("mtv_demo")?.split('|')?.let { p -> state.demo(p[0].split(',').map { it.trim() }, p.getOrNull(1)?.trim(), p.getOrNull(2)?.trim() == "cards") } }
            // Any of the 34 language tags (en, nl, …, zh-TW), or auto (follow the TV)
            on("mtv_language") { i.getStringExtra("mtv_language")?.let { state.provisionLanguage(if (it == "auto") null else it) } }
            on("mtv_bg") { i.getStringExtra("mtv_bg")?.let { state.setBackground(it, animate = false) } }
            // Start-up app: a package name, or none
            on("mtv_autostart") { i.getStringExtra("mtv_autostart")?.let { state.setAutostartPackage(if (it == "none") null else it) } }
            return handled
        }
    }
}
