// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.mytv.launcher.data.BgImage

/** What glass panels sample from: current + previous background and the cross-fade progress. */
class BgRender {
    var current by mutableStateOf<BgImage?>(null)
    var previous by mutableStateOf<BgImage?>(null)
    var fade by mutableFloatStateOf(1f)          // 0 = previous, 1 = current
    var rootSize by mutableStateOf(IntSize(1920, 1080))
    var blur by mutableStateOf(true)

    /** Brightness of the current photo under a rectangle in root coordinates (0 dark .. 1 bright). */
    fun brightness(r: Rect): Float =
        current?.brightness(r.left, r.top, r.right, r.bottom, rootSize.width, rootSize.height) ?: 0.3f
}

val LocalBg = staticCompositionLocalOf { BgRender() }

/** Brightness (0..1) of the photo under this element, plus the modifier that measures it. */
@Composable
fun rememberBrightness(): Pair<Modifier, Float> {
    val bg = LocalBg.current
    var rect by remember { mutableStateOf(Rect.Zero) }
    val lum = if (rect == Rect.Zero) 0.3f else bg.brightness(rect)
    val anim by animateFloatAsState(lum, tween(1200), label = "lum")   // with the background cross-fade
    return Modifier.onGloballyPositioned { rect = it.boundsInRoot() } to anim
}

private val WIDE_HALO = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 4f), 32f)
private val TIGHT_HALO = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 0f), 6f)

/**
 * White text on the photo, legible in all conditions: drawn twice, once with a wide soft
 * dark halo and once with a tight dark edge, on top of a scrim that darkens the area.
 */
@Composable
fun PhotoText(text: String, size: Int, weight: FontWeight, modifier: Modifier = Modifier,
              letterSpacing: Float = 0f, color: Color = Color.White, sizeSpExact: Float? = null, maxLines: Int = Int.MAX_VALUE) {
    val base = TextStyle(fontSize = (sizeSpExact ?: size.toFloat()).sp, fontWeight = weight, letterSpacing = letterSpacing.sp, color = color)
    val ov = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    Box(modifier) {
        androidx.compose.foundation.text.BasicText(text, style = base.copy(shadow = WIDE_HALO), maxLines = maxLines, overflow = ov)
        androidx.compose.foundation.text.BasicText(text, style = base.copy(shadow = TIGHT_HALO), maxLines = maxLines, overflow = ov)
    }
}

object T {
    val white = Color.White
    val dim = Color.White.copy(alpha = 0.78f)
    val faint = Color.White.copy(alpha = 0.55f)
    /** Text inside glass: white; the glass provides the contrast. */
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = white) =
        TextStyle(color = color, fontSize = size.sp, fontWeight = weight)
}

/**
 * Frosted glass: the pre-blurred photo, position-aligned under the panel, darkened by a neutral tint that gets
 * stronger over bright parts of the photo (so white text inside stays readable), a faint top sheen and a
 * top-lit hairline. Without blur (lightweight mode) a solid dark tint is used.
 */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    strength: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val bg = LocalBg.current
    var rect by remember { mutableStateOf(Rect.Zero) }
    val lum = if (rect == Rect.Zero) 0.3f else bg.brightness(rect)
    // ~0.30 on dark photos .. ~0.75 on very bright ones.
    val target = (0.30f + 0.45f * smooth((lum - 0.12f) / 0.6f)) * strength
    // As long as the background's cross-fade (1.2 s), so the tint changes with the photo, not ahead of it.
    val shade by animateFloatAsState(target.coerceIn(0f, 0.85f), tween(1200), label = "glass")
    Box(
        modifier
            .onGloballyPositioned { rect = it.boundsInRoot() }
            // Outline clip on the layer: handled by the GPU, far cheaper than a per-frame path clip.
            .clip(RoundedCornerShape(radius))
            .drawBehind { drawGlass(bg, Offset(rect.left, rect.top), radius.toPx(), shade, strength) },
        content = content,
    )
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

private val GLASS_TINT = Color(0xFF12151B)

// Colours used in more than one place.
/** Dark text and icons on a white (focused) surface. */
internal val INK_ON_WHITE = Color(0xFF14171B)
/** A warning or a problem (amber). */
internal val WARN = Color(0xFFFFD58A)
/** Done, connected, new (green). */
internal val OK_GREEN = Color(0xFF30D158)
/** Behind an app picture or poster while it loads, and around letter tiles. */
internal val TILE_DARK = Color(0xFF1C1F24)
/** Cold: minimum temperature, rain. */
internal val COOL_BLUE = Color(0xFF8CCBFF)
/** Warm: maximum temperature. */
internal val WARM = Color(0xFFFFC56B)

fun DrawScope.drawGlass(bg: BgRender, origin: Offset, r: Float, shade: Float, strength: Float = 1f) {
    if (bg.blur) {
        val dst = IntSize(bg.rootSize.width, bg.rootSize.height)
        val off = IntOffset(-origin.x.toInt(), -origin.y.toInt())
        // During the background's cross-fade the glass fades along: the previous photo, with the new one over it
        // (two draws per panel for those 1.2 s only; one otherwise).
        val prev = bg.previous?.takeIf { bg.fade < 1f }
        prev?.let { drawImage(it.blurred, dstOffset = off, dstSize = dst, filterQuality = FilterQuality.Low) }
        bg.current?.let { drawImage(it.blurred, dstOffset = off, dstSize = dst, alpha = if (prev != null) bg.fade else 1f, filterQuality = FilterQuality.Low) }
        drawRect(GLASS_TINT.copy(alpha = shade))
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f))))
    } else {
        // Simple glass has no frosting to separate it from what is behind: panels (higher strength) are near-solid.
        drawRect(GLASS_TINT.copy(alpha = (0.70f + 0.15f * strength).coerceAtMost(0.96f)))
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.05f))),
        cornerRadius = CornerRadius(r, r), style = Stroke(width = 2.5f),
    )
}

/**
 * Remote-safe click: fires on key-up only when the matching key-down was received by this element,
 * so the OK press that opened an overlay never "clicks" the newly focused item. Long press (OK held
 * ~600 ms, see [OkPress]) or the Menu key triggers [onLongClick].
 */
@Composable
fun Modifier.tvClick(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val st = remember { OkPress() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val longClick by androidx.compose.runtime.rememberUpdatedState(onLongClick)
    val timer = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }   // not state: nothing to redraw
    // For a screen reader or switch access: this is a button, with its click and (where there is one) its menu.
    return this.semantics(mergeDescendants = true) {
        role = androidx.compose.ui.semantics.Role.Button
        onClick { onClick(); true }
        if (onLongClick != null) onLongClick { onLongClick(); true }
    }.onKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (e.key == Key.Menu && e.type == KeyEventType.KeyUp && onLongClick != null) { onLongClick(); return@onKeyEvent true }
        if (!ok) return@onKeyEvent false
        val n = e.nativeKeyEvent
        when (e.type) {
            KeyEventType.KeyDown -> {
                when (st.down(n.eventTime, n.repeatCount, n.isLongPress, onLongClick != null)) {
                    OkPress.Down.NEW -> {
                        timer[0]?.cancel()
                        // No repeats at all from this remote: the hold still counts after HOLD_MS.
                        if (onLongClick != null) timer[0] = scope.launch { kotlinx.coroutines.delay(OkPress.HOLD_MS); if (st.hold()) longClick?.invoke() }
                    }
                    OkPress.Down.HOLD -> { timer[0]?.cancel(); longClick?.invoke() }
                    OkPress.Down.NONE -> {}
                }
                true
            }
            KeyEventType.KeyUp -> {
                timer[0]?.cancel(); timer[0] = null
                val wasDown = st.isDown
                if (!wasDown) android.util.Log.i("MyTV", "key: OK released without its press here (ignored)")
                if (st.up()) onClick()
                true
            }
            else -> false
        }
    }
}

/** The OK key itself, across all elements: down since the last key-down, no key-up yet. */
internal class OkKey { var held = false; var lastDownAt = 0L }

/**
 * One OK press on one element: click on key-up, or a hold. A hold is recognised however the remote reports it:
 * Android's repeat mark, a second key-down without a key-up in between (some TVs do not mark repeats), or no repeats
 * at all (the caller's timer calls [hold]).
 *
 * The key is followed across elements ([OkKey]): a hold opens a menu while OK is still down, and the repeats that
 * follow arrive at the menu's first item. They are not a press there (it would act on release).
 */
internal class OkPress(private val key: OkKey = SHARED) {
    enum class Down { NEW, HOLD, NONE }
    var isDown = false; private set
    private var long = false
    private var downAt = 0L

    fun down(time: Long, repeatCount: Int, longPress: Boolean, canHold: Boolean): Down {
        // Still the same key-down: a marked repeat, or an unmarked one close after the previous key-down.
        val stillHeld = repeatCount > 0 || (key.held && time - key.lastDownAt < REPEAT_GAP_MS)
        key.held = true; key.lastDownAt = time
        if (!stillHeld) { isDown = true; long = false; downAt = time; return Down.NEW }
        if (!isDown) return Down.NONE               // pressed elsewhere (a menu opened under the held key)
        // An unmarked second key-down is a hold at once; a marked repeat once it is long enough.
        val holdNow = canHold && (repeatCount == 0 || longPress || time - downAt >= HOLD_MS)
        return if (holdNow && hold()) Down.HOLD else Down.NONE
    }

    /** True once per press: the moment it becomes a hold. */
    fun hold(): Boolean = (isDown && !long).also { if (it) long = true }

    /** True when the release is a click (pressed here, not held). */
    fun up(): Boolean { key.held = false; val click = isDown && !long; isDown = false; long = false; return click }

    companion object {
        const val HOLD_MS = 600L
        /** Repeats of a held key come every ~50 ms; a key-down further apart than this is a new press. */
        const val REPEAT_GAP_MS = 700L
        private val SHARED = OkKey()
    }
}
