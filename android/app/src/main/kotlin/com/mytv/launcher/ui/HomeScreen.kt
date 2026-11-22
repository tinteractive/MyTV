// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.mytv.launcher.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.launcher.HomeState
import com.mytv.launcher.Overlay
import com.mytv.launcher.data.AppEntry
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Registers a focus target under a stable key and reports focus changes. Put key handlers before this. */
/** False for an overlay that is shown underneath another one (e.g. All apps under an app menu). */
val LocalFocusable = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun Modifier.focusKey(state: HomeState, key: String, enabled: Boolean = true, onFocus: (Boolean) -> Unit = {}): Modifier {
    val allowed = enabled && LocalFocusable.current
    val fr = remember(key) { FocusRequester() }
    val has = remember(key) { booleanArrayOf(false) }
    DisposableEffect(key, fr) {
        state.focus[key] = fr
        onDispose {
            if (state.focus[key] === fr) state.focus.remove(key)
            // A home-screen item that goes away while it has focus (playback ended, weather too old, a card left the
            // row) would leave the remote pointing at nothing.
            if (has[0] && (key == "wx" || key.startsWith("np:") || key.startsWith("cw:"))) state.focusGone()
        }
    }
    return this.focusRequester(fr).onFocusChanged {
        has[0] = it.isFocused
        if (it.isFocused) state.noteFocus(key)
        onFocus(it.isFocused)
    }.focusable(allowed)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(state: HomeState) {
    // Every scrolling list scrolls only as far as needed (TV's default "pivot" scrolling makes items hop).
    val minimalScroll = remember { MinimalScroll(0f) }
    CompositionLocalProvider(LocalBg provides state.bg, LocalBringIntoViewSpec provides minimalScroll, LocalUnits provides state.units()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { state.bg.rootSize = it },
        ) {
            BackgroundLayer(state)
            // Night mode: the photo darker and a little warmer; fades in and out over a few seconds.
            LaunchedEffect(state.nightMode, state.weather.report) { while (true) { state.updateNight(); delay(60_000L) } }
            val night by animateFloatAsState(if (state.isNight) 1f else 0f, tween(4000), label = "night")
            if (night > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = night }
                .background(Color(0xFF1A0E00).copy(alpha = 0.48f)))
            val (clockProbe, clockLum) = rememberBrightness()
            // Scrims: dark at the top for the clock (stronger over bright photos, and tall enough to
            // cover the date), much darker at the bottom for the dock.
            val topA = 0.45f + 0.25f * ((clockLum - 0.2f) / 0.6f).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = topA), 0.26f to Color.Black.copy(alpha = topA * 0.55f),
                        0.48f to Color.Transparent, 0.58f to Color.Transparent,
                        0.78f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.80f),
                    ),
                ),
            )
            val main = state.overlays.isEmpty()
            // Burn-in protection: the whole UI (not the photo) walks a small circle of at most 6 px, one step every
            // two minutes, and dims a little after ten idle minutes. Too small and too slow to notice.
            val shift by produceState(androidx.compose.ui.unit.IntOffset.Zero, state.pixelShift) {
                var k = 0
                while (state.pixelShift) {
                    val ang = Math.toRadians(k * 22.5); val r = if (k % 2 == 0) 4.0 else 6.0
                    value = androidx.compose.ui.unit.IntOffset((r * Math.cos(ang)).toInt(), (r * Math.sin(ang)).toInt())
                    k = (k + 1) % 16
                    delay(120_000L)
                }
                value = androidx.compose.ui.unit.IntOffset.Zero
            }
            LaunchedEffect(state.pixelShift) { while (state.pixelShift) { delay(30_000L); state.checkIdle() } }
            val uiAlpha by animateFloatAsState(if (state.idle && main) 0.55f else 1f, tween(if (state.idle) 4000 else 300), label = "dim")
            val burn = Modifier.offset { shift }.graphicsLayer { alpha = uiAlpha }
            // How far the clock reaches in from its own side (the left; the right in right-to-left languages).
            var clockRight by remember { mutableFloatStateOf(0f) }
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            ClockBlock(state, Modifier.align(Alignment.TopStart).then(burn).padding(start = 64.dp, top = 36.dp)
                .onGloballyPositioned { val r = it.boundsInRoot(); clockRight = if (rtl) state.bg.rootSize.width - r.left else r.right }, clockProbe)
            // Never run into the clock: what is left of the screen width, minus a gap.
            val room = with(LocalDensity.current) { (state.bg.rootSize.width - clockRight).toDp() - 48.dp - 40.dp }
            TopRight(state, main, Modifier.align(Alignment.TopEnd).then(burn).padding(end = 48.dp, top = 44.dp)
                .then(if (clockRight > 0f && state.bg.rootSize.width > 0) Modifier.widthIn(max = room) else Modifier))
            // The dock (Continue watching is on the right, under the weather).
            Column(Modifier.align(Alignment.BottomCenter).then(burn).padding(bottom = 30.dp), horizontalAlignment = Alignment.Start) {
                Dock(state, main, Modifier)
            }
            // An automatic check found a new version: show it once, when nothing else is open.
            val upd = com.mytv.launcher.data.Updates.ui
            val ctxU = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(upd.available, main) {
                if (upd.available && main && com.mytv.launcher.data.Updates.shouldPrompt(ctxU)) state.open(Overlay.Update, null)
            }
            OverlayHost(state)
            // One invisible build of the heavier pages shortly after start (see WarmUpSheets).
            var warm by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(2500)
                if (state.overlays.isEmpty()) {
                    warm = true
                    repeat(2) { withFrameNanos { } }
                    warm = false
                }
            }
            if (warm) WarmUpSheets(state)
        }
    }

    BackHandler(enabled = true) {
        when {
            state.moving != null -> state.moving = null
            state.backHook?.invoke() == true -> Unit
            state.overlays.isNotEmpty() -> state.close()
        }
    }

    LaunchedEffect(state.pendingFocus) {
        val k = state.pendingFocus ?: return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            val fr = state.focus[k]
            if (fr != null) {
                runCatching { fr.requestFocus() }
                state.pendingFocus = null
                return@LaunchedEffect
            }
        }
        // Target vanished (e.g. app hidden): fall back to the first dock item.
        state.focus["app:" + state.visibleApps.firstOrNull()?.key]?.let { runCatching { it.requestFocus() } }
        state.pendingFocus = null
    }
}

@Composable
private fun BackgroundLayer(state: HomeState) {
    val bg = state.bg
    Canvas(Modifier.fillMaxSize()) {
        val dst = IntSize(size.width.toInt(), size.height.toInt())
        fun draw(i: com.mytv.launcher.data.BgImage, alpha: Float) =
            drawImage(i.full, i.src.topLeft, i.src.size, dstSize = dst, alpha = alpha, filterQuality = FilterQuality.Medium)
        bg.previous?.let { if (bg.fade < 1f) draw(it, 1f) }
        bg.current?.let { draw(it, bg.fade) }
    }
}

@Composable
private fun ClockBlock(state: HomeState, modifier: Modifier, probe: Modifier) {
    val now by produceState(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) {
        while (true) {
            value = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
            delay(1000L - System.currentTimeMillis() % 1000L + 20L)
        }
    }
    val (time, ampm) = LocalUnits.current.clock(now)
    // Dutch writes day and month names in lower case.
    val date = now.format(datePattern("EEEEdMMMM"))
    if (state.compactClock) {
        Row(modifier.then(probe), verticalAlignment = Alignment.Bottom) {
            PhotoText(time + (ampm?.let { " $it" } ?: ""), 66, FontWeight.Medium, letterSpacing = -1f)
            Spacer(Modifier.width(20.dp))
            PhotoText(date, 24, FontWeight.Medium, Modifier.padding(bottom = 12.dp))
        }
    } else {
        Column(modifier) {
            Row(verticalAlignment = Alignment.Bottom) {
                PhotoText(time, 128, FontWeight.Medium, probe, letterSpacing = -3f)
                if (ampm != null) PhotoText(ampm, 30, FontWeight.Medium, Modifier.padding(start = 10.dp, bottom = 34.dp))
            }
            // Pulled up towards the time (the big time's line box has generous space below the digits).
            Box(Modifier.offset(y = (-16).dp)) { MatchedDate(time, date, timeSp = 128f, timeLetterSpacingSp = -3f) }
        }
    }
}

@Composable
private fun TopRight(state: HomeState, enabled: Boolean, modifier: Modifier) {
    var rowWidth by remember { mutableIntStateOf(0) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(Modifier.onSizeChanged { rowWidth = it.width }, verticalAlignment = Alignment.CenterVertically) {
            // No usable weather: no widget at all, until a fetch succeeds again. Without a place: the way to choose one.
            if (state.city == null || state.weather.usable()) WeatherWidget(state, enabled, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canSwitch) ProfileButton(state, enabled)
                // Two buttons of your choice; the settings button always stays.
                listOf(1 to state.button1, 2 to state.button2).forEach { (slot, v) -> SlotButton(state, slot, v, enabled) }
                if (!state.isChild) Box {
                    RoundButton(state, "btn:settings", "gear", stringResource(R.string.settings), enabled) { state.open(Overlay.Settings, "btn:settings") }
                    // A small dot when an update is ready (only after a check found one).
                    if (com.mytv.launcher.data.Updates.ui.available) Box(
                        Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(OK_GREEN).border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
        // Same left and right edge as the weather + buttons row above.
        val w = with(LocalDensity.current) { rowWidth.toDp() }
        state.nowPlaying?.let { if (rowWidth > 0) NowPlayingCard(state, it, enabled, Modifier.width(w)) }
        if (state.watchItems.isNotEmpty() && rowWidth > 0) ContinuePanel(state, enabled, Modifier.width(w))
        // Right after an update: "Updated to …" once, for a few seconds.
        val updated = com.mytv.launcher.data.Updates.ui.justUpdated
        if (updated.isNotEmpty()) {
            val ctxN = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(updated) { kotlinx.coroutines.delay(8000); com.mytv.launcher.data.Updates.announced(ctxN) }
            Glass(Modifier.padding(top = 16.dp), radius = 20.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon("refresh", 20.dp, color = OK_GREEN)
                    Spacer(Modifier.width(10.dp))
                    BasicText(stringResource(R.string.upd_done, updated), style = T.style(15, FontWeight.Medium))
                }
            }
        }
        // A Bluetooth device almost empty: a short notice, then it goes away by itself.
        state.batteryNotice?.let { (name, level) ->
            Glass(Modifier.padding(top = 16.dp), radius = 20.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon("bluetooth", 20.dp, color = WARN)
                    Spacer(Modifier.width(10.dp))
                    BasicText(stringResource(R.string.bt_low, name, level), style = T.style(15, FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun WeatherWidget(state: HomeState, enabled: Boolean, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f) 1.05f else 1f, tween(160), label = "w")
    val c = state.city
    // One line, same height as the round buttons next to it (56 dp).
    Glass(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .tvClick { if (c == null) { state.open(Overlay.Settings, "wx"); state.settingsSection = "weather"; state.pendingFocus = "wx:field" } else state.open(Overlay.Weather, "wx") }
            .focusKey(state, "wx", enabled) { f = it }
            .focusRing(f, 28)
            .height(56.dp),
        radius = 28.dp,
    ) {
        Row(Modifier.fillMaxHeight().padding(start = 18.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            val ws = state.weather
            val r = ws.report
            when {
                c == null -> {
                    LineIcon("search", 24.dp)
                    Spacer(Modifier.width(10.dp))
                    BasicText(stringResource(R.string.wx_choose_location), style = T.style(17, FontWeight.Medium))
                }
                r != null -> {
                    WxIcon(r.code, r.isDay, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    BasicText("${LocalUnits.current.temp(r.temp)}°", style = T.style(26, FontWeight.SemiBold))
                    val today = r.days.firstOrNull()
                    if (ws.isStale() || ws.error != null) {
                        val age = ws.ageMinutes()
                        Spacer(Modifier.width(14.dp))
                        val ageText = when { age == null -> ""; age < 90 -> stringResource(R.string.wx_age_min, age.toInt()); else -> stringResource(R.string.wx_age_hours, (age / 60).toInt()) }
                        BasicText(((if (ws.error != null) stringResource(R.string.wx_offline_prefix) else "") + ageText).trimEnd(' ', '·'),
                            style = T.style(14, FontWeight.Medium, WARN))
                    } else if (today != null) {
                        Spacer(Modifier.width(14.dp))
                        MinMax(LocalUnits.current.temp(today.max).toDouble(), LocalUnits.current.temp(today.min).toDouble(), 16)
                    }
                }
            }
        }
    }
}


fun Modifier.focusRing(focused: Boolean, radiusDp: Int): Modifier =
    if (focused) this.border(2.5.dp, Color.White, RoundedCornerShape(radiusDp.dp)) else this

/** Scroll only as far as needed (plus a margin for the faded edge); the TV default "pivot" scroll caused jitter. */
@OptIn(ExperimentalFoundationApi::class)
internal class MinimalScroll(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            size >= containerSize -> 0f
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(state: HomeState, enabled: Boolean, modifier: Modifier) {
    val apps = state.visibleApps
    var dockFocus by remember { mutableStateOf<String?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Only shown while moving an app; otherwise the dock carries no labels.
        if (state.moving != null) {
            Glass(Modifier.padding(bottom = 12.dp), radius = 18.dp, strength = 1.4f) {
                BasicText(stringResource(R.string.move_hint), Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = T.style(15, FontWeight.Medium))
            }
        }
        Box(Modifier.widthIn(max = 900.dp)) {
            // Fixed tile size; with fewer apps the dock simply gets narrower (centred). More than fits: it scrolls.
            val tileW = 92.dp
            val tileH = tileW * 3f / 4f
            Glass(radius = 30.dp, strength = 0.9f) {
                val scroll = rememberScrollState()
                val fade = with(LocalDensity.current) { 36.dp.toPx() }
                CompositionLocalProvider(LocalBringIntoViewSpec provides remember(fade) { MinimalScroll(fade) }) {
                    Row(
                        Modifier
                            // Nothing lies below the dock. Without this, Down could pick a neighbouring tile: the
                            // focused tile is lifted and enlarged, so its neighbours count as "below" it (holding
                            // Down then flipped between two tiles).
                            .onPreviewKeyEvent { it.key == Key.DirectionDown }
                            // Coming back into the dock from above: the tile you left, not the one nearest to where
                            // the remote was (Down from the weather landed on whatever app was under it).
                            .focusProperties {
                                onEnter = {
                                    if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down)
                                        state.lastDockKey?.let { k -> state.focus[k] }?.let { runCatching { it.requestFocus() } }
                                }
                            }
                            .focusGroup()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                val fw = 36.dp.toPx()
                                if (scroll.canScrollBackward) drawRect(
                                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fw),
                                    size = Size(fw, size.height), blendMode = BlendMode.DstIn,
                                )
                                if (scroll.canScrollForward) drawRect(
                                    Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fw, endX = size.width),
                                    topLeft = Offset(size.width - fw, 0f), size = Size(fw, size.height), blendMode = BlendMode.DstIn,
                                )
                            }
                            .horizontalScroll(scroll)
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        apps.forEach { app ->
                            // Stable identity per app: focus and animation follow the app when it moves.
                            key(app.key) {
                                AppTile(state, app, enabled, tileW, tileH, dockFocus != null) { has ->
                                    if (has) dockFocus = app.key else if (dockFocus == app.key) dockFocus = null
                                }
                            }
                        }
                        if (state.showOtherTile && state.moving == null) OtherAppsTile(state, enabled, tileW, tileH, dockFocus != null)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    state: HomeState, app: AppEntry, enabled: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp,
    dockFocused: Boolean, onFocus: (Boolean) -> Unit,
) {
    val moving = state.moving == app.key
    val fk = "app:${app.key}"
    // Right-to-left languages show the dock mirrored: the arrow still moves the app the way it points.
    val step = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
    // Moving keeps the focus on this tile, so the row would not scroll by itself: bring the tile into view after
    // every step (also to the very start or end of a long dock).
    val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val pos = state.visibleApps.indexOfFirst { it.key == app.key }
    LaunchedEffect(moving, pos) { if (moving) { androidx.compose.runtime.withFrameNanos { }; inView.bringIntoView() } }
    DockTile(state, fk, enabled, w, h, dockFocused, moving, TILE_DARK, onFocus = { onFocus(it); if (!it && moving) state.moving = null },
        keys = Modifier.bringIntoViewRequester(inView).onPreviewKeyEvent { e ->
                    if (!moving) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { if (e.type == KeyEventType.KeyDown) state.moveApp(app.key, -step); true }
                        Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) state.moveApp(app.key, step); true }
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (e.type == KeyEventType.KeyUp) state.moving = null; true }
                        else -> false
                    }
                },
        onLongClick = if (state.isChild) null else ({ state.open(Overlay.AppOptions(app.key), fk) }), onClick = { state.launch(app) },
    ) { AppArt(app, tall = true) }
}

/**
 * A dock tile: focus without a border; the focused tile lifts and grows, the others dim, and a bar marks it.
 * Used by the apps and by "Other apps", so they line up and focus the same way.
 */
@Composable
private fun DockTile(
    state: HomeState, fk: String, enabled: Boolean, w: Dp, h: Dp, dockFocused: Boolean, moving: Boolean, background: Color,
    onFocus: (Boolean) -> Unit = {}, keys: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val active = f || moving
    val scale by animateFloatAsState(if (moving) 1.2f else if (f) 1.14f else 1f, tween(180), label = "s")
    val lift by animateFloatAsState(if (moving) -12f else if (f) -5f else 0f, tween(180), label = "l")
    val dim by animateFloatAsState(if (dockFocused && !active) 0.22f else 0f, tween(180), label = "d")
    val bar by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "b")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .onGloballyPositioned { state.tileRects[fk] = it.boundsInRoot() }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift * density }
                .shadow(if (active) 18.dp else 0.dp, RoundedCornerShape(12.dp), clip = false)
                .then(keys)
                .tvClick(onLongClick, onClick)
                .focusKey(state, fk, enabled) { f = it; onFocus(it) }
                .size(w, h)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .drawWithContent { drawContent(); if (dim > 0f) drawRect(Color.Black.copy(alpha = dim)) },
            contentAlignment = Alignment.Center,
            content = content,
        )
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier.graphicsLayer { alpha = bar; scaleX = 0.4f + 0.6f * bar }
                .size(26.dp, 4.dp).clip(CircleShape).background(Color.White),
        )
    }
}

/** App artwork. With [tall], the banner pre-extended to 4:3 (see AppRepository.tallBanner) fills the dock tile. */
@Composable
fun AppArt(app: AppEntry, tall: Boolean = false) {
    // Chosen picture: the app icon on a dark tile, or the first letter on a colour.
    if (app.art == "icon" && app.icon != null) {
        Box(Modifier.fillMaxSize().background(TILE_DARK), contentAlignment = Alignment.Center) { Image(app.icon, app.label, Modifier.fillMaxSize(0.55f)) }
        return
    }
    if (app.art == "letter") {
        Box(Modifier.fillMaxSize().background(PROFILE_COLORS[Math.floorMod(app.origLabel.hashCode(), PROFILE_COLORS.size)]), contentAlignment = Alignment.Center) {
            BasicText(app.label.take(1).uppercase(), style = T.style(30, FontWeight.Bold, Color.White))
        }
        return
    }
    if (tall && app.bannerTall != null) {
        Image(app.bannerTall, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Medium)
        return
    }
    val b = app.banner
    if (b != null) {
        Image(b, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
    } else if (app.icon != null) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(app.icon, app.label, Modifier.size(34.dp))
            Spacer(Modifier.width(6.dp))
            BasicText(app.label, style = T.style(12), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        BasicText(app.label, style = T.style(12))
    }
}

/**
 * Round button: on glass at the top right (56 dp), or on the "now playing" card (44 dp, no glass of its own).
 * White when focused; shows an icon, or an app's own icon.
 */
@Composable
private fun RoundButton(
    state: HomeState, key: String, icon: String, label: String?, enabled: Boolean,
    size: Dp = 56.dp, image: ImageBitmap? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f) 1.1f else 1f, tween(160), label = "s")
    val glass = size >= 56.dp
    val m = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .tvClick(onLongClick, onClick)
        .focusKey(state, key, enabled) { f = it }
        .onGloballyPositioned { state.tileRects[key] = it.boundsInRoot() }
        .size(size)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
    val face: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(if (f) Color.White else if (glass) Color.Transparent else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) Image(image, null, Modifier.size(30.dp))
            else LineIcon(icon, if (glass) 26.dp else 18.dp, color = if (f) INK_ON_WHITE else Color.White)
        }
    }
    if (glass) Glass(m, radius = size / 2) { face() } else Box(m) { face() }
}


/**
 * The date, sized so its ink width is exactly the ink width of the time ("HH:mm") above it, and shifted so the
 * left ink edges line up. Measured with the same typeface (Roboto Medium) the text is drawn with.
 */
@Composable
private fun MatchedDate(time: String, date: String, timeSp: Float, timeLetterSpacingSp: Float) {
    val density = LocalDensity.current
    val (sizeSp, shiftPx) = remember(time, date, density) {
        val tf = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val pxPerSp = density.density * density.fontScale
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = timeSp * pxPerSp; letterSpacing = timeLetterSpacingSp / timeSp
        }
        val tb = android.graphics.Rect().also { tp.getTextBounds(time, 0, time.length, it) }
        val base = 28f
        val dp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = base * pxPerSp }
        val db = android.graphics.Rect().also { dp.getTextBounds(date, 0, date.length, it) }
        val scale = tb.width().toFloat() / db.width().coerceAtLeast(1)
        (base * scale) to (tb.left - db.left * scale)
    }
    PhotoText(date, 0, FontWeight.Medium, Modifier.offset { androidx.compose.ui.unit.IntOffset(shiftPx.toInt(), 0) }, sizeSpExact = sizeSp)
}


/**
 * "Continue watching": on the right under the weather (and Now playing), in the same glass, as compact lines: a small
 * picture, the title, the app or episode and how far. At most three (two while Now playing shows), so it stays clear of
 * the dock. OK continues; hold OK for the menu.
 */
@Composable
private fun ContinuePanel(state: HomeState, enabled: Boolean, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val max = if (state.nowPlaying != null) 2 else 3
    Glass(modifier.padding(top = 16.dp), radius = 20.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            BasicText(stringResource(R.string.continue_watching), Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp),
                style = T.style(14, FontWeight.SemiBold, T.dim))
            // Keyed by the item: when the row reorders (a card that was just watched moves to the top), each line keeps
            // its own state and focus instead of the slot handing them to another card (focus would jump to the dock).
            state.watchItems.take(max).forEach { item -> key(item.pkg, item.title) { ContinueLine(state, ctx, item, enabled) } }
        }
    }
}

@Composable
private fun ContinueLine(state: HomeState, ctx: android.content.Context, item: com.mytv.launcher.data.WatchItem, enabled: Boolean) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.03f else 1f, tween(140), label = "cw")
    // Right after switching on, pictures can be asked for before the network is up: try again a few times.
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.image) {
        val u = item.image ?: return@produceState
        for (wait in listOf(0L, 1_500L, 3_000L, 6_000L, 12_000L, 20_000L, 30_000L)) {
            kotlinx.coroutines.delay(wait)
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.WatchNext.image(ctx, u) }
            if (value != null) break
        }
    }
    // No picture: the app's own icon, so the line still says where it continues.
    val icon = state.apps.firstOrNull { it.component.packageName == item.pkg }?.icon
    val app = remember(item.pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(item.pkg, 0)).toString() }.getOrNull()
    }
    val id = "cw:${item.pkg}:${item.title}"
    val fg = if (f) INK_ON_WHITE else Color.White
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }
            .tvClick(onLongClick = { state.open(Overlay.WatchMenu(id), id) }) { state.openWatchItem(item) }
            .focusKey(state, id, enabled) { f = it }
            .onGloballyPositioned { state.tileRects[id] = it.boundsInRoot() }
            .clip(RoundedCornerShape(14.dp)).background(if (f) Color.White else Color.Transparent)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CW_W, CW_W * 9 / 16).clip(RoundedCornerShape(8.dp)).background(TILE_DARK)) {
            img?.let { Image(it, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium) }
                ?: icon?.let { Image(it, null, Modifier.align(Alignment.Center).size(28.dp), filterQuality = FilterQuality.Medium) }
            item.progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p).height(3.dp).background(Color(0xFFE50914)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(item.title, style = T.style(15, FontWeight.SemiBold, fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(item.subtitle, app).distinct().joinToString(" · ")
            if (sub.isNotEmpty()) BasicText(sub, style = T.style(12, color = if (f) INK_ON_WHITE.copy(alpha = 0.7f) else T.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The picture of a Continue watching line (16:9). */
private val CW_W = 80.dp



/** Sonos "now playing": only while a group plays or is paused; play/pause and next (volume stays on the remote). */
@Composable
private fun NowPlayingCard(state: HomeState, np: com.mytv.launcher.data.NowPlaying, enabled: Boolean, modifier: Modifier) {
    val art by produceState<androidx.compose.ui.graphics.ImageBitmap?>(np.image, np.art, np.image) {
        value = np.image ?: np.art?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Sonos.art(u) } }
    }
    // The card itself is the first stop (like the weather): OK opens the playing app, or the menu when there is no app.
    // Right goes on to play/pause and next. Hold OK / Menu anywhere on the card: open, hide or stop.
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f) 1.03f else 1f, tween(160), label = "np")
    val menu = { k: String -> { state.open(Overlay.NowPlayingMenu, k) } }
    val openLabel = stringResource(R.string.a11y_open_app)
    Glass(modifier.padding(top = 16.dp).graphicsLayer { scaleX = scale; scaleY = scale }.focusRing(f, 20).height(72.dp), radius = 20.dp) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Row(
            Modifier.weight(1f).fillMaxHeight()
                .tvClick(onLongClick = menu("np:card")) { if (np.pkg != null) state.openNowPlayingApp() else menu("np:card")() }
                .focusKey(state, "np:card", enabled) { f = it }
                .onGloballyPositioned { state.tileRects["np:card"] = it.boundsInRoot() }
                .then(if (np.pkg != null) Modifier.semantics { contentDescription = openLabel } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                val a = art
                if (a != null) Image(a, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
                else LineIcon("play", 20.dp, color = T.dim)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Too long for the card: glides back and forth a few times (only when it does not fit), then rests.
                key(np.title) {
                    BasicText(np.title, Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2000, repeatDelayMillis = 2500, velocity = 40.dp),
                        style = T.style(16, FontWeight.SemiBold), maxLines = 1)
                }
                // Picture, title and artist only (the play/pause button shows the state).
                np.artist?.let { a -> key(a) {
                    BasicText(a, Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2000, repeatDelayMillis = 2500, velocity = 40.dp),
                        style = T.style(13, color = T.dim), maxLines = 1)
                } }
            }
          }
            Spacer(Modifier.width(12.dp))
            RoundButton(state, "np:pp", if (np.playing) "pause" else "play",
                stringResource(if (np.playing) R.string.a11y_pause else R.string.a11y_play), enabled, size = 44.dp, onLongClick = menu("np:pp")) { state.sonos(if (np.playing) "Pause" else "Play") }
            Spacer(Modifier.width(8.dp))
            RoundButton(state, "np:next", "next", stringResource(R.string.a11y_next), enabled, size = 44.dp, onLongClick = menu("np:next")) { state.sonos("Next") }
        }
    }
}




internal val PROFILE_COLORS = listOf(Color(0xFF4F8EF7), Color(0xFFF2994A), Color(0xFF34C77B), Color(0xFFE06C9F), Color(0xFF9B7BF2), Color(0xFF2EC4C4))

/** Profile pictures to choose from: a symbol on its own colour. */
internal val AVATARS = listOf("star" to Color(0xFFF2B233), "heart" to Color(0xFFE0556F), "moon" to Color(0xFF5B6CF0), "bolt" to Color(0xFFF2994A),
    "smile" to Color(0xFF34C77B), "flower" to Color(0xFFD86CC9), "note" to Color(0xFF2EB6C4), "sun" to Color(0xFFE8C230),
    "crown" to Color(0xFFC9A227), "leaf" to Color(0xFF3FA34D), "paw" to Color(0xFF8B6B4A), "cloud" to Color(0xFF4A90D9), "planet" to Color(0xFF7E57C2))

/** Round avatar: the chosen symbol, or the first letter of the name. */
@Composable
internal fun Avatar(p: com.mytv.launcher.data.Profile, index: Int, size: androidx.compose.ui.unit.Dp) {
    val a = AVATARS.getOrNull(p.icon)
    Box(Modifier.size(size).clip(CircleShape).background(a?.second ?: PROFILE_COLORS[index % PROFILE_COLORS.size]), contentAlignment = Alignment.Center) {
        if (a != null) AvatarSymbol(a.first, size * 0.56f)
        else BasicText(p.name.take(1).uppercase(), style = T.style((size.value * 0.42f).toInt(), FontWeight.Bold, Color.White))
    }
}

@Composable
internal fun AvatarSymbol(kind: String, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension; val w = Color.White
        fun star(cx: Float, cy: Float, r: Float) = androidx.compose.ui.graphics.Path().apply {
            for (i in 0 until 10) {
                val rr = if (i % 2 == 0) r else r * 0.45f
                val ang = Math.toRadians(-90.0 + i * 36.0)
                val x = cx + (rr * Math.cos(ang)).toFloat(); val y = cy + (rr * Math.sin(ang)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        when (kind) {
            "star" -> drawPath(star(s / 2, s * 0.54f, s * 0.5f), w)
            "heart" -> drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(s / 2, s * 0.9f)
                cubicTo(s * 0.05f, s * 0.6f, s * 0.0f, s * 0.15f, s * 0.28f, s * 0.12f)
                cubicTo(s * 0.42f, s * 0.1f, s * 0.5f, s * 0.22f, s / 2, s * 0.28f)
                cubicTo(s * 0.5f, s * 0.22f, s * 0.58f, s * 0.1f, s * 0.72f, s * 0.12f)
                cubicTo(s * 1.0f, s * 0.15f, s * 0.95f, s * 0.6f, s / 2, s * 0.9f)
                close()
            }, w)
            "moon" -> drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference,
                androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f)) },
                androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(s * 0.32f, s * 0.0f, s * 1.1f, s * 0.78f)) }), w)
            "bolt" -> drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(s * 0.6f, 0f); lineTo(s * 0.15f, s * 0.58f); lineTo(s * 0.45f, s * 0.58f)
                lineTo(s * 0.35f, s); lineTo(s * 0.85f, s * 0.38f); lineTo(s * 0.55f, s * 0.38f); close()
            }, w)
            "smile" -> {
                drawCircle(w, s * 0.09f, Offset(s * 0.33f, s * 0.36f)); drawCircle(w, s * 0.09f, Offset(s * 0.67f, s * 0.36f))
                drawArc(w, 20f, 140f, false, Offset(s * 0.18f, s * 0.3f), Size(s * 0.64f, s * 0.52f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.1f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
            "flower" -> {
                for (i in 0 until 5) {
                    val ang = Math.toRadians(-90.0 + i * 72.0)
                    drawCircle(w, s * 0.2f, Offset(s / 2 + (s * 0.26f * Math.cos(ang)).toFloat(), s / 2 + (s * 0.26f * Math.sin(ang)).toFloat()))
                }
                drawCircle(Color(0x55000000), s * 0.13f, Offset(s / 2, s / 2))
            }
            "note" -> {
                drawOval(w, Offset(s * 0.12f, s * 0.62f), Size(s * 0.36f, s * 0.28f))
                drawRect(w, Offset(s * 0.4f, s * 0.12f), Size(s * 0.08f, s * 0.66f))
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(s * 0.48f, s * 0.12f); lineTo(s * 0.86f, s * 0.24f); lineTo(s * 0.86f, s * 0.38f); lineTo(s * 0.48f, s * 0.27f); close() }, w)
            }
            "crown" -> drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(s * 0.1f, s * 0.78f); lineTo(s * 0.1f, s * 0.3f); lineTo(s * 0.32f, s * 0.52f); lineTo(s * 0.5f, s * 0.2f)
                lineTo(s * 0.68f, s * 0.52f); lineTo(s * 0.9f, s * 0.3f); lineTo(s * 0.9f, s * 0.78f); close()
            }, w)
            "leaf" -> {
                drawPath(androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.15f, s * 0.85f); cubicTo(s * 0.1f, s * 0.35f, s * 0.45f, s * 0.1f, s * 0.9f, s * 0.1f)
                    cubicTo(s * 0.9f, s * 0.55f, s * 0.65f, s * 0.9f, s * 0.15f, s * 0.85f); close()
                }, w)
                drawLine(Color(0x55000000), Offset(s * 0.2f, s * 0.8f), Offset(s * 0.7f, s * 0.3f), s * 0.05f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "paw" -> {
                drawOval(w, Offset(s * 0.28f, s * 0.48f), Size(s * 0.44f, s * 0.38f))
                listOf(0.18f to 0.36f, 0.38f to 0.18f, 0.62f to 0.18f, 0.82f to 0.36f).forEach { (x, y) -> drawCircle(w, s * 0.1f, Offset(s * x, s * y)) }
            }
            "cloud" -> {
                drawCircle(w, s * 0.2f, Offset(s * 0.32f, s * 0.56f)); drawCircle(w, s * 0.26f, Offset(s * 0.55f, s * 0.44f))
                drawCircle(w, s * 0.18f, Offset(s * 0.76f, s * 0.6f))
                drawRect(w, Offset(s * 0.32f, s * 0.56f), Size(s * 0.44f, s * 0.2f))
            }
            "planet" -> {
                drawCircle(w, s * 0.26f, Offset(s / 2, s / 2))
                drawOval(w, Offset(s * 0.04f, s * 0.38f), Size(s * 0.92f, s * 0.24f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.07f))
            }
            else -> {
                drawCircle(w, s * 0.22f, Offset(s / 2, s / 2))
                for (i in 0 until 8) {
                    val ang = Math.toRadians(i * 45.0)
                    drawLine(w, Offset(s / 2 + (s * 0.33f * Math.cos(ang)).toFloat(), s / 2 + (s * 0.33f * Math.sin(ang)).toFloat()),
                        Offset(s / 2 + (s * 0.47f * Math.cos(ang)).toFloat(), s / 2 + (s * 0.47f * Math.sin(ang)).toFloat()), s * 0.08f,
                        androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ProfileButton(state: HomeState, enabled: Boolean) {
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f) 1.1f else 1f, tween(160), label = "pf")
    val i = state.profiles.indexOfFirst { it.id == state.profileId }.coerceAtLeast(0)
    val p = state.profiles.getOrNull(i) ?: return
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .tvClick { state.open(Overlay.Profiles(), "btn:profile") }
            .focusKey(state, "btn:profile", enabled) { f = it }
            .size(56.dp).clip(CircleShape).border(if (f) 3.dp else 0.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Avatar(p, i, 56.dp) }
}


/** A top-right button with the chosen function. */
@Composable
private fun SlotButton(state: HomeState, slot: Int, v: String, enabled: Boolean) {
    val key = "btn:s$slot"
    when {
        v == "none" -> return
        state.isChild && v in setOf("allapps", "android", "stock") -> return
        v == "allapps" -> RoundButton(state, key, "grid", stringResource(R.string.all_apps), enabled) { state.runButton(v, key) }
        v == "sources" -> {
            if (state.hasInputs) RoundButton(state, key, "input", stringResource(R.string.sources), enabled) { state.runButton(v, key) }
        }
        v == "android" -> RoundButton(state, key, "gear", stringResource(R.string.sys_android), enabled) { state.runButton(v, key) }
        v == "stock" -> RoundButton(state, key, "tv", stringResource(R.string.btn_google_tv), enabled) { state.runButton(v, key) }
        v == "bluetooth" -> RoundButton(state, key, "bluetooth", stringResource(R.string.bluetooth), enabled) { state.runButton(v, key) }
        v == "sleep" && !state.canSleep && !com.mytv.launcher.SleepTimer.active -> return
        v == "sleep" -> {
            @Suppress("UNUSED_VARIABLE") val t = state.sleepTick
            val label = if (com.mytv.launcher.SleepTimer.active) stringResource(R.string.sleep_left, com.mytv.launcher.SleepTimer.minutesLeft()) else stringResource(R.string.sleep_timer)
            Box {
                RoundButton(state, key, "timer", label, enabled) {
                    val left = com.mytv.launcher.SleepTimer.minutesLeft()
                    state.setSleep(listOf(15, 30, 60, 90, 120).firstOrNull { it > left } ?: 0)
                }
                if (com.mytv.launcher.SleepTimer.active) Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF2994A)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    BasicText("${com.mytv.launcher.SleepTimer.minutesLeft()}", style = T.style(12, FontWeight.Bold, Color.White))
                }
            }
        }
        v.startsWith("app:") -> {
            val app = state.apps.firstOrNull { it.key == v.removePrefix("app:") } ?: return
            RoundButton(state, key, "grid", app.label, enabled, image = app.icon) { state.runButton(v, key) }
        }
    }
}

/** Last dock tile: the apps that are not in the dock (and so "All apps" stays one click away). */
@Composable
private fun OtherAppsTile(state: HomeState, enabled: Boolean, w: Dp, h: Dp, dockFocused: Boolean) {
    DockTile(state, "app:__other", enabled, w, h, dockFocused, false, Color(0xFF2A2E35), onClick = { state.open(Overlay.AllApps, "app:__other") }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LineIcon("grid", 24.dp)
            BasicText(stringResource(R.string.other_apps), Modifier.padding(top = 4.dp), style = T.style(12, FontWeight.SemiBold), maxLines = 1)
        }
    }
}
