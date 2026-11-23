package com.vinz.music

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.data.prefs.TitleSize
import com.vinz.music.data.prefs.UiDensity
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.index
import com.vinz.music.ui.screens.settings.oldGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Tabs & mini player rebuilt with the kit (plan §2.3–2.7, the ledger's Tabs & mini player table): four
 * groups of the five rows and the tabs' list, the status bar's two switches as one choice, the mini player's three
 * button switches as show chips, "Mini player progress color", and a control for every stored setting the ledger
 * gives the page. On a window tall enough to hold the whole page (the list is lazy).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h4000dp-xhdpi")
class TabsMiniPlayerPageTest : TextTabsPageHarness(SettingsPageId.TABS_MINI_PLAYER) {
    private val bar = "Navigation bar"
    private val tabs = "Tabs"
    private val mini = "Mini player"
    private val pages = "Pages"

    /** The ledger's rows for the page, as the page names them (with a fresh install's settings, on a phone). */
    private val expected = listOf(
        "Navigation bar › Style", "Navigation bar › Labels", "Navigation bar › Height", "Navigation bar › Background",
        "Navigation bar › The tab you're on", "Navigation bar › Shrink while scrolling",
        // The tabs' list starts with its first tab.
        "Tabs › Home", "Tabs › Open Vinz on",
        "Mini player › Style", "Mini player › Joined to the tab bar", "Mini player › Mini player buttons", "Mini player › Progress",
        "Mini player › Mini player progress color",
        "Pages › Spacing", "Pages › Page titles", "Pages › Album & playlist cards", "Pages › Status bar",
    )

    @Test fun theGroupsAndRowsAreTheLedgersWithinTheBudgets() {
        show()
        assertEquals(expected, rowNames())
        assertBudgets()
        // The ledger's counts: Navigation bar 6 · Tabs 2 · Mini player 5 · Pages 4.
        assertEquals(listOf(6, 2, 5, 4), groups().map { it.rows.size })
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // The old page has no bar-and-mini-player sample to reuse: no pinned preview.
        assertEquals(0, compose.onAllNodes(hasTestTag(SettingsTags.PREVIEW)).fetchSemanticsNodes().size)
        assertTrue(groups().flatMap { it.rows }.filter { typeOf(it) != listTag }.none { it.config[SettingsRowChanged] })
        // "Swipe between tabs" is Gestures & buttons' (the ledger).
        assertTrue(rowNames().none { it.contains("Swipe") })
        assertTrue(SettingsPageId.TABS_MINI_PLAYER.index.none { it.title == tr(R.string.swipe_between_tabs) })
    }

    @Test fun theRowsAreTheirTypesAndSayTheirValues() {
        show()
        fun type(group: String, title: String) = typeOf(rowNode(group, title))
        for ((g, t) in listOf(
            bar to "Style", bar to "Height", bar to "Background", bar to "The tab you're on",
            tabs to "Open Vinz on", mini to "Style", mini to "Mini player buttons", mini to "Progress", mini to "Mini player progress color",
            pages to "Spacing", pages to "Page titles", pages to "Album & playlist cards", pages to "Status bar",
        )) assertEquals("$g › $t", SettingsTags.ROW_CHOICE, type(g, t))
        // Four choices in words: a Pick with its list (as one capsule of chips, "Words only" was cut short at 360 dp).
        assertEquals(SettingsTags.ROW_PICK, type(bar, "Labels"))
        assertEquals("Always", valueOf(rowNode(bar, "Labels")))
        assertEquals(SettingsTags.ROW_SWITCH, type(bar, "Shrink while scrolling"))
        assertEquals(SettingsTags.ROW_SWITCH, type(mini, "Joined to the tab bar"))
        assertEquals(listTag, type(tabs, "Home"))
        assertEquals("Floating", valueOf(rowNode(bar, "Style")))
        assertEquals("Plain", valueOf(rowNode(bar, "The tab you're on")))
        assertEquals("Home", valueOf(rowNode(tabs, "Open Vinz on")))
        assertEquals("Play/pause · Next", valueOf(rowNode(mini, "Mini player buttons")))
        assertEquals("Your light", valueOf(rowNode(mini, "Mini player progress color")))
        assertEquals("Shown", valueOf(rowNode(pages, "Status bar")))
    }

    // ------------------------------------------------------------------ Nothing missing (the ledger's keys)

    @Test fun navigationBarRowsChangeWhatIsStored() {
        show()
        pick(bar, "Labels", "Never")
        await("nav_labels") { stored.navLabels == NavLabels.NEVER }
        chip(bar, "Height", "Tall")
        await("nav_bar_size") { stored.navBarSize == NavBarSize.TALL }
        chip(bar, "Background", "Solid")
        await("nav_bar_fill") { stored.navBarFill == NavBarFill.SOLID }
        chip(bar, "The tab you're on", "Accent")
        await("tab_icon_tint") { stored.shapes.tabTint == TabTint.ACCENT }
        tap(bar, "Shrink while scrolling")
        await("nav_hide_on_scroll") { !stored.navHideOnScroll }
        chip(bar, "Style", "Minimal")
        await("nav_style") { stored.navStyle == NavBarStyle.MINIMAL }
        // The Minimal bar has no labels, and a mini player can only join a floating bar.
        assertTrue(rowNames().none { it == "Navigation bar › Labels" || it == "Mini player › Joined to the tab bar" })
        val labels = SettingsPageId.TABS_MINI_PLAYER.index.first { it.title == "Labels" }
        assertTrue("its search line says what to pick first", labels.needs != null && !labels.needs!!.on(stored))
    }

    /** tab_bar_position only means something on a tablet or a wide window: the row is there then (the group's seventh). */
    @Test fun onATabletTheTabsCanSitDownTheSide() {
        show(wide = true)
        assertEquals(7, groups().first { it.title == bar }.rows.size)
        assertBudgets()
        assertEquals(TabBarPosition.SIDE, stored.behavior.tabBarPosition)
        chip(bar, "Tabs on a tablet", "At the bottom")
        await("tab_bar_position") { stored.behavior.tabBarPosition == TabBarPosition.BOTTOM }
    }

    /** 1.8.4 review: with only "Tabs on a tablet" changed (not a Look key), Reset lists it and puts it back. */
    @Test fun resetPutsBackTabsOnATablet() {
        show(wide = true)
        chip(bar, "Tabs on a tablet", "At the bottom")
        await("tab_bar_position") { stored.behavior.tabBarPosition == TabBarPosition.BOTTOM }
        assertTrue(rowNode(bar, "Tabs on a tablet").config[SettingsRowChanged])
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(listOf("Tabs on a tablet: ${TabBarPosition.BOTTOM.label} → ${TabBarPosition.SIDE.label}"), listed)
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        await("the reset") { stored.behavior.tabBarPosition == TabBarPosition.SIDE }
    }

    /** nav_tabs, tab_names, start_tab: the tabs' list works as it did (tick, move, rename), with "Open Vinz on" under it. */
    @Test fun theTabsListStillTicksMovesAndRenames() {
        show()
        assertEquals(listOf(NavTab.HOME, NavTab.LIBRARY, NavTab.SEARCH, NavTab.REPLAY), stored.navTabs)
        // Tick one more tab (the checkbox is named after its tab).
        compose.onNode(hasContentDescription("Downloads") and androidx.compose.ui.test.isToggleable()).performClick()
        await("nav_tabs: one more") { stored.navTabs.last() == NavTab.DOWNLOADS && stored.navTabs.size == 5 }
        // Move the first one down (1.8.4 §2.8: the row's arrows went; its grip's TalkBack move does it).
        compose.reorderAction(tr(R.string.drag, NavTab.HOME.label), tr(R.string.move_down))
        await("nav_tabs: moved") { stored.navTabs.take(2) == listOf(NavTab.LIBRARY, NavTab.HOME) }
        // Rename: a tap on a tab's name still opens its dialog (the action is there, named), and the list shows a
        // tab's own name with its usual one under it. (The dialog itself isn't worked here: with it open Compose is
        // never idle under Robolectric, on the old page as well; it is the old page's dialog, unchanged.)
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher("its name, tapped to rename") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == "Rename Library"
        }).assertExists()
        container.settings.setTabName(NavTab.LIBRARY, "Shelf")
        await("tab_names") { stored.tabNames[NavTab.LIBRARY] == "Shelf" }
        compose.onNode(hasText("Shelf") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(listTag)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Library") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(listTag)), useUnmergedTree = true).assertExists()
        // Open Vinz on: one of the tabs in the bar, by the name it has now.
        chip(tabs, "Open Vinz on", "Shelf")
        await("start_tab") { stored.startTab == NavTab.LIBRARY }
        assertEquals("Shelf", valueOf(rowNode(tabs, "Open Vinz on")))
        assertBudgets()
    }

    @Test fun miniPlayerRowsChangeWhatIsStored() {
        show()
        chip(mini, "Style", "Slim")
        await("mini_player_style") { stored.miniPlayerStyle == MiniPlayerStyle.SLIM }
        assertFalse(stored.miniPlayerJoined)
        tap(mini, "Joined to the tab bar")
        await("mini_player_joined") { stored.miniPlayerJoined }
        // §2.5: the three button switches are show chips; each still writes its own stored value.
        assertEquals(Triple(false, true, true), Triple(stored.miniPrevButton, stored.view.miniPlayButton, stored.miniSkipButtons))
        chip(mini, "Mini player buttons", "Previous")
        await("mini_prev_button") { stored.miniPrevButton }
        chip(mini, "Mini player buttons", "Play/pause")
        await("mini_play_button") { !stored.view.miniPlayButton }
        chip(mini, "Mini player buttons", "Next")
        await("mini_skip_buttons") { !stored.miniSkipButtons }
        assertEquals("Previous", valueOf(rowNode(mini, "Mini player buttons")))
        // mini_progress_color, then mini_progress with what shows under each choice.
        chip(mini, "Mini player progress color", "Accent")
        await("mini_progress_color") { stored.view.miniProgressColor == ScrubberColor.ACCENT }
        assertTrue(rowNames().none { it.endsWith("Fill strength") })
        chip(mini, "Progress", "Fill")
        await("mini_progress") { stored.miniProgress == MiniProgress.FILL }
        slide(mini, "Fill strength", 0.5f)
        await("mini_fill_strength") { kotlin.math.abs(stored.view.miniFillStrength - 0.5f) < 0.01f }
        assertEquals("50%", valueOf(rowNode(mini, "Fill strength")))
        assertBudgets()
        chip(mini, "Progress", "None")
        await("mini_progress none") { stored.miniProgress == MiniProgress.NONE }
        assertTrue("no color for no progress", rowNames().none { it.endsWith("Fill strength") || it.endsWith("Mini player progress color") })
    }

    @Test fun pagesRowsChangeWhatIsStored() {
        show()
        chip(pages, "Spacing", "Compact")
        await("ui_density") { stored.uiDensity == UiDensity.COMPACT }
        chip(pages, "Page titles", "Small")
        await("title_size") { stored.titleSize == TitleSize.SMALL }
        chip(pages, "Album & playlist cards", "Large")
        await("card_size") { stored.cardSize == CardSize.LARGE }
        // §2.5: hide_status_bar + hide_status_bar_player are one choice.
        chip(pages, "Status bar", "Hidden on the player")
        await("hide_status_bar_player") { !stored.hideStatusBar && stored.hideStatusBarPlayer }
        assertEquals("Hidden on the player", valueOf(rowNode(pages, "Status bar")))
        chip(pages, "Status bar", "Hidden everywhere")
        await("hide_status_bar") { stored.hideStatusBar }
        assertTrue("the player's own choice is kept under it, as the old switches kept it", stored.hideStatusBarPlayer)
        assertEquals("Hidden everywhere", valueOf(rowNode(pages, "Status bar")))
        chip(pages, "Status bar", "Shown")
        await("shown") { !stored.hideStatusBar && !stored.hideStatusBarPlayer }
    }

    /** Both switches stored on (possible before 1.8.4): shown as Hidden everywhere, and left as they are. */
    @Test fun bothStatusBarSwitchesStoredOnReadAsHiddenEverywhere() {
        container.settings.setHideStatusBar(true)
        container.settings.setHideStatusBarPlayer(true)
        await("both on") { stored.hideStatusBar && stored.hideStatusBarPlayer }
        show()
        assertEquals("Hidden everywhere", valueOf(rowNode(pages, "Status bar")))
        assertTrue(rowNode(pages, "Status bar").config[SettingsRowChanged])
        // A tap on the choice it already shows writes nothing.
        chip(pages, "Status bar", "Hidden everywhere")
        compose.waitForIdle()
        assertTrue(stored.hideStatusBar && stored.hideStatusBarPlayer)
    }

    @Test fun resetThisPageListsTheChangesAndPutsThemBack() {
        container.settings.setNavStyle(NavBarStyle.DOCKED)
        container.settings.setMiniPrevButton(true)
        container.settings.setHideStatusBarPlayer(true)
        await("the changes") { stored.navStyle == NavBarStyle.DOCKED && stored.miniPrevButton && stored.hideStatusBarPlayer }
        show()
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(
            listOf("Navigation bar: Docked → Floating","Mini player buttons: Previous · Play/pause · Next → Play/pause · Next", "Status bar: Hidden on the player → Shown"),
            listed,
        )
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        await("the reset") { stored.navStyle == NavBarStyle.FLOATING && !stored.miniPrevButton && !stored.hideStatusBarPlayer }
        assertEquals("Floating", valueOf(rowNode(bar, "Style")))
    }

    // ------------------------------------------------------------------ Search and old links

    @Test fun everyRowIsInThePagesSearchLinesUnderItsGroup() {
        show()
        val index = SettingsPageId.TABS_MINI_PLAYER.index
        val missing = groups().flatMap { g -> g.rows.filter { typeOf(it) != listTag }.map { g.title to titleOf(it) } }
            .filter { (g, t) -> index.none { it.group == g && (it.title == t || it.shown == t) } }
        assertTrue("rows without a search line in their group: $missing", missing.isEmpty())
        assertTrue("the tabs' list is found as Tabs", index.any { it.group == tabs && it.title == tabs })
        val drawn = groups().map { it.title }.toSet()
        assertTrue(index.filter { it.group !in drawn }.map { it.title }.toString(), index.all { it.group in drawn })
        // The old Screen group's two switches are Pages' Status bar now.
        assertEquals("Pages", SettingsPageId.TABS_MINI_PLAYER.oldGroups["Screen"])
        // The old names still find the merged rows, and say where they are now.
        fun found(q: String) = com.vinz.music.ui.screens.SettingsIndex.search(q).filter { it.page == SettingsPageId.TABS_MINI_PLAYER }.map { it.shown }
        assertTrue(found("hide the status bar").toString(), "Status bar" in found("hide the status bar"))
        assertTrue(found("previous button").toString(), "Mini player buttons: Previous" in found("previous button"))
        assertTrue("Mini player progress color" in found("progress bar color"))
        // Rows that only show under another choice say what to pick first.
        val color = index.first { it.shown == "Mini player progress color" }
        assertTrue(color.needs != null && color.needs!!.on(stored) && !color.needs!!.on(stored.copy(miniProgress = MiniProgress.NONE)))
        val fill = index.first { it.title == "Fill strength" }
        assertTrue(fill.needs != null && !fill.needs!!.on(stored) && fill.needs!!.on(stored.copy(miniProgress = MiniProgress.FILL)))
        val joined = index.first { it.title == "Joined to the tab bar" }
        assertTrue(joined.needs != null && joined.needs!!.on(stored) && !joined.needs!!.on(stored.copy(navStyle = NavBarStyle.DOCKED)))
    }

    // ------------------------------------------------------------------ Drawn

    private fun draws(name: String, fontScale: Float = 1f, glass: Boolean = true, set: () -> Unit, ready: () -> Boolean) {
        set()
        await(name, ready)
        show(fontScale, glass)
        assertEquals(expected, rowNames())
        assertBudgets()
        assertEquals("$name: cut-off text", emptyList<String>(),
            // The kit's small bar title (shown once the page is scrolled) has 128 dp between the round buttons at
            // 360 dp, and "Tabs & mini player" ends in "…" there: the scaffold's, reported to the coordinator.
            clippedTexts().filterNot { it.startsWith("Tabs & mini player (") && it.endsWith("ellipsized true)") })
    }

    @Test fun drawsInLight() = draws("light", set = { container.settings.setThemeMode(ThemeMode.LIGHT) }) { stored.themeMode == ThemeMode.LIGHT }
    @Test fun drawsInDark() = draws("dark", set = { container.settings.setThemeMode(ThemeMode.DARK) }) { stored.themeMode == ThemeMode.DARK }
    @Test fun drawsInPerformanceMode() = draws("performance", glass = false, set = { container.settings.setPerformanceMode(true) }) { stored.performanceMode }
    @Test fun drawsAtLargeTextWithNothingCutOff() = draws("large text", fontScale = 1.5f, set = { container.settings.setThemeMode(ThemeMode.DARK) }) { stored.themeMode == ThemeMode.DARK }

    /** PNGs for a look (app/build/settings-pages/tabs_mini_player/), on a 360 dp phone. */
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    @Test fun renders() {
        container.settings.setThemeMode(ThemeMode.DARK)
        await("dark") { stored.themeMode == ThemeMode.DARK }
        show(glass = true)
        assertEquals(1080, save("dark").width)
        pageList().performScrollToIndex(4)
        compose.waitForIdle()
        save("dark-scrolled")
        pageList().performScrollToIndex(0)
        container.settings.setThemeMode(ThemeMode.LIGHT)
        await("light") { stored.themeMode == ThemeMode.LIGHT }
        save("light")
        container.settings.setThemeMode(ThemeMode.DARK)
        container.settings.setPerformanceMode(true)
        await("performance") { stored.performanceMode && stored.themeMode == ThemeMode.DARK }
        save("performance")
    }

    @Test fun rendersTheWholePage() {
        container.settings.setThemeMode(ThemeMode.DARK)
        await("dark") { stored.themeMode == ThemeMode.DARK }
        show(glass = true)
        save("dark-whole")
    }
}
