// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Optional extra system access (GitHub edition), granted once from a computer:
 *   adb shell pm grant com.tinteractive.mytv android.permission.WRITE_SECURE_SETTINGS
 * With it MyTV keeps its own Home-button service switched on: Android switches an accessibility service off
 * when its app is force-stopped or crashes, after which Home would land on the stock launcher again.
 * Undo: adb shell pm revoke com.tinteractive.mytv android.permission.WRITE_SECURE_SETTINGS
 */
object SystemAccess {

    fun canWriteSecure(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun enabledServices(c: Context) =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

    private fun listed(c: Context) = enabledServices(c).split(':').any { it.startsWith(c.packageName + "/") }

    /** Listed and accessibility switched on as a whole (a listed service is not bound while that is 0). */
    fun homeWatchOn(c: Context) = listed(c) && Settings.Secure.getInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    /**
     * Opens the TV's switch for MyTV's Home-button service. On this Philips the standard accessibility link goes to a
     * stub ("no app can handle this"); TV Settings has its own screen that takes the service info as extra.
     */
    fun openHomeWatchSettings(c: Context): Boolean {
        // Philips: the accessibility deep links are stubs or "not supported in current flavor"; the Android settings
        // panel (Device preferences → Accessibility) is reachable through its main screen.
        val tries = listOfNotNull(
            real(c, android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),   // most Android / Google TVs
            androidTvSettings().takeIf { c.packageManager.resolveActivity(it, 0) != null },   // Philips & co: via the main panel
            android.content.Intent(Settings.ACTION_SETTINGS),
        )
        return Net.startFirst(c, *tries.toTypedArray())
    }

    /** MyTV is the default home, or holds Android's home role (on this Philips the role does not steer Home). */
    fun holdsDefaultHome(c: Context): Boolean = isDefaultHome(c) || android.os.Build.VERSION.SDK_INT >= 29 &&
        c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true

    /** Whether Android's default home is MyTV already. */
    fun isDefaultHome(c: Context) = c.packageManager.resolveActivity(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.packageName == c.packageName

    /**
     * How to ask Android to make MyTV the default home: the home role (Android 10+), otherwise
     * the system's own "home app" screen. Null when MyTV already is, or on Google TV, which always keeps its own home.
     */
    fun defaultHomeRequest(c: Context): android.content.Intent? {
        if (isDefaultHome(c)) return null
        if (runCatching { c.packageManager.getPackageInfo("com.google.android.apps.tv.launcherx", 0) }.isSuccess) return null
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                return if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) null else rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
            }
        }
        return real(c, android.content.Intent(Settings.ACTION_HOME_SETTINGS))
    }

    /** The intent, unless this TV routes it to a stub or an "ignore" screen (Philips does both). */
    fun real(c: Context, i: android.content.Intent): android.content.Intent? {
        val a = c.packageManager.resolveActivity(i, 0)?.activityInfo ?: return null
        val n = (a.packageName + "/" + a.name).lowercase()
        return if ("stub" in n || "ignore" in n) null else i
    }

    fun androidTvSettings(): android.content.Intent =
        android.content.Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings")

    /**
     * Re-enables the Home-button service when it was switched off and MyTV may do so; called when MyTV comes to the
     * front, when its notification listener connects, and after a boot or an update. Returns true if it acted.
     */
    fun repairHomeWatch(c: Context): Boolean {
        if (!canWriteSecure(c)) return false
        // Switched off by the owner in the TV settings (the service saw it go): leave it off.
        if (c.getSharedPreferences("mytv", Context.MODE_PRIVATE).getBoolean("homeWatchOffByUser", false)) return false
        val me = android.content.ComponentName(c, com.mytv.launcher.HomeWatch::class.java).flattenToString()
        val others = enabledServices(c).split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName + "/") }
        fun write(list: List<String>) {
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return runCatching {
            if (homeWatchOn(c)) {
                // Listed but not bound (seen after crashes): off and on again makes Android bind it.
                // Not in the first half minute of the process: at boot binding simply takes a while.
                val upMs = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
                if (com.mytv.launcher.HomeWatch.connected || upMs < 30_000) return false
                write(others)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { write(others + me) } }, 2_000)
                Log.i("MyTV", "Home-button service listed but not running: switched off and on")
                return true
            }
            write(others + me)
            Log.i("MyTV", "Home-button service was off: switched back on")
            true
        }.getOrElse { Log.w("MyTV", "could not re-enable Home-button service: $it"); false }
    }
}
