// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context

/** Play Store edition: no connection to the TV's own debug bridge, so no Wireless debugging pairing either. */
@Suppress("EmptyFunctionBlock", "FunctionOnlyReturningConstant")
object AdbPairing {
    enum class State { IDLE, WAITING, PAIRED, NO_HELPER, UNSUPPORTED }
    val state = State.UNSUPPORTED
    fun supported() = false
    @Suppress("UNUSED_PARAMETER") fun start(c: Context) {}
    fun stop() {}
}
