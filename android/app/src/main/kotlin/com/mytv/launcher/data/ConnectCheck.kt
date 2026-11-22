// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * "Connection to the TV" (Settings → System): why MyTV cannot use the TV's own debugging connection, in one line with
 * the next step. [diagnose] is a plain function over [Facts], checked by ConnectCheckTest.
 */
object ConnectCheck {
    /** What Android reports; null where this TV does not say. [last] is the outcome of the last try (HomeState.adbStatus). */
    data class Facts(
        val devOptions: Boolean?, val adbEnabled: Boolean?, val wireless: Boolean?, val portOpen: Boolean,
        val last: String?, val verified: Boolean,
        /** MyTV is paired for Wireless debugging (Android 11+). */
        val paired: Boolean = false,
    )

    enum class Reason { OK, UNCHECKED, DEV_OFF, ADB_OFF, WIRELESS_ONLY, WIRELESS_OFF, PORT_CLOSED, NO_ANSWER, REFUSED, FAILED }

    /** The one thing that is wrong (the first in the order a viewer has to fix them), or OK. */
    fun diagnose(f: Facts): Reason = when {
        f.portOpen && f.last == null && f.verified -> Reason.OK
        f.devOptions == false -> Reason.DEV_OFF
        // Wireless debugging is on but MyTV cannot use it: pair (again; the TV may have forgotten MyTV).
        !f.portOpen && f.wireless == true -> Reason.WIRELESS_ONLY
        // Paired before, and Wireless debugging went off (it does after a restart or on another network).
        !f.portOpen && f.paired && f.wireless == false -> Reason.WIRELESS_OFF
        f.adbEnabled == false && !f.portOpen -> Reason.ADB_OFF
        !f.portOpen -> Reason.PORT_CLOSED
        f.last == "noanswer" -> Reason.NO_ANSWER
        f.last == "refused" -> Reason.REFUSED
        f.last == "failed" || f.last == "off" -> Reason.FAILED
        else -> Reason.UNCHECKED
    }

    fun facts(c: Context, portOpen: Boolean, last: String?, verified: Boolean, paired: Boolean = false): Facts {
        fun global(name: String): Boolean? = runCatching { Settings.Global.getInt(c.contentResolver, name) == 1 }.getOrNull()
        return Facts(
            devOptions = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            adbEnabled = global(Settings.Global.ADB_ENABLED),
            wireless = if (Build.VERSION.SDK_INT >= 30) global("adb_wifi_enabled") else null,
            portOpen = portOpen, last = last, verified = verified, paired = paired,
        )
    }
}
