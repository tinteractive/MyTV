// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small box at the bottom of the screen for the six-digit Wireless debugging pairing code, drawn by the Home-button
 * helper over the TV's own "Pair device with pairing code" window. It takes no focus, so that window stays open;
 * while it shows, the helper hands it the remote's keys: the number keys, or Up/Down to change a digit and
 * Left/Right to move, OK to pair, Back to close the box. Home, volume and power still go to the TV.
 */
object PairPad {
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var digitViews: List<TextView> = emptyList()
    private var status: TextView? = null
    private val digits = IntArray(6) { -1 }
    private var cursor = 0
    private var busy = false
    private var onCode: ((String) -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    val showing: Boolean get() = root != null

    /** Shows the box (on the main thread) through [service]; [onCode] gets the six digits, [onClose] a Back. */
    fun show(service: AccessibilityService, onCode: (String) -> Unit, onClose: () -> Unit): Boolean {
        if (root != null) return true
        val ctx = com.mytv.launcher.ui.withMyTvLanguage(service)
        val d = ctx.resources.displayMetrics.density
        this.onCode = onCode; this.onClose = onClose
        digits.fill(-1); cursor = 0; busy = false
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * d).toInt(), (18 * d).toInt(), (28 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable().apply { setColor(0xF0202024.toInt()); cornerRadius = 22 * d; setStroke((2 * d).toInt(), 0x66FFFFFF) }
        }
        box.addView(text(ctx, ctx.getString(R.string.pad_title), 18f, 0xFFFFFFFF.toInt(), bold = true))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, (12 * d).toInt(), 0, (10 * d).toInt()) }
        digitViews = List(6) {
            text(ctx, "", 30f, 0xFFFFFFFF.toInt(), bold = true).apply { gravity = Gravity.CENTER }.also { v ->
                row.addView(v, LinearLayout.LayoutParams((46 * d).toInt(), (58 * d).toInt()).apply { marginStart = (5 * d).toInt(); marginEnd = (5 * d).toInt() })
            }
        }
        box.addView(row)
        box.addView(text(ctx, ctx.getString(R.string.pad_hint), 14f, 0xB3FFFFFF.toInt()))
        status = text(ctx, "", 15f, 0xFFFFD58A.toInt(), bold = true).apply { visibility = View.GONE; setPadding(0, (8 * d).toInt(), 0, 0) }
        box.addView(status)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (40 * d).toInt() }
        return runCatching {
            (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
            root = box
            draw()
            true
        }.onFailure { Log.w("MyTV", "pair pad: $it") }.getOrDefault(false)
    }

    private fun text(ctx: Context, s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun draw() {
        val d = root?.resources?.displayMetrics?.density ?: return
        digitViews.forEachIndexed { i, v ->
            v.text = if (digits[i] >= 0) digits[i].toString() else ""
            v.background = GradientDrawable().apply {
                setColor(0x33FFFFFF); cornerRadius = 10 * d
                if (i == cursor && !busy) setStroke((3 * d).toInt(), 0xFFFFFFFF.toInt())
            }
        }
    }

    /** A line under the digits (pairing, wrong code, done); [error] clears the digits for another try. */
    fun say(text: String, error: Boolean = false) = main.post {
        val s = status ?: return@post
        s.text = text; s.visibility = View.VISIBLE
        s.setTextColor(if (error) 0xFFFFD58A.toInt() else 0xFF30D158.toInt())
        busy = false
        if (error) { digits.fill(-1); cursor = 0 }
        draw()
    }

    fun hide() = main.post {
        val v = root ?: return@post
        root = null; digitViews = emptyList(); status = null; onCode = null; onClose = null
        runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
    }

    /** From the Home-button helper while the box shows: true when the key was for the box. */
    fun onKey(event: KeyEvent): Boolean {
        if (root == null) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        main.post { press(code) }
        return true
    }

    private fun press(code: Int) {
        if (busy) return
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        when {
            digit >= 0 -> { digits[cursor] = digit; if (cursor < 5) cursor++ }
            code == KeyEvent.KEYCODE_DPAD_UP -> digits[cursor] = (digits[cursor] + 1).mod(10)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> digits[cursor] = if (digits[cursor] <= 0) 9 else digits[cursor] - 1
            code == KeyEvent.KEYCODE_DPAD_RIGHT && cursor < 5 -> { digits[cursor] = digits[cursor].coerceAtLeast(0); cursor++ }
            (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DEL) && cursor > 0 -> cursor--
            code == KeyEvent.KEYCODE_BACK -> { val c = onClose; hide(); c?.invoke(); return }
            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (digits.all { it >= 0 }) {
                    busy = true
                    status?.apply { text = context.getString(R.string.pad_pairing); visibility = View.VISIBLE; setTextColor(0xFFFFFFFF.toInt()) }
                    val c = onCode
                    val s = digits.joinToString("")
                    draw()
                    c?.invoke(s)
                    return
                }
            }
        }
        draw()
    }
}
