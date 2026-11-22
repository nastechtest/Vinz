package com.vinz.music.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.RoundAction
import com.vinz.music.ui.components.SearchField
import com.vinz.music.ui.screens.AccountCard
import com.vinz.music.ui.screens.NavRow
import com.vinz.music.ui.screens.PERFORMANCE_PAUSES
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalIsDark
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The root's own lines in Settings' search: what sits on the root itself, not on a page. */
val rootIndex: List<SettingEntry> by lazy {
    listOf(
        SettingEntry(null, tr(R.string.account), tr(R.string.account), tr(R.string.google_youtube_sign_in_sign_out)),
        SettingEntry(null, tr(R.string.account), tr(R.string.vinz_account), tr(R.string.profile_name_picture_photo_local_on)),
        SettingEntry(null, tr(R.string.performance), tr(R.string.performance_mode), tr(R.string.slow_lag_laggy_stutter_battery_old)),
        SettingEntry(null, tr(R.string.performance), tr(R.string.performance_mode_is_on), tr(R.string.paused_effects_turn_off)),
        SettingEntry(null, tr(R.string.performance), tr(R.string.smooth_scrolling), tr(R.string.smooth_scrolling_keywords)),
    )
}

/** On a root entry's row: the key of the page it opens (for tests; the row's test tag is the kit's Link row's). */
val SettingsRootEntry = SemanticsPropertyKey<String>("settings_root_entry")
var SemanticsPropertyReceiver.settingsRootEntry by SettingsRootEntry

/** The root's search field, and the list of results under it. */
object SettingsRootTags {
    const val SEARCH = "settings:root:search"
    const val RESULTS = "settings:root:results"
}

/**
 * The Settings root (1.8.4, plan §2.3): the search field, the account card, Performance mode, then the map's 17 pages
 * in its 5 groups, each a row with its icon on a tile and its state in words. Every page is one tap away: there are no
 * pages of pages any more ("two taps, never three").
 *
 * - [showBack] false: Settings is a tab (a root page with nothing to go back to).
 * - [selected]: the page open beside the root on a wide window, marked in the list.
 * - [searchFocus]: lets the page beside the root put the cursor in the search field (its bar's search button).
 * - [onOpen]: a row, or a search result on a page, was tapped: open that page (at that group, for a result).
 *
 * Call it inside `StoredSettings`: the rows say what the listener chose, also while Performance mode draws lighter.
 */
@Composable
fun SettingsRoot(
    bottomPadding: Dp,
    showBack: Boolean,
    selected: SettingsPageId? = null,
    searchFocus: FocusRequester? = null,
    onOpen: (SettingsPageId, String?) -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    // #336: whether this screen has a mode faster than its slowest (90 or 120 Hz); read once, it doesn't change.
    val view = androidx.compose.ui.platform.LocalView.current
    val fastScreen = remember(view) {
        (view.context as? android.app.Activity)?.let { a ->
            runCatching { com.vinz.music.ui.displayModes(com.vinz.music.ui.displayOf(a)).let { (m, id) -> com.vinz.music.ui.hasFasterMode(m, id) } }.getOrDefault(false)
        } ?: false
    }
    SettingsRootContent(
        bottomPadding, LocalAppSettings.current, LocalIsDark.current, showBack, selected,
        account = { AccountCard() }, onPerformance = { c.settings.setPerformanceMode(it) },
        onOpen = onOpen, onRoute = { nav.go(it) }, searchFocus = searchFocus,
        fastScreen = fastScreen, onSmoothScrolling = { c.settings.setSmoothScrolling(it) },
    )
}

/**
 * The root itself, on plain values (drawn in tests without the app behind it). [account]: the account card.
 * [onRoute]: a search result on a screen that isn't a settings page (Customize Home) was tapped.
 */
@Composable
internal fun SettingsRootContent(
    bottomPadding: Dp,
    settings: AppSettings,
    dark: Boolean,
    showBack: Boolean,
    selected: SettingsPageId?,
    account: @Composable () -> Unit,
    onPerformance: (Boolean) -> Unit,
    onOpen: (SettingsPageId, String?) -> Unit,
    onRoute: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    searchFocus: FocusRequester? = null,
    /** #336: the screen can go faster than 60 Hz; on a screen that can't, "Smoother scrolling" is left out (unless on). */
    fastScreen: Boolean = false,
    onSmoothScrolling: (Boolean) -> Unit = {},
) {
    // Search (#73): matching settings in place of the groups. A result on the root itself (the account, Performance
    // mode) is found here; one on a page opens that page at its group.
    var query by rememberSaveable { mutableStateOf("") }
    var foundHere by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(query) { if (query.isBlank()) emptyList() else SettingsIndex.search(query) }
    // The search is ready before the first letter, without the main thread reading the whole index (#355).
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { SettingsIndex.prepare() } }
    val accountKey = tr(R.string.account)
    val performanceKey = tr(R.string.performance)
    val groupCount = SettingsMap.groups.size

    SettingsPageScaffold(tr(R.string.settings), bottomPadding, showBack = showBack, onBack = onBack, find = foundHere) {
        item("find") {
            SearchField(
                query, tr(R.string.search_settings), { query = it; foundHere = null },
                Modifier.testTag(SettingsRootTags.SEARCH).then(if (searchFocus != null) Modifier.focusRequester(searchFocus) else Modifier),
            )
        }
        if (query.isNotBlank()) {
            item("results") {
                SettingsSearchResults(query, results, settings) { e ->
                    // The keyboard goes with the search: left up, the page's first Back only closed it.
                    keyboard?.hide()
                    when {
                        // A page outside Settings' own (Customize Home) opens as itself, beside the root or not.
                        e.route != null -> onRoute(e.route)
                        e.page == null -> { query = ""; foundHere = e.group }
                        else -> onOpen(e.page, e.group.ifBlank { null })
                    }
                }
            }
            return@SettingsPageScaffold
        }
        item(accountKey) { account() }
        // Performance mode (1.7.6): one switch for slower phones. Nothing is removed and no choice is changed: the
        // effects that cost the most on every frame pause until it's off again.
        group(null, key = performanceKey) {
            SwitchRow(
                tr(R.string.performance_mode), settings.performanceMode,
                value = if (settings.performanceMode) tr(R.string.on_vinz_is_drawn_lightly) else tr(R.string.for_slower_phones_lighter_fewer_effects),
                info = tr(R.string.pauses_every_feature_still_works_and, PERFORMANCE_PAUSES.lowercase()),
                onChange = onPerformance,
            )
            // #336 (1.8.7): the screen's fastest refresh rate while Vinz is open. Left out on a screen with nothing
            // faster than 60 Hz, as other pages leave out what a phone can't do; still shown while on, to turn it off.
            if (fastScreen || settings.behavior.smoothScrolling) SwitchRow(
                tr(R.string.smooth_scrolling), settings.behavior.smoothScrolling,
                info = tr(R.string.smooth_scrolling_info),
                onChange = onSmoothScrolling,
            )
        }
        // Not `group(...)`: five titled groups would give the root jump chips, and it has its search for that.
        SettingsMap.groups.forEachIndexed { i, (group, pages) ->
            item("group:" + group.name) {
                // Each group's tiles are one stop of the listener's light, red at the top to violet at the foot.
                val tile = VinzTokens.spectrumAt(i / (groupCount - 1).coerceAtLeast(1).toFloat())
                SettingsGroup(group.title) {
                    pages.forEach { p ->
                        RootRow(p.key, p.icon, tile, p.title, p.value(settings, dark), selected = p == selected) { onOpen(p, null) }
                    }
                }
            }
        }
    }
}

/** A root entry's tile: 36 dp, rounded square, a white glyph. */
private val TILE = 36.dp
private val TILE_GAP = 14.dp

/**
 * A root entry: the page's icon in white on a 36 dp rounded-square tile of its group's color, the page's name and,
 * under it, its state in words ([value]). A Link row of the kit in everything else (its heights, gutters, type, test
 * tag and row value), which has no tile; hence its own composable. One line of value at the usual text size (it ends in
 * an ellipsis, as on the mockup); at large text it wraps and the row grows.
 */
@Composable
internal fun RootRow(pageKey: String, icon: ImageVector, tile: Color, title: String, value: String, selected: Boolean = false, onClick: () -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val large = LocalDensity.current.fontScale >= 1.3f
    Row(
        Modifier.fillMaxWidth().then(HairlineInset(16.dp + TILE + TILE_GAP))
            .testTag(SettingsTags.ROW_LINK)
            .semantics { settingsRowValue = value; settingsRowChanged = false; settingsRootEntry = pageKey; this.selected = selected }
            // The page open beside the root (wide windows) is marked.
            .then(if (selected) Modifier.background(scheme.onSurface.copy(alpha = 0.08f)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = ui.gap(64.dp).coerceAtLeast(48.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(TILE).clip(RoundedCornerShape(10.dp)).background(tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
        }
        Spacer(Modifier.width(TILE_GAP))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (value.isNotBlank()) Text(
                value, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface.copy(alpha = 0.7f),
                maxLines = if (large) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(VinzIcons.ChevronRight, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
    }
}

/**
 * What Settings' search found for [query]: up to 30 results in one card, each with where it lives ("Player › Cover")
 * and, when it's hidden behind another switch, what to turn on first; or a line saying nothing matches.
 */
@Composable
internal fun SettingsSearchResults(query: String, results: List<SettingEntry>, settings: AppSettings, onOpen: (SettingEntry) -> Unit) {
    if (results.isEmpty()) {
        Text(
            tr(R.string.no_settings_match, query.trim()),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).testTag(SettingsRootTags.RESULTS),
        )
        return
    }
    SettingsGroup(null, Modifier.testTag(SettingsRootTags.RESULTS)) {
        results.take(30).forEach { e ->
            // Keyed: a result still there after the next letter keeps its row, so its text isn't laid out again.
            key(e.page, e.route, e.group, e.title) {
                // Hidden until another switch is on: the result says so, and opens at that switch's group.
                val hidden = e.needs?.takeIf { !it.on(settings) }
                // A page's own line says which of the root's groups it's in.
                val where = if (e.isPage) e.page?.group?.title.orEmpty()
                else listOf(e.pageTitle, e.group).filter { it.isNotBlank() && it != e.title }.joinToString(" › ")
                NavRow(e.page?.icon ?: VinzIcons.Settings, e.shown, where, hint = hidden?.hint) { onOpen(e) }
            }
        }
    }
}

/**
 * Settings' search over a page (its bar's search button): the same field and the same results as on the root. A result
 * opens its page; Back, or the button beside the field, closes the search and leaves the page as it was.
 */
@Composable
internal fun SettingsSearchOverlay(bottomPadding: Dp, onClose: () -> Unit) {
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query) { if (query.isBlank()) emptyList() else SettingsIndex.search(query) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { SettingsIndex.prepare() }
        runCatching { focus.requestFocus() }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            // The page under it isn't tapped through.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .statusBarsPadding().padding(horizontal = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction(VinzIcons.ArrowBack, tr(R.string.root_close_search), glass = true, onClick = onClose)
            Spacer(Modifier.width(8.dp))
            SearchField(query, tr(R.string.search_settings), { query = it }, Modifier.weight(1f).testTag(SettingsRootTags.SEARCH).focusRequester(focus))
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = bottomPadding + 28.dp)) {
            if (query.isNotBlank()) SettingsSearchResults(query, results, settings) { e ->
                keyboard?.hide()
                onClose()
                // The root's own (the account, Performance mode) are on the root.
                nav.go(e.opens ?: Routes.SETTINGS)
            }
        }
    }
}
