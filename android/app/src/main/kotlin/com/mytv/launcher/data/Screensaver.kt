// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.mytv.launcher.MyTvDream

/**
 * Android's screensaver ("dream") settings for MyTV. Choosing the screensaver is a secure setting
 * (WRITE_SECURE_SETTINGS, granted once over adb); when it starts is a system setting, written directly when MyTV may,
 * otherwise via the TV's own adb.
 */
object Screensaver {
    private const val COMPONENTS = "screensaver_components"
    private const val ENABLED = "screensaver_enabled"
    private const val DEFAULT = "com.google.android.backdrop/.Backdrop"
    /** Android's "never" on this TV (7 days). */
    private const val NEVER_MS = 604_800_000

    fun component(c: Context) = ComponentName(c, MyTvDream::class.java).flattenToString()

    fun isMine(c: Context): Boolean = Settings.Secure.getString(c.contentResolver, COMPONENTS)?.split(',')?.firstOrNull() == component(c) &&
        Settings.Secure.getInt(c.contentResolver, ENABLED, 1) == 1

    /** Switches MyTV on as screensaver, or back to what was set before. Needs WRITE_SECURE_SETTINGS. */
    fun setMine(c: Context, prefs: Prefs, on: Boolean) {
        val cr = c.contentResolver
        if (on) {
            Settings.Secure.getString(cr, COMPONENTS)?.takeIf { it.isNotBlank() && !it.startsWith(c.packageName + "/") }?.let { prefs.previousDream = it }
            Settings.Secure.putString(cr, COMPONENTS, component(c))
            Settings.Secure.putInt(cr, ENABLED, 1)
        } else {
            Settings.Secure.putString(cr, COMPONENTS, prefs.previousDream ?: DEFAULT)
        }
    }

    /** Minutes of no input before the screensaver starts; 0 = never. */
    fun timeoutMinutes(c: Context): Int {
        val ms = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, NEVER_MS)
        return if (ms >= 24 * 3_600_000) 0 else ms / 60_000
    }

    /** Whether MyTV may write the time itself ("modify system settings", or the secure-settings right from setup). */
    fun canSetTimeout(c: Context) = Settings.System.canWrite(c) || SystemAccess.canWriteSecure(c)

    /** Android's "modify system settings" switch for MyTV, when this TV has that screen (this Philips does not). */
    fun permissionScreen(c: Context): android.content.Intent? =
        android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + c.packageName))
            .takeIf { it.resolveActivity(c.packageManager) != null }

    /** Blocking: straight into the system setting when allowed, otherwise through the TV's own adb. */
    fun setTimeoutMinutes(c: Context, minutes: Int, onApprovalNeeded: () -> Unit) {
        val ms = if (minutes <= 0) NEVER_MS else minutes * 60_000
        c.getSharedPreferences("mytv", Context.MODE_PRIVATE).edit().putInt(CHOSEN, minutes.coerceAtLeast(0)).apply()
        val direct = runCatching { Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) }
            .onFailure { android.util.Log.i("MyTV", "screensaver time: no direct write ($it), using adb") }.isSuccess
        if (direct) android.util.Log.i("MyTV", "screensaver time: written directly ($minutes min)")
        else LocalAdb.shell("settings put system screen_off_timeout $ms", onApprovalNeeded)
    }

    val CHOICES = listOf(5, 10, 15, 30, 60, 0)

    /** After a restore: the start time from the backup, written when MyTV may do so directly (never through adb). */
    fun applyChosen(c: Context) {
        val chosen = c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getInt(CHOSEN, -1)
        if (chosen < 0 || chosen == timeoutMinutes(c) || !canSetTimeout(c)) return
        runCatching { Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, if (chosen == 0) NEVER_MS else chosen * 60_000) }
            .onFailure { android.util.Log.w("MyTV", "restore: screensaver time: $it") }
    }

    /** The start time the viewer chose in MyTV (minutes, 0 = never); absent until chosen there. */
    private const val CHOSEN = "dreamMinutesChosen"
    private val reapplied = ArrayDeque<Long>()

    /**
     * Some TVs (Philips) put the start time back to "never" now and then, by themselves. When the viewer chose a time
     * in MyTV and MyTV may write it directly, it is put back; never through adb (that could ask a question on screen),
     * and at most a few times an hour, so MyTV never keeps fighting the TV. Another time chosen in the TV's own
     * settings is taken over as the new choice.
     */
    fun keepChoice(c: Context) {
        val sp = c.getSharedPreferences("mytv", Context.MODE_PRIVATE)
        val chosen = sp.getInt(CHOSEN, -1)
        if (chosen <= 0) return
        val current = timeoutMinutes(c)
        if (current == chosen) return
        if (current > 0) { sp.edit().putInt(CHOSEN, current).apply(); return }
        if (!canSetTimeout(c)) return
        val want = chosen * 60_000
        val now = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        val t = android.os.SystemClock.elapsedRealtime()
        synchronized(reapplied) {
            while (reapplied.isNotEmpty() && t - reapplied.first() > 3_600_000) reapplied.removeFirst()
            if (reapplied.size >= 5) return
            reapplied.addLast(t)
        }
        runCatching { Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, want) }
            .onSuccess { android.util.Log.i("MyTV", "screensaver time: the TV changed it to $now ms, back to $chosen min") }
            .onFailure { android.util.Log.w("MyTV", "screensaver time: could not put back: $it") }
    }
}
