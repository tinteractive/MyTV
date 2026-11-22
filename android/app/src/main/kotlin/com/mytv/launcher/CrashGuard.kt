// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import android.util.Log

/**
 * Never traps the TV in a loop of "stock home → MyTV → crash → stock home".
 * Two crashes, or starts that never settled, within three minutes make the Home-button helper stand aside for five
 * minutes, so the TV's own home stays usable. An update starts with a clean slate. Written synchronously (commit):
 * a crashing process gets no second chance to save.
 */
object CrashGuard {
    private const val WINDOW = 3 * 60_000L
    private const val ASIDE = 5 * 60_000L
    @Volatile private var installed = false

    private fun sp(c: Context) = c.getSharedPreferences("crashguard", Context.MODE_PRIVATE)
    private fun updatedAt(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { failed(app, "crash: $e") }
            previous?.uncaughtException(t, e)
        }
    }

    /** MyTV's screen is starting; a start that never settles (killed, frozen) counts as a failure at the next one. */
    fun starting(c: Context) {
        if (sp(c).getLong("startedAt", 0L) > 0L) failed(c, "start did not settle")
        sp(c).edit().putLong("startedAt", System.currentTimeMillis()).commit()
        // Development check only (set through adb setup commands): crash this many starts on purpose.
        val test = sp(c).getInt("testCrashes", 0)
        if (test > 0) { sp(c).edit().putInt("testCrashes", test - 1).commit(); throw IllegalStateException("crash guard test") }
    }

    fun settled(c: Context) { sp(c).edit().putLong("startedAt", 0L).apply() }

    fun testCrashes(c: Context, n: Int) { sp(c).edit().putInt("testCrashes", n).putString("fails", "").putLong("asideUntil", 0L).commit() }

    private fun failed(c: Context, why: String) {
        val now = System.currentTimeMillis()
        val since = maxOf(now - WINDOW, updatedAt(c))
        val times = sp(c).getString("fails", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            .filter { it in (since + 1)..now } + now   // a time in the future: noted while the clock ran ahead
        val e = sp(c).edit().putString("fails", times.joinToString(",")).putLong("startedAt", 0L)
        if (times.size >= 2) { e.putLong("asideUntil", now + ASIDE); Log.w("MyTV", "crash guard: standing aside for 5 minutes ($why)") }
        else Log.w("MyTV", "crash guard: failure noted ($why)")
        e.commit()
    }

    /** Whether the Home-button helper should leave the TV's own home alone for now. */
    fun standingAside(c: Context): Boolean {
        val until = sp(c).getLong("asideUntil", 0L)
        val now = System.currentTimeMillis()
        // Never longer than ASIDE from now: a TV whose clock was ahead when it was noted (some boot with a wrong time
        // and correct it later) would otherwise keep the helper aside far too long.
        return until > now && until - now <= ASIDE && until - ASIDE > updatedAt(c)
    }
}
