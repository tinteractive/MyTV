// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.View
import com.mytv.launcher.data.BackgroundRepository
import com.mytv.launcher.data.Prefs
import com.mytv.launcher.data.WeatherReport
import com.mytv.launcher.data.WeatherRepository
import com.mytv.launcher.ui.Units
import com.mytv.launcher.ui.datePattern
import com.mytv.launcher.ui.wxRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * MyTV as Android screensaver: the MyTV photos slowly zooming and cross-fading, with clock, date and weather that
 * move to another part of the screen every minute (against burn-in, on every screen type). Plain Canvas drawing, no
 * Compose: it may run for hours.
 */
class MyTvDream : DreamService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.mytv.launcher.ui.withMyTvLanguage(newBase))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false        // any key ends the screensaver
        isFullscreen = true
        isScreenBright = true
        setContentView(DreamView(this))
    }
}

/** The same view inside MyTV, as preview: any key closes it. */
class DreamPreviewActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.mytv.launcher.ui.withMyTvLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(DreamView(this))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}

class DreamView(c: Context) : View(c) {
    private val prefs = Prefs(c)
    private val repo = BackgroundRepository(c)
    private val photos = runCatching { repo.pool(repo.list(), prefs.photoSource, prefs.photosOff).shuffled().toMutableList() }.getOrDefault(mutableListOf())
    private val showClock = prefs.dreamClock
    private var scope: CoroutineScope? = null

    private class Shot(val bmp: Bitmap, val start: Long) {
        // Slow "Ken Burns": zoom in or out by 8 % while drifting from one point to another.
        val zoomIn = Random.nextBoolean()
        val p0x = Random.nextFloat(); val p0y = Random.nextFloat()
        val p1x = Random.nextFloat(); val p1y = Random.nextFloat()
    }
    @Volatile private var cur: Shot? = null
    @Volatile private var prev: Shot? = null

    private var weather: WeatherReport? = null
    private var wxIcon: Bitmap? = null
    private val d = resources.displayMetrics.density
    private val matrix = Matrix()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val timePaint = text(76f, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val smallPaint = text(24f, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val scrim = Paint()
    /** Where the clock block is now: 0..3 = top left, top right, bottom left, bottom right; plus a small random offset. */
    private var spot = 2
    private var spotDx = 0f
    private var spotDy = 0f
    private var spotSince = 0L

    private fun text(sizeDp: Float, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = sizeDp * d; typeface = tf
        setShadowLayer(6 * d, 0f, 1.5f * d, 0x99000000.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        keepScreenOn = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        s.launch {
            var i = 0
            while (isActive && photos.isNotEmpty()) {
                val bmp = withContext(Dispatchers.Default) { runCatching { load(photos[i % photos.size]) }.getOrNull() }
                if (bmp != null) {
                    val old = prev
                    prev = cur; cur = Shot(bmp, SystemClock.uptimeMillis())
                    invalidate()
                    delay(FADE_MS + 200)
                    old?.bmp?.let { if (it !== cur?.bmp && it !== prev?.bmp) it.recycle() }
                }
                i++
                // A new random round after each pass (not the same order again).
                if (i % photos.size == 0) { val last = photos.last(); photos.shuffle(); if (photos.size > 1 && photos.first() == last) photos.add(photos.removeAt(0)) }
                delay(PHOTO_MS - FADE_MS)
            }
        }
        if (showClock) s.launch {
            val city = prefs.cities.firstOrNull() ?: return@launch
            val repo = WeatherRepository(context, prefs)
            while (isActive) {
                weather = repo.cached(city)
                if (weather == null || weatherAgeMin() > 30) {
                    runCatching { withContext(Dispatchers.IO) { repo.fetch(city, units().fahrenheit) } }.onSuccess { weather = it }
                }
                weather?.let { w -> wxIcon = BitmapFactory.decodeResource(resources, wxRes(w.code, w.isDay)) }
                delay(30 * 60_000L)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel(); scope = null
    }

    private fun weatherAgeMin() = weather?.let { (System.currentTimeMillis() - it.fetchedAt) / 60_000 } ?: Long.MAX_VALUE

    private fun units() = Units.of(context, prefs.tempUnit, prefs.timeFormat, weather?.fahrenheit)

    /** 1920×1080 photo, as GPU bitmap when possible (no copy in the app's own memory). */
    private fun load(info: com.mytv.launcher.data.BgInfo): Bitmap {
        val b = repo.photo(info)
        b.copy(Bitmap.Config.HARDWARE, false)?.let { b.recycle(); return it }
        return b
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        canvas.drawColor(Color.BLACK)
        val c = cur
        val fade = if (c == null) 1f else ((now - c.start) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        prev?.takeIf { fade < 1f }?.let { drawShot(canvas, it, now, 255) }
        c?.let { drawShot(canvas, it, now, (fade * 255).toInt()) }
        if (showClock) drawClock(canvas)
        // Zoom and drift move ~2 px per second: 10 frames per second is smooth enough and saves CPU for hours;
        // only the cross-fade runs at full frame rate.
        val clockFading = showClock && spotSince > 0 && (System.currentTimeMillis() - spotSince).let { it < FADE_TEXT_MS || it > SPOT_MS - FADE_TEXT_MS }
        if (fade < 1f || clockFading) postInvalidateOnAnimation() else postInvalidateDelayed(100)
    }

    private fun drawShot(canvas: Canvas, s: Shot, now: Long, alpha: Int) {
        val t = ((now - s.start) / (PHOTO_MS + FADE_MS).toFloat()).coerceIn(0f, 1f)
        val zoom = if (s.zoomIn) 1f + 0.08f * t else 1.08f - 0.08f * t
        val sc = maxOf(width / s.bmp.width.toFloat(), height / s.bmp.height.toFloat()) * zoom
        val px = s.p0x + (s.p1x - s.p0x) * t; val py = s.p0y + (s.p1y - s.p0y) * t
        matrix.setScale(sc, sc)
        matrix.postTranslate(-(s.bmp.width * sc - width) * px, -(s.bmp.height * sc - height) * py)
        photoPaint.alpha = alpha
        canvas.drawBitmap(s.bmp, matrix, photoPaint)
    }

    /**
     * Every minute the clock, date and weather move to another corner of the screen (never the same one twice in a
     * row), with a short fade. Nothing stays on the same pixels for long, whether the screen is OLED or not.
     */
    private fun drawClock(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = System.currentTimeMillis()
        if (spotSince == 0L) spotSince = now
        if (now - spotSince >= SPOT_MS) {
            spot = (spot + 1 + Random.nextInt(3)) % 4
            spotDx = Random.nextFloat() * 120 * d; spotDy = Random.nextFloat() * 50 * d
            spotSince = now
        }
        // Fade out in the last half second before a move, in again in the first half second after it.
        val t = now - spotSince
        val alpha = (minOf(t, SPOT_MS - t).coerceAtLeast(0L) / FADE_TEXT_MS.toFloat()).coerceIn(0f, 1f)
        val top = spot < 2; val right = spot % 2 == 1

        val u = units()
        val nowT = LocalDateTime.now()
        val (time, ampm) = u.clock(nowT)
        val date = nowT.format(datePattern("EEEEdMMMM")).lowercase(com.mytv.launcher.ui.LOC)
        val wx = weather
        val line = if (wx != null && weatherAgeMin() in 0 until 180) "${u.temp(wx.temp)}°" else null   // as on the home screen
        val iconSz = 30 * d
        val dateLineW = smallPaint.measureText(date) + if (line != null) 22 * d + (if (wxIcon != null) iconSz + 8 * d else 0f) + smallPaint.measureText(line) else 0f
        val timeW = timePaint.measureText(time) + if (ampm != null) 10 * d + smallPaint.measureText(ampm) else 0f
        val blockW = maxOf(dateLineW, timeW)
        val blockH = timePaint.textSize + 14 * d + smallPaint.textSize

        // A soft dark band behind the text, on the side where it is.
        val band = 0x8C000000.toInt()
        scrim.shader = if (top) LinearGradient(0f, 0f, 0f, h * 0.5f, band, 0, Shader.TileMode.CLAMP)
            else LinearGradient(0f, h * 0.5f, 0f, h, 0, band, Shader.TileMode.CLAMP)
        scrim.alpha = (alpha * 255).toInt()
        if (top) canvas.drawRect(0f, 0f, w, h * 0.5f, scrim) else canvas.drawRect(0f, h * 0.5f, w, h, scrim)

        val margin = 64 * d
        val x = if (right) w - margin - blockW - spotDx else margin + spotDx
        val blockTop = if (top) margin + spotDy else h - margin - blockH - spotDy
        val timeY = blockTop + timePaint.textSize * 0.85f
        val dateY = timeY + 14 * d + smallPaint.textSize
        val a = (alpha * 255).toInt()
        timePaint.alpha = a; smallPaint.alpha = a
        canvas.drawText(time, x, timeY, timePaint)
        if (ampm != null) canvas.drawText(ampm, x + timePaint.measureText(time) + 10 * d, timeY, smallPaint)
        canvas.drawText(date, x, dateY, smallPaint)
        if (line != null) {
            var lx = x + smallPaint.measureText(date) + 22 * d
            wxIcon?.let { ic ->
                canvas.drawBitmap(ic, null, android.graphics.RectF(lx, dateY - iconSz * 0.82f, lx + iconSz, dateY + iconSz * 0.18f), photoPaint.apply { this.alpha = a })
                lx += iconSz + 8 * d
            }
            canvas.drawText(line, lx, dateY, smallPaint)
        }
        timePaint.alpha = 255; smallPaint.alpha = 255
    }

    companion object {
        const val PHOTO_MS = 60_000L
        const val FADE_MS = 3_000L
        /** How long the clock stays in one place, and how long it fades out and in when it moves. */
        const val SPOT_MS = 60_000L
        const val FADE_TEXT_MS = 500L
    }
}
