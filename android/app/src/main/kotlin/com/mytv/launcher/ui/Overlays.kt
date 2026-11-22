// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.mytv.launcher.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.mytv.launcher.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mytv.launcher.HomeState
import com.mytv.launcher.Overlay
import com.mytv.launcher.data.WeatherReport
import com.mytv.launcher.data.Wmo
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit


@Composable
fun OverlayHost(state: HomeState) {
    val top = state.top ?: return
    val under = state.overlays.getOrNull(state.overlays.size - 2)?.overlay
    if (under != null && (top is Overlay.AppOptions || under == Overlay.Welcome || under == Overlay.Settings)) {
        // A popup opened from a sheet, the welcome screen or Settings (an app menu, Restore, Plex, Bluetooth…): keep
        // what it came from visible (not focusable) underneath, not the home screen.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        androidx.compose.runtime.CompositionLocalProvider(LocalFocusable provides false) { OverlayContent(state, under) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
        OverlayContent(state, top)
        return
    }
    val light = top is Overlay.AppOptions || top is Overlay.NowPlayingMenu || top is Overlay.WatchMenu || top is Overlay.Sources || top is Overlay.Bluetooth || top is Overlay.Profiles && !top.ask
    if (top is Overlay.Profiles && top.ask) {
        // "Who is watching?" decides whose home screen comes: nothing of the last profile's (photo, dock, apps) shows.
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1C2330), Color(0xFF0B0E14)))))
    } else Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (light) 0.2f else 0.38f)))
    OverlayContent(state, top)
}

@Composable
private fun OverlayContent(state: HomeState, top: Overlay) {
    when (top) {
        Overlay.Weather -> WeatherSheet(state)
        Overlay.Settings -> SettingsScreen(state)
        Overlay.AllApps -> AllAppsSheet(state)
        Overlay.Sources -> SourcesSheet(state)
        Overlay.PlexLink -> PlexLinkSheet(state)
        is Overlay.PlexUser -> PlexUserSheet(state, top.profile)
        Overlay.OwnPhotos -> OwnPhotosSheet(state)
        is Overlay.Backup -> BackupSheet(state, top.restore)
        is Overlay.RestoreRights -> RestoreRightsSheet(state, org.json.JSONObject(top.rights))
        Overlay.OtherTvs -> OtherTvsSheet(state)
        Overlay.Welcome -> WelcomeScreen(state)
        is Overlay.Profiles -> ProfilesSheet(state, top.ask)
        Overlay.PinSetup -> PinSetupSheet(state)
        is Overlay.Rename -> RenameSheet(state, top.key)
        Overlay.Bluetooth -> BluetoothSheet(state)
        Overlay.Update -> UpdateSheet(state)
        is Overlay.AppOptions -> AppMenu(state, top.key, state.overlays.last().opener)
        Overlay.NowPlayingMenu -> NowPlayingMenu(state, state.overlays.last().opener)
        is Overlay.WatchMenu -> WatchMenu(state, top.id)
    }
}

/** First focus for a freshly opened sheet, unless focus is being restored to an opener. */
@Composable
internal fun InitialFocus(state: HomeState, key: String) {
    val focusable = LocalFocusable.current
    LaunchedEffect(Unit) { if (focusable && state.pendingFocus == null) state.pendingFocus = key }
}

@Composable
private fun Sheet(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null,
    outer: androidx.compose.ui.unit.Dp = 24.dp, inner: androidx.compose.ui.unit.Dp = 28.dp,
    trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(outer), contentAlignment = Alignment.Center) {
        Glass(modifier, radius = 34.dp, strength = 1.7f) {
            Column(Modifier.padding(horizontal = 36.dp, vertical = inner)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(title, style = T.style(28, FontWeight.Medium))
                        if (subtitle != null) BasicText(subtitle, Modifier.padding(top = 4.dp), style = T.style(14, color = T.dim))
                    }
                    trailing?.invoke()
                }
                Spacer(Modifier.height(20.dp))
                content()
            }
        }
    }
}

/** A focusable row/button used throughout the sheets: white when focused. */
@Composable
internal fun Pill(
    state: HomeState, key: String, label: String, modifier: Modifier = Modifier, value: String? = null,
    icon: String? = null, sub: String? = null, image: ImageBitmap? = null, selected: Boolean = false, check: Boolean? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.02f else 1f, tween(140), label = "p")
    Row(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .tvClick(onClick = onClick)
            .semantics {
                // What is only drawn (the tick, the chosen value) is said as well.
                if (selected) this.selected = true
                if (check != null) toggleableState = if (check) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
            }
            .focusKey(state, key) { f = it }
            .clip(RoundedCornerShape(16.dp))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.09f))
            // Every row the same height, with or without a subtitle.
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val fg = if (f) INK_ON_WHITE else Color.White
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
            else if (icon != null) { LineIcon(icon, 22.dp, color = fg); Spacer(Modifier.width(12.dp)) }
            if (image != null) {
                Image(image, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)), filterQuality = FilterQuality.Medium)
                Spacer(Modifier.width(14.dp))
            }
            Column {
                BasicText(label, style = T.style(17, FontWeight.Medium, fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) BasicText(sub, style = T.style(13, color = if (f) Color(0xFF4A515B) else T.dim), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(18.dp))
            BasicText(value, style = T.style(16, color = if (f) Color(0xFF3A4048) else T.dim), maxLines = 1)
        }
        if (selected) { Spacer(Modifier.width(14.dp)); SelectedBadge(true) }
        if (check != null) { Spacer(Modifier.width(14.dp)); SelectedBadge(check, showEmpty = true, focused = f) }
    }
}

// ---------------------------------------------------------------- weather
/** Today / tomorrow, then the weekday as the language writes it (lower case in nl/fr/es/pt, capitalised in en/de). */
@Composable
private fun dayName(i: Int, d: java.time.LocalDate) = when (i) {
    0 -> stringResource(R.string.today); 1 -> stringResource(R.string.tomorrow)
    else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, LOC).let { n -> if (LOC.language == "en" || LOC.language == "de") n.replaceFirstChar { it.titlecase(LOC) } else n }
}


@Composable
private fun WeatherSheet(state: HomeState) {
    // Timing: from OK on the weather pill to the first drawn frame of this page.
    val real = LocalFocusable.current
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        if (real) android.util.Log.i("MyTV", "forecast first frame ${android.os.SystemClock.uptimeMillis() - state.openedAt} ms")
    }
    val ws = state.weather
    val r = ws.report
    val c = state.city
    val units = LocalUnits.current
    val updated = r?.fetchedAt?.let { stringResource(R.string.wx_updated, units.time(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault()))) }
        ?: stringResource(R.string.wx_not_updated)
    val status = when {
        ws.loading -> stringResource(R.string.wx_updating)
        ws.error != null -> stringResource(R.string.wx_offline_suffix, updated)
        else -> updated
    }
    Sheet(
        stringResource(R.string.wx_title, c?.name ?: "—"), Modifier.width(900.dp),
        outer = 14.dp, inner = 22.dp,
        trailing = { BasicText(status, Modifier.padding(start = 16.dp), style = T.style(15, color = T.dim)) },
    ) {
        // The weather went out of date while this page was open: close it, no message.
        // Only the real, focused page closes itself: the invisible warm-up copy, or this page under a popup, would
        // otherwise close whatever sheet is on top.
        if (r == null || !ws.usable()) { LaunchedEffect(real) { if (real) state.close() }; return@Sheet }
        Row {
            CurrentColumn(r, state.moon, Modifier.width(330.dp))
            Spacer(Modifier.width(24.dp))
            Box(Modifier.width(1.dp).height(360.dp).background(Color.White.copy(alpha = 0.14f)))
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.wx_next_hours), first = true)
                HourlyRow(r)
                Spacer(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.wx_next_days), first = true)
                DailyList(r)
            }
        }
    }
}

@Composable
/** Heading inside a page; extra room above it when it follows an item ([first] = top of the page). */
internal fun SectionLabel(t: String, first: Boolean = false) =
    BasicText(t.uppercase(LOC), Modifier.padding(top = if (first) 0.dp else 18.dp, bottom = 8.dp), style = T.style(12, FontWeight.SemiBold, T.faint).copy(letterSpacing = 1.2.sp))

@Composable
private fun CurrentColumn(r: WeatherReport, moon: com.mytv.launcher.data.MoonInfo?, modifier: Modifier) {
    Column(modifier) {
        // Big icon, big temperature with "voelt als" under it, and min (top) / max (bottom) to its right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            WxIcon(r.code, r.isDay, 112.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                BasicText("${LocalUnits.current.temp(r.temp)}°", style = T.style(76, FontWeight.Medium).copy(letterSpacing = (-2).sp, lineHeight = 80.sp))
                BasicText(stringResource(R.string.feels_like, LocalUnits.current.temp(r.feelsLike)), style = T.style(16, color = T.dim))
            }
            val today = r.days.firstOrNull()
            if (today != null) MinMaxStack(LocalUnits.current.temp(today.max).toDouble(), LocalUnits.current.temp(today.min).toDouble(), 19, Modifier.padding(start = 16.dp, bottom = 18.dp))
        }
        Spacer(Modifier.height(16.dp))
        val today = r.days.firstOrNull()
        val units = LocalUnits.current
        val riseFmt = stringResource(R.string.rise_at, "%s"); val setFmt = stringResource(R.string.set_at, "%s")
        val upDown: (java.time.LocalDateTime?, java.time.LocalDateTime?) -> String? = { a, b ->
            listOfNotNull(a?.let { riseFmt.format(units.time(it)) }, b?.let { setFmt.format(units.time(it)) }).joinToString(" · ").ifBlank { null }
        }
        val dirs = stringArrayResource(R.array.wind_dirs)
        Detail("wind", stringResource(R.string.label_wind), r.windKmh?.let {
            stringResource(if (units.fahrenheit) R.string.wind_value_mph else R.string.wind_value, units.wind(it)) + (r.windDir?.let { d -> " " + dirs[Wmo.windIndex(d)] } ?: "")
        })
        Detail("humidity", stringResource(R.string.label_humidity), r.humidity?.let { "$it%" })
        Detail("raindrops", stringResource(R.string.label_precip), today?.precipSum?.let {
            stringResource(if (units.fahrenheit) R.string.precip_today_in else R.string.precip_today, units.precip(it))
        })
        Detail("sunrise", stringResource(R.string.label_sun), upDown(today?.sunrise, today?.sunset))
        if (moon != null) {
            // Phase, rise and set from Open-Meteo when available (local calculation is the offline fallback);
            // the next full moon is often beyond the forecast range, so that one is always calculated locally.
            val phase = today?.moonPhase ?: moon.phase
            val illum = (1 - kotlin.math.cos(2 * Math.PI * phase)) / 2
            val name = stringResource(com.mytv.launcher.data.MoonInfo(phase, illum, null, null, moon.nextFull, moon.nextNew).nameRes)
            Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { MoonPhaseIcon(phase, 24.dp) }
                Spacer(Modifier.width(10.dp))
                BasicText(stringResource(R.string.label_moon), Modifier.width(100.dp), style = T.style(14, color = T.dim))
                BasicText("$name · ${Math.round(illum * 100)}%", style = T.style(15, FontWeight.Medium))
            }
            Detail("blank", "", upDown(today?.moonrise ?: moon.rise, today?.moonset ?: moon.set))
            Detail("blank", "", stringResource(R.string.full_moon_on, moon.nextFull.format(datePattern("EEEEdMMM"))))
        }
    }
}

@Composable
private fun Detail(icon: String, label: String, value: String?) {
    if (value == null) return
    // Fixed row height: rows without an icon (moonrise, full moon) line up with the others.
    Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon == "blank") Spacer(Modifier.width(24.dp)) else WxDetailIcon(icon, 24.dp)
        Spacer(Modifier.width(10.dp))
        BasicText(label, Modifier.width(100.dp), style = T.style(14, color = T.dim))
        BasicText(value, style = T.style(15, FontWeight.Medium), maxLines = 1)
    }
}

@Composable
private fun HourlyRow(r: WeatherReport) {
    val nowHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
    val hours = r.hours.filter { !it.time.isBefore(nowHour) }.take(10)
    if (hours.isEmpty()) { BasicText(stringResource(R.string.no_hourly), style = T.style(14, color = T.dim)); return }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        hours.forEachIndexed { i, h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                BasicText(if (i == 0) stringResource(R.string.now) else LocalUnits.current.hour(h.time), style = T.style(14, FontWeight.Medium, if (i == 0) Color.White else T.dim))
                WxIcon(h.code, h.isDay, 34.dp, Modifier.padding(vertical = 1.dp))
                BasicText("${LocalUnits.current.temp(h.temp)}°", style = T.style(17, FontWeight.Medium))
                // Rain chance, always as a percentage; small chances dimmer so real rain stands out.
                val pp = h.precipProb ?: 0
                BasicText("$pp%", style = T.style(12, FontWeight.Medium, if (pp < 10) COOL_BLUE.copy(alpha = 0.55f) else COOL_BLUE))
            }
        }
    }
}

@Composable
private fun DailyList(r: WeatherReport) {
    val days = r.days.take(8)
    val lo = days.minOf { it.min }; val hi = days.maxOf { it.max }
    Column {
        days.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(dayName(i, d.date), Modifier.width(104.dp), style = T.style(17, FontWeight.Medium, if (i == 0) Color.White else Color.White.copy(alpha = 0.92f)))
                WxIcon(d.code, true, 30.dp)
                val dp = d.precipProb ?: 0
                BasicText("$dp%", Modifier.width(52.dp).padding(start = 10.dp), style = T.style(13, FontWeight.Medium, if (dp < 10) COOL_BLUE.copy(alpha = 0.55f) else COOL_BLUE))
                BasicText("${LocalUnits.current.temp(d.min)}°", Modifier.width(38.dp), style = T.style(16, FontWeight.Medium, COOL_BLUE))
                RangeBar(lo, hi, d.min, d.max, Modifier.weight(1f).height(8.dp))
                BasicText("${LocalUnits.current.temp(d.max)}°", Modifier.width(46.dp).padding(start = 12.dp), style = T.style(16, FontWeight.SemiBold, WARM))
            }
        }
    }
}

@Composable
private fun RangeBar(lo: Double, hi: Double, min: Double, max: Double, modifier: Modifier) {
    Canvas(modifier) {
        val span = (hi - lo).takeIf { it > 0.1 } ?: 1.0
        val r = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.14f), cornerRadius = CornerRadius(r, r))
        val x0 = ((min - lo) / span * size.width).toFloat(); val x1 = ((max - lo) / span * size.width).toFloat().coerceAtLeast(x0 + size.height)
        drawRoundRect(
            Brush.horizontalGradient(listOf(COOL_BLUE, Color(0xFFFFD27A)), startX = 0f, endX = size.width),
            topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = CornerRadius(r, r),
        )
    }
}

// ---------------------------------------------------------------- backgrounds
internal val ROTATE_OPTIONS = listOf(0, 10, 30, 60, 300, 600, 1800, 3600, 10800)
@Composable
internal fun rotateLabel(s: Int) = when {
    s <= 0 -> stringResource(R.string.rot_off); s < 60 -> stringResource(R.string.rot_sec, s); s < 3600 -> stringResource(R.string.rot_min, s / 60)
    s == 3600 -> stringResource(R.string.rot_hour); else -> stringResource(R.string.rot_hours, s / 3600)
}

// ---------------------------------------------------------------- apps
@Composable
private fun AllAppsSheet(state: HomeState) {
    InitialFocus(state, "all:${state.visibleApps.firstOrNull()?.key ?: state.apps.firstOrNull()?.key}")
    val hiddenApps = state.apps.filter { it.key in state.hidden }
    Sheet(stringResource(R.string.all_apps), Modifier.width(900.dp), subtitle = stringResource(R.string.all_apps_hint)) {
        // Fits the screen; scrolling keeps the focused tile *and its name* in view (name sits below the tile).
        val nameRoom = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(nameRoom) { MinimalScroll(nameRoom) },
        ) { Column(Modifier.heightIn(max = 320.dp).scrollWithBar()) {
            AppGrid(state, state.visibleApps)
            if (hiddenApps.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(Color.White.copy(alpha = 0.16f)))
                SectionLabel(stringResource(R.string.other_apps), first = true)
                AppGrid(state, hiddenApps)
            }
        } }
    }
}

@Composable
private fun AppGrid(state: HomeState, apps: List<com.mytv.launcher.data.AppEntry>) {
    FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        apps.forEach { app ->
            var f by remember { mutableStateOf(false) }
            val hid = app.key in state.hidden
            val sc by animateFloatAsState(if (f) 1.1f else 1f, tween(160), label = "a")
            val fk = "all:${app.key}"
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .onGloballyPositioned { state.tileRects[fk] = it.boundsInRoot() }
                        .graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (hid && !f) 0.55f else 1f }
                        .tvClick(onLongClick = { state.open(Overlay.AppOptions(app.key), fk) }) { state.launch(app) }
                        .focusKey(state, fk) { f = it }
                        .shadow(if (f) 16.dp else 0.dp, RoundedCornerShape(12.dp), clip = false)
                        .size(136.dp, 76.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TILE_DARK),
                ) { AppArt(app) }
                BasicText(app.label, Modifier.width(136.dp).padding(top = 6.dp),
                    style = T.style(13, color = if (f) Color.White else T.dim).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Places its content just above [anchor] (root px), centred on it and kept on screen; below it if there is no room. */
@Composable
private fun AnchoredAbove(anchor: Rect?, gapDp: Int = 22, content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { gapDp.dp.roundToPx() }
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    Layout(content, Modifier.fillMaxSize()) { measurables, c ->
        val p = measurables.first().measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, c.maxHeight) {
            val a = anchor ?: Rect(c.maxWidth / 2f, c.maxHeight / 2f, c.maxWidth / 2f, c.maxHeight / 2f)
            val x = (a.center.x - p.width / 2f).toInt().coerceIn(margin, (c.maxWidth - p.width - margin).coerceAtLeast(margin))
            val above = a.top.toInt() - gap - p.height
            val y = if (above >= margin) above else (a.bottom.toInt() + gap).coerceAtMost(c.maxHeight - p.height - margin)
            p.place(x, y)
        }
    }
}

@Composable
private fun AppMenu(state: HomeState, key: String, opener: String?) {
    val app = state.apps.firstOrNull { it.key == key } ?: run { state.close(); return }
    val hid = key in state.hidden
    InitialFocus(state, if (hid) "menu:show" else "menu:move")
    val pos = state.visibleApps.indexOfFirst { it.key == key } + 1
    CardMenu(state, opener, { AppArt(app) }, app.label,
        if (hid) stringResource(R.string.hidden) else stringResource(R.string.dock_position, pos, state.visibleApps.size), 360.dp) {
        if (hid) {
            MenuRow(state, "menu:show", "eye", stringResource(R.string.menu_show)) { state.setHidden(key, false); state.close() }
        } else {
            MenuRow(state, "menu:move", "move", stringResource(R.string.menu_move)) {
                state.closeAll("app:$key")
                state.moving = key
            }
            MenuRow(state, "menu:rename", "pencil", stringResource(R.string.menu_rename)) { state.close(); state.open(Overlay.Rename(key), opener) }
            MenuRow(state, "menu:art", "image", stringResource(R.string.menu_art, stringResource(when (app.art) {
                "icon" -> R.string.art_icon; "letter" -> R.string.art_letter; else -> R.string.art_banner }))) { state.nextAppArt(key) }
            MenuRow(state, "menu:hide", "eye-off", stringResource(R.string.menu_hide)) {
                val next = state.visibleApps.let { v -> val i = v.indexOfFirst { it.key == key }; v.getOrNull(i + 1) ?: v.getOrNull(i - 1) }
                state.setHidden(key, true)
                if (state.overlays.size > 1) state.close() else state.closeAll(next?.let { "app:${it.key}" } ?: "app:__other")
            }
        }
        MenuRow(state, "menu:info", "info", stringResource(R.string.menu_info)) { state.close(); state.openAppInfo(app) }
        if (!app.isSystem) MenuRow(state, "menu:del", "trash", stringResource(R.string.menu_uninstall)) { state.close(); state.uninstall(app) }
    }
}

/** Small menu for a card: picture, title, subtitle, then a few actions. */
@Composable
private fun CardMenu(state: HomeState, anchor: String?, picture: @Composable () -> Unit, title: String, sub: String?, width: Dp = 400.dp,
                     rows: @Composable ColumnScope.() -> Unit) {
    AnchoredAbove(anchor?.let { state.tileRects[it] }) {
        PopupGlass(width) {
            Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(10.dp)).background(TILE_DARK)) { picture() }
                Spacer(Modifier.width(14.dp))
                Column {
                    BasicText(title, style = T.style(19, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    sub?.let { BasicText(it, style = T.style(13, color = T.dim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = rows)
        }
    }
}

@Composable
private fun Cover(image: androidx.compose.ui.graphics.ImageBitmap?) { image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }

@Composable
private fun NowPlayingMenu(state: HomeState, opener: String?) {
    val np = state.nowPlaying ?: run { state.close(); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = np.pkg?.let { p -> remember(p) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() } }
    InitialFocus(state, if (app != null) "npm:open" else "npm:hide")
    CardMenu(state, opener, { Cover(np.image) }, np.title, listOfNotNull(np.artist, app ?: np.room.ifBlank { null }).joinToString(" · ").ifBlank { null }) {
        if (app != null) MenuRow(state, "npm:open", "right", stringResource(R.string.np_open, app)) { state.close(); state.openNowPlayingApp() }
        MenuRow(state, "npm:hide", "eye-off", stringResource(R.string.np_hide)) { state.close(); state.hideNowPlaying(stop = false) }
        if (np.playing) MenuRow(state, "npm:stop", "pause", stringResource(R.string.np_stop)) { state.close(); state.hideNowPlaying(stop = true) }
    }
}

@Composable
private fun WatchMenu(state: HomeState, id: String) {
    val item = state.watchItem(id) ?: run { state.close(); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    InitialFocus(state, "cwm:play")
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.image) {
        value = item.image?.let { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.WatchNext.image(ctx, u) } }
    }
    val app = remember(item.pkg) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(item.pkg, 0)).toString() }.getOrNull() }
    CardMenu(state, id, { Cover(img) }, item.title, listOfNotNull(item.subtitle, app).joinToString(" · ").ifBlank { null }) {
        MenuRow(state, "cwm:play", "play", stringResource(R.string.cw_play)) { state.close(); state.openWatchItem(item) }
        MenuRow(state, "cwm:remove", "trash", stringResource(R.string.cw_remove)) { state.close(); state.removeWatchItem(item) }
    }
}

/**
 * A row in the small popups (app and card menus, sources, Bluetooth, profiles): white when focused, a round picture
 * on the left, a title with an optional subtitle, and something optional at the end.
 */
@Composable
internal fun ListRow(
    state: HomeState, key: String, title: String, sub: String? = null, icon: String = "", circle: Dp = 40.dp,
    dim: Boolean = false, leading: (@Composable () -> Unit)? = null, end: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.03f else 1f, tween(140), label = "row")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (dim && !f) 0.55f else 1f }
            .tvClick(onClick = onClick)
            .focusKey(state, key) { f = it }
            .clip(RoundedCornerShape(20.dp)).background(if (f) Color.White else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (f) INK_ON_WHITE else Color.White
        if (leading != null) leading() else Box(
            Modifier.size(circle).clip(CircleShape).background(if (f) INK_ON_WHITE.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { LineIcon(icon, if (circle < 40.dp) 20.dp else 22.dp, color = fg) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = T.style(18, FontWeight.Medium, fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) BasicText(sub, style = T.style(13, color = if (f) Color(0xFF4A515B) else T.dim), maxLines = 1)
        }
        end?.invoke()
    }
}

/** A menu line: icon and label (app and card menus). */
@Composable
private fun MenuRow(state: HomeState, key: String, icon: String, label: String, onClick: () -> Unit) =
    ListRow(state, key, label, icon = icon, circle = 36.dp, onClick = onClick)

/** The glass of every small popup. */
@Composable
private fun PopupGlass(width: Dp, strength: Float = 1.55f, content: @Composable ColumnScope.() -> Unit) {
    Glass(Modifier.width(width), radius = 30.dp, strength = strength) { Column(Modifier.padding(12.dp), content = content) }
}

@Composable
private fun PopupTitle(text: String, size: Int = 20) =
    BasicText(text, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 10.dp), style = T.style(size, FontWeight.SemiBold))

/** The popups that open from the buttons top right (sources, Bluetooth, profiles). */
@Composable
private fun TopRightPopup(width: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(top = 112.dp, end = 48.dp), contentAlignment = Alignment.TopEnd) { PopupGlass(width, content = content) }
}

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(1.dp).background(Color.White.copy(alpha = 0.12f)))

/** Vertical scrolling with a slim scrollbar on the right whenever the content does not fit. */
@Composable
internal fun Modifier.scrollWithBar(state: androidx.compose.foundation.ScrollState? = null): Modifier {
    val st = state ?: rememberScrollState()
    return this
        .drawWithContent {
            drawContent()
            if (st.maxValue in 1 until Int.MAX_VALUE) {
                val h = size.height
                val thumb = (h * h / (h + st.maxValue)).coerceAtLeast(28.dp.toPx())
                val y = (h - thumb) * st.value / st.maxValue
                val w = 4.dp.toPx(); val x = size.width - w
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(x, 0f), Size(w, h), CornerRadius(w / 2))
                drawRoundRect(Color.White.copy(alpha = 0.75f), Offset(x, y), Size(w, thumb), CornerRadius(w / 2))
            }
        }
        .verticalScroll(st)
        .padding(end = 16.dp)
}


/**
 * Builds the forecast and All-apps pages once, invisibly and without focus, while the home screen is idle after
 * start. The first real opening then skips the one-time cost of preparing that UI code on the TV.
 */
@Composable
internal fun WarmUpSheets(state: HomeState) {
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusable provides false) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0f }) {
            WeatherSheet(state)
            AllAppsSheet(state)
        }
    }
}


@Composable
internal fun SourcesSheet(state: HomeState, toHome: (() -> Unit)? = null) {
    // A compact list right under the input button (top right), like the app menu: connected devices first,
    // empty ports dimmed below a hairline.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sources = remember { com.mytv.launcher.data.Sources.list(ctx) }
    InitialFocus(state, "src:" + (sources.firstOrNull()?.id ?: ""))
    TopRightPopup(400.dp) {
        PopupTitle(stringResource(R.string.sources))
        // Over another app or a source (see SourcesPopup): MyTV itself is one of the choices.
        if (toHome != null) {
            ListRow(state, "src:mytv", "MyTV", stringResource(R.string.sec_home), "tv") { toHome() }
            Hairline()
        }
        if (sources.isEmpty()) {
            BasicText(stringResource(R.string.no_sources), Modifier.padding(10.dp), style = T.style(15, color = T.dim)); return@TopRightPopup
        }
        // Philips reports empty HDMI ports as "standby": only a real connection (or live TV) counts as on.
        val (on, off) = sources.partition { it.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED || it.type == android.media.tv.TvInputInfo.TYPE_TUNER }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            on.forEach { SourceRow(state, ctx, it, true) }
            if (off.isNotEmpty()) {
                Hairline()
                off.forEach { SourceRow(state, ctx, it, false) }
            }
        }
    }
}

@Composable
private fun SourceRow(state: HomeState, ctx: android.content.Context, s: com.mytv.launcher.data.Source, active: Boolean) {
    val live = s.type == android.media.tv.TvInputInfo.TYPE_TUNER
    ListRow(state, "src:${s.id}", if (live) stringResource(R.string.src_live_tv) else s.name, s.port, if (live) "tv" else "input", dim = !active,
        // Green dot: something is connected and on.
        end = if (active && !live && s.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED) ({
            Box(Modifier.size(10.dp).clip(CircleShape).background(OK_GREEN))
        }) else null,
    ) { state.closeAll(null); com.mytv.launcher.data.Sources.open(ctx, s) }
}


/**
 * Linking Plex: a 4-character code to enter at plex.tv/link on a phone or computer (Plex's own TV sign-in). MyTV waits
 * for it (up to 15 minutes), then finds the Plex server on the home network. Once linked: the server, and unlinking.
 */
@Composable
private fun PlexLinkSheet(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = state.plexTick
    val linked = com.mytv.launcher.data.Plex.linked(ctx)
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("search") }    // search | wait | linked | noserver | error
    InitialFocus(state, "plex:done")
    // Linked: back to where Plex was chosen (the Plex row then shows the server, with a tick); no "Done" needed.
    fun done() { state.plexChanged(); if (state.top == com.mytv.launcher.Overlay.PlexLink) state.close() }
    if (!linked) LaunchedEffect(Unit) {
        // Linked before but no server then: look again first, without a new code.
        when (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Plex.retryServer(ctx) }) {
            com.mytv.launcher.data.Plex.Result.LINKED -> { status = "linked"; done(); return@LaunchedEffect }
            com.mytv.launcher.data.Plex.Result.NO_SERVER -> { status = "noserver"; return@LaunchedEffect }
            else -> {}
        }
        val pin = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Plex.newCode(ctx) }
        if (pin == null) { status = "error"; return@LaunchedEffect }
        code = pin.second; status = "wait"
        repeat(450) {                                      // 2 s apart: 15 minutes, as long as Plex keeps the code
            kotlinx.coroutines.delay(2_000L)
            when (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Plex.check(ctx, pin.first) }) {
                com.mytv.launcher.data.Plex.Result.LINKED -> { status = "linked"; done(); return@LaunchedEffect }
                com.mytv.launcher.data.Plex.Result.NO_SERVER -> { status = "noserver"; return@LaunchedEffect }
                else -> {}
            }
        }
        status = "error"
    }
    Sheet(stringResource(R.string.plex_link_title), Modifier.width(720.dp)) {
        if (linked) {
            BasicText(stringResource(R.string.plex_linked_text, com.mytv.launcher.data.Plex.serverName(ctx) ?: "Plex"), style = T.style(17, color = T.dim))
            Spacer(Modifier.height(20.dp))
            MenuRow(state, "plex:unlink", "eye-off", stringResource(R.string.plex_unlink)) { com.mytv.launcher.data.Plex.unlink(ctx); state.plexChanged(); state.close() }
        } else {
            // The code only once there is one; first MyTV looks for the server with an earlier link.
            code?.let { cd ->
                BasicText(stringResource(R.string.plex_link_text), style = T.style(17, color = T.dim))
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(cd, style = T.style(64, FontWeight.Bold).copy(letterSpacing = 12.sp))
                }
                Spacer(Modifier.height(16.dp))
            }
            BasicText(stringResource(when (status) {
                "noserver" -> R.string.plex_no_server; "error" -> R.string.plex_error; "search" -> R.string.plex_searching
                else -> R.string.plex_link_wait
            }), style = T.style(15, color = if (status == "wait" || status == "search") T.dim else WARN))
            // Linked with the account, but no server: start again with a new code if the viewer wants.
            if (status == "noserver") {
                Spacer(Modifier.height(12.dp))
                MenuRow(state, "plex:unlink", "eye-off", stringResource(R.string.plex_unlink)) { com.mytv.launcher.data.Plex.unlink(ctx); state.plexChanged(); state.close() }
            }
        }
        Spacer(Modifier.height(20.dp))
        MenuRow(state, "plex:done", "check", stringResource(R.string.wel_done)) { state.close() }
    }
}

/**
 * Which Plex user a MyTV profile shows Continue watching for: the linked account, or a user of the Plex Home (their
 * own libraries and progress; a PIN when Plex protects that user, entered with MyTV's own PIN pad).
 */
@Composable
private fun PlexUserSheet(state: HomeState, profile: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var users by remember { mutableStateOf<List<com.mytv.launcher.data.Plex.HomeUser>?>(null) }
    var asking by remember { mutableStateOf<com.mytv.launcher.data.Plex.HomeUser?>(null) }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { users = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Plex.homeUsers(ctx) } }
    InitialFocus(state, "pu:account")
    fun use(u: com.mytv.launcher.data.Plex.HomeUser, pin: String?) = scope.launch {
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Plex.useUser(ctx, profile, u, pin) }
        if (ok) { state.plexChanged(); state.close() } else wrong = true
    }
    Sheet(stringResource(R.string.plex_user_title), Modifier.width(620.dp)) {
        val u = asking
        if (u != null) {
            BasicText(stringResource(if (wrong) R.string.prof_wrong_pin else R.string.prof_enter_pin) + " · " + u.name,
                Modifier.padding(start = 10.dp, bottom = 8.dp), style = T.style(15, FontWeight.Medium, if (wrong) WARN else T.dim))
            PinPad(state) { pin -> wrong = false; use(u, pin) }
            return@Sheet
        }
        val current = com.mytv.launcher.data.Plex.userName(ctx, profile)
        ListRow(state, "pu:account", stringResource(R.string.plex_user_default), icon = "user", end = if (current == null) ({ SelectedBadge(true) }) else null) {
            com.mytv.launcher.data.Plex.useAccount(ctx, profile); state.plexChanged(); state.close()
        }
        val list = users
        if (list == null) BasicText("…", Modifier.padding(10.dp), style = T.style(15, color = T.dim))
        list?.forEach { pu ->
            ListRow(state, "pu:${pu.uuid}", pu.name, icon = "user", end = if (current == pu.name) ({ SelectedBadge(true) }) else null) {
                if (pu.protected) { wrong = false; asking = pu } else use(pu, null)
            }
        }
    }
}

/** Settings to the phone, or back from it: the same QR code + PIN page as own photos. */
@Composable
private fun BackupSheet(state: HomeState, restore: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var locked by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Int?>(null) }   // what happened: saved or restored
    val inbox = remember {
        com.mytv.launcher.data.PhotoInbox(ctx.applicationContext, com.mytv.launcher.data.PhotoInbox.Texts(
            res.getString(R.string.web_save_title), res.getString(R.string.web_choose_file),
            res.getString(R.string.web_restore), res.getString(R.string.web_restored), res.getString(R.string.web_wrong_pin),
            res.getString(R.string.own_pin), res.getString(R.string.bk_bad_file), res.getString(R.string.web_save), res.getString(R.string.web_saved),
        ), onReceived = { done = R.string.bk_saved }, onLocked = { locked = true },
            mode = if (restore) com.mytv.launcher.data.PhotoInbox.Mode.RESTORE_SETTINGS else com.mytv.launcher.data.PhotoInbox.Mode.SETTINGS,
            onRestored = { done = R.string.bk_restored; state.settingsRestored() })
    }
    var url by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        url = if (inbox.start()) inbox.url() else null
        onDispose { inbox.stop() }
    }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    InitialFocus(state, "bk:done")
    Sheet(stringResource(if (restore) R.string.sys_restore else R.string.sys_settings_backup), Modifier.width(860.dp),
        subtitle = stringResource(if (restore) R.string.sys_restore_sub else R.string.sys_settings_backup_sub)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                else BasicText(stringResource(R.string.own_no_network), style = T.style(15, color = INK_ON_WHITE))
            }
            Spacer(Modifier.width(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = done
                if (d != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectedBadge(true, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    BasicText(stringResource(d), style = T.style(24, FontWeight.Bold))
                } else {
                    BasicText("1.  " + stringResource(R.string.own_step1), style = T.style(18, FontWeight.Medium))
                    BasicText("2.  " + stringResource(if (restore) R.string.bk_restore_step2 else R.string.bk_both_step2), style = T.style(18, FontWeight.Medium))
                    url?.let { u ->
                        BasicText(stringResource(R.string.own_or) + "  " + u.substringBefore("/?"), Modifier.padding(top = 6.dp), style = T.style(15, color = T.dim))
                        BasicText(stringResource(R.string.own_pin) + "  " + u.substringAfter("pin="), style = T.style(26, FontWeight.Bold))
                    }
                    if (locked) BasicText(stringResource(R.string.own_locked), style = T.style(16, FontWeight.SemiBold, WARN))
                }
                Pill(state, "bk:done", stringResource(R.string.own_finish), Modifier.width(200.dp).padding(top = 8.dp), icon = "check") { state.close() }
            }
        }
    }
}

/**
 * Other TVs with MyTV: this TV shows its code (others link with it while this screen is open); a TV found on the
 * network is linked once with the code it shows, and then this TV can take over all its settings (OK twice).
 */
@Composable
private fun OtherTvsSheet(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val link = com.mytv.launcher.data.TvLink
    val found = remember { mutableStateMapOf<String, com.mytv.launcher.data.TvLink.Tv>() }
    var peers by remember { mutableStateOf(link.peers(ctx)) }
    var code by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<com.mytv.launcher.data.TvLink.Tv?>(null) }   // entering the code of this TV
    var wrong by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<String?>(null) }   // linked TV whose actions show
    var armed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(link.following(ctx)?.id) }               // the TV this one follows
    var followers by remember { mutableStateOf(link.followers(ctx).map { it.id }.toSet()) }   // TVs that follow this one
    var armedFollow by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        code = link.openPairing(ctx)
        link.onChange = {
            peers = link.peers(ctx); code = link.code().orEmpty(); locked = link.locked
            following = link.following(ctx)?.id; followers = link.followers(ctx).map { it.id }.toSet()
        }
        onDispose { link.onChange = null; link.closePairing(ctx); link.stopDiscovery(ctx) }
    }
    // Discovery stops when MyTV leaves the front: start it again when it is back.
    LaunchedEffect(state.resumeTick) { link.stopDiscovery(ctx); link.discover(ctx) { found[it.id] = it } }
    InitialFocus(state, "tl:done")

    fun pair(tv: com.mytv.launcher.data.TvLink.Tv, typed: String) = scope.launch {
        busy = true
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { link.pair(ctx, tv, typed) }
        busy = false
        peers = link.peers(ctx)
        when (r) {
            com.mytv.launcher.data.TvLink.Result.OK -> { asking = null; opened = tv.id; armed = false; state.pendingFocus = "tl:take:${tv.id}" }
            com.mytv.launcher.data.TvLink.Result.WRONG_CODE -> wrong = true
            else -> {
                asking = null; state.pendingFocus = "tl:${tv.id}"
                message = res.getString(if (r == com.mytv.launcher.data.TvLink.Result.LOCKED) R.string.link_locked else R.string.link_failed, tv.name)
            }
        }
    }
    fun take(tv: com.mytv.launcher.data.TvLink.Tv) = scope.launch {
        busy = true; message = null
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { link.take(ctx, tv) }
        busy = false; armed = false
        peers = link.peers(ctx)
        when (r) {
            com.mytv.launcher.data.TvLink.Result.OK -> { done = true; state.pendingFocus = "tl:done"; state.settingsRestored() }
            com.mytv.launcher.data.TvLink.Result.UNLINKED -> { opened = null; state.pendingFocus = "tl:${tv.id}"; message = res.getString(R.string.link_relink, tv.name) }
            else -> message = res.getString(R.string.link_failed, tv.name)
        }
    }
    fun follow(tv: com.mytv.launcher.data.TvLink.Tv, on: Boolean) = scope.launch {
        busy = true; message = null
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { link.setFollow(ctx, tv, on) }
        busy = false; armedFollow = false
        peers = link.peers(ctx); following = link.following(ctx)?.id
        when {
            r == com.mytv.launcher.data.TvLink.Result.OK && on -> { done = true; state.pendingFocus = "tl:done"; state.settingsRestored() }
            r == com.mytv.launcher.data.TvLink.Result.OK -> {}
            r == com.mytv.launcher.data.TvLink.Result.UNLINKED -> { opened = null; state.pendingFocus = "tl:${tv.id}"; message = res.getString(R.string.link_relink, tv.name) }
            else -> message = res.getString(R.string.link_failed, tv.name)
        }
    }

    Sheet(stringResource(R.string.sys_link), Modifier.width(760.dp), subtitle = stringResource(R.string.sys_link_sub)) {
        val a = asking
        if (a != null) {
            BasicText(if (wrong) stringResource(R.string.link_wrong_code) else stringResource(R.string.link_enter_code, a.name),
                Modifier.padding(start = 10.dp, bottom = 8.dp), style = T.style(15, FontWeight.Medium, if (wrong) WARN else T.dim))
            PinPad(state) { typed -> if (!busy) { wrong = false; pair(a, typed) } }
            return@Sheet
        }
        if (done) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                SelectedBadge(true, size = 30.dp)
                Spacer(Modifier.width(12.dp))
                BasicText(stringResource(R.string.bk_restored), style = T.style(24, FontWeight.Bold))
            }
            Pill(state, "tl:done", stringResource(R.string.own_finish), Modifier.width(200.dp), icon = "check") { state.close() }
            return@Sheet
        }
        BasicText(if (locked) stringResource(R.string.link_locked_here) else stringResource(R.string.link_this_tv, link.deviceName(ctx), code),
            Modifier.padding(start = 10.dp, bottom = 12.dp), style = T.style(18, FontWeight.SemiBold, if (locked) WARN else Color.White))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ids = (peers.map { it.id } + found.keys).distinct()
            if (ids.isEmpty()) BasicText(stringResource(R.string.link_searching), Modifier.padding(10.dp), style = T.style(15, color = T.dim))
            ids.forEach { id ->
                val tv = found[id]
                val peer = peers.firstOrNull { it.id == id }
                val name = tv?.name ?: peer?.name.orEmpty()
                Pill(state, "tl:$id", name, Modifier.fillMaxWidth(), icon = "tv", sub = stringResource(when {
                    peer == null -> R.string.link_pair_sub
                    following == id -> R.string.link_follows
                    id in followers -> R.string.link_followed
                    tv == null -> R.string.link_offline
                    else -> R.string.link_linked
                })) {
                    message = null
                    if (peer == null) { if (tv != null) { wrong = false; asking = tv; state.pendingFocus = "pin:1" } }
                    else { opened = if (opened == id) null else id; armed = false; armedFollow = false }
                }
                if (peer != null && opened == id) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Following: this TV takes over every change of that one by itself. Not a TV that follows this one.
                    if (id !in followers && (tv != null || following == id)) Pill(state, "tl:follow:$id", stringResource(R.string.link_follow),
                        Modifier.fillMaxWidth(), icon = "refresh", check = following == id, value = if (busy) "…" else null,
                        sub = when {
                            following == id -> stringResource(R.string.link_follow_on, name)
                            armedFollow -> stringResource(R.string.link_follow_confirm, name)
                            else -> null
                        }) {
                        if (busy) return@Pill
                        val target = tv ?: com.mytv.launcher.data.TvLink.Tv(id, name, "", 0)
                        when {
                            following == id -> follow(target, false)
                            armedFollow -> follow(target, true)
                            else -> armedFollow = true
                        }
                    }
                    if (tv != null) Pill(state, "tl:take:$id", stringResource(R.string.link_take), Modifier.fillMaxWidth(), icon = "download",
                        sub = if (armed) stringResource(R.string.link_take_confirm, name) else null, value = if (busy) "…" else null) {
                        if (busy) return@Pill
                        if (armed) take(tv) else armed = true
                    }
                    Pill(state, "tl:unlink:$id", stringResource(R.string.link_unlink), Modifier.fillMaxWidth(), icon = "trash") {
                        link.forget(ctx, id); peers = link.peers(ctx); opened = null; state.pendingFocus = "tl:done"
                    }
                }
            }
        }
        message?.let { BasicText(it, Modifier.padding(start = 10.dp, top = 10.dp), style = T.style(16, FontWeight.SemiBold, WARN)) }
        Pill(state, "tl:done", stringResource(R.string.own_finish), Modifier.width(200.dp).padding(top = 12.dp), icon = "check") { state.close() }
    }
}

/** Which of the backup's permissions are on there but not here (keys of the backup's "rights"). */
internal fun missingRights(state: HomeState, r: org.json.JSONObject): List<String> {
    val c = state.appContext
    fun now(k: String) = when (k) {
        "homeButton" -> com.mytv.launcher.data.SystemAccess.homeWatchOn(c)
        "notifications" -> com.mytv.launcher.data.MediaApps.allowed(c)
        "tvListings" -> com.mytv.launcher.data.WatchNext.allowed(c)
        "extraRights" -> com.mytv.launcher.data.SystemAccess.canWriteSecure(c) || !state.adbReachable   // looked up in the background (checkAdb)
        "defaultHome" -> com.mytv.launcher.data.SystemAccess.holdsDefaultHome(c) || com.mytv.launcher.data.SystemAccess.defaultHomeRequest(c) == null
        "screensaver" -> com.mytv.launcher.data.Screensaver.isMine(c)
        "pin" -> state.hasPin
        else -> true
    }
    return listOf("homeButton", "extraRights", "notifications", "tvListings", "defaultHome", "screensaver", "pin").filter { r.optBoolean(it) && !now(it) }
}

/** After a restore: one line per permission that was on in the backup and still needs the viewer here. */
@Composable
private fun RestoreRightsSheet(state: HomeState, rights: org.json.JSONObject) {
    @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick
    val missing = missingRights(state, rights)
    InitialFocus(state, missing.firstOrNull()?.let { "rr:$it" } ?: "rr:done")
    Sheet(stringResource(R.string.rr_title), Modifier.width(760.dp), subtitle = stringResource(R.string.rr_sub)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            missing.forEach { k ->
                when (k) {
                    "homeButton" -> Pill(state, "rr:$k", stringResource(R.string.sys_home), Modifier.fillMaxWidth(), icon = "power") {
                        if (!state.enableHomeWatch()) state.openAccessibilitySettings()
                    }
                    "extraRights" -> Pill(state, "rr:$k", stringResource(R.string.sys_rights), Modifier.fillMaxWidth(), icon = "power",
                        sub = stringResource(R.string.sys_rights_sub)) { state.grantRights() }
                    "notifications" -> Pill(state, "rr:$k", stringResource(R.string.np_src_apps), Modifier.fillMaxWidth(), icon = "play",
                        sub = stringResource(R.string.np_apps_access)) { state.changeNpSource("npApps", true) }
                    "tvListings" -> Pill(state, "rr:$k", stringResource(R.string.continue_watching), Modifier.fillMaxWidth(), icon = "tv") {
                        state.requestTvListings?.invoke()
                    }
                    "defaultHome" -> Pill(state, "rr:$k", stringResource(R.string.sys_default_home), Modifier.fillMaxWidth(), icon = "tv",
                        sub = stringResource(R.string.sys_default_home_sub)) { state.askDefaultHome() }
                    "screensaver" -> Pill(state, "rr:$k", stringResource(R.string.ss_use), Modifier.fillMaxWidth(), icon = "image") { state.toggleDream() }
                    "pin" -> Pill(state, "rr:$k", stringResource(R.string.rr_pin), Modifier.fillMaxWidth(), icon = "user") {
                        state.open(com.mytv.launcher.Overlay.PinSetup, "rr:pin")
                    }
                }
            }
            Pill(state, "rr:done", stringResource(R.string.own_finish), Modifier.width(200.dp).padding(top = 8.dp), icon = "check") { state.close() }
        }
    }
}

/** Own photos: QR code + PIN for the phone, the photos received so far (OK twice removes one). */
@Composable
private fun OwnPhotosSheet(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var locked by remember { mutableStateOf(false) }
    val inbox = remember {
        com.mytv.launcher.data.PhotoInbox(ctx.applicationContext, com.mytv.launcher.data.PhotoInbox.Texts(
            res.getString(R.string.web_title), res.getString(R.string.web_choose), res.getString(R.string.web_send),
            res.getString(R.string.web_done_long), res.getString(R.string.web_wrong_pin), res.getString(R.string.own_pin),
            notAdded = res.getString(R.string.web_not_added),
            reasons = mapOf("large" to res.getString(R.string.web_too_large), "many" to res.getString(R.string.web_too_many),
                "small" to res.getString(R.string.web_too_small), "image" to res.getString(R.string.web_not_photo),
                "cut" to res.getString(R.string.web_cut_off)),
        ), onReceived = { state.photoReceived() }, onLocked = { locked = true })
    }
    var url by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        state.resetPhotosReceived()
        url = if (inbox.start()) inbox.url() else null
        onDispose { inbox.stop() }
    }
    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val own = state.backgrounds.filter { it.own }.sortedByDescending { it.file!!.lastModified() }   // newest first
    val thumbs = remember { androidx.compose.runtime.mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(own) { for (b in own) if (b.id !in thumbs) state.bgRepo.thumb(b)?.let { thumbs[b.id] = it } }
    var armed by remember { mutableStateOf<String?>(null) }
    InitialFocus(state, "own:close")
    Sheet(stringResource(R.string.own_title), Modifier.width(860.dp), subtitle = stringResource(R.string.own_open_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                else BasicText(stringResource(R.string.own_no_network), style = T.style(15, color = INK_ON_WHITE))
            }
            Spacer(Modifier.width(28.dp))
            // Next to the QR code: the steps (or what arrived), and below them which photos take part in the rotation.
            if (state.photosReceived > 0) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectedBadge(true, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    BasicText(if (state.photosReceived == 1) stringResource(R.string.own_done_one) else stringResource(R.string.own_done_title, state.photosReceived), style = T.style(24, FontWeight.Bold))
                }
                BasicText(stringResource(R.string.own_done_text), style = T.style(16, color = T.dim))
                Pill(state, "own:done", stringResource(R.string.own_finish), Modifier.width(200.dp), icon = "check") { state.close() }
            } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("1.  " + stringResource(R.string.own_step1), style = T.style(18, FontWeight.Medium))
                BasicText("2.  " + stringResource(R.string.own_step2), style = T.style(18, FontWeight.Medium))
                url?.let { u ->
                    BasicText(stringResource(R.string.own_or) + "  " + u.substringBefore("/?"), Modifier.padding(top = 6.dp), style = T.style(15, color = T.dim))
                    BasicText(stringResource(R.string.own_pin) + "  " + u.substringAfter("pin="), style = T.style(26, FontWeight.Bold))
                }
                if (locked) BasicText(stringResource(R.string.own_locked), style = T.style(16, FontWeight.SemiBold, WARN))
                Pill(state, "own:close", stringResource(R.string.own_finish), Modifier.width(200.dp).padding(top = 14.dp), icon = "check") { state.close() }
            }
        }
        if (own.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            BasicText(stringResource(if (armed != null) R.string.own_delete_confirm else R.string.own_delete_hint), Modifier.padding(start = 8.dp, bottom = 6.dp),
                style = T.style(14, color = if (armed != null) WARN else T.dim))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                own.forEach { b ->
                    var f by remember { mutableStateOf(false) }
                    val sc by animateFloatAsState(if (f) 1.07f else 1f, tween(160), label = "own")
                    Box(
                        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
                            .tvClick { if (armed == b.id) { armed = null; state.pendingFocus = "own:close"; state.deleteOwnPhoto(b) } else armed = b.id }
                            .focusKey(state, "own:${b.id}") { f = it; if (!it && armed == b.id) armed = null }
                            .size(176.dp, 99.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))
                            .border(if (f) 3.dp else 0.dp, if (armed == b.id) Color(0xFFFF6B6B) else if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp)),
                    ) {
                        thumbs[b.id]?.let { Image(it, b.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium) }
                        if (armed == b.id) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            LineIcon("trash", 28.dp)
                        }
                    }
                }
            }
        }
    }
}

/** QR code as a small bitmap (one pixel per module); drawn scaled up without smoothing. */
private fun qrBitmap(text: String): ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}


/** Which photos take turns: all / my photos (only when there are any) / MyTV photos. */
@Composable
internal fun PhotoSourcePill(state: HomeState, key: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val hasOwn = state.backgrounds.any { it.own }
    val sources = listOfNotNull("all" to R.string.own_source_all, if (hasOwn) "own" to R.string.own_source_own else null, "mytv" to R.string.own_source_mytv)
    val cur = sources.firstOrNull { it.first == state.photoSource } ?: sources.first()
    Pill(state, key, stringResource(R.string.own_source), modifier, icon = "image", value = stringResource(cur.second)) {
        state.changePhotoSource(sources[(sources.indexOf(cur) + 1) % sources.size].first)
    }
}


/** Profile picker under the profile button; for a parent profile from a child profile the PIN is asked here. */
@Composable
private fun ProfilesSheet(state: HomeState, ask: Boolean) {
    var pinFor by remember { mutableStateOf<com.mytv.launcher.data.Profile?>(null) }
    var wrong by remember { mutableStateOf(false) }
    InitialFocus(state, "who:" + state.profileId)
    fun go(p: com.mytv.launcher.data.Profile) {
        state.switchProfile(p.id)
        state.closeAll("app:" + (state.visibleApps.firstOrNull()?.key ?: ""), answered = true)
        if (ask) state.afterWhoIsWatching()
    }
    // Back from the PIN pad returns to the list of profiles (and "who is watching" itself cannot be skipped).
    androidx.compose.runtime.DisposableEffect(pinFor) {
        if (pinFor != null) state.backHook = { pinFor = null; wrong = false; state.pendingFocus = "who:" + state.profileId; true }
        onDispose { state.backHook = null }
    }
    val content: @Composable ColumnScope.() -> Unit = {
            PopupTitle(stringResource(if (ask) R.string.prof_who else R.string.prof_switch), if (ask) 28 else 20)
            val target = pinFor
            if (target != null) {
                // Counts down on screen (the wait after five wrong tries).
                val wait by androidx.compose.runtime.produceState(state.pinWait(), wrong, target) {
                    while (true) { value = state.pinWait(); kotlinx.coroutines.delay(500) }
                }
                BasicText(if (wait > 0) stringResource(R.string.pin_wait, wait)
                    else stringResource(if (wrong) R.string.prof_wrong_pin else R.string.prof_enter_pin), Modifier.padding(start = 10.dp, bottom = 8.dp),
                    style = T.style(15, FontWeight.Medium, if (wrong) WARN else T.dim))
                PinPad(state) { pin -> if (state.checkPin(pin)) go(target) else wrong = true }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.profiles.forEachIndexed { i, p ->
                    ListRow(state, "who:${p.id}", p.name, stringResource(if (p.child) R.string.prof_child else R.string.prof_parent),
                        leading = { Avatar(p, i, 40.dp) },
                        end = if (p.id == state.profileId && !ask) ({ SelectedBadge(true, size = 24.dp) }) else null,
                    ) { if (state.needsPin(p)) { pinFor = p; state.pendingFocus = "pin:1" } else go(p) }
                }
            }
    }
    // Plain panel, no glass: glass would show a blurred piece of the last profile's photo.
    if (ask) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF242B37)).padding(12.dp), content = content)
    }
    else TopRightPopup(360.dp, content)
}

/** Four digits with the remote: number keys, or the on-screen pad (arrow keys + OK). */
@Composable
internal fun PinPad(state: HomeState, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun add(d: String) { if (pin.length < 4) { pin += d; if (pin.length == 4) { val p = pin; pin = ""; onDone(p) } } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0
            // One digit per press: a held key repeats, and four repeats would count as a wrong PIN.
            val digit = e.type == KeyEventType.KeyDown && n in 0..9
            if (digit && e.nativeKeyEvent.repeatCount == 0) add(n.toString())
            digit
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) Color.White else Color.White.copy(alpha = 0.18f))) }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { d ->
                    if (d.isEmpty()) Spacer(Modifier.size(64.dp, 52.dp)) else {
                        var f by remember { mutableStateOf(false) }
                        Box(
                            Modifier.size(64.dp, 52.dp).tvClick { if (d == "<") pin = pin.dropLast(1) else add(d) }
                                .focusKey(state, "pin:$d") { f = it }
                                .clip(RoundedCornerShape(16.dp)).background(if (f) Color.White else Color.White.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (d == "<") LineIcon("left", 20.dp, color = if (f) INK_ON_WHITE else Color.White)
                            else BasicText(d, style = T.style(22, FontWeight.SemiBold, if (f) INK_ON_WHITE else Color.White))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinSetupSheet(state: HomeState) {
    InitialFocus(state, "pin:1")
    Sheet(stringResource(R.string.prof_pin), Modifier.width(460.dp), subtitle = stringResource(R.string.prof_new_pin)) {
        PinPad(state) { pin -> state.setPin(pin); state.close() }
        if (state.hasPin) Pill(state, "pin:remove", stringResource(R.string.prof_pin_remove), Modifier.fillMaxWidth(), icon = "trash") { state.setPin(null); state.close() }
    }
}


/** Rename an app: the keyboard opens right away; empty or "Original name" restores the app's own name. */
@Composable
private fun RenameSheet(state: HomeState, key: String) {
    val app = state.apps.firstOrNull { it.key == key } ?: run { state.close(); return }
    var tf by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(app.label, androidx.compose.ui.text.TextRange(app.label.length))) }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; kb?.show() }
    Sheet(stringResource(R.string.menu_rename), Modifier.width(560.dp), subtitle = app.origLabel) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 14.dp)) {
            androidx.compose.foundation.text.BasicTextField(tf, { tf = it }, singleLine = true,
                textStyle = T.style(20, FontWeight.Medium, INK_ON_WHITE), cursorBrush = androidx.compose.ui.graphics.SolidColor(INK_ON_WHITE),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide(); state.renameApp(key, tf.text); state.close() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr).onPreviewKeyEvent { e ->
                    // The field keeps Down for itself on this TV: go to the buttons explicitly.
                    if (e.key == androidx.compose.ui.input.key.Key.DirectionDown && e.type == KeyEventType.KeyDown) { kb?.hide(); state.pendingFocus = "ren:save"; true } else false
                })
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(state, "ren:save", stringResource(R.string.save), Modifier.width(220.dp), icon = "check") { kb?.hide(); state.renameApp(key, tf.text); state.close() }
            Pill(state, "ren:reset", stringResource(R.string.rename_reset), Modifier.width(260.dp), icon = "refresh") { kb?.hide(); state.renameApp(key, null); state.close() }
        }
    }
}


/** Bluetooth headphones and speakers: one press connects or disconnects a paired device. */
@Composable
private fun BluetoothSheet(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var devices by remember { mutableStateOf<List<com.mytv.launcher.data.BtDevice>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    suspend fun reload() { devices = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Bluetooth.devices(ctx) } }
    LaunchedEffect(Unit) {
        com.mytv.launcher.data.Bluetooth.prepare(ctx) { scope.launch { reload() } }
        var first = true
        while (true) {
            reload()
            // The list is empty when the sheet opens: focus the first device once it is there.
            if (first) { first = false; devices.firstOrNull()?.let { state.pendingFocus = "bt:${it.address}" } }
            kotlinx.coroutines.delay(2000)      // connection changes take a few seconds
        }
    }
    InitialFocus(state, devices.firstOrNull()?.let { "bt:${it.address}" } ?: "bt:pair")
    TopRightPopup(420.dp) {
                PopupTitle(stringResource(R.string.bluetooth))
                if (!com.mytv.launcher.data.Bluetooth.enabled(ctx)) BasicText(stringResource(R.string.bt_off), Modifier.padding(10.dp), style = T.style(15, color = T.dim))
                else if (devices.isEmpty()) BasicText(stringResource(R.string.bt_none), Modifier.padding(10.dp), style = T.style(15, color = T.dim))
                if (failed) BasicText(stringResource(R.string.bt_failed), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = T.style(14, color = WARN))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { d ->
                        val sub = listOfNotNull(if (busy == d.address) "…" else null, d.battery?.let { stringResource(R.string.bt_battery, it) }).joinToString(" · ")
                        ListRow(state, "bt:${d.address}", d.name, sub, "bluetooth", end = if (d.connected) ({ SelectedBadge(true, size = 24.dp) }) else null) {
                            busy = d.address
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.mytv.launcher.data.Bluetooth.toggle(ctx, d.address, !d.connected)
                                }
                                if (!ok) { failed = true; com.mytv.launcher.data.Bluetooth.openSettings(ctx) }
                                kotlinx.coroutines.delay(3000); busy = null; reload()
                            }
                        }
                    }
                    Hairline()
                    MenuRow(state, "bt:pair", "bluetooth", stringResource(R.string.bt_pair)) { com.mytv.launcher.data.Bluetooth.pairNew(ctx) }
                }
    }
}


/** A newer version: its notes since the installed one, in the viewer's language, and Skip / Install. */
@Composable
private fun UpdateSheet(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val u = com.mytv.launcher.data.Updates.ui
    val lang = com.mytv.launcher.ui.languageFor(com.mytv.launcher.ui.LOC) ?: "en"
    InitialFocus(state, "updsheet:install")
    val versions = remember(u.notes) { runCatching { org.json.JSONArray(u.notes) }.getOrDefault(org.json.JSONArray()) }
    Sheet(stringResource(R.string.upd_new_title), Modifier.width(820.dp), subtitle = stringResource(R.string.upd_you_have, u.latest, com.mytv.launcher.data.Updates.current())) {
        // Long notes: Up from the buttons enters the list, Up/Down scroll it, Down at the end returns to the buttons.
        val notesScroll = rememberScrollState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var notesFocused by remember { mutableStateOf(false) }
        // The list gets what is left of the screen after the title, subtitle and buttons: the buttons always stay in view.
        val screenH = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
        Column(Modifier.heightIn(max = (screenH - 300.dp).coerceIn(120.dp, 420.dp))
            .border(2.dp, if (notesFocused) Color.White.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> if (notesScroll.canScrollForward) { scope.launch { notesScroll.animateScrollBy(160f) }; true } else false
                    androidx.compose.ui.input.key.Key.DirectionUp -> { if (notesScroll.canScrollBackward) scope.launch { notesScroll.animateScrollBy(-160f) }; true }
                    else -> false
                }
            }
            .focusKey(state, "updsheet:notes", notesScroll.maxValue > 0) { notesFocused = it }
            .scrollWithBar(notesScroll).padding(6.dp)) {
            for (i in 0 until versions.length()) {
                val v = versions.optJSONObject(i) ?: continue
                val date = v.optString("date").let { d -> runCatching { java.time.LocalDate.parse(d).format(com.mytv.launcher.ui.datePattern("dMMMMyyyy")) }.getOrNull() }
                SectionLabel(v.optString("version") + (date?.let { " · $it" } ?: ""), first = i == 0)
                val notes = v.optJSONArray("notes") ?: continue
                for (j in 0 until notes.length()) {
                    val n = notes.optJSONObject(j) ?: continue
                    // Notes in this language, else the language without its region (pt-BR → pt), else English.
                    val text = listOf(lang, lang.substringBefore('-'), "en").firstNotNullOfOrNull { l -> n.optString(l).takeIf { it.isNotBlank() } }.orEmpty()
                    val (label, color) = when (n.optString("tag")) {
                        "new" -> R.string.tag_new to OK_GREEN
                        "fixed" -> R.string.tag_fixed to Color(0xFFFFB340)
                        "security" -> R.string.tag_security to Color(0xFFFF6B6B)
                        else -> R.string.tag_improved to Color(0xFF64A8FF)
                    }
                    Row(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(118.dp)) {
                            BasicText(stringResource(label), Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.22f))
                                .padding(horizontal = 8.dp, vertical = 2.dp), style = T.style(13, FontWeight.SemiBold, color))
                        }
                        BasicText(text, Modifier.weight(1f), style = T.style(16))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill(state, "updsheet:skip", stringResource(R.string.upd_skip), Modifier.width(220.dp), icon = "eye-off") {
                com.mytv.launcher.data.Updates.skip(ctx); state.close()
            }
            val busy = u.phase != com.mytv.launcher.data.UpdateUi.Phase.IDLE
            Pill(state, "updsheet:install", stringResource(if (u.phase == com.mytv.launcher.data.UpdateUi.Phase.INSTALLING && u.quiet) R.string.upd_installing_quiet else R.string.upd_install),
                Modifier.width(if (u.phase == com.mytv.launcher.data.UpdateUi.Phase.INSTALLING && u.quiet) 520.dp else 320.dp), icon = "refresh",
                value = when (u.phase) {
                    com.mytv.launcher.data.UpdateUi.Phase.DOWNLOADING -> "${u.percent}%"
                    com.mytv.launcher.data.UpdateUi.Phase.INSTALLING -> "…"
                    else -> null
                }) { if (!busy) com.mytv.launcher.data.Updates.install(ctx) }
        }
    }
}
