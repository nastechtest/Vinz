package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.CardSize
import com.vinz.music.data.prefs.MiniPlayerStyle
import com.vinz.music.data.prefs.MiniProgress
import com.vinz.music.data.prefs.NavBarFill
import com.vinz.music.data.prefs.NavBarSize
import com.vinz.music.data.prefs.NavBarStyle
import com.vinz.music.data.prefs.NavLabels
import com.vinz.music.data.prefs.NavTab
import com.vinz.music.data.prefs.ScrubberColor
import com.vinz.music.data.prefs.TabBarPosition
import com.vinz.music.data.prefs.TabTint
import com.vinz.music.data.prefs.TitleSize
import com.vinz.music.data.prefs.UiDensity
import com.vinz.music.data.prefs.cleanTabName
import com.vinz.music.tr
import com.vinz.music.ui.components.DragGrip
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.VinzTextField
import com.vinz.music.ui.components.ReorderList
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.VinzCheck
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalWindowLayout
import com.vinz.music.ui.theme.VinzIcons
import kotlin.math.roundToInt

private val page = SettingsPageId.TABS_MINI_PLAYER

// ---------------------------------------------------------------------------- §2.5: the status bar, one choice

/**
 * The status bar (plan §2.5): "Hide the status bar" and "Hide it only on the player" as one choice. Underneath it is
 * still the two stored switches (`hide_status_bar`, `hide_status_bar_player`).
 */
enum class StatusBarChoice(val label: String) {
    SHOWN(tr(R.string.shown)),
    HIDDEN_ON_THE_PLAYER(tr(R.string.status_bar_hidden_on_the_player)),
    HIDDEN_EVERYWHERE(tr(R.string.status_bar_hidden_everywhere)),
}

/** The two stored switches as the one choice. Hidden everywhere wins: the player's own switch is moot under it. */
fun statusBarToMerged(hide: Boolean, hidePlayer: Boolean): StatusBarChoice = when {
    hide -> StatusBarChoice.HIDDEN_EVERYWHERE
    hidePlayer -> StatusBarChoice.HIDDEN_ON_THE_PLAYER
    else -> StatusBarChoice.SHOWN
}

/**
 * What [choice] stores, as (`hide_status_bar`, `hide_status_bar_player`). Hidden everywhere leaves the player's own
 * switch as it is ([hidePlayerNow]), as the old page did (it only hid that switch), so both-on stays both-on.
 */
fun statusBarToStored(choice: StatusBarChoice, hidePlayerNow: Boolean): Pair<Boolean, Boolean> = when (choice) {
    StatusBarChoice.SHOWN -> false to false
    StatusBarChoice.HIDDEN_ON_THE_PLAYER -> false to true
    StatusBarChoice.HIDDEN_EVERYWHERE -> true to hidePlayerNow
}

// ---------------------------------------------------------------------------- §2.5: the mini player's buttons, show chips

/** The mini player's buttons (plan §2.5): three switches as chips that tick on and off. Each keeps its own stored value. */
enum class MiniButton(val label: String) {
    PREVIOUS(tr(R.string.widget_previous)), PLAY_PAUSE(tr(R.string.play_pause)), NEXT(tr(R.string.widget_next)),
}

/** The three stored switches (`mini_prev_button`, `mini_play_button`, `mini_skip_buttons`) as the buttons that show. */
fun miniButtonsToMerged(previous: Boolean, playPause: Boolean, next: Boolean): Set<MiniButton> = buildSet {
    if (previous) add(MiniButton.PREVIOUS)
    if (playPause) add(MiniButton.PLAY_PAUSE)
    if (next) add(MiniButton.NEXT)
}

/** The buttons that show, back as (`mini_prev_button`, `mini_play_button`, `mini_skip_buttons`). */
fun miniButtonsToStored(shown: Set<MiniButton>): Triple<Boolean, Boolean, Boolean> =
    Triple(MiniButton.PREVIOUS in shown, MiniButton.PLAY_PAUSE in shown, MiniButton.NEXT in shown)

private fun miniButtonsText(shown: Set<MiniButton>) = MiniButton.entries.filter { it in shown }.joinToString(" · ") { it.label }

// ---------------------------------------------------------------------------- The page

/** The tabs' reorder list: a list, not one of the five rows (plan §2.1.6). The kit has no tag for one yet. */
internal const val SETTINGS_LIST_TAG = "settings:list"

private fun onOff(on: Boolean) = if (on) tr(R.string.on_2) else tr(R.string.off)

/** "Plain" for the mini player's white (it is the text's color there, black in light mode). */
private fun progressColorName(c: ScrubberColor) = if (c == ScrubberColor.WHITE) tr(R.string.plain) else c.label

/**
 * Settings → Tabs & mini player (1.8.4's map, plan §2.3), rebuilt with the kit. This file is the only place for the
 * page: its composable, its lines in Settings' search ([tabsMiniPlayerIndex]), its "Reset this page" keys
 * ([tabsMiniPlayerLookKeys]), its line on the root ([tabsMiniPlayerValue]) and the old group names it answers to
 * ([tabsMiniPlayerOldGroups]).
 *
 * Four groups, as the ledger proposes: **Navigation bar**, **Tabs** (the tabs' list as it was, and the tab Vinz opens
 * on), **Mini player** and **Pages** (spacing, titles, cards and the status bar). No pinned preview: the old page drew
 * no bar or mini player to reuse. "Swipe between tabs" is Gestures & buttons'.
 */
@Composable
fun TabsMiniPlayerPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val tabs = s.navTabs
    fun name(t: NavTab) = s.tabNames[t] ?: t.label
    val start = if (s.startTab in tabs) s.startTab else tabs.first()
    val status = statusBarToMerged(s.hideStatusBar, s.hideStatusBarPlayer)
    val statusDefault = statusBarToMerged(d.hideStatusBar, d.hideStatusBarPlayer)
    val buttons = miniButtonsToMerged(s.miniPrevButton, s.view.miniPlayButton, s.miniSkipButtons)
    val buttonsDefault = miniButtonsToMerged(d.miniPrevButton, d.view.miniPlayButton, d.miniSkipButtons)

    // Every row with its value now and as Vinz comes: "Reset this page" lists the ones that differ. (Two rows are
    // called Style: the list names them by their group.)
    val rows = listOf(
        Triple(tr(R.string.navigation_bar), s.navStyle.label, d.navStyle.label),
        Triple(tr(R.string.labels), s.navLabels.label, d.navLabels.label),
        Triple(tr(R.string.height), s.navBarSize.label, d.navBarSize.label),
        Triple(tr(R.string.background), s.navBarFill.label, d.navBarFill.label),
        Triple(tr(R.string.tabs_on_a_tablet), s.behavior.tabBarPosition.label, d.behavior.tabBarPosition.label),
        Triple(tr(R.string.the_tab_you_re_on), s.shapes.tabTint.label, d.shapes.tabTint.label),
        Triple(tr(R.string.shrink_while_scrolling), onOff(s.navHideOnScroll), onOff(d.navHideOnScroll)),
        Triple(tr(R.string.tabs), tabs.joinToString(" · ") { name(it) }, d.navTabs.joinToString(" · ") { it.label }),
        Triple(tr(R.string.open_vinz_on), start.label, d.startTab.label),
        Triple(tr(R.string.mini_player), s.miniPlayerStyle.label, d.miniPlayerStyle.label),
        Triple(tr(R.string.joined_to_the_tab_bar), onOff(s.miniPlayerJoined), onOff(d.miniPlayerJoined)),
        Triple(tr(R.string.mini_player_buttons), miniButtonsText(buttons), miniButtonsText(buttonsDefault)),
        Triple(tr(R.string.progress), s.miniProgress.label, d.miniProgress.label),
        Triple(tr(R.string.mini_player_progress_color_row), progressColorName(s.view.miniProgressColor), progressColorName(d.view.miniProgressColor)),
        Triple(tr(R.string.fill_strength), percent(s.view.miniFillStrength), percent(d.view.miniFillStrength)),
        Triple(tr(R.string.spacing), s.uiDensity.label, d.uiDensity.label),
        Triple(tr(R.string.page_titles), s.titleSize.label, d.titleSize.label),
        Triple(tr(R.string.album_playlist_cards), s.cardSize.label, d.cardSize.label),
        Triple(tr(R.string.status_bar), status.label, statusDefault.label),
    )
    val reset = PageReset(rows.filter { it.second != it.third }.map { resetLine(it.first, it.second, it.third) }) {
        prefs.resetLookKeys(tabsMiniPlayerLookKeys)
        // Not part of a Look: put back by its own setter.
        if (s.behavior.tabBarPosition != d.behavior.tabBarPosition) prefs.setTabBarPosition(d.behavior.tabBarPosition)
    }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, reset = reset, find = find) {
        group(tr(R.string.navigation_bar)) { TabsMiniPlayerBarRows() }
        group(tr(R.string.tabs), info = tr(R.string.tick_up_to_tabs_and_hold, NavTab.MAX)) {
            TabsList()
            ChoiceRow(
                tr(R.string.open_vinz_on), tabs, start, { name(it) }, info = tr(R.string.from_vinz_s_next_start), changed = start != d.startTab,
            ) { prefs.setStartTab(it) }
        }
        group(tr(R.string.mini_player)) { TabsMiniPlayerMiniRows() }
        group(tr(R.string.pages)) { TabsMiniPlayerPagesRows() }
    }
}

/** The Navigation bar group's rows: on the page, and in "Customize this" for the tab bar. */
@Composable
internal fun TabsMiniPlayerBarRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val tablet = LocalWindowLayout.current.rail
    ChoiceRow(tr(R.string.style), NavBarStyle.entries, s.navStyle, { it.label }, changed = s.navStyle != d.navStyle) { prefs.setNavStyle(it) }
    // The Minimal bar has no labels to show. A Pick, not chips: the kit draws four chips as one capsule, which
    // cuts "Words only" short on a 360 dp phone.
    if (s.navStyle != NavBarStyle.MINIMAL) PickListRow(
        tr(R.string.labels), NavLabels.entries, s.navLabels, { it.label }, info = tr(R.string.never_shows_only_the_icons_words),
        changed = s.navLabels != d.navLabels, searchable = false,
    ) { prefs.setNavLabels(it) }
    ChoiceRow(tr(R.string.height), NavBarSize.entries, s.navBarSize, { it.label }, changed = s.navBarSize != d.navBarSize) { prefs.setNavBarSize(it) }
    ChoiceRow(
        tr(R.string.background), NavBarFill.entries, s.navBarFill, { it.label }, info = tr(R.string.see_through_lets_the_page_show),
        changed = s.navBarFill != d.navBarFill,
    ) { prefs.setNavBarFill(it) }
    // #264 (1.8.0): only where it means something, a tablet or wide window (phones: always the bottom).
    if (tablet) ChoiceRow(
        tr(R.string.tabs_on_a_tablet), TabBarPosition.entries, s.behavior.tabBarPosition, { it.label }, info = tr(R.string.tabs_on_a_tablet_info),
        changed = s.behavior.tabBarPosition != d.behavior.tabBarPosition,
    ) { prefs.setTabBarPosition(it) }
    // A color only: chips (plan §2.4).
    ChoiceRow(tr(R.string.the_tab_you_re_on), TabTint.entries, s.shapes.tabTint, { it.label }, changed = s.shapes.tabTint != d.shapes.tabTint) { prefs.setTabTint(it) }
    SwitchRow(tr(R.string.shrink_while_scrolling), s.navHideOnScroll, changed = s.navHideOnScroll != d.navHideOnScroll) { prefs.setNavHideOnScroll(it) }
}

/** The Mini player group's rows: on the page, and in "Customize this" for the mini player. */
@Composable
internal fun TabsMiniPlayerMiniRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val buttons = miniButtonsToMerged(s.miniPrevButton, s.view.miniPlayButton, s.miniSkipButtons)
    val buttonsDefault = miniButtonsToMerged(d.miniPrevButton, d.view.miniPlayButton, d.miniSkipButtons)
    ChoiceRow(tr(R.string.style), MiniPlayerStyle.entries, s.miniPlayerStyle, { it.label }, changed = s.miniPlayerStyle != d.miniPlayerStyle) { prefs.setMiniPlayerStyle(it) }
    if (s.navStyle == NavBarStyle.FLOATING) SwitchRow(
        tr(R.string.joined_to_the_tab_bar), s.miniPlayerJoined, info = tr(R.string.the_mini_player_sits_on_top),
        changed = s.miniPlayerJoined != d.miniPlayerJoined,
    ) { prefs.setMiniPlayerJoined(it) }
    ShowChipsRow(
        tr(R.string.mini_player_buttons),
        listOf(
            ShowOption(MiniButton.PREVIOUS.label, MiniButton.PREVIOUS in buttons) { prefs.setMiniPrevButton(it) },
            ShowOption(MiniButton.PLAY_PAUSE.label, MiniButton.PLAY_PAUSE in buttons) { prefs.setMiniPlayButton(it) },
            ShowOption(MiniButton.NEXT.label, MiniButton.NEXT in buttons) { prefs.setMiniSkipButtons(it) },
        ),
        info = listOf(R.string.swiping_right_goes_back_too, R.string.swiping_left_skips_too, R.string.the_player_and_your_phone_s_controls_still_have_it).joinToString(" · ") { tr(it) },
        changed = buttons != buttonsDefault,
    )
    ChoiceRow(tr(R.string.progress), MiniProgress.entries, s.miniProgress, { it.label }, changed = s.miniProgress != d.miniProgress) { prefs.setMiniProgress(it) }
    // #231 (1.7.9.2): its color, and how strongly Fill tints the card.
    if (s.miniProgress != MiniProgress.NONE) ChoiceRow(
        tr(R.string.mini_player_progress_color_row), ScrubberColor.entries, s.view.miniProgressColor, ::progressColorName,
        changed = s.view.miniProgressColor != d.view.miniProgressColor,
    ) { prefs.setMiniProgressColor(it) }
    if (s.miniProgress == MiniProgress.FILL) SliderRow(
        tr(R.string.fill_strength), s.view.miniFillStrength, 0.1f..0.9f, ::percent, changed = s.view.miniFillStrength != d.view.miniFillStrength,
    ) { prefs.setMiniFillStrength(it) }
}

/** The Pages group's rows: on the page, and in "Customize this" for a Home section. */
@Composable
internal fun TabsMiniPlayerPagesRows() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    val status = statusBarToMerged(s.hideStatusBar, s.hideStatusBarPlayer)
    val statusDefault = statusBarToMerged(d.hideStatusBar, d.hideStatusBarPlayer)
    ChoiceRow(tr(R.string.spacing), UiDensity.entries, s.uiDensity, { it.label }, changed = s.uiDensity != d.uiDensity) { prefs.setUiDensity(it) }
    ChoiceRow(tr(R.string.page_titles), TitleSize.entries, s.titleSize, { it.label }, changed = s.titleSize != d.titleSize) { prefs.setTitleSize(it) }
    ChoiceRow(tr(R.string.album_playlist_cards), CardSize.entries, s.cardSize, { it.label }, changed = s.cardSize != d.cardSize) { prefs.setCardSize(it) }
    ChoiceRow(
        tr(R.string.status_bar), StatusBarChoice.entries, status, { it.label },
        info = tr(R.string.the_clock_battery_and_notification_icons) + " " + tr(R.string.only_while_the_player_its_lyrics),
        changed = status != statusDefault,
    ) { picked ->
        // The choice it already shows writes nothing (both switches stored on stay as they are).
        if (picked != status) {
            val (hide, hidePlayer) = statusBarToStored(picked, s.hideStatusBarPlayer)
            if (hide != s.hideStatusBar) prefs.setHideStatusBar(hide)
            if (hidePlayer != s.hideStatusBarPlayer) prefs.setHideStatusBarPlayer(hidePlayer)
        }
    }
}

/**
 * Which tabs the bar holds and in what order, each with its own name: the old page's tabs editor (tick, hold or drag the
 * grip to move, a tap on the name to rename), in the group's card. The grip also carries TalkBack's moves and the
 * D-pad / keyboard's (1.8.4: the up / down arrows went).
 */
@Composable
private fun TabsList() {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val tabs = s.navTabs
    var renaming by remember { mutableStateOf<NavTab?>(null) }
    renaming?.let { t -> RenameTabDialog(t, s.tabNames[t] ?: t.label, { renaming = null }) { prefs.setTabName(t, it); renaming = null } }
    // Settings opens from Home and Library, or is a tab itself: one of them always stays.
    fun allowed(next: List<NavTab>) = next.size in 2..NavTab.MAX && next.any { it == NavTab.HOME || it == NavTab.LIBRARY || it == NavTab.SETTINGS }
    @Composable
    fun TabRow(t: NavTab, modifier: Modifier, grip: Modifier?) {
        val on = t in tabs
        val name = s.tabNames[t]
        Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VinzCheck(on, enabled = if (on) allowed(tabs - t) else allowed(tabs + t), label = t.label) { v ->
                val next = if (v) tabs + t else tabs - t
                if (allowed(next)) {
                    prefs.setNavTabs(next)
                    if (!v && s.startTab == t) prefs.setStartTab(next.first())
                }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = tr(R.string.rename_2, t.label)) { renaming = t }.padding(vertical = 6.dp)) {
                Text(name ?: t.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (name != null) Text(t.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (grip != null) DragGrip(grip.padding(horizontal = 4.dp), tr(R.string.drag, t.label))
        }
    }
    Column(Modifier.fillMaxWidth().testTag(SETTINGS_LIST_TAG).padding(vertical = 4.dp)) {
        ReorderList(tabs, { it }, { prefs.setNavTabs(it) }) { t, _, _, mod -> TabRow(t, mod, Modifier.dragHandle()) }
        NavTab.entries.filter { it !in tabs }.forEach { t -> TabRow(t, Modifier, null) }
    }
}

/** A tab's own name (1.7.6); blank gives it back its own. */
@Composable
private fun RenameTabDialog(tab: NavTab, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.rename_2, tab.label)) },
        text = {
            VinzTextField(text, { text = cleanTabName(it) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(tab.label) })
        },
        confirmButton = { VinzTextButton(onClick = { onSave(text) }) { Text(tr(R.string.save)) } },
        dismissButton = {
            Row {
                if (current != tab.label) VinzTextButton(onClick = { onSave("") }) { Text(tr(R.string.use_2, tab.label)) }
                VinzTextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) }
            }
        },
    )
}

/** The root's line for Tabs & mini player: the bar's style, how many tabs, the mini player's style. */
fun tabsMiniPlayerValue(s: AppSettings, dark: Boolean): String = SettingsSection.INTERFACE.summary(s, dark)

/** The look settings "Reset this page" puts back. */
val tabsMiniPlayerLookKeys: List<String> = listOf(
    // 1.8.0: the tab you're on's tint is with the tab bar now (from Icons & shapes).
    "tab_icon_tint",
    "nav_style", "nav_labels", "nav_hide_on_scroll", "nav_tabs", "start_tab", "mini_player_style", "mini_skip_buttons",
    "mini_prev_button", "mini_play_button", "mini_progress", "mini_progress_color", "mini_fill_strength", "ui_density", "title_size", "card_size", "hide_status_bar", "hide_status_bar_player",
    "tab_names", "nav_bar_size", "nav_bar_fill", "mini_player_joined",
)

/** Old group titles → this page's group keys (a group's key is its title): the old Screen group's switches are Pages' Status bar. */
val tabsMiniPlayerOldGroups: Map<String, String> by lazy { mapOf(tr(R.string.screen) to tr(R.string.pages)) }

/**
 * The page's lines in Settings' search. A row that was renamed or merged keeps its old words as the line's title
 * (they still find it, and the old page's code is still read by SettingsIndexTest) and reads as it is named now
 * (`shown`).
 */
val tabsMiniPlayerIndex: List<SettingEntry> by lazy {
    val bar = tr(R.string.navigation_bar)
    val tabs = tr(R.string.tabs)
    val mini = tr(R.string.mini_player)
    val pages = tr(R.string.pages)
    val buttons = tr(R.string.mini_player_buttons)
    val statusWords = tr(R.string.status_bar_clock_notifications_full_screen)
    fun shownAs(row: String, part: String) = tr(R.string.search_shown_as, row, part)
    listOf(
        SettingEntry(page, bar, tr(R.string.style), tr(R.string.tab_bar_bottom_bar)),
        SettingEntry(page, bar, tr(R.string.labels), tr(R.string.tab_bar_names),
            needs = Needs(tr(R.string.the_minimal_bar_has_no_labels)) { it.navStyle != NavBarStyle.MINIMAL }),
        SettingEntry(page, bar, tr(R.string.height), tr(R.string.tab_bar_size_tall_compact)),
        SettingEntry(page, bar, tr(R.string.background), tr(R.string.tab_bar_see_through_solid_opacity)),
        SettingEntry(page, bar, tr(R.string.tabs_on_a_tablet), tr(R.string.down_the_side) + " " + tr(R.string.at_the_bottom)),
        SettingEntry(page, bar, tr(R.string.the_tab_you_re_on), tr(R.string.tab_icon_color_tint_accent_light)),
        SettingEntry(page, bar, tr(R.string.shrink_while_scrolling), tr(R.string.tab_bar_hide)),
        SettingEntry(page, tabs, tabs, tr(R.string.tab_bar_order_home_search_library)),
        SettingEntry(page, tabs, tr(R.string.open_vinz_on), tr(R.string.start_tab_first_screen)),
        SettingEntry(page, mini, tr(R.string.style), tr(R.string.style_card_slim_pill)),
        SettingEntry(page, mini, tr(R.string.joined_to_the_tab_bar), tr(R.string.mini_player_merged_tab_bar_one),
            needs = Needs(tr(R.string.mini_player_joined_needs)) { it.navStyle == NavBarStyle.FLOATING }),
        SettingEntry(page, mini, buttons, tr(R.string.back_skip) + " " + tr(R.string.play_pause) + " " + tr(R.string.skip_forward)),
        // The three old switches are chips of Mini player buttons.
        SettingEntry(page, mini, tr(R.string.previous_button), tr(R.string.back_skip), shown = shownAs(buttons, MiniButton.PREVIOUS.label)),
        SettingEntry(page, mini, tr(R.string.play_pause_button), tr(R.string.play_pause), shown = shownAs(buttons, MiniButton.PLAY_PAUSE.label)),
        SettingEntry(page, mini, tr(R.string.next_button), tr(R.string.skip_forward), shown = shownAs(buttons, MiniButton.NEXT.label)),
        SettingEntry(page, mini, tr(R.string.progress)),
        SettingEntry(page, mini, tr(R.string.mini_player_progress_color_row), tr(R.string.scrubber_color_accent_white_cover_spectrum) + " " + tr(R.string.progress_bar_color),
            needs = Needs(tr(R.string.mini_player_progress_needs)) { it.miniProgress != MiniProgress.NONE }),
        SettingEntry(page, mini, tr(R.string.fill_strength),
            needs = Needs(tr(R.string.mini_player_fill_needs)) { it.miniProgress == MiniProgress.FILL }),
        SettingEntry(page, pages, tr(R.string.spacing), tr(R.string.density_compact)),
        SettingEntry(page, pages, tr(R.string.page_titles)),
        SettingEntry(page, pages, tr(R.string.album_playlist_cards)),
        // The two old switches are one choice, Status bar: each still has a line that says which choice it is now.
        SettingEntry(page, pages, tr(R.string.status_bar), statusWords + " " + tr(R.string.hide_the_status_bar)),
        SettingEntry(page, pages, tr(R.string.hide_the_status_bar), statusWords + " " + tr(R.string.status_bar),
            shown = shownAs(tr(R.string.status_bar), StatusBarChoice.HIDDEN_EVERYWHERE.label)),
        SettingEntry(page, pages, tr(R.string.hide_status_bar_on_the_player), statusWords + " " + tr(R.string.status_bar),
            shown = shownAs(tr(R.string.status_bar), StatusBarChoice.HIDDEN_ON_THE_PLAYER.label)),
    )
}
