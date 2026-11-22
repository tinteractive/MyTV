// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Play Store edition: no setup commands from a computer. */
object Provision {
    @Suppress("UNUSED_PARAMETER")
    fun handle(c: Context, scope: CoroutineScope, state: HomeState): Boolean? = null
}
