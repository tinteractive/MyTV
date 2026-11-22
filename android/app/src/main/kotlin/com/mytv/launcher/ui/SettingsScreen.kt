// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import com.mytv.launcher.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mytv.launcher.HomeState
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy

/** first: focus key of the page's first item (Right from the section list goes there). */
private data class Section(val key: String, val label: Int, val icon: String, val first: String)

/**
 * Eight sections that fit on one screen. Pages that belong to a section (start-up app under Apps; clean up, updates and
 * about under System; one profile under Profiles) open in place and Back returns to the button that opened them.
 */
/** Sections for the whole TV: only shown in the first profile (see [HomeState.isMainProfile]). */
private val MAIN_ONLY = setOf("screensaver", "profiles")

private val SECTIONS = listOf(
    Section("home", R.string.sec_home, "tv", "disp:clock"),
    Section("apps", R.string.sec_apps, "grid", "apps:startup"),
    Section("background", R.string.sec_background, "image", "bg:rotate"),
    Section("weather", R.string.sec_weather, "sun", "wx:first"),
    Section("screensaver", R.string.sec_screensaver, "timer", "ss:use"),
    Section("profiles", R.string.sec_profiles, "user", "prof:first"),
    Section("remote", R.string.remote_title, "remote", "rem:first"),
    Section("system", R.string.sec_system, "gear", "sys:lang"),
)

/** A page inside a section: parent section, title, and the button on the parent page that opens it. */
private data class Sub(val parent: String, val label: Int, val opener: String)
private val SUBS = mapOf(
    "startup" to Sub("apps", R.string.sec_startup, "apps:startup"),
    "nowplaying" to Sub("home", R.string.now_playing, "disp:np"),
    "continuewatching" to Sub("home", R.string.continue_watching, "disp:cw"),
    "button1" to Sub("home", R.string.grp_buttons, "disp:btn1"),
    "button2" to Sub("home", R.string.grp_buttons, "disp:btn2"),
    "cleanup" to Sub("system", R.string.sec_cleanup, "sys:clean"),
    "updates" to Sub("system", R.string.sec_updates, "sys:updates"),
    "connection" to Sub("system", R.string.sys_conn, "sys:conn"),
    "quiethours" to Sub("screensaver", R.string.quiet_title, "sys:quiet"),
    "language" to Sub("system", R.string.sec_language, "sys:lang"),
    "remote:red" to Sub("remote", R.string.remote_red, "rem:red"),
    "remote:green" to Sub("remote", R.string.remote_green, "rem:green"),
    "remote:yellow" to Sub("remote", R.string.remote_yellow, "rem:yellow"),
    "remote:blue" to Sub("remote", R.string.remote_blue, "rem:blue"),
    "about" to Sub("system", R.string.sec_about, "sys:about"),
)
/** The top section a page belongs to (also for a page inside a page, such as System → Remote control → Red button). */
private fun parentOf(section: String): String = SUBS[section]?.parent?.let { parentOf(it) } ?: if (section.startsWith("profile:")) "profiles" else section

/**
 * Settings, tvOS style: sections on the left, the selected section's page on the right.
 * Moving through the sections shows their page immediately; Right enters the page, Back returns to the sections.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(state: HomeState) {
    InitialFocus(state, "sec:${state.settingsSection}")
    var inPage by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        state.backHook = {
            when {
                // A page inside a section: Back returns to the button that opened it.
                inPage && state.settingsSection in SUBS -> {
                    val sub = SUBS.getValue(state.settingsSection); state.settingsSection = sub.parent; state.pendingFocus = sub.opener; true
                }
                inPage && state.settingsSection.startsWith("profile:") -> {
                    state.pendingFocus = "prof:" + state.settingsSection.removePrefix("profile:"); state.settingsSection = "profiles"; true
                }
                inPage -> { state.pendingFocus = "sec:${state.settingsSection}"; true }
                else -> false
            }
        }
        onDispose { state.backHook = null }
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Glass(Modifier.fillMaxSize(), radius = 0.dp, strength = 3f) {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 34.dp, bottom = 24.dp)) {
            // Left from a page always lands on that page's own section (not on whatever row is at the same height).
            // Right-to-left languages: the sections are on the right, so that is Right.
            val toSections = if (rtl) androidx.compose.ui.focus.FocusDirection.Right else androidx.compose.ui.focus.FocusDirection.Left
            Column(Modifier.width(280.dp).focusProperties {
                onEnter = {
                    if (requestedFocusDirection == toSections)
                        runCatching { state.focus["sec:" + parentOf(state.settingsSection)]?.requestFocus() }
                }
            }.focusGroup()) {
                BasicText(stringResource(R.string.settings), Modifier.padding(start = 10.dp, bottom = 22.dp), style = T.style(32, FontWeight.Bold))
                // Scrolls when the sections do not fit (so "About" is always reachable).
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SECTIONS.filter { state.isMainProfile || it.key !in MAIN_ONLY }.forEach { s -> SectionRow(state, s, s.key == parentOf(state.settingsSection)) }
                }
            }
            Spacer(Modifier.width(40.dp))
            val title = SUBS[state.settingsSection]?.label ?: SECTIONS.first { it.key == parentOf(state.settingsSection) }.label
            Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { inPage = it.hasFocus }) {
                BasicText(stringResource(title), Modifier.padding(top = 12.dp, bottom = 16.dp), style = T.style(26, FontWeight.Bold))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val sec = state.settingsSection
                    when {
                        sec == "weather" -> WeatherPage(state)
                        sec == "background" -> BackgroundPage(state)
                        sec == "home" -> DisplayPage(state, "home")
                        sec == "apps" -> AppsPage(state)
                        sec == "startup" -> AutostartPage(state)
                        sec == "nowplaying" -> NowPlayingPage(state)
                        sec == "continuewatching" -> PageColumn { ContinueWatchingSettings(state) }
                        sec == "button1" -> ButtonChoicePage(state, 1)
                        sec == "button2" -> ButtonChoicePage(state, 2)
                        sec == "screensaver" -> ScreensaverPage(state)
                        sec == "profiles" -> ProfilesPage(state)
                        sec.startsWith("profile:") -> ProfilePage(state, sec.removePrefix("profile:"))
                        sec == "language" -> DisplayPage(state, "language")
                        sec == "system" -> SystemPage(state)
                        sec == "cleanup" -> CleanupPage(state)
                        sec == "updates" -> UpdatesPage(state)
                        sec == "connection" -> ConnectionPage(state)
                        sec == "quiethours" -> QuietHoursPage(state)
                        sec == "remote" -> RemotePage(state)
                        sec.startsWith("remote:") -> ColorKeyPage(state, sec.removePrefix("remote:"))
                        else -> AboutPage(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(state: HomeState, s: Section, selected: Boolean) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.04f else 1f, tween(160), label = "sec")
    // The arrow that points at the page (Left in right-to-left languages, where the page is on the left).
    val intoPage = if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .shadow(if (f) 14.dp else 0.dp, RoundedCornerShape(16.dp), clip = false)
            .onPreviewKeyEvent { e ->
                // On a page inside this section (System → Quiet hours, a profile, …): Right goes back into that page,
                // to the nearest item. Its keys differ from the section page's, so the usual first item is not there.
                val onSubPage = state.settingsSection != s.key && parentOf(state.settingsSection) == s.key
                // Right always enters the page at its first item (not at whatever happens to be nearest).
                if (e.key == intoPage && e.type == KeyEventType.KeyDown && !onSubPage) {
                    state.pendingFocus = when (s.first) {
                        "prof:first" -> if (state.profiles.isEmpty()) "prof:add" else "prof:" + state.profiles.first().id
                        "rem:first" -> if (state.isMainProfile) "sys:home" else "rem:red"
                        "wx:first" -> {
                            // The place in use, not the search field (that would open the keyboard).
                            val recent = state.recentCities.ifEmpty { listOfNotNull(state.city) }
                            val i = recent.indexOfFirst { c -> state.city?.let { it.lat == c.lat && it.lon == c.lon } == true }
                            if (i >= 0) "recent:$i" else "wx:field"
                        }
                        else -> s.first
                    }
                    true
                } else false
            }
            // A page switch in progress (e.g. System → Clean up) moves focus away for a moment; that must not
            // switch the section.
            .focusKey(state, "sec:${s.key}") { f = it; if (it && state.pendingFocus.let { p -> p == null || p.startsWith("sec:") } && parentOf(state.settingsSection) != s.key) state.settingsSection = s.key }
            .clip(RoundedCornerShape(16.dp))
            .background(if (f) Color.White else if (selected) Color.White.copy(alpha = 0.13f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (f) INK_ON_WHITE else if (selected) Color.White else Color.White.copy(alpha = 0.76f)
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(if (f) Color.Black.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { LineIcon(s.icon, 20.dp, color = fg) }
        Spacer(Modifier.width(14.dp))
        BasicText(stringResource(s.label), style = T.style(17, FontWeight.SemiBold, fg))
    }
}

/** Where there are buttons below the page (the welcome screen): Down past the last item goes to this one. */
private val LocalDownLeavesPage = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun PageColumn(
    header: (@Composable () -> Unit)? = null, footer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit,
) {
    val downLeaves = LocalDownLeavesPage.current
    // Scrolling keeps a margin around the focused item (scaled tiles and their shadows stay fully visible),
    // returns to the very top near the top of the page, and the page ends with room so the last row is never cut off.
    val st = androidx.compose.foundation.rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 20.dp.toPx() }
    val step = with(density) { 120.dp.toPx() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(margin) { MinimalScroll(margin) },
    ) {
        Column(Modifier.fillMaxSize().focusProperties {
            // Up and Down stay on the page: past its last (or first) item there is nothing to go to, not the sections.
            // On the welcome screen Down goes on to its Next button (not to the nearest one, Back).
            onExit = {
                val dir = requestedFocusDirection
                if (dir == androidx.compose.ui.focus.FocusDirection.Down || dir == androidx.compose.ui.focus.FocusDirection.Up) {
                    cancelFocusChange()
                    if (dir == androidx.compose.ui.focus.FocusDirection.Down) downLeaves?.invoke()
                }
            }
        }.focusGroup()) {
            // Fixed above the scrolling part: explanation, search field, settings rows that belong to the list.
            if (header != null) {
                Column(Modifier.fillMaxWidth().padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { header() }
                Spacer(Modifier.height(12.dp))
            }
            // Only the list itself scrolls. Normal scrolling follows the focus; when there is nothing more to select
            // below (or above), Down (Up) scrolls on in small steps so text after the last item is reachable too.
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val dir = when (e.key) {
                            Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                            Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                            else -> return@onPreviewKeyEvent false
                        }
                        if (focusManager.moveFocus(dir)) return@onPreviewKeyEvent true
                        val down = dir == androidx.compose.ui.focus.FocusDirection.Down
                        if ((down && st.value < st.maxValue) || (!down && st.value > 0)) {
                            scope.launch { st.animateScrollBy(if (down) step else -step) }
                            true
                        } else false
                    }
                    .scrollWithBar(st),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            // Fixed below the scrolling part (e.g. the caption of the focused photo).
            if (footer != null) footer()
        }
    }
}

// ---------------------------------------------------------------- pages
@Composable
private fun WeatherPage(state: HomeState, onChosen: () -> Unit = {}) {
    val kb = LocalSoftwareKeyboardController.current
    var tf by remember { mutableStateOf(TextFieldValue("")) }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { state.search(""); state.addApproxPlace() }
    PageColumn(header = {
        BasicText(stringResource(R.string.weather_hint),
            Modifier.padding(bottom = 4.dp), style = T.style(14, color = T.dim))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (fieldFocused) Color.White else Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (fieldFocused) INK_ON_WHITE else Color.White
            LineIcon("search", 22.dp, color = fg)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (tf.text.isEmpty()) BasicText(stringResource(R.string.place_hint), style = T.style(18, color = if (fieldFocused) Color(0xFF6A717B) else T.faint))
                BasicTextField(
                    value = tf,
                    onValueChange = { v -> tf = v; if (v.text != state.searchText) state.search(v.text) },
                    singleLine = true,
                    textStyle = T.style(18, FontWeight.Medium, fg),
                    cursorBrush = SolidColor(fg),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { kb?.hide(); if (state.searchResults.isNotEmpty()) state.pendingFocus = "place:0" }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).registerAs(state, "wx:field", field)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        .tvTextKeys(),
                )
            }
            if (state.searching) BasicText(stringResource(R.string.searching), style = T.style(14, color = if (fieldFocused) Color(0xFF6A717B) else T.dim))
        }
        if (state.locateFailed) BasicText(stringResource(R.string.wx_locate_failed), Modifier, style = T.style(15, color = WARN))
        if (state.searchError) BasicText(stringResource(R.string.search_failed), Modifier, style = T.style(15, color = WARN))
        if (tf.text.trim().length >= 2 && !state.searching && state.searchResults.isEmpty() && !state.searchError) {
            BasicText(stringResource(R.string.no_places), Modifier, style = T.style(15, color = T.dim))
        }
    }) {
        state.searchResults.forEachIndexed { i, p ->
            val cur = state.city?.let { it.lat == p.city.lat && it.lon == p.city.lon } == true
            Pill(state, "place:$i", p.city.name, Modifier.fillMaxWidth(), sub = p.subtitle.ifBlank { null }, icon = "pin",
                selected = cur) {
                state.chooseCity(p.city)
                tf = TextFieldValue("")
                state.search("")
                onChosen()
            }
        }
        // Not searching: the recently used places; the active one carries the check mark.
        if (tf.text.trim().length < 2) {
            val recent = state.recentCities.ifEmpty { listOfNotNull(state.city) }
            recent.forEachIndexed { i, c ->
                val cur = state.city?.let { it.lat == c.lat && it.lon == c.lon } == true
                Pill(state, "recent:$i", c.name, Modifier.fillMaxWidth(), icon = "pin", selected = cur) { state.chooseCity(c); onChosen() }
            }
        }
    }
}

/** Every app as under "All apps": the dock first in its order, then the other apps by name. */
private fun allAppsInOrder(state: HomeState): List<com.mytv.launcher.data.AppEntry> {
    val dock = state.visibleApps
    return dock + state.apps.filter { it !in dock }.sortedBy { it.label.lowercase() }
}

@Composable
private fun AutostartPage(state: HomeState, onChosen: () -> Unit = {}) {
    PageColumn(header = {
        BasicText(stringResource(R.string.startup_hint), Modifier, style = T.style(14, color = T.dim))
    }) {
        Pill(state, "auto:none", stringResource(R.string.none), Modifier.fillMaxWidth(), sub = stringResource(R.string.none_sub), icon = "tv",
            selected = state.autostart == null) { state.setAutostartApp(null); onChosen() }
        allAppsInOrder(state).forEach { app ->
            Pill(state, "auto:${app.key}", app.label, Modifier.fillMaxWidth(), image = app.icon,
                selected = state.autostart == app.key) { state.setAutostartApp(app.key); onChosen() }
        }
    }
}

@Composable
private fun BackgroundPage(state: HomeState) {
    val current = state.bg.current?.info?.id
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(state.backgrounds) { for (b in state.backgrounds) if (b.id !in thumbs) state.bgRepo.thumb(b)?.let { thumbs[b.id] = it } }
    PageColumn {
        Pill(state, "bg:rotate", stringResource(R.string.bg_rotate), Modifier.fillMaxWidth(), icon = "timer", value = rotateLabel(state.rotateSeconds)) {
            val i = ROTATE_OPTIONS.indexOf(state.rotateSeconds)
            state.setRotate(ROTATE_OPTIONS[(i + 1).mod(ROTATE_OPTIONS.size)])
        }
        PhotoSourcePill(state, "bg:source")
        Pill(state, "bg:trans", stringResource(R.string.bg_transition), Modifier.fillMaxWidth(), icon = "fade", value = stringResource(if (state.transitions) R.string.trans_smooth else R.string.trans_direct)) {
            state.changeTransitions(!state.transitions)
        }
        Pill(state, "bg:add", stringResource(R.string.bg_add_own), Modifier.fillMaxWidth(), icon = "image", sub = stringResource(R.string.bg_add_own_sub)) {
            state.open(com.mytv.launcher.Overlay.OwnPhotos, "bg:add")
        }
        SectionLabel(stringResource(R.string.grp_photos))
        // Own photos: hold OK, then OK again removes one (moving away cancels), as in the rest of MyTV.
        // MyTV's own photos: hold OK switches one off (out of the rotation and the screensaver) or on again.
        var armed by remember { mutableStateOf<String?>(null) }
        var focusedOwn by remember { mutableStateOf(false) }
        var focusedMytv by remember { mutableStateOf<String?>(null) }
        BasicText(stringResource(when {
                armed != null -> R.string.own_delete_confirm
                focusedOwn -> R.string.bg_own_delete_hint
                focusedMytv != null -> if ((focusedMytv ?: "") in state.photosOff) R.string.bg_photo_on_hint else R.string.bg_photo_off_hint
                state.rotateSeconds > 0 -> R.string.bg_pick_rotating
                else -> R.string.bg_pick
            }), Modifier.padding(bottom = 4.dp), style = T.style(14, color = if (armed != null) WARN else T.dim))
        FlowRow(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.backgrounds.forEach { b ->
                var f by remember { mutableStateOf(false) }
                val sc by animateFloatAsState(if (f) 1.07f else 1f, tween(160), label = "t")
                Box(
                    Modifier
                        .graphicsLayer { scaleX = sc; scaleY = sc }
                        .shadow(if (f) 14.dp else 0.dp, RoundedCornerShape(14.dp), clip = false)
                        .tvClick(onLongClick = if (b.own) ({ armed = b.id }) else ({ state.togglePhotoOff(b.id) })) {
                            if (armed == b.id) { armed = null; focusedOwn = false; state.pendingFocus = "bg:add"; state.deleteOwnPhoto(b) }
                            else { armed = null; state.setBackground(b.id) }
                        }
                        .focusKey(state, "bg:${b.id}") {
                            f = it
                            if (it) focusedOwn = b.own else if (b.own) focusedOwn = false
                            if (it) focusedMytv = if (b.own) null else b.id else if (focusedMytv == b.id) focusedMytv = null
                            if (!it && armed == b.id) armed = null
                        }
                        .size(156.dp, 88.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(14.dp)),
                ) {
                    thumbs[b.id]?.let { Image(it, b.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium) }
                    SelectedBadge(b.id == current, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 26.dp)
                    if (b.id in state.photosOff) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
                        LineIcon("eye-off", 30.dp)
                    }
                    if (armed == b.id) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        LineIcon("trash", 32.dp, color = WARN)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisplayPage(state: HomeState, part: String) {
    val sysLang = systemLanguageName()
    val langs = listOf<String?>(null) + LANGUAGES
    val autoUnits = Units(Units.autoFahrenheit(), Units.autoH24(androidx.compose.ui.platform.LocalContext.current))
    PageColumn {
        if (part == "language") {
        var langOpen by remember { mutableStateOf(false) }
        Pill(state, "disp:lang", stringResource(R.string.disp_language), Modifier.fillMaxWidth(), icon = "globe",
            sub = stringResource(R.string.disp_language_sub),
            value = state.language?.let { LANGUAGE_NAMES[it] } ?: stringResource(R.string.auto_value, sysLang)) { langOpen = !langOpen }
        if (langOpen) {
            // The choices, each in its own language; picking one rebuilds MyTV in that language.
            langs.forEach { l ->
                Pill(state, "lang:${l ?: "auto"}", l?.let { LANGUAGE_NAMES[it]!! } ?: stringResource(R.string.auto_value, sysLang),
                    Modifier.fillMaxWidth().padding(start = 28.dp), icon = "globe", selected = state.language == l) {
                    if (state.language == l) langOpen = false else state.chooseLanguage(l)
                }
            }
        }
        val units = listOf("auto", "c", "f")
        Pill(state, "disp:temp", stringResource(R.string.disp_temp), Modifier.fillMaxWidth(), icon = "thermo", sub = stringResource(R.string.disp_temp_sub),
            value = when (state.tempUnit) { "c" -> "°C (Celsius)"; "f" -> "°F (Fahrenheit)"; else -> stringResource(R.string.auto_value, if (autoUnits.fahrenheit) "°F" else "°C") }) {
            state.chooseTempUnit(units[(units.indexOf(state.tempUnit) + 1) % units.size])
        }
        val fmts = listOf("auto", "24", "12")
        Pill(state, "disp:time", stringResource(R.string.disp_timefmt), Modifier.fillMaxWidth(), icon = "clock", sub = stringResource(R.string.disp_timefmt_sub),
            value = when (state.timeFormat) { "24" -> stringResource(R.string.fmt_24); "12" -> stringResource(R.string.fmt_12)
                else -> stringResource(R.string.auto_value, stringResource(if (autoUnits.h24) R.string.fmt_24 else R.string.fmt_12)) }) {
            state.chooseTimeFormat(fmts[(fmts.indexOf(state.timeFormat) + 1) % fmts.size])
        }
        } else {
            SectionLabel(stringResource(R.string.grp_look), first = true)
            Pill(state, "disp:clock", stringResource(R.string.disp_clock), Modifier.fillMaxWidth(), icon = "clock", sub = stringResource(R.string.disp_clock_sub),
                value = stringResource(if (state.compactClock) R.string.clock_compact else R.string.clock_large)) { state.setCompact(!state.compactClock) }
            Pill(state, "disp:glass", stringResource(R.string.disp_glass), Modifier.fillMaxWidth(), icon = "layers", sub = stringResource(R.string.disp_glass_sub),
                value = stringResource(if (state.bg.blur) R.string.glass_frosted else R.string.glass_simple)) { state.setBlur(!state.bg.blur) }
            val nights = listOf("off" to R.string.off, "sunset" to R.string.night_sunset, "fixed" to R.string.night_fixed)
            Pill(state, "disp:night", stringResource(R.string.night_mode), Modifier.fillMaxWidth(), icon = "moon", sub = stringResource(R.string.night_sub),
                value = stringResource(nights.first { it.first == state.nightMode }.second)) {
                state.changeNightMode(nights[(nights.indexOfFirst { it.first == state.nightMode } + 1) % nights.size].first)
            }
            Pill(state, "disp:burn", stringResource(R.string.burn_in), Modifier.fillMaxWidth(), icon = "timer", sub = stringResource(R.string.burn_in_sub),
                value = stringResource(if (state.pixelShift) R.string.on else R.string.off)) { state.changePixelShift(!state.pixelShift) }
            SectionLabel(stringResource(R.string.grp_on_home))
            Pill(state, "disp:cw", stringResource(R.string.continue_watching), Modifier.fillMaxWidth(), icon = "tv", sub = stringResource(R.string.disp_continue_sub),
                value = stringResource(if (state.continueWatching) R.string.on else R.string.off)) {
                state.settingsSection = "continuewatching"; state.pendingFocus = "cw:on"
            }
            Pill(state, "disp:np", stringResource(R.string.now_playing), Modifier.fillMaxWidth(), icon = "play", sub = stringResource(R.string.disp_np_sub),
                value = stringResource(if (state.nowPlayingOn) R.string.on else R.string.off)) { state.settingsSection = "nowplaying"; state.pendingFocus = "np:on" }
            SectionLabel(stringResource(R.string.grp_buttons))
            listOf(1 to state.button1, 2 to state.button2).forEach { (slot, v) ->
                Pill(state, "disp:btn$slot", stringResource(R.string.btn_slot, slot), Modifier.fillMaxWidth(), icon = "grid",
                    sub = stringResource(if (slot == 1) R.string.btn_slot_sub else R.string.btn_slot2_sub),
                    value = buttonLabel(state, v)) { state.settingsSection = "button$slot"; state.pendingFocus = "bc:$v" }
            }
        }
    }
}

@Composable
private fun AppsPage(state: HomeState) {
    PageColumn(header = {
        val cur = state.apps.firstOrNull { it.key == state.autostart }
        Pill(state, "apps:startup", stringResource(R.string.sec_startup), Modifier.fillMaxWidth(), icon = "power", sub = stringResource(R.string.apps_startup_sub),
            value = cur?.label ?: stringResource(R.string.none)) {
            state.settingsSection = "startup"; state.pendingFocus = cur?.let { "auto:${it.key}" } ?: "auto:none"
        }
        SectionLabel(stringResource(R.string.grp_in_dock))
        BasicText(stringResource(R.string.apps_hint), Modifier, style = T.style(14, color = T.dim))
    }) {
        (state.visibleApps + state.apps.filter { it.key in state.hidden }).forEach { app ->
            val shown = app.key !in state.hidden
            // The check mark says it all: no subtitle.
            ToggleRow(state, "appvis:${app.key}", app.label, app.icon, shown, "") {
                state.setHidden(app.key, shown)
            }
        }
    }
}

/**
 * Quiet hours: where they apply, or null when MyTV cannot limit the volume here (sound over HDMI to a soundbar
 * that is not a Sonos found on the network). Keeps the chosen soundbar current (its address may change).
 */
@Composable
private fun rememberQuietTarget(): String? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hdmi = remember { com.mytv.launcher.QuietHours.soundOverHdmi(ctx) }
    var sonos by remember { mutableStateOf(com.mytv.launcher.QuietHours.settings(ctx).sonos) }
    LaunchedEffect(hdmi) {
        if (!hdmi) return@LaunchedEffect
        com.mytv.launcher.data.Sonos.discover(ctx)
        var bars = emptyList<String>()
        repeat(4) {
            bars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.mytv.launcher.data.Sonos.homeTheaters() }
            if (bars.isNotEmpty()) return@repeat
            kotlinx.coroutines.delay(1_000)
        }
        // The chosen soundbar by room, otherwise the first one found.
        val s = com.mytv.launcher.QuietHours.settings(ctx)
        val room = s.sonos?.split('|')?.getOrNull(1)
        val pick = bars.firstOrNull { it.split('|').getOrNull(1) == room } ?: bars.firstOrNull()
        if (pick != null && pick != s.sonos) com.mytv.launcher.QuietHours.save(ctx, s.copy(sonos = pick))
        sonos = pick ?: s.sonos
    }
    return if (hdmi) sonos?.split('|')?.takeIf { it.size >= 3 }?.let { "${it[2]} (${it[1]})" } else stringResource(R.string.quiet_tv_speakers)
}

/** Quiet hours in System: on/off as its value; OK opens its own page (several settings belong together). */
@Composable
private fun QuietHoursEntry(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val target = rememberQuietTarget() ?: return
    val s = remember(state.settingsSection) { com.mytv.launcher.QuietHours.settings(ctx) }
    Pill(state, "sys:quiet", stringResource(R.string.quiet_title), Modifier.fillMaxWidth(), icon = "moon",
        sub = if (s.on) stringResource(R.string.quiet_for, target) else stringResource(R.string.quiet_sub),
        value = stringResource(if (s.on) R.string.on else R.string.off)) {
        state.settingsSection = "quiethours"; state.pendingFocus = "quiet:on"
    }
}

/**
 * What the remote's buttons do. Everywhere (seen by the Home-button helper): the Sources and Apps buttons open MyTV's
 * own, or do what the TV does. In MyTV: the coloured buttons get a function, the number buttons open the dock's apps.
 * One line per button; its choices (and teaching MyTV a button with its own code) open under it.
 */
/** The Home button: MyTV's helper, the default home screen and the TV's own home screen. */
@Composable
private fun HomeButtonGroup(state: HomeState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick     // re-read after returning from TV settings
    val homeOn = state.homeWatchEnabled()
    SectionLabel(stringResource(R.string.grp_home_button), first = true)
    DefaultHomePill(state)
    Pill(state, "sys:home", stringResource(R.string.sys_home), Modifier.fillMaxWidth(), icon = "power",
        sub = stringResource(if (homeOn) R.string.sys_home_on else R.string.sys_home_off),
        value = stringResource(if (homeOn) R.string.on else R.string.off)) { state.openAccessibilitySettings() }
    LaunchedEffect(Unit) { state.refreshPkgStates(); state.checkAdb() }
    val stockOn = state.pkgStates[state.stockLauncher]
    if (stockOn != null && state.adbReachable) {
        Pill(state, "sys:stockswitch", stringResource(R.string.sys_stock_launcher), Modifier.fillMaxWidth(), icon = "layers",
            sub = stringResource(if (stockOn) R.string.sys_stock_on_sub else R.string.sys_stock_off_sub),
            value = if (state.adbBusy == state.stockLauncher) "…" else stringResource(if (stockOn) R.string.on else R.string.off)) {
            state.setPackage(state.stockLauncher, !stockOn)
        }
        AdbStatusLine(state)
    }
    if (stockOn != false) Pill(state, "sys:stock", stringResource(R.string.sys_stock), Modifier.fillMaxWidth(), icon = "tv",
        sub = stringResource(R.string.sys_stock_sub)) { state.openStockHome() }
}

@Composable
private fun RemotePage(state: HomeState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick
    @Suppress("UNUSED_VARIABLE") val rt = state.remoteTick
    val helper = state.homeWatchEnabled()
    // Leaving the page stops learning (a coloured button is learned on its own page).
    DisposableEffect(Unit) { onDispose { if (state.learning == "sources" || state.learning == "apps") state.learnKey(null) } }
    var open by remember { mutableStateOf<String?>(null) }
    PageColumn {
        if (state.isMainProfile) {
        HomeButtonGroup(state)
        SectionLabel(stringResource(R.string.grp_everywhere))
        GlobalKeyRow(state, "sources", R.string.remote_sources, R.string.remote_sources_sub, state.sourcesButton, helper, open == "sources",
            { open = if (open == "sources") null else "sources" }) { state.changeSourcesButton(it) }
        GlobalKeyRow(state, "apps", R.string.remote_apps, R.string.remote_apps_sub, state.appsButton, helper, open == "apps",
            { open = if (open == "apps") null else "apps" }) { state.changeAppsButton(it) }
        }
        SectionLabel(stringResource(R.string.grp_in_mytv), first = !state.isMainProfile)
        com.mytv.launcher.COLORS.forEach { (c, _) ->
            Pill(state, "rem:$c", stringResource(colorTitle(c)), Modifier.fillMaxWidth(), image = rememberDot(c),
                sub = stringResource(R.string.remote_color_sub), value = buttonLabel(state, state.prefs.colorAction(c))) {
                state.settingsSection = "remote:$c"; state.pendingFocus = "rk:none"
            }
        }
        Pill(state, "rem:digits", stringResource(R.string.remote_digits), Modifier.fillMaxWidth(), icon = "grid",
            sub = stringResource(R.string.remote_digits_sub), value = stringResource(if (state.digitKeys) R.string.on else R.string.off)) {
            state.changeDigitKeys(!state.digitKeys)
        }
    }
}

/** A button that works everywhere: MyTV's own screen, what the TV does, or teaching MyTV the button. */
@Composable
private fun GlobalKeyRow(
    state: HomeState, id: String, title: Int, sub: Int, mine: Boolean, helper: Boolean, expanded: Boolean,
    toggle: () -> Unit, set: (Boolean) -> Unit,
) {
    val learning = state.learning == id
    Pill(state, "rem:$id", stringResource(title), Modifier.fillMaxWidth(), icon = if (id == "apps") "grid" else "input",
        sub = stringResource(if (!helper) R.string.sleep_needs_home else if (learning) R.string.remote_learn_wait_any else sub),
        value = if (learning) "…" else if (mine) "MyTV" else stringResource(R.string.remote_from_tv)) { toggle() }
    if (!expanded) return
    val indent = Modifier.fillMaxWidth().padding(start = 28.dp)
    Pill(state, "rem:$id:mytv", "MyTV", indent, icon = "tv", selected = mine) { set(true); toggle(); state.pendingFocus = "rem:$id" }
    Pill(state, "rem:$id:tv", stringResource(R.string.remote_from_tv), indent, icon = "power", selected = !mine) {
        set(false); toggle(); state.pendingFocus = "rem:$id"
    }
    if (helper) Pill(state, "rem:$id:learn", stringResource(R.string.remote_learn_any), indent, icon = "search",
        sub = stringResource(if (state.learned(id)) R.string.remote_learned else R.string.remote_learn_any_sub)) {
        set(true); state.learnKey(id); toggle(); state.pendingFocus = "rem:$id"
    }
}

/** A coloured button: teach MyTV the button (when the remote uses its own code), then the same choices as Button 1/2. */
@Composable
private fun ColorKeyPage(state: HomeState, color: String) {
    @Suppress("UNUSED_VARIABLE") val rt = state.remoteTick
    val learning = state.learning == color
    DisposableEffect(Unit) { onDispose { if (state.learning == color) state.learnKey(null) } }
    PageColumn {
        if (state.homeWatchEnabled()) Pill(state, "rk:learn", stringResource(R.string.remote_learn_any), Modifier.fillMaxWidth(), icon = "search",
            sub = stringResource(if (learning) R.string.remote_learn_wait_any else if (state.learned(color)) R.string.remote_learned else R.string.remote_learn_any_sub),
            value = if (learning) "…" else null) { state.learnKey(color) }
        ButtonChoices(state, state.prefs.colorAction(color), "rk") { v ->
            state.setColorAction(color, v); state.settingsSection = "remote"; state.pendingFocus = "rem:$color"
        }
    }
}

private fun colorTitle(c: String) = when (c) {
    "red" -> R.string.remote_red; "green" -> R.string.remote_green; "yellow" -> R.string.remote_yellow; else -> R.string.remote_blue
}

/** A small dot in the button's colour, as the row's picture. */
@Composable
private fun rememberDot(c: String): ImageBitmap = remember(c) {
    val color = when (c) { "red" -> 0xFFE5484D; "green" -> 0xFF30A46C; "yellow" -> 0xFFF5D90A; else -> 0xFF3E7BFA }.toInt()
    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawCircle(24f, 24f, 18f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    bmp.asImageBitmap()
}

/** "22:00" or "10:00 PM", as the viewer chose for the clock. */
@Composable
private fun hourLabel(hour: Int) = LocalUnits.current.time(java.time.LocalDateTime.of(2000, 1, 1, hour, 0))

/** Quiet hours: on/off, from, until and the maximum volume. */
@Composable
private fun QuietHoursPage(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var s by remember { mutableStateOf(com.mytv.launcher.QuietHours.settings(ctx)) }
    val target = rememberQuietTarget()
    fun update(n: com.mytv.launcher.QuietHours.Settings) { s = n; com.mytv.launcher.QuietHours.save(ctx, n) }
    fun next(v: Int, all: List<Int>) = all[(all.indexOf(v) + 1).mod(all.size)]
    PageColumn {
        Pill(state, "quiet:on", stringResource(R.string.quiet_title), Modifier.fillMaxWidth(), icon = "moon",
            sub = target?.let { stringResource(R.string.quiet_for, it) } ?: stringResource(R.string.quiet_sub),
            value = stringResource(if (s.on) R.string.on else R.string.off)) { update(s.copy(on = !s.on)) }
        if (!s.on) return@PageColumn
        Pill(state, "quiet:from", stringResource(R.string.quiet_from), Modifier.fillMaxWidth(), icon = "clock",
            value = hourLabel(s.from)) { update(s.copy(from = next(s.from, listOf(19, 20, 21, 22, 23)))) }
        Pill(state, "quiet:to", stringResource(R.string.quiet_to), Modifier.fillMaxWidth(), icon = "clock",
            value = hourLabel(s.to)) { update(s.copy(to = next(s.to, listOf(5, 6, 7, 8, 9)))) }
        Pill(state, "quiet:max", stringResource(R.string.quiet_max), Modifier.fillMaxWidth(), icon = "timer",
            value = "${s.maxPercent}%") { update(s.copy(maxPercent = next(s.maxPercent, listOf(10, 15, 20, 25, 30, 40, 50)))) }
    }
}

/** Other TVs: Android's own question to make MyTV the default home; only where the TV offers it. */
@Composable
private fun DefaultHomePill(state: HomeState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (com.mytv.launcher.data.SystemAccess.defaultHomeRequest(ctx) == null) return
    Pill(state, "sys:defhome", stringResource(R.string.sys_default_home), Modifier.fillMaxWidth(), icon = "tv",
        sub = stringResource(R.string.sys_default_home_sub)) { state.askDefaultHome() }
}

/** On/off line: a [Pill] with a check circle at the end. */
@Composable
private fun ToggleRow(state: HomeState, key: String, label: String, image: ImageBitmap?, on: Boolean, sub: String, icon: String? = null, onClick: () -> Unit) =
    Pill(state, key, label, Modifier.fillMaxWidth(), icon = icon, sub = sub.ifEmpty { null }, image = image, check = on, onClick = onClick)

@Composable
private fun SystemPage(state: HomeState) {
    PageColumn {
        Pill(state, "sys:lang", stringResource(R.string.sec_language), Modifier.fillMaxWidth(), icon = "globe",
            sub = stringResource(R.string.sys_lang_sub)) { state.settingsSection = "language"; state.pendingFocus = "disp:lang" }
        Pill(state, "sys:bt", stringResource(R.string.bluetooth), Modifier.fillMaxWidth(), icon = "bluetooth",
            sub = stringResource(R.string.sys_bt_sub)) { state.open(com.mytv.launcher.Overlay.Bluetooth, "sys:bt") }
        if (state.isMainProfile) {
        SectionLabel(stringResource(R.string.grp_maint))
        LaunchedEffect(state.adbReachable) { if (state.adbReachable) state.refreshPkgStates() }
        // A part that makes the TV slow is still on: Clean up says so itself.
        val slow = com.mytv.launcher.data.Cleanup.SLOW.any { state.pkgStates[it] == true }
        if (state.adbReachable) Pill(state, "sys:clean", stringResource(R.string.sec_cleanup), Modifier.fillMaxWidth(), icon = "trash",
            sub = stringResource(if (slow) R.string.cleanup_slow_advice else R.string.sys_cleanup_sub)) {
            state.settingsSection = "cleanup"
            state.pendingFocus = "clean:" + com.mytv.launcher.data.Cleanup.ITEMS.firstOrNull { it.pkg in state.pkgStates }?.pkg
        }
        // GitHub edition: what works through the TV's own debugging connection, and why not.
        if (com.mytv.launcher.data.LocalAdb.SUPPORTED) Pill(state, "sys:conn", stringResource(R.string.sys_conn), Modifier.fillMaxWidth(), icon = "code",
            sub = stringResource(R.string.sys_conn_sub),
            value = stringResource(if (state.adbReachable && state.adbVerified) R.string.sys_conn_ok else R.string.sys_conn_bad)) {
            state.settingsSection = "connection"; state.pendingFocus = "conn:check"
        }
        Pill(state, "sys:backup", stringResource(R.string.sys_settings_backup), Modifier.fillMaxWidth(), icon = "download",
            sub = stringResource(R.string.sys_settings_backup_sub)) { state.open(com.mytv.launcher.Overlay.Backup(), "sys:backup") }
        // Which TV this one follows, or which TVs follow this one; otherwise what the screen is for.
        val linkCtx = androidx.compose.ui.platform.LocalContext.current
        val followed = remember(state.resumeTick, state.overlays.size) { com.mytv.launcher.data.TvLink.following(linkCtx)?.name }
        val followers = remember(state.resumeTick, state.overlays.size) { com.mytv.launcher.data.TvLink.followers(linkCtx).joinToString(", ") { it.name } }
        Pill(state, "sys:link", stringResource(R.string.sys_link), Modifier.fillMaxWidth(), icon = "tv", sub = when {
            followed != null -> stringResource(R.string.sys_link_follows, followed)
            followers.isNotEmpty() -> stringResource(R.string.sys_link_followed, followers)
            else -> stringResource(R.string.sys_link_sub)
        }) { state.open(com.mytv.launcher.Overlay.OtherTvs, "sys:link") }
        Pill(state, "sys:android", stringResource(R.string.sys_android), Modifier.fillMaxWidth(), icon = "gear",
            sub = stringResource(R.string.sys_android_sub)) { state.openSystemSettings() }
        }
        SectionLabel("MyTV")
        // The Play Store edition has no own updater (the Play Store updates it).
        if (com.mytv.launcher.data.Updates.ui.supported && state.isMainProfile) Pill(state, "sys:updates", stringResource(R.string.sec_updates), Modifier.fillMaxWidth(), icon = "refresh",
            sub = stringResource(R.string.sys_updates_sub),
            value = if (com.mytv.launcher.data.Updates.ui.available) stringResource(R.string.sys_update_ready) else null) {
            state.settingsSection = "updates"; state.pendingFocus = if (com.mytv.launcher.data.Updates.ui.available) "upd:install" else "upd:check"
        }
        Pill(state, "sys:about", stringResource(R.string.sec_about), Modifier.fillMaxWidth(), icon = "info",
            sub = stringResource(R.string.sys_about_sub), value = com.mytv.launcher.BuildConfig.VERSION_NAME) {
            state.settingsSection = "about"; state.pendingFocus = "ph:" + state.backgrounds.firstOrNull { it.mytv }?.id
        }
    }
}

@Composable
private fun ScreensaverPage(state: HomeState) {
    // Also when coming back from the TV's own settings (the screensaver may have been chosen there).
    LaunchedEffect(state.resumeTick) { state.refreshDream() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Without the extra permissions MyTV cannot switch the screensaver itself: say where OK leads instead.
    val byHand = !state.dreamMine && remember(state.resumeTick) { !com.mytv.launcher.data.SystemAccess.canWriteSecure(ctx) }
    PageColumn {
        Pill(state, "ss:use", stringResource(R.string.ss_use), Modifier.fillMaxWidth(), icon = "image",
            sub = stringResource(if (byHand) R.string.ss_use_manual_sub else R.string.ss_use_sub),
            value = stringResource(if (state.dreamMine) R.string.on else R.string.off)) { state.toggleDream() }
        LaunchedEffect(Unit) { state.checkAdb() }
        if (state.canSetDreamTimeout) Pill(state, "ss:after", stringResource(R.string.ss_after), Modifier.fillMaxWidth(), icon = "timer", sub = stringResource(R.string.ss_after_sub),
            value = if (state.adbBusy == "dream") "…" else if (state.dreamTimeout == 0) stringResource(R.string.ss_never)
                else stringResource(R.string.ss_minutes, state.dreamTimeout)) { state.cycleDreamTimeout() }
        AdbStatusLine(state)
        Pill(state, "ss:clock", stringResource(R.string.ss_clock), Modifier.fillMaxWidth(), icon = "clock", sub = stringResource(R.string.ss_clock_sub),
            value = stringResource(if (state.dreamClock) R.string.on else R.string.off)) { state.changeDreamClock(!state.dreamClock) }
        Pill(state, "ss:preview", stringResource(R.string.ss_preview), Modifier.fillMaxWidth(), icon = "eye", sub = stringResource(R.string.ss_preview_sub)) { state.previewDream() }
        Pill(state, "ss:android", stringResource(R.string.ss_android), Modifier.fillMaxWidth(), icon = "gear", sub = stringResource(R.string.ss_android_sub)) { state.openDreamSettings() }
        SectionLabel(stringResource(R.string.grp_sleep))
        val homeOn = state.homeWatchEnabled()
        @Suppress("UNUSED_VARIABLE") val st = state.sleepTick
        val opts = listOf(0, 15, 30, 60, 90, 120)
        if (state.canSleep || com.mytv.launcher.SleepTimer.active) Pill(state, "sys:sleep", stringResource(R.string.sleep_timer), Modifier.fillMaxWidth(), icon = "timer",
            sub = if (homeOn) stringResource(R.string.sleep_sub) else stringResource(R.string.sleep_needs_home),
            value = if (com.mytv.launcher.SleepTimer.active) stringResource(R.string.sleep_left, com.mytv.launcher.SleepTimer.minutesLeft()) else stringResource(R.string.off)) {
            // Next choice after the current remaining time: off → 15 → 30 → 60 → 90 → 120 → off.
            val left = com.mytv.launcher.SleepTimer.minutesLeft()
            state.setSleep(opts.firstOrNull { it > left } ?: 0)
        }
        QuietHoursEntry(state)
    }
}

@Composable
private fun AboutPage(state: HomeState) {
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    val photos = state.backgrounds.filter { it.mytv }
    LaunchedEffect(photos) { for (b in photos) if (b.id !in thumbs) state.bgRepo.thumb(b)?.let { thumbs[b.id] = it } }
    var focused by remember { mutableStateOf<com.mytv.launcher.data.BgInfo?>(null) }
    PageColumn(header = { AboutHeader() }, footer = {
        // Caption of the focused photo, always visible below the scrolling part.
        val f = focused
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 10.dp).height(40.dp),
        ) {
            if (f == null) BasicText(stringResource(R.string.about_photo_hint), style = T.style(14, color = T.dim))
            else {
                BasicText(f.title.replace(Regex("\\.(jpe?g|png|tiff?|webp)$", RegexOption.IGNORE_CASE), ""),
                    style = T.style(15, FontWeight.Medium), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                BasicText(listOf(f.author, f.license, f.site).filter { it.isNotBlank() }.joinToString(" · "), style = T.style(13, color = T.dim), maxLines = 1)
            }
        }
    }) {
        SectionLabel(stringResource(R.string.about_photos, photos.size), first = true)
        FlowRow(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            photos.forEach { b ->
                var on by remember { mutableStateOf(false) }
                val sc by animateFloatAsState(if (on) 1.07f else 1f, tween(160), label = "ph")
                Box(
                    Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
                        .shadow(if (on) 14.dp else 0.dp, RoundedCornerShape(12.dp), clip = false)
                        .focusKey(state, "ph:${b.id}") { on = it; if (it) focused = b }
                        .size(160.dp, 90.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f))
                        .border(if (on) 3.dp else 0.dp, if (on) Color.White else Color.Transparent, RoundedCornerShape(12.dp)),
                ) { thumbs[b.id]?.let { Image(it, b.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium) } }
            }
        }
        SectionLabel(stringResource(R.string.about_made_with))
        // Plain information (not selectable): what MyTV uses, with licences.
        MadeWith("cloud", "Open-Meteo", stringResource(R.string.credit_weather) + " · CC BY 4.0")
        MadeWith("cloud", "MET Norway", stringResource(R.string.credit_weather_backup) + " · CC BY 4.0")
        MadeWith("sun", "Lucide", stringResource(R.string.credit_icons) + " · ISC")
        MadeWith("info", "SunCalc", "Vladimir Agafonkin · BSD-2-Clause")
        MadeWith("image", "Wikimedia Commons", stringResource(R.string.credit_photos))
        MadeWith("grid", "ZXing", stringResource(R.string.credit_qr) + " · Apache-2.0")
        MadeWith("pin", "ipapi.co", stringResource(R.string.credit_location))
        MadeWith("code", "Jetpack Compose · AndroidX · Kotlin", stringResource(R.string.credit_toolkit) + " · Apache-2.0")
    }
}

/** Logo, name, version and what MyTV is (fixed at the top of the About page). */
@Composable
private fun AboutHeader() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.07f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(androidx.compose.ui.res.painterResource(R.drawable.banner), null,
            Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.app_name), style = T.style(26, FontWeight.Bold))
            BasicText(stringResource(R.string.about_version, com.mytv.launcher.BuildConfig.VERSION_NAME), style = T.style(15, color = T.dim))
        }
    }
}

@Composable
private fun MadeWith(icon: String, name: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LineIcon(icon, 20.dp, color = T.dim)
        Spacer(Modifier.width(14.dp))
        BasicText(name, Modifier.width(180.dp), style = T.style(15, FontWeight.Medium))
        BasicText(detail, style = T.style(14, color = T.dim), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}


@Composable
private fun UpdatesPage(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val u = com.mytv.launcher.data.Updates.ui
    val units = LocalUnits.current
    PageColumn(header = {
        BasicText(stringResource(R.string.upd_intro), Modifier, style = T.style(14, color = T.dim))
    }) {
        if (!u.supported) {
            BasicText(stringResource(R.string.upd_not_set), Modifier, style = T.style(15, color = WARN))
            return@PageColumn
        }
        val checked = if (u.checkedAt > 0) stringResource(R.string.upd_last_checked,
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(u.checkedAt), java.time.ZoneId.systemDefault()).let { it.format(datePattern("dMMM")) + " " + units.time(it) })
            else stringResource(R.string.upd_never)
        if (u.available) {
            Pill(state, "upd:install", stringResource(R.string.upd_available, u.latest), Modifier.fillMaxWidth(), icon = "refresh",
                sub = when (u.phase) {
                    com.mytv.launcher.data.UpdateUi.Phase.DOWNLOADING -> stringResource(R.string.upd_downloading, u.percent)
                    com.mytv.launcher.data.UpdateUi.Phase.INSTALLING -> stringResource(if (u.quiet) R.string.upd_installing_quiet else R.string.upd_installing)
                    else -> stringResource(R.string.upd_view)
                }) { state.open(com.mytv.launcher.Overlay.Update, "upd:install") }
        }
        // A check the viewer started that finds a new version opens the update screen right away.
        var asked by remember { mutableStateOf(false) }
        LaunchedEffect(u.phase, u.available) {
            if (asked && u.phase == com.mytv.launcher.data.UpdateUi.Phase.IDLE) { asked = false; if (u.available) state.open(com.mytv.launcher.Overlay.Update, "upd:install") }
        }
        val checking = u.phase == com.mytv.launcher.data.UpdateUi.Phase.CHECKING
        Pill(state, "upd:check", stringResource(if (checking) R.string.upd_checking else R.string.upd_check), Modifier.fillMaxWidth(), icon = "search",
            sub = checked,
            value = com.mytv.launcher.data.Updates.current()) { asked = true; com.mytv.launcher.data.Updates.check(ctx, userAsked = true) }
        val problem = when (u.problem) {
            com.mytv.launcher.data.UpdateProblem.NONE -> null
            com.mytv.launcher.data.UpdateProblem.OFFLINE -> R.string.upd_err_net
            com.mytv.launcher.data.UpdateProblem.CORRUPT -> R.string.upd_err_bad
            com.mytv.launcher.data.UpdateProblem.FOREIGN_KEY -> R.string.upd_err_other_key
            com.mytv.launcher.data.UpdateProblem.NEEDS_PERMISSION -> R.string.upd_err_allow
            com.mytv.launcher.data.UpdateProblem.CANCELLED -> R.string.upd_err_cancelled
            com.mytv.launcher.data.UpdateProblem.FAILED -> R.string.upd_err_failed
            com.mytv.launcher.data.UpdateProblem.NO_SPACE -> R.string.upd_err_space
            com.mytv.launcher.data.UpdateProblem.NOT_NEWER -> null
        }
        if (problem != null) BasicText(stringResource(problem), Modifier, style = T.style(15, color = WARN))
        ToggleRow(state, "upd:auto", stringResource(R.string.upd_auto), null, u.auto, stringResource(R.string.upd_auto_sub), icon = "timer") {
            com.mytv.launcher.data.Updates.setAuto(ctx, !u.auto)
        }
    }
}
@Composable
private fun Modifier.registerAs(state: HomeState, key: String, fr: FocusRequester): Modifier {
    DisposableEffect(key, fr) { state.focus[key] = fr; onDispose { if (state.focus[key] === fr) state.focus.remove(key) } }
    return this
}


/** Connection to the TV: the one thing that is wrong with the next step, a check, and pairing for Wireless debugging. */
@Composable
private fun ConnectionPage(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.resumeTick) { state.checkAdb() }
    val last = state.adbStatus?.takeIf { it != "waiting" }
    val facts = remember(state.adbReachable, last, state.adbVerified, state.resumeTick) {
        com.mytv.launcher.data.ConnectCheck.facts(ctx, state.adbReachable, last, state.adbVerified, com.mytv.launcher.data.LocalAdb.wirelessPaired())
    }
    val pairing = com.mytv.launcher.data.AdbPairing.state
    // Just paired (MyTV came back by itself): try it at once.
    LaunchedEffect(pairing) { if (pairing == com.mytv.launcher.data.AdbPairing.State.PAIRED) state.checkConnection() }
    val reason = com.mytv.launcher.data.ConnectCheck.diagnose(facts)
    val line = when (reason) {
        com.mytv.launcher.data.ConnectCheck.Reason.OK -> R.string.conn_ok
        com.mytv.launcher.data.ConnectCheck.Reason.UNCHECKED -> R.string.conn_unchecked
        com.mytv.launcher.data.ConnectCheck.Reason.DEV_OFF -> R.string.conn_dev_off
        com.mytv.launcher.data.ConnectCheck.Reason.ADB_OFF -> R.string.conn_adb_off
        com.mytv.launcher.data.ConnectCheck.Reason.WIRELESS_ONLY -> R.string.conn_wireless_only
        com.mytv.launcher.data.ConnectCheck.Reason.WIRELESS_OFF -> R.string.conn_wireless_off
        com.mytv.launcher.data.ConnectCheck.Reason.PORT_CLOSED -> R.string.conn_port_closed
        com.mytv.launcher.data.ConnectCheck.Reason.NO_ANSWER -> R.string.adb_no_answer
        com.mytv.launcher.data.ConnectCheck.Reason.REFUSED -> R.string.conn_refused
        com.mytv.launcher.data.ConnectCheck.Reason.FAILED -> R.string.conn_failed
    }
    PageColumn(header = {
        BasicText(stringResource(line), Modifier, style = T.style(15, FontWeight.Medium, if (reason == com.mytv.launcher.data.ConnectCheck.Reason.OK) OK_GREEN else WARN))
        if (state.adbStatus == "waiting") AdbStatusLine(state)
    }) {
        Pill(state, "conn:check", stringResource(R.string.conn_check), Modifier.fillMaxWidth(), icon = "search",
            sub = stringResource(R.string.conn_check_sub), value = if (state.adbBusy == "check") "…" else null) { state.checkConnection() }
        // Connected: MyTV can give itself the setup right, and close debugging again afterwards.
        val rights = remember(state.resumeTick, state.adbBusy) { com.mytv.launcher.data.SystemAccess.canWriteSecure(ctx) }
        if (state.adbReachable && !rights) Pill(state, "conn:rights", stringResource(R.string.sys_rights), Modifier.fillMaxWidth(), icon = "power",
            sub = stringResource(R.string.sys_rights_sub), value = if (state.adbBusy == "rights") "…" else null) { state.grantRights() }
        if (state.adbReachable && rights) {
            var armed by remember { mutableStateOf(false) }
            Pill(state, "conn:adboff", stringResource(R.string.sys_adb_off), Modifier.fillMaxWidth(), icon = "eye-off",
                sub = stringResource(if (armed) R.string.sys_adb_off_confirm else R.string.sys_adb_off_sub)) {
                if (armed) { armed = false; state.closeNetworkDebugging() } else armed = true
            }
        }
        // Android 11+: Wireless debugging, paired once with the code the TV shows.
        if (com.mytv.launcher.data.AdbPairing.supported() && reason != com.mytv.launcher.data.ConnectCheck.Reason.OK) {
            Pill(state, "conn:pair", stringResource(R.string.conn_pair), Modifier.fillMaxWidth(), icon = "code",
                sub = stringResource(R.string.conn_pair_sub),
                value = if (pairing == com.mytv.launcher.data.AdbPairing.State.WAITING) "…" else null) {
                com.mytv.launcher.data.AdbPairing.start(ctx)
                if (com.mytv.launcher.data.AdbPairing.state == com.mytv.launcher.data.AdbPairing.State.WAITING) {
                    val dev = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (com.mytv.launcher.data.SystemAccess.real(ctx, dev) == null || runCatching { ctx.startActivity(dev) }.isFailure) state.openSystemSettings()
                }
            }
            when (pairing) {
                com.mytv.launcher.data.AdbPairing.State.WAITING ->
                    BasicText(stringResource(R.string.conn_pair_steps), Modifier.padding(horizontal = 6.dp), style = T.style(14, color = T.dim))
                com.mytv.launcher.data.AdbPairing.State.NO_HELPER ->
                    BasicText(stringResource(R.string.conn_pair_needs_helper), Modifier.padding(horizontal = 6.dp), style = T.style(14, FontWeight.Medium, WARN))
                else -> {}
            }
        }
    }
}

@Composable
private fun AdbStatusLine(state: HomeState) {
    val st = state.adbStatus ?: return
    val res = when (st) { "waiting" -> R.string.adb_waiting; "off" -> R.string.adb_off; "refused" -> R.string.adb_refused; "noanswer" -> R.string.adb_no_answer; else -> R.string.adb_failed }
    BasicText(stringResource(res), Modifier, style = T.style(15, FontWeight.Medium, WARN))
}

@Composable
private fun CleanupPage(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { state.refreshPkgStates() }
    PageColumn(header = {
        BasicText(stringResource(R.string.cleanup_hint), Modifier, style = T.style(14, color = T.dim))
        AdbStatusLine(state)
    }) {
        val all = com.mytv.launcher.data.Cleanup.ITEMS + state.otherPkgs.map {
            com.mytv.launcher.data.Cleanup.Item(it, if (it in com.mytv.launcher.data.Cleanup.CAUTION) com.mytv.launcher.data.Cleanup.Category.CAUTION
                else com.mytv.launcher.data.Cleanup.Category.OTHER)
        }
        var armed by remember { mutableStateOf<String?>(null) }
        val firstCat = com.mytv.launcher.data.Cleanup.Category.values().firstOrNull { cat -> all.any { it.category == cat && it.pkg in state.pkgStates } }
        com.mytv.launcher.data.Cleanup.Category.values().forEach { cat ->
            val items = all.filter { it.category == cat && it.pkg in state.pkgStates }
            if (items.isEmpty()) return@forEach
            SectionLabel(stringResource(cat.label), first = cat == firstCat)
            val caution = cat == com.mytv.launcher.data.Cleanup.Category.CAUTION
            if (caution) BasicText(stringResource(R.string.clean_caution_sub), Modifier.padding(end = 14.dp, bottom = 4.dp),
                style = T.style(14, color = WARN))
            items.forEach { item ->
                val on = state.pkgStates[item.pkg] == true
                val busy = state.adbBusy == item.pkg
                ToggleRow(state, "clean:${item.pkg}", remember(item.pkg) { com.mytv.launcher.data.Cleanup.label(ctx, item.pkg) }, null, on,
                    if (busy) "…" else if (armed == item.pkg) stringResource(R.string.clean_confirm)
                    else if (item.slows && on) stringResource(R.string.clean_slows)
                    else item.pkg,
                    icon = if (on) "check" else "eye-off") {
                    // TV functions: switching off needs a second OK.
                    if (caution && on && armed != item.pkg) armed = item.pkg else { armed = null; state.setPackage(item.pkg, !on) }
                }
            }
        }
        if (state.pkgStates.any { (p, on) -> !on && p != state.stockLauncher }) {
            Spacer(Modifier.height(6.dp))
            Pill(state, "clean:restore", stringResource(R.string.cleanup_restore_all), Modifier.fillMaxWidth(), icon = "refresh") { state.restoreAll() }
        }
    }
}


/**
 * Welcome on a fresh install: a start page, six steps (language, weather, Home button, start-up app, Continue
 * watching, profiles) and an end page with tips. A choice goes on to the next step by itself; every step can be left
 * as it is (Next), the whole setup can be left for later, and everything can be changed in Settings.
 */
@Composable
internal fun WelcomeScreen(state: HomeState) {
    data class Step(val label: Int, val first: String)
    val steps = listOf(
        Step(R.string.wel_intro, "wel:next"),
        Step(R.string.wel_language, "lang:${state.language ?: "auto"}"), Step(R.string.sec_weather, if (state.city != null) "recent:0" else "wel:next"),
        Step(R.string.wel_home, "wel:home"), Step(R.string.sec_startup, "auto:none"), Step(R.string.continue_watching, "cw:on"),
        Step(R.string.sec_profiles, if (state.profiles.isEmpty()) "wp:none" else "wp:family"), Step(R.string.wel_done, "wel:next"),
    )
    val last = steps.lastIndex
    val step = state.welcomeStep.coerceIn(0, last)
    InitialFocus(state, steps[step].first)
    fun go(to: Int) { state.welcomeStep = to; state.pendingFocus = steps[to].first }
    Glass(Modifier.fillMaxSize(), radius = 0.dp, strength = 3f) {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 34.dp, bottom = 24.dp)) {
            Column(Modifier.width(280.dp)) {
                BasicText(stringResource(R.string.wel_title), Modifier.padding(start = 10.dp, bottom = 10.dp), style = T.style(30, FontWeight.Bold))
                BasicText(stringResource(R.string.wel_sub), Modifier.padding(start = 10.dp, bottom = 24.dp), style = T.style(14, color = T.dim))
                // Only the real steps are numbered; the start and end pages are not steps.
                for (i in 1 until last) {
                    Row(Modifier.padding(vertical = 8.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(when {
                            i == step -> Color.White; i < step -> OK_GREEN; else -> Color.White.copy(alpha = 0.12f) }),
                            contentAlignment = Alignment.Center) {
                            if (i < step) LineIcon("check", 16.dp)
                            else BasicText("$i", style = T.style(14, FontWeight.Bold, if (i == step) INK_ON_WHITE else Color.White))
                        }
                        Spacer(Modifier.width(14.dp))
                        BasicText(stringResource(steps[i].label), style = T.style(18, if (i == step) FontWeight.SemiBold else FontWeight.Normal,
                            if (i == step) Color.White else T.dim))
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                BasicText(stringResource(steps[step].label), Modifier.padding(top = 12.dp, bottom = 16.dp), style = T.style(26, FontWeight.Bold))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val toNext = remember(state) { { state.focus["wel:next"]?.let { runCatching { it.requestFocus() } }; Unit } }
                    androidx.compose.runtime.CompositionLocalProvider(LocalDownLeavesPage provides toNext) { WelcomeStep(state, step, last, ::go) }
                }
                // Always the same two buttons in the same place (Back closes the welcome screen on its first page).
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    if (step > 0) {
                        Pill(state, "wel:back", stringResource(R.string.wel_back), Modifier.width(200.dp), icon = "left") { go(step - 1) }
                        Spacer(Modifier.width(14.dp))
                    }
                    Pill(state, "wel:next", stringResource(if (step == last || step == 0) R.string.wel_start else R.string.wel_next), Modifier.width(220.dp),
                        icon = if (step == last) "check" else "right") {
                        if (step == last) state.closeAll(state.dockFocus()) else go(step + 1)
                    }
                }
            }
        }
    }
}

/** The content of one welcome step; a choice calls [go] with the next step. */
@Composable
private fun WelcomeStep(state: HomeState, step: Int, last: Int, go: (Int) -> Unit) {
    when (step) {
        0 -> PageColumn {
            BasicText(stringResource(R.string.wel_intro_text), Modifier.padding(end = 14.dp), style = T.style(20))
            Spacer(Modifier.height(18.dp))
            BasicText(stringResource(R.string.wel_intro_steps), Modifier.padding(end = 14.dp), style = T.style(17, color = T.dim))
            // Installing again, or on another TV: everything back in one go from the phone.
            Spacer(Modifier.height(18.dp))
            Pill(state, "wel:restore", stringResource(R.string.sys_restore), Modifier.fillMaxWidth(), icon = "upload",
                sub = stringResource(R.string.sys_restore_sub)) {
                android.util.Log.i("MyTV", "welcome: restore pressed")
                state.open(com.mytv.launcher.Overlay.Backup(restore = true), "wel:restore")
            }
        }
        1 -> PageColumn {
            val sysLang = systemLanguageName()
            (listOf<String?>(null) + LANGUAGES).forEach { l ->
                Pill(state, "lang:${l ?: "auto"}", l?.let { LANGUAGE_NAMES[it]!! } ?: stringResource(R.string.auto_value, sysLang),
                    Modifier.fillMaxWidth(), icon = "globe", selected = state.language == l) {
                    // On to the weather; a new language rebuilds the screen, which comes back on that step.
                    if (state.language == l) go(2) else { state.welcomeStep = 2; state.chooseLanguage(l) }
                }
            }
        }
        2 -> {
            // Fresh install: look up the approximate place right away and preselect it; Up reaches the search field.
            LaunchedEffect(Unit) { if (state.city == null) state.detectLocation { state.pendingFocus = "recent:0" } }
            WeatherPage(state) { go(3) }
        }
        3 -> WelcomeHomeStep(state) { go(4) }
        4 -> AutostartPage(state) { go(5) }
        5 -> {
            // The TV-listings permission is asked here (once), not at start-up over the welcome screen.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) {
                if (state.continueWatching && !com.mytv.launcher.data.WatchNext.allowed(ctx) && !state.prefs.flag("askedTvListings")) state.askTvListings()
            }
            PageColumn { ContinueWatchingSettings(state) }
        }
        6 -> PageColumn {
            val parent = stringResource(R.string.prof_parent); val child = stringResource(R.string.prof_child)
            Pill(state, "wp:none", stringResource(R.string.prof_only_me), Modifier.fillMaxWidth(), icon = "user",
                sub = stringResource(R.string.prof_only_me_sub), selected = state.profiles.isEmpty()) {
                state.disableProfiles(); go(last)
            }
            Pill(state, "wp:family", stringResource(R.string.prof_parent) + " + " + stringResource(R.string.prof_child), Modifier.fillMaxWidth(), icon = "user",
                sub = stringResource(R.string.prof_family_sub), selected = state.profiles.isNotEmpty()) {
                state.enableProfiles(parent, child); state.pendingFocus = "pin:1"
            }
            if (state.profiles.isNotEmpty()) {
                BasicText((if (state.hasPin) stringResource(R.string.prof_pin) + ": " + stringResource(R.string.prof_pin_set) else stringResource(R.string.prof_new_pin)) + " · " + stringResource(R.string.prof_pin_sub),
                    Modifier.padding(top = 10.dp, bottom = 8.dp), style = T.style(15, color = T.dim))
                PinPad(state) { pin -> state.setPin(pin); go(last) }
            }
        }
        else -> PageColumn {
            listOf(R.string.wel_done_text to "grid", R.string.wel_tip_buttons to "tv", R.string.wel_tip_gear to "gear").forEach { (text, icon) ->
                Row(Modifier.padding(bottom = 22.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineIcon(icon, 26.dp)
                    Spacer(Modifier.width(18.dp))
                    BasicText(stringResource(text), style = T.style(18))
                }
            }
        }
    }
}

/** Home button step: shows plainly whether the helper is on; switching it on goes to the next step. */
@Composable
private fun WelcomeHomeStep(state: HomeState, next: () -> Unit) {
    PageColumn {
        @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick     // re-read after returning from TV settings
        val on = state.homeWatchEnabled()
        val direct = com.mytv.launcher.data.SystemAccess.canWriteSecure(androidx.compose.ui.platform.LocalContext.current)
        // Switched on in the TV's own settings and back again: on to the next step.
        val wasOn = remember { on }
        LaunchedEffect(on) { if (on && !wasOn) next() }
        BasicText(stringResource(R.string.wel_home_text), Modifier.padding(bottom = 10.dp), style = T.style(16, color = T.dim))
        Pill(state, "wel:home", stringResource(when { on -> R.string.wel_home_is_on; direct -> R.string.wel_home_enable; else -> R.string.wel_open_access }),
            Modifier.fillMaxWidth(), icon = "power", sub = stringResource(if (on) R.string.sys_home_on else R.string.sys_home_off), selected = on) {
            when { on -> next(); direct -> { state.enableHomeWatch(); next() }; else -> state.openAccessibilitySettings() }
        }
        // GitHub edition with network debugging on: one OK sets it all up, no computer needed.
        if (!direct && state.adbReachable) Pill(state, "wel:rights", stringResource(R.string.sys_rights), Modifier.fillMaxWidth().padding(top = 8.dp), icon = "power",
            sub = stringResource(R.string.sys_rights_sub), value = if (state.adbBusy == "rights") "…" else null) { state.grantRights() }
        if (!on && !direct) BasicText(stringResource(R.string.wel_home_manual), Modifier.padding(top = 8.dp), style = T.style(16))
        Box(Modifier.padding(top = 8.dp)) { DefaultHomePill(state) }
    }
}

@Composable
private fun ProfilesPage(state: HomeState) {
    PageColumn(header = {
        BasicText(stringResource(R.string.prof_intro), Modifier.padding(end = 14.dp), style = T.style(14, color = T.dim))
    }) {
        state.profiles.forEachIndexed { i, p ->
            Pill(state, "prof:${p.id}", p.name, Modifier.fillMaxWidth(), selected = p.id == state.profileId, leading = { Avatar(p, i, 36.dp) },
                sub = stringResource(if (p.child) R.string.prof_child else R.string.prof_parent) + if (p.id == state.profileId) " · " + stringResource(R.string.prof_active) else "") {
                state.settingsSection = "profile:${p.id}"; state.pendingFocus = "pp:kind"
            }
        }
        // The first "add" also creates the profile for what is set up now (named "Profile 1"), so there are two.
        val first = stringResource(R.string.prof_new_name, 1)
        val next = stringResource(R.string.prof_new_name, maxOf(2, state.profiles.size + 1))
        Pill(state, "prof:add", stringResource(R.string.prof_add), Modifier.fillMaxWidth(), icon = "user",
            sub = if (state.profiles.isEmpty()) stringResource(R.string.prof_add_first_sub) else null) {
            val id = state.addProfile(next, first); state.settingsSection = "profile:$id"; state.pendingFocus = "pp:name"
        }
        SectionLabel(stringResource(R.string.grp_options))
        Pill(state, "prof:pin", stringResource(R.string.prof_pin), Modifier.fillMaxWidth(), icon = "eye-off", sub = stringResource(R.string.prof_pin_sub),
            value = stringResource(if (state.hasPin) R.string.prof_pin_set else R.string.prof_pin_none)) { state.open(com.mytv.launcher.Overlay.PinSetup, "prof:pin") }
        Pill(state, "prof:start", stringResource(R.string.prof_start), Modifier.fillMaxWidth(), icon = "power",
            value = stringResource(if (state.askWho) R.string.prof_start_ask else R.string.prof_start_last)) { state.changeAskWho(!state.askWho) }
        if (state.profiles.isNotEmpty()) Pill(state, "prof:off", stringResource(R.string.prof_off), Modifier.fillMaxWidth(), icon = "trash", sub = stringResource(R.string.prof_off_sub)) {
            state.disableProfiles(); state.pendingFocus = "prof:add"
        }
    }
}

@Composable
private fun ProfilePage(state: HomeState, id: String) {
    val p = state.profiles.firstOrNull { it.id == id } ?: run { state.settingsSection = "profiles"; return }
    val kb = LocalSoftwareKeyboardController.current
    var tf by remember(id) { mutableStateOf(TextFieldValue(p.name)) }
    var fieldFocused by remember { mutableStateOf(false) }
    var lastAdult by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    @Suppress("UNUSED_VARIABLE") val tick = state.profileTick
    PageColumn {
        // Name: OK opens the keyboard; leaving the field saves it.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (fieldFocused) Color.White else Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (fieldFocused) INK_ON_WHITE else Color.White
            LineIcon("user", 22.dp, color = fg)
            Spacer(Modifier.width(12.dp))
            BasicText(stringResource(R.string.prof_name), style = T.style(16, color = if (fieldFocused) Color(0xFF4A515B) else T.dim))
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = tf, onValueChange = { tf = it }, singleLine = true,
                textStyle = T.style(18, FontWeight.Medium, fg), cursorBrush = SolidColor(fg),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { kb?.hide(); state.renameProfile(id, tf.text) }),
                modifier = Modifier.weight(1f).focusRequester(field).registerAs(state, "pp:name", field)
                    .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) state.renameProfile(id, tf.text) }
                    .tvTextKeys(),
            )
        }
        // Profile picture: the initial letter or one of the coloured symbols.
        BasicText(stringResource(R.string.prof_icon), Modifier.padding(top = 6.dp), style = T.style(14, color = T.dim))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val index = state.profiles.indexOfFirst { it.id == id }
            (listOf(-1) + AVATARS.indices).forEach { icon ->
                var f by remember { mutableStateOf(false) }
                val sc by animateFloatAsState(if (f) 1.12f else 1f, tween(140), label = "av")
                Box(
                    Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.tvClick { state.setProfileIcon(id, icon) }
                        .focusKey(state, "pp:icon:$icon") { f = it }
                        .border(3.dp, if (f) Color.White else if (p.icon == icon) Color.White.copy(alpha = 0.85f) else Color.Transparent, CircleShape)
                        .padding(4.dp),
                ) { Avatar(p.copy(icon = icon), index, 48.dp) }
            }
        }
        Pill(state, "pp:kind", stringResource(R.string.prof_kind), Modifier.fillMaxWidth(), icon = "user",
            sub = if (lastAdult) stringResource(R.string.prof_last_adult) else stringResource(R.string.prof_kind_sub),
            value = stringResource(if (p.child) R.string.prof_child else R.string.prof_parent)) { lastAdult = !state.setChild(id, !p.child) }
        // Plex linked: which Plex user's Continue watching this profile shows (e.g. the children's own Plex user).
        val ctx = androidx.compose.ui.platform.LocalContext.current
        @Suppress("UNUSED_VARIABLE") val pt = state.plexTick
        if (com.mytv.launcher.data.Plex.linked(ctx)) Pill(state, "pp:plex", "Plex", Modifier.fillMaxWidth(), icon = "tv", sub = stringResource(R.string.plex_user_sub),
            value = com.mytv.launcher.data.Plex.userName(ctx, id) ?: stringResource(R.string.plex_user_default)) {
            state.open(com.mytv.launcher.Overlay.PlexUser(id), "pp:plex")
        }
        if (id != state.profileId) Pill(state, "pp:switch", stringResource(R.string.prof_switch_to), Modifier.fillMaxWidth(), icon = "right") {
            state.switchProfile(id); state.closeAll("app:" + (state.visibleApps.firstOrNull()?.key ?: ""))
        }
        if (id.isNotEmpty()) Pill(state, "pp:delete", stringResource(if (armed) R.string.prof_delete_confirm else R.string.prof_delete), Modifier.fillMaxWidth(), icon = "trash") {
            if (armed) { state.settingsSection = "profiles"; state.pendingFocus = "prof:add"; state.deleteProfile(id) } else armed = true
        }
        // Only a child profile gets a chosen set of apps; a parent profile simply has all apps (and its own dock order).
        if (!p.child) return@PageColumn
        SectionLabel(stringResource(R.string.prof_apps))
        val hidden = state.hiddenFor(id).let { h ->
            // A child profile that never ran: it starts with the apps of the dock.
            if (id != state.profileId && p.child && state.profileTick == 0 && h.isEmpty()) state.childStartHidden() else h
        }
        // The apps of the dock first, in their order; then the others by name.
        allAppsInOrder(state).forEach { app ->
            val on = app.key !in hidden
            ToggleRow(state, "ppapp:${app.key}", app.label, app.icon, on, "") {
                state.setHiddenFor(id, app.key, on)
            }
        }
    }
}


@Composable
private fun NowPlayingPage(state: HomeState) {
    @Suppress("UNUSED_VARIABLE") val tick = state.resumeTick
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PageColumn {
        Pill(state, "np:on", stringResource(R.string.np_show), Modifier.fillMaxWidth(), icon = "play", sub = stringResource(R.string.disp_np_sub),
            value = stringResource(if (state.nowPlayingOn) R.string.on else R.string.off)) { state.changeNowPlaying(!state.nowPlayingOn) }
        if (!state.nowPlayingOn) return@PageColumn
        SectionLabel(stringResource(R.string.grp_sources))
        fun found(names: List<String>) = if (names.isEmpty()) null else names.distinct().joinToString(", ")
        val sonos = found(com.mytv.launcher.data.Sonos.roomNames())
        val cast = found(com.mytv.launcher.data.Cast.names())
        if (sonos != null) Pill(state, "np:sonos", stringResource(R.string.np_src_sonos), Modifier.fillMaxWidth(), icon = "play",
            sub = stringResource(R.string.np_found, sonos),
            value = stringResource(if (state.npSonos) R.string.on else R.string.off)) { state.changeNpSource("npSonos", !state.npSonos) }
        if (cast != null) Pill(state, "np:cast", stringResource(R.string.np_src_cast), Modifier.fillMaxWidth(), icon = "play",
            sub = stringResource(R.string.np_found, cast),
            value = stringResource(if (state.npCast) R.string.on else R.string.off)) { state.changeNpSource("npCast", !state.npCast) }
        // State, so the row updates as soon as access is granted (not only on the next redraw).
        LaunchedEffect(state.resumeTick) { state.refreshNpAppsAllowed() }
        val other = found(com.mytv.launcher.data.Speakers.names())
        if (other != null) Pill(state, "np:other", stringResource(R.string.np_src_other), Modifier.fillMaxWidth(), icon = "play",
            sub = stringResource(R.string.np_found, other),
            value = stringResource(if (state.npOther) R.string.on else R.string.off)) { state.changeNpSource("npOther", !state.npOther) }
        val allowed = state.npAppsAllowed
        // Only apps that were found reporting what they play; otherwise a general line (no brand names).
        val apps = remember(state.resumeTick, allowed) { found(com.mytv.launcher.data.MediaApps.reportingApps(ctx)) }
        Pill(state, "np:apps", stringResource(R.string.np_src_apps), Modifier.fillMaxWidth(), icon = "tv",
            sub = if (state.npApps && !allowed) stringResource(R.string.np_apps_access)
                else apps?.let { stringResource(R.string.np_found, it) } ?: stringResource(R.string.np_apps_sub),
            value = stringResource(if (state.npApps && allowed) R.string.on else R.string.off)) {
            if (state.npApps && !allowed) state.changeNpSource("npApps", true) else state.changeNpSource("npApps", !state.npApps)
        }
    }
}


/**
 * Continue watching: on/off, Plex (its list comes from the viewer's own server, once linked) and which apps take part.
 * The same lines in Settings and in the welcome steps.
 */
@Composable
private fun ContinueWatchingSettings(state: HomeState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE") val pt = state.plexTick
    LaunchedEffect(state.resumeTick) { state.loadCwSources() }
    // Built like Now playing: "show …" first, then its sources, each with On/Off.
    Pill(state, "cw:on", stringResource(R.string.cw_show), Modifier.fillMaxWidth(), icon = "tv", sub = stringResource(R.string.disp_continue_sub),
        value = stringResource(if (state.continueWatching) R.string.on else R.string.off)) { state.changeContinueWatching(!state.continueWatching) }
    if (!state.continueWatching) return
    // Without the TV-listings permission apps' own "continue watching" rows stay invisible: say so, and ask from here.
    val allowed = remember(state.resumeTick) { com.mytv.launcher.data.WatchNext.allowed(ctx) }
    if (!allowed) Pill(state, "cw:allow", stringResource(R.string.cw_allow), Modifier.fillMaxWidth(), icon = "eye",
        sub = stringResource(R.string.cw_allow_sub), value = stringResource(R.string.cw_allow_action)) { state.askTvListings() }
    SectionLabel(stringResource(R.string.grp_sources))
    if (remember { com.mytv.launcher.data.Plex.installed(ctx) }) Pill(state, "cw:plex", "Plex", Modifier.fillMaxWidth(), icon = "tv",
        sub = stringResource(R.string.plex_sub),
        value = com.mytv.launcher.data.Plex.serverName(ctx) ?: stringResource(R.string.plex_link),
        selected = com.mytv.launcher.data.Plex.linked(ctx)) { state.open(com.mytv.launcher.Overlay.PlexLink, "cw:plex") }
    if (state.cwSources.isEmpty()) BasicText(stringResource(R.string.cw_apps_none), Modifier.padding(start = 10.dp, end = 14.dp), style = T.style(15, color = T.dim))
    state.cwSources.forEach { pkg ->
        val app = state.apps.firstOrNull { it.component.packageName == pkg }
        val label = app?.label ?: remember(pkg) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
        Pill(state, "cwa:$pkg", label, Modifier.fillMaxWidth(), image = app?.icon, icon = if (app?.icon == null) "tv" else null,
            value = stringResource(if (pkg !in state.cwOff) R.string.on else R.string.off)) { state.setCwApp(pkg, pkg in state.cwOff) }
    }
}

@Composable
private fun buttonLabel(state: HomeState, v: String): String = when {
    v == "allapps" -> stringResource(R.string.all_apps)
    v == "sources" -> stringResource(R.string.sources)
    v == "android" -> stringResource(R.string.sys_android)
    v == "stock" -> stringResource(R.string.btn_google_tv)
    v == "sleep" -> stringResource(R.string.sleep_timer)
    v == "bluetooth" -> stringResource(R.string.bluetooth)
    v.startsWith("app:") -> state.apps.firstOrNull { it.key == v.removePrefix("app:") }?.label ?: stringResource(R.string.btn_none)
    else -> stringResource(R.string.btn_none)
}

/** What a top-right button does: a function or one of the apps. */
@Composable
private fun ButtonChoicePage(state: HomeState, slot: Int) {
    val cur = if (slot == 1) state.button1 else state.button2
    fun pick(v: String) { state.setButton(slot, v); state.settingsSection = "home"; state.pendingFocus = "disp:btn$slot" }
    PageColumn { ButtonChoices(state, cur, "bc") { pick(it) } }
}

/** What a button can do: nothing, one of MyTV's screens or functions, or opening an app. */
@Composable
private fun ButtonChoices(state: HomeState, cur: String, prefix: String, pick: (String) -> Unit) {
    listOfNotNull("none" to "eye-off", "allapps" to "grid", "sources" to "input", ("sleep" to "timer").takeIf { state.canSleep },
        "bluetooth" to "bluetooth", "android" to "gear", "stock" to "tv").forEach { (v, icon) ->
        Pill(state, "$prefix:$v", buttonLabel(state, v), Modifier.fillMaxWidth(), icon = icon, selected = cur == v) { pick(v) }
    }
    SectionLabel(stringResource(R.string.grp_an_app))
    allAppsInOrder(state).forEach { app ->
        Pill(state, "$prefix:app:${app.key}", app.label, Modifier.fillMaxWidth(), image = app.icon, selected = cur == "app:${app.key}") { pick("app:${app.key}") }
    }
}


/**
 * Remote-control keys for a text field: OK opens the keyboard; Down/Up leave the field (this TV's text fields keep
 * them for themselves, so focus would otherwise stay stuck in the field).
 */
@Composable
internal fun Modifier.tvTextKeys(): Modifier {
    val kb = LocalSoftwareKeyboardController.current
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        when {
            (e.key == Key.DirectionCenter || e.key == Key.Enter) && e.type == KeyEventType.KeyUp -> { kb?.show(); true }
            e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown -> { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
            e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown -> { kb?.hide(); fm.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
            else -> false
        }
    }
}
