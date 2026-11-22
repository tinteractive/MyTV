// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sleep timer: puts the TV in standby after the chosen time, also while another app plays. Runs in MyTV's
 * Home-button service (always on). One minute before, a small overlay over whatever plays offers "+15 minutes" and
 * "Off now"; Back closes the overlay (the timer keeps running).
 */
// The service and the warning view are held only while the Home-button service lives: it clears both in onDestroy.
@android.annotation.SuppressLint("StaticFieldLeak")
object SleepTimer {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var endsAt = 0L; private set          // elapsedRealtime, 0 = off
    var service: AccessibilityService? = null
    private var overlay: View? = null

    val active get() = endsAt > SystemClock.elapsedRealtime()
    /** Standby needs MyTV's Home-button service (or, as fallback, the TV's own adb). */
    val canStandby get() = service != null && android.os.Build.VERSION.SDK_INT >= 28
    fun minutesLeft() = if (!active) 0 else (((endsAt - SystemClock.elapsedRealtime()) + 59_999) / 60_000).toInt()

    fun set(c: Context, minutes: Int) = setMs(c, minutes * 60_000L)

    fun setMs(c: Context, ms: Long) {
        main.removeCallbacksAndMessages(null)
        hideWarning()
        if (ms <= 0) { endsAt = 0; return }
        val minutes = ms / 60_000
        endsAt = SystemClock.elapsedRealtime() + ms
        // The delays stop counting while the TV sleeps but [endsAt] does not: a timer that comes back long overdue
        // (the TV was switched off by hand meanwhile) is dropped instead of acted on.
        main.postDelayed({ if (overdue()) setMs(c, 0) else showWarning(c.applicationContext) }, (ms - 60_000L).coerceAtLeast(0))
        main.postDelayed({ if (overdue()) setMs(c, 0) else sleep(c.applicationContext) }, ms)
        Log.i("MyTV", "sleep timer: $minutes min")
    }

    private fun overdue() = endsAt == 0L || SystemClock.elapsedRealtime() - endsAt > 120_000L

    private fun sleep(c: Context) {
        main.removeCallbacksAndMessages(null)
        hideWarning(); endsAt = 0
        Log.i("MyTV", "sleep timer: standby")
        // Since Android 9 the service's "lock screen" action sends the TV to standby (as tv-vibehack's sleep timer
        // does): no network debugging needed. The TV's own adb sleep key only when that is not possible.
        val ok = canStandby && runCatching { service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true }.getOrDefault(false)
        Log.i("MyTV", "sleep timer: lock-screen action " + if (ok) "sent" else "not available, trying adb")
        if (!ok) Thread {
            // No 90 s wait for an approval prompt over the film.
            runCatching { com.mytv.launcher.data.LocalAdb.shell("input keyevent KEYCODE_SLEEP", approvalTimeoutMs = 3_000) }
        }.start()
    }

    private fun showWarning(c: Context) {
        val svc = service ?: return
        if (overlay != null) return
        val ctx = localized(svc)
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = ctx.resources.displayMetrics.density
        fun pill(text: String, onClick: () -> Unit) = Button(ctx).apply {
            this.text = text; isAllCaps = false; textSize = 18f; setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0x33FFFFFF) }
            setTextColor(0xFFFFFFFF.toInt())
            setOnFocusChangeListener { v, f ->
                (v.background as GradientDrawable).setColor(if (f) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
                (v as Button).setTextColor(if (f) 0xFF14171B.toInt() else 0xFFFFFFFF.toInt())
            }
            setOnClickListener { onClick() }
        }
        val root = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { hideWarning(); return true }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * d).toInt(), (22 * d).toInt(), (28 * d).toInt(), (22 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(0xE6202328.toInt()) }
            gravity = Gravity.CENTER_HORIZONTAL
            val title = TextView(ctx).apply { text = ctx.getString(R.string.sleep_warning); textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; setPadding(0, 0, 0, (18 * d).toInt()) }
            addView(title)
            // Counts down: 1 minute, then 50, 40, 30, 20 seconds, then every second.
            val tick = object : Runnable {
                override fun run() {
                    if (overlay == null) return
                    val left = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                    val shown = if (left > 55) 60 else if (left > 10) (left + 9) / 10 * 10 else left
                    title.text = if (shown >= 60) ctx.getString(R.string.sleep_warning) else ctx.getString(R.string.sleep_warning_sec, shown)
                    main.postDelayed(this, 1000)
                }
            }
            post(tick)      // once the warning is on screen
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val more = pill(ctx.getString(R.string.sleep_more)) { set(c, 15) }
            row.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt()).apply { rightMargin = (12 * d).toInt() })
            row.addView(pill(ctx.getString(R.string.sleep_now)) { sleep(c) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt()))
            addView(row)
            post { more.requestFocus() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        overlay = root
        runCatching { wm.addView(root, lp) }.onFailure { overlay = null; Log.w("MyTV", "sleep overlay: $it") }
    }

    fun hideWarning() {
        val v = overlay ?: return
        overlay = null
        runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    private fun localized(c: Context): Context {
        return com.mytv.launcher.ui.withMyTvLanguage(c)
    }
}
