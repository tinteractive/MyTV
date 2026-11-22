// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import android.content.Context
import com.mytv.launcher.BuildConfig

/** Play Store edition: the Play Store updates MyTV, so there is no own updater ([UpdateUi.supported] stays false). */
@Suppress("UNUSED_PARAMETER")
object Updates {
    val ui = UpdateUi()
    fun current(): String = BuildConfig.VERSION_NAME
    fun init(c: Context) {}
    fun onResume(c: Context) {}
    fun skip(c: Context) {}
    fun shouldPrompt(c: Context) = false
    fun setAuto(c: Context, on: Boolean) {}
    fun check(c: Context, userAsked: Boolean) {}
    fun install(c: Context) {}
    fun announced(c: Context) {}
    fun afterUpdate(c: Context) {}
}
