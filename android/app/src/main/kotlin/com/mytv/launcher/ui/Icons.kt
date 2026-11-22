// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mytv.launcher.R
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LineIcon(kind: String, size: Dp, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(size)) { lineIcon(kind, color) }
}

private fun DrawScope.lineIcon(kind: String, color: Color) {
    val s = size.minDimension
    val st = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Arrows meaning "back" and "forward" point the other way in right-to-left languages (media icons do not).
    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
    when (if (rtl && kind == "left") "right" else if (rtl && kind == "right") "left" else kind) {
        "image" -> {
            drawRoundRect(color, Offset(s * 0.1f, s * 0.2f), Size(s * 0.8f, s * 0.6f), CornerRadius(s * 0.1f), style = st)
            val p = Path().apply { moveTo(s * 0.16f, s * 0.72f); lineTo(s * 0.4f, s * 0.46f); lineTo(s * 0.56f, s * 0.62f); lineTo(s * 0.66f, s * 0.52f); lineTo(s * 0.84f, s * 0.72f) }
            drawPath(p, color, style = st)
            drawCircle(color, s * 0.06f, Offset(s * 0.68f, s * 0.36f))
        }
        "gear" -> {
            val c = Offset(s / 2, s / 2)
            drawCircle(color, s * 0.13f, c, style = st)
            drawCircle(color, s * 0.27f, c, style = st)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                drawLine(color, Offset(c.x + cos(a).toFloat() * s * 0.31f, c.y + sin(a).toFloat() * s * 0.31f),
                    Offset(c.x + cos(a).toFloat() * s * 0.42f, c.y + sin(a).toFloat() * s * 0.42f), st.width * 1.3f, StrokeCap.Round)
            }
        }
        "grid" -> for (i in 0 until 3) for (j in 0 until 3) drawCircle(color, s * 0.06f, Offset(s * (0.25f + i * 0.25f), s * (0.25f + j * 0.25f)))
        "check" -> drawPath(Path().apply { moveTo(s * 0.2f, s * 0.52f); lineTo(s * 0.42f, s * 0.72f); lineTo(s * 0.8f, s * 0.3f) }, color, style = st)
        "left" -> drawPath(Path().apply { moveTo(s * 0.6f, s * 0.25f); lineTo(s * 0.35f, s * 0.5f); lineTo(s * 0.6f, s * 0.75f) }, color, style = st)
        "right" -> drawPath(Path().apply { moveTo(s * 0.4f, s * 0.25f); lineTo(s * 0.65f, s * 0.5f); lineTo(s * 0.4f, s * 0.75f) }, color, style = st)
        "eye-off" -> {
            drawArc(color, 200f, 140f, false, Offset(s * 0.1f, s * 0.28f), Size(s * 0.8f, s * 0.6f), style = st)
            drawArc(color, 20f, 140f, false, Offset(s * 0.1f, s * 0.12f), Size(s * 0.8f, s * 0.6f), style = st)
            drawLine(color, Offset(s * 0.2f, s * 0.8f), Offset(s * 0.8f, s * 0.2f), st.width, StrokeCap.Round)
        }
        "eye" -> {
            drawArc(color, 200f, 140f, false, Offset(s * 0.1f, s * 0.28f), Size(s * 0.8f, s * 0.6f), style = st)
            drawArc(color, 20f, 140f, false, Offset(s * 0.1f, s * 0.12f), Size(s * 0.8f, s * 0.6f), style = st)
            drawCircle(color, s * 0.1f, Offset(s / 2, s / 2), style = st)
        }
        "refresh" -> {
            drawArc(color, -60f, 290f, false, Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f), style = st)
            val a = Math.toRadians(-60.0); val cx = s / 2 + cos(a).toFloat() * s * 0.3f; val cy = s / 2 + sin(a).toFloat() * s * 0.3f
            drawPath(Path().apply { moveTo(cx - s * 0.16f, cy - s * 0.04f); lineTo(cx, cy); lineTo(cx - s * 0.02f, cy + s * 0.16f) }, color, style = st)
        }
        "move" -> {
            drawLine(color, Offset(s * 0.15f, s / 2), Offset(s * 0.85f, s / 2), st.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(s * 0.3f, s * 0.35f); lineTo(s * 0.15f, s / 2); lineTo(s * 0.3f, s * 0.65f) }, color, style = st)
            drawPath(Path().apply { moveTo(s * 0.7f, s * 0.35f); lineTo(s * 0.85f, s / 2); lineTo(s * 0.7f, s * 0.65f) }, color, style = st)
        }
        // Rename: a pencil writing towards the bottom left.
        "pencil" -> {
            drawPath(Path().apply {
                moveTo(s * 0.18f, s * 0.82f); lineTo(s * 0.23f, s * 0.62f); lineTo(s * 0.64f, s * 0.21f)
                lineTo(s * 0.79f, s * 0.36f); lineTo(s * 0.38f, s * 0.77f); close()
            }, color, style = st)
            drawLine(color, Offset(s * 0.55f, s * 0.30f), Offset(s * 0.70f, s * 0.45f), st.width, StrokeCap.Round)
        }
        "info" -> {
            drawCircle(color, s * 0.38f, Offset(s / 2, s / 2), style = st)
            drawLine(color, Offset(s / 2, s * 0.46f), Offset(s / 2, s * 0.7f), st.width, StrokeCap.Round)
            drawCircle(color, st.width * 0.75f, Offset(s / 2, s * 0.32f))
        }
        // Settings backup: an arrow down into a tray (save), or up out of it (restore).
        "download", "upload" -> {
            val down = kind == "download"
            drawPath(Path().apply { moveTo(s * 0.2f, s * 0.62f); lineTo(s * 0.2f, s * 0.8f); lineTo(s * 0.8f, s * 0.8f); lineTo(s * 0.8f, s * 0.62f) }, color, style = st)
            drawLine(color, Offset(s * 0.5f, s * 0.18f), Offset(s * 0.5f, s * 0.62f), st.width, StrokeCap.Round)
            val tip = if (down) s * 0.62f else s * 0.18f; val back = if (down) s * 0.46f else s * 0.34f
            drawPath(Path().apply { moveTo(s * 0.34f, back); lineTo(s * 0.5f, tip); lineTo(s * 0.66f, back) }, color, style = st)
        }
        "trash" -> {
            drawLine(color, Offset(s * 0.2f, s * 0.28f), Offset(s * 0.8f, s * 0.28f), st.width, StrokeCap.Round)
            drawLine(color, Offset(s * 0.4f, s * 0.18f), Offset(s * 0.6f, s * 0.18f), st.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(s * 0.28f, s * 0.28f); lineTo(s * 0.33f, s * 0.84f); lineTo(s * 0.67f, s * 0.84f); lineTo(s * 0.72f, s * 0.28f) }, color, style = st)
        }
        "search" -> {
            drawCircle(color, s * 0.24f, Offset(s * 0.43f, s * 0.43f), style = st)
            drawLine(color, Offset(s * 0.61f, s * 0.61f), Offset(s * 0.82f, s * 0.82f), st.width, StrokeCap.Round)
        }
        "power" -> {
            drawArc(color, -60f, 300f, false, Offset(s * 0.2f, s * 0.22f), Size(s * 0.6f, s * 0.6f), style = st)
            drawLine(color, Offset(s / 2, s * 0.12f), Offset(s / 2, s * 0.48f), st.width, StrokeCap.Round)
        }
        "wind" -> {
            drawPath(Path().apply { moveTo(s * 0.12f, s * 0.4f); lineTo(s * 0.62f, s * 0.4f); cubicTo(s * 0.86f, s * 0.4f, s * 0.86f, s * 0.12f, s * 0.64f, s * 0.16f) }, color, style = st)
            drawPath(Path().apply { moveTo(s * 0.12f, s * 0.6f); lineTo(s * 0.72f, s * 0.6f); cubicTo(s * 0.94f, s * 0.6f, s * 0.92f, s * 0.88f, s * 0.7f, s * 0.84f) }, color, style = st)
        }
        "sun" -> {
            drawCircle(color, s * 0.17f, Offset(s / 2, s / 2), style = st)
            for (i in 0 until 8) { val a = i * Math.PI / 4
                drawLine(color, Offset(s / 2 + cos(a).toFloat() * s * 0.29f, s / 2 + sin(a).toFloat() * s * 0.29f), Offset(s / 2 + cos(a).toFloat() * s * 0.4f, s / 2 + sin(a).toFloat() * s * 0.4f), st.width, StrokeCap.Round) }
        }
        "globe" -> {
            drawCircle(color, s * 0.38f, Offset(s / 2, s / 2), style = st)
            drawOval(color, Offset(s * 0.33f, s * 0.12f), Size(s * 0.34f, s * 0.76f), style = st)
            drawLine(color, Offset(s * 0.13f, s / 2), Offset(s * 0.87f, s / 2), st.width, StrokeCap.Round)
        }
        "thermo" -> {
            drawRoundRect(color, Offset(s * 0.4f, s * 0.1f), Size(s * 0.2f, s * 0.52f), CornerRadius(s * 0.1f), style = st)
            drawCircle(color, s * 0.15f, Offset(s / 2, s * 0.74f), style = st)
            drawLine(color, Offset(s / 2, s * 0.4f), Offset(s / 2, s * 0.66f), st.width, StrokeCap.Round)
        }
        "clock" -> {
            drawCircle(color, s * 0.38f, Offset(s / 2, s / 2), style = st)
            drawLine(color, Offset(s / 2, s / 2), Offset(s / 2, s * 0.28f), st.width, StrokeCap.Round)
            drawLine(color, Offset(s / 2, s / 2), Offset(s * 0.66f, s * 0.6f), st.width, StrokeCap.Round)
        }
        "layers" -> {
            drawPath(Path().apply { moveTo(s / 2, s * 0.14f); lineTo(s * 0.88f, s * 0.34f); lineTo(s / 2, s * 0.54f); lineTo(s * 0.12f, s * 0.34f); close() }, color, style = st)
            drawPath(Path().apply { moveTo(s * 0.12f, s * 0.52f); lineTo(s / 2, s * 0.72f); lineTo(s * 0.88f, s * 0.52f) }, color, style = st)
            drawPath(Path().apply { moveTo(s * 0.12f, s * 0.68f); lineTo(s / 2, s * 0.88f); lineTo(s * 0.88f, s * 0.68f) }, color, style = st)
        }
        "timer" -> {
            drawCircle(color, s * 0.34f, Offset(s / 2, s * 0.56f), style = st)
            drawLine(color, Offset(s * 0.4f, s * 0.1f), Offset(s * 0.6f, s * 0.1f), st.width, StrokeCap.Round)
            drawLine(color, Offset(s / 2, s * 0.56f), Offset(s * 0.62f, s * 0.42f), st.width, StrokeCap.Round)
        }
        "fade" -> {
            drawRoundRect(color, Offset(s * 0.1f, s * 0.22f), Size(s * 0.5f, s * 0.5f), CornerRadius(s * 0.08f), style = st)
            drawRoundRect(color.copy(alpha = color.alpha * 0.5f), Offset(s * 0.4f, s * 0.36f), Size(s * 0.5f, s * 0.5f), CornerRadius(s * 0.08f), style = st)
        }
        "pin" -> {
            drawPath(Path().apply { moveTo(s / 2, s * 0.9f); cubicTo(s * 0.18f, s * 0.56f, s * 0.2f, s * 0.12f, s / 2, s * 0.12f); cubicTo(s * 0.8f, s * 0.12f, s * 0.82f, s * 0.56f, s / 2, s * 0.9f); close() }, color, style = st)
            drawCircle(color, s * 0.1f, Offset(s / 2, s * 0.4f), style = st)
        }
        "cloud" -> {
            drawPath(Path().apply { moveTo(s * 0.28f, s * 0.72f); lineTo(s * 0.74f, s * 0.72f); cubicTo(s * 0.94f, s * 0.72f, s * 0.94f, s * 0.44f, s * 0.74f, s * 0.44f)
                cubicTo(s * 0.7f, s * 0.24f, s * 0.42f, s * 0.22f, s * 0.38f, s * 0.42f); cubicTo(s * 0.12f, s * 0.4f, s * 0.1f, s * 0.72f, s * 0.28f, s * 0.72f); close() }, color, style = st)
        }
        "code" -> {
            drawPath(Path().apply { moveTo(s * 0.34f, s * 0.28f); lineTo(s * 0.12f, s / 2); lineTo(s * 0.34f, s * 0.72f) }, color, style = st)
            drawPath(Path().apply { moveTo(s * 0.66f, s * 0.28f); lineTo(s * 0.88f, s / 2); lineTo(s * 0.66f, s * 0.72f) }, color, style = st)
            drawLine(color, Offset(s * 0.56f, s * 0.2f), Offset(s * 0.44f, s * 0.8f), st.width, StrokeCap.Round)
        }
        "input" -> {
            drawRoundRect(color, Offset(s * 0.34f, s * 0.18f), Size(s * 0.54f, s * 0.64f), CornerRadius(s * 0.08f), style = st)
            drawLine(color, Offset(s * 0.1f, s / 2), Offset(s * 0.58f, s / 2), st.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(s * 0.44f, s * 0.36f); lineTo(s * 0.58f, s / 2); lineTo(s * 0.44f, s * 0.64f) }, color, style = st)
        }
        "bluetooth" -> drawPath(Path().apply {
            moveTo(s * 0.3f, s * 0.32f); lineTo(s * 0.7f, s * 0.68f); lineTo(s * 0.5f, s * 0.86f); lineTo(s * 0.5f, s * 0.14f)
            lineTo(s * 0.7f, s * 0.32f); lineTo(s * 0.3f, s * 0.68f)
        }, color, style = st)
        "moon" -> drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference,
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f)) },
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(s * 0.34f, s * 0.04f, s * 1.02f, s * 0.7f)) }), color, style = st)
        "user" -> {
            drawCircle(color, s * 0.17f, Offset(s / 2, s * 0.33f), style = st)
            drawArc(color, 180f, 180f, false, Offset(s * 0.2f, s * 0.6f), Size(s * 0.6f, s * 0.5f), style = st)
        }
        "play" -> drawPath(Path().apply { moveTo(s * 0.3f, s * 0.18f); lineTo(s * 0.82f, s / 2); lineTo(s * 0.3f, s * 0.82f); close() }, color)
        "pause" -> {
            drawRoundRect(color, Offset(s * 0.24f, s * 0.18f), Size(s * 0.18f, s * 0.64f), CornerRadius(s * 0.04f))
            drawRoundRect(color, Offset(s * 0.58f, s * 0.18f), Size(s * 0.18f, s * 0.64f), CornerRadius(s * 0.04f))
        }
        "next" -> {
            drawPath(Path().apply { moveTo(s * 0.18f, s * 0.2f); lineTo(s * 0.64f, s / 2); lineTo(s * 0.18f, s * 0.8f); close() }, color)
            drawRoundRect(color, Offset(s * 0.68f, s * 0.2f), Size(s * 0.13f, s * 0.6f), CornerRadius(s * 0.04f))
        }
        "remote" -> {
            drawRoundRect(color, Offset(s * 0.32f, s * 0.08f), Size(s * 0.36f, s * 0.84f), CornerRadius(s * 0.12f), style = st)
            drawCircle(color, s * 0.08f, Offset(s / 2, s * 0.3f), style = Stroke(st.width * 0.8f))
            drawCircle(color, s * 0.035f, Offset(s / 2, s * 0.52f))
            drawCircle(color, s * 0.035f, Offset(s / 2, s * 0.66f))
        }
        "tv" -> {
            drawRoundRect(color, Offset(s * 0.1f, s * 0.2f), Size(s * 0.8f, s * 0.52f), CornerRadius(s * 0.08f), style = st)
            drawLine(color, Offset(s * 0.35f, s * 0.84f), Offset(s * 0.65f, s * 0.84f), st.width, StrokeCap.Round)
        }
    }
}

/** Moon disc with the lit part for [phase] (0 new .. 0.5 full .. 1 new), lit side right while waxing. */
@Composable
fun MoonIcon(phase: Double, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(Color.White.copy(alpha = 0.14f), r, c)
        val p = ((phase % 1.0) + 1.0) % 1.0
        val waxing = p < 0.5
        // Terminator ellipse half-width: +r at new, 0 at quarter, -r at full.
        val k = kotlin.math.cos(2 * Math.PI * p).toFloat()
        val rect = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)
        val half = androidx.compose.ui.graphics.Path().apply {
            // Lit half: right half while waxing, left half while waning.
            arcTo(rect, -90f, if (waxing) 180f else -180f, true); close()
        }
        val ell = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(c.x - r * kotlin.math.abs(k), c.y - r, c.x + r * kotlin.math.abs(k), c.y + r))
        }
        val lit = androidx.compose.ui.graphics.Path().apply {
            op(half, ell, if (k > 0) androidx.compose.ui.graphics.PathOperation.Difference else androidx.compose.ui.graphics.PathOperation.Union)
        }
        drawPath(lit, Color.White)
        drawCircle(Color.White.copy(alpha = 0.35f), r, c, style = Stroke(width = r * 0.06f))
    }
}


/** Clear max/min temperatures: filled arrows in warm/cool colours with bright numbers. */
@Composable
fun MinMax(max: Double, min: Double, sizeSp: Int, modifier: Modifier = Modifier) {
    val arrow = (sizeSp * 0.8f).dp
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TempArrow(up = true, color = WARM, size = arrow)
        androidx.compose.foundation.text.BasicText("${Math.round(max)}°", androidx.compose.ui.Modifier.padding(start = 4.dp),
            style = T.style(sizeSp, androidx.compose.ui.text.font.FontWeight.SemiBold))
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width((sizeSp * 0.8f).dp))
        TempArrow(up = false, color = COOL_BLUE, size = arrow)
        androidx.compose.foundation.text.BasicText("${Math.round(min)}°", androidx.compose.ui.Modifier.padding(start = 4.dp),
            style = T.style(sizeSp, androidx.compose.ui.text.font.FontWeight.SemiBold, Color.White.copy(alpha = 0.9f)))
    }
}

@Composable
private fun TempArrow(up: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.16f
        val (tip, tail) = if (up) s * 0.12f to s * 0.9f else s * 0.88f to s * 0.1f
        drawLine(color, Offset(s / 2, tail), Offset(s / 2, tip + (if (up) w else -w)), w, StrokeCap.Round)
        val head = Path().apply {
            moveTo(s / 2, tip)
            lineTo(s * 0.16f, tip + (if (up) s * 0.42f else -s * 0.42f))
            lineTo(s * 0.84f, tip + (if (up) s * 0.42f else -s * 0.42f))
            close()
        }
        drawPath(head, color)
    }
}


/** Weather icon for a WMO code, day/night aware: Lucide line icons (ISC), rendered white to PNG. */
@Composable
fun WxIcon(code: Int, day: Boolean, size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(WxCache.get(androidx.compose.ui.platform.LocalContext.current, wxRes(code, day)),
        androidx.compose.ui.res.stringResource(wxText(code)), modifier.size(size))
}

/** The weather in a word, for a screen reader (the icon is the only place it is shown). */
fun wxText(code: Int): Int = when (code) {
    0, 1 -> com.mytv.launcher.R.string.wx_d_clear
    2 -> com.mytv.launcher.R.string.wx_d_partly
    3 -> com.mytv.launcher.R.string.wx_d_overcast
    45, 48 -> com.mytv.launcher.R.string.wx_d_fog
    51, 53, 55, 56, 57 -> com.mytv.launcher.R.string.wx_d_drizzle
    71, 73, 75, 77, 85, 86 -> com.mytv.launcher.R.string.wx_d_snow
    95, 96, 99 -> com.mytv.launcher.R.string.wx_d_thunder
    else -> com.mytv.launcher.R.string.wx_d_rain
}

fun wxRes(code: Int, day: Boolean): Int =
    when (code) {
        0, 1 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        2 -> if (day) R.drawable.wx_partly_cloudy_day else R.drawable.wx_partly_cloudy_night
        3 -> R.drawable.wx_overcast
        45, 48 -> if (day) R.drawable.wx_fog_day else R.drawable.wx_fog_night
        51, 53, 55, 56, 57 -> R.drawable.wx_drizzle
        61, 63, 65, 66, 67 -> R.drawable.wx_rain
        80, 81, 82 -> if (day) R.drawable.wx_partly_cloudy_day_rain else R.drawable.wx_partly_cloudy_night_rain
        71, 73, 75, 77 -> R.drawable.wx_snow
        85, 86 -> if (day) R.drawable.wx_partly_cloudy_day_snow else R.drawable.wx_partly_cloudy_night_snow
        95, 96, 99 -> if (day) R.drawable.wx_thunderstorms_day else R.drawable.wx_thunderstorms_night
        else -> R.drawable.wx_cloudy
    }

/** Moon phase in the same white line style as the weather icons (drawn; Lucide has no moon phases). */
@Composable
fun MoonPhaseIcon(phase: Double, size: Dp, modifier: Modifier = Modifier) = MoonIcon(phase, size * 0.82f, modifier)

/** Small detail icon (Lucide) by name: sunrise, wind, humidity, raindrops. */
@Composable
fun WxDetailIcon(name: String, size: Dp, modifier: Modifier = Modifier) {
    val id = when (name) {
        "sunrise" -> R.drawable.wx_sunrise
        "wind" -> R.drawable.wx_wind; "humidity" -> R.drawable.wx_humidity
        else -> R.drawable.wx_raindrops
    }
    androidx.compose.foundation.Image(WxCache.get(androidx.compose.ui.platform.LocalContext.current, id), null, modifier.size(size))
}

/** Min (top) and max (bottom) stacked, for next to the big temperature. */
@Composable
fun MinMaxStack(max: Double, min: Double, sizeSp: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier) {
        MinMaxLine(false, min, sizeSp)
        MinMaxLine(true, max, sizeSp)
    }
}

@Composable
private fun MinMaxLine(up: Boolean, v: Double, sizeSp: Int) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TempArrow(up = up, color = if (up) WARM else COOL_BLUE, size = (sizeSp * 0.85f).dp)
        androidx.compose.foundation.text.BasicText("${Math.round(v)}°", androidx.compose.ui.Modifier.padding(start = 6.dp),
            style = T.style(sizeSp, androidx.compose.ui.text.font.FontWeight.SemiBold, if (up) Color.White else Color.White.copy(alpha = 0.9f)))
    }
}

/** The one "selected / on" marker used everywhere: a green circle with a white check, outlined (white ring with a thin
 *  dark edge) so it stays visible on dark glass, bright photos and white focused rows alike. */
@Composable
fun SelectedBadge(on: Boolean, modifier: Modifier = Modifier, showEmpty: Boolean = false, focused: Boolean = false, size: Dp = 28.dp) {
    if (!on && !showEmpty) return
    androidx.compose.foundation.layout.Box(
        modifier.size(size).drawBehind {
            val r = this.size.minDimension / 2f
            if (on) {
                drawCircle(Color.Black.copy(alpha = 0.45f), r)                 // thin dark outer edge
                drawCircle(Color.White, r - 1.2.dp.toPx())                      // white ring
                drawCircle(OK_GREEN, r - 3.2.dp.toPx())               // green fill
            } else {
                drawCircle(if (focused) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f), r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { if (on) LineIcon("check", size * 0.56f, color = Color.White) }
}


/**
 * Weather icons decoded once (in the background at app start) and kept in memory, so opening the forecast does
 * not decode ~20 PNGs on the UI thread every time.
 */
object WxCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>()

    fun get(ctx: android.content.Context, id: Int): androidx.compose.ui.graphics.ImageBitmap =
        cache.getOrPut(id) { android.graphics.BitmapFactory.decodeResource(ctx.resources, id).asImageBitmap() }

    fun preload(ctx: android.content.Context) {
        listOf(R.drawable.wx_clear_day, R.drawable.wx_clear_night, R.drawable.wx_cloudy, R.drawable.wx_drizzle, R.drawable.wx_fog_day,
            R.drawable.wx_fog_night, R.drawable.wx_humidity, R.drawable.wx_overcast,
            R.drawable.wx_partly_cloudy_day, R.drawable.wx_partly_cloudy_day_rain, R.drawable.wx_partly_cloudy_day_snow,
            R.drawable.wx_partly_cloudy_night, R.drawable.wx_partly_cloudy_night_rain, R.drawable.wx_partly_cloudy_night_snow,
            R.drawable.wx_rain, R.drawable.wx_raindrops, R.drawable.wx_snow, R.drawable.wx_sunrise,
            R.drawable.wx_thunderstorms_day, R.drawable.wx_thunderstorms_night, R.drawable.wx_wind,
        ).forEach { id -> runCatching { get(ctx, id) } }
    }
}
