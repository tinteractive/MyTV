// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.lifecycleScope
import com.mytv.launcher.ui.LocalBg
import com.mytv.launcher.ui.SourcesSheet

/**
 * The remote's Sources button while another app plays (live TV, a streaming app): only MyTV's Sources, over that
 * app, instead of MyTV's whole home screen. Choosing a source switches to it; Back returns to what was playing.
 */
class SourcesPopup : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.mytv.launcher.ui.withMyTvLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A small state of its own: no apps, photos or weather are loaded for this.
        val state = HomeState(applicationContext, lifecycleScope)
        state.bg.blur = false            // nothing of MyTV's photo behind it: plain dark glass over the picture
        state.open(Overlay.Sources, null)
        setContent {
            CompositionLocalProvider(LocalBg provides state.bg) {
                if (state.overlays.isEmpty()) { LaunchedEffect(Unit) { finish() }; return@CompositionLocalProvider }
                SourcesSheet(state) {
                    startActivity(android.content.Intent(this@SourcesPopup, MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                BackHandler { finish() }
                LaunchedEffect(state.pendingFocus) {
                    val k = state.pendingFocus ?: return@LaunchedEffect
                    repeat(6) {
                        withFrameNanos { }
                        state.focus[k]?.let { runCatching { it.requestFocus() }; state.pendingFocus = null; return@LaunchedEffect }
                    }
                }
            }
        }
    }

    // Something else came to the front (a source was chosen, Home): this popup is done.
    override fun onStop() { super.onStop(); finish() }
}
