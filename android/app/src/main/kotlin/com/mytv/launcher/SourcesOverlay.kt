// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mytv.launcher.ui.LocalBg
import com.mytv.launcher.ui.SourcesSheet

/**
 * MyTV's Sources drawn by the Home-button helper itself, over whatever plays. Unlike a screen of its own
 * ([SourcesPopup]), Android does not pause the app underneath for it, so a stream keeps playing. Choosing a source
 * switches to it; MyTV goes home; Back closes it.
 */
object SourcesOverlay {
    private var view: ComposeView? = null
    private var owner: Owner? = null

    /** False when the window could not be added (the caller then falls back to [SourcesPopup]). */
    fun show(service: android.accessibilityservice.AccessibilityService): Boolean {
        if (view != null) return true
        val ctx = com.mytv.launcher.ui.withMyTvLanguage(service)
        val o = Owner().also { it.start() }
        val state = HomeState(ctx.applicationContext, o.lifecycleScope)
        state.bg.blur = false              // no MyTV photo behind it: plain dark glass over the picture
        state.open(Overlay.Sources, null)
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                CompositionLocalProvider(LocalBg provides state.bg) {
                    if (state.overlays.isEmpty()) { LaunchedEffect(Unit) { hide() }; return@CompositionLocalProvider }
                    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        // Back closes; the Sources button again closes too (it opened this).
                        val close = e.key == Key.Back || e.key == Key.Escape
                        if (close && e.type == KeyEventType.KeyUp) hide()
                        close
                    }) {
                        SourcesSheet(state) {
                            hide()
                            runCatching {
                                ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
        )
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return runCatching { wm.addView(v, lp); view = v; owner = o; true }
            .onFailure { Log.w("MyTV", "sources overlay: $it"); o.stop() }.getOrDefault(false)
    }

    val showing get() = view != null

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        owner?.stop(); owner = null
    }

    /** What Compose needs from a window that is not an activity: a lifecycle and a place for saved state. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
