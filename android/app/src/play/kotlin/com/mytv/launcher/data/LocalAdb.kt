// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import java.io.IOException

/** Play Store edition: no connection to the TV's own debug bridge, so the functions that need it stay hidden. */
@Suppress("EmptyFunctionBlock", "FunctionOnlyReturningConstant")
object LocalAdb {
    const val SUPPORTED = false
    open class NotAvailable(msg: String) : IOException(msg)
    open class NotApproved(msg: String) : IOException(msg)
    class NoAnswer(msg: String) : NotApproved(msg)

    @Suppress("UNUSED_PARAMETER") fun init(c: android.content.Context) {}
    fun reachable(): Boolean = false
    fun plainReachable(): Boolean = false
    fun wirelessPaired(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun shell(command: String, onApprovalNeeded: () -> Unit = {}, approvalTimeoutMs: Int = 0, prompt: Boolean = true): String =
        throw NotAvailable("not in this edition")
}
