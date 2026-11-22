package com.vinz.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.HomeHeader
import com.vinz.music.data.prefs.HomeHeaderHeight
import com.vinz.music.data.prefs.HomeSections
import com.vinz.music.data.prefs.HomeShortcut
import com.vinz.music.data.prefs.QuickPicksSource
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Customize Home (its own route; a settings page for the two-tap rule, plan §2.6 and §8.3) rebuilt with the
 * kit's groups and rows around its sections editor: four groups within the budgets, a control for every stored setting
 * the ledger gives it, "Hide from Home" in place of the two "leave off" switches, the three rows it alone has now, a
 * search result that opens it at Options, Reset this page, and the page drawn in light, dark, Performance mode and at
 * large text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class CustomizeHomePageTest : HomePagesHarness() {
    private fun t(id: Int) = tr(id)

    private val style = t(R.string.style)
    private val headline = t(R.string.headline)
    private val showName = t(R.string.show_your_name)
    private val height = t(R.string.height)
    private val picture = t(R.string.picture)
    private val show = t(R.string.show)
    private val pinned = t(R.string.pinned_playlists)
    private val recentAlbums = t(R.string.recently_played_albums)
    private val addNew = t(R.string.add_new_sections_automatically)
    private val hide = t(R.string.ch_hide_from_home)
    private val quickPicksFrom = t(R.string.quick_picks_from)
    private val quickRadio = t(R.string.quick_picks_start_a_radio)
    private val youTubeOnly = t(R.string.speed_dial_youtube_music_s_only)
    private val yoursFirst = t(R.string.speed_dial_your_plays_first)
    private val customizeButton = t(R.string.customize_button_on_home)

    private val groups = listOf(R.string.top_of_home, R.string.sections, R.string.section_shortcut_tiles, R.string.options).map { t(it) }

    private fun sectionsValue() = compose.onNode(hasTestTag(SECTIONS)).fetchSemanticsNode().config[SettingsRowValue]

    @Test fun fourGroupsWithinTheBudgets() {
        showAt(Routes.CATALOGUE)
        assertFits("defaults")
        assertEquals(groups, groupTitles())
        // Options: seven, with "Hide from Home" in place of the two "leave off" switches (it was eight).
        assertEquals(listOf(3, 1, 3, 7), sizes())
        assertEquals("nothing differs from how Vinz comes", 0, changedRows())
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        assertEquals(SettingsTags.ROW_PICK, typeOf(style))
        assertEquals(HomeHeader.GREETING.label, valueOf(style))
        assertEquals(SettingsTags.ROW_LINK, typeOf(headline))
        assertEquals(t(R.string.good_morning_afternoon_evening), valueOf(headline))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(show))
        assertEquals(SettingsTags.ROW_LINK, typeOf(pinned))
        assertEquals(t(R.string.none), valueOf(pinned))
        assertEquals(SettingsTags.ROW_SLIDER, typeOf(recentAlbums))
        assertEquals("3", valueOf(recentAlbums))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(hide))
        assertEquals(t(R.string.none), valueOf(hide))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(quickPicksFrom))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(yoursFirst))
        // The two switches are gone, and the button has the one name it has everywhere.
        assertFalse(hasRow(t(R.string.leave_off_music_video_sections)) || hasRow(t(R.string.leave_off_podcast_sections)))
        assertTrue(hasRow(customizeButton))
        assertTrue(sectionsValue(), sectionsValue().isNotBlank())
    }

    /** The ledger's "(check)": the headline (and "Show your name") show with every top of Home that has a greeting. */
    @Test fun theHeadlineShowsWithEveryTopThatHasAGreeting() {
        showAt(Routes.CATALOGUE)
        for (header in HomeHeader.entries) {
            container.settings.setHomeHeader(header)
            until("$header") { it.home.header == header }
            val greeting = header != HomeHeader.NONE
            val pictured = header == HomeHeader.PICTURE || header == HomeHeader.NOW_PLAYING
            assertEquals("$header: Headline", greeting, hasRow(headline))
            assertEquals("$header: Show your name", greeting, hasRow(showName))
            assertEquals("$header: Height", pictured, hasRow(height))
            assertEquals("$header: Picture", header == HomeHeader.PICTURE, hasRow(picture))
            assertFits("$header")
        }
    }

    /** Principle 8: every stored setting the ledger gives Customize Home has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        showAt(Routes.CATALOGUE)
        val d = AppSettings()

        // greeting_text: the headline, typed in its own sheet.
        tap(headline)
        assertEquals(1, sheets())
        compose.onNode(hasSetTextAction()).performTextReplacement("Evening, all")
        compose.onNode(hasText(t(R.string.save))).performClick()
        until("greeting_text") { it.greetingText == "Evening, all" }
        compose.waitUntil(5_000) { sheets() == 0 }
        assertEquals("Evening, all", valueOf(headline))
        // greeting_show_name
        tap(showName)
        until("greeting_show_name") { it.greetingShowName != d.greetingShowName }
        // home_header: its four tops in a sheet, each drawn.
        tap(style)
        assertEquals(HomeHeader.entries.size, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        assertEquals(d.home.header, stored.home.header)
        pick(style, HomeHeader.PICTURE.label)
        until("home_header") { it.home.header == HomeHeader.PICTURE }
        // home_header_height
        choose(height, HomeHeaderHeight.TALL.label)
        until("home_header_height") { it.home.headerHeight == HomeHeaderHeight.TALL }
        // home_header_picture: a design from the list (or a photo of your own, which opens the phone's picker).
        val design = Backdrop.entries.first { it.group == BackdropGroup.DESIGNS && it.season == null && it.name != d.home.headerPicture }
        tap(picture)
        assertTrue(compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(t(R.string.your_photo))).fetchSemanticsNodes().isNotEmpty())
        swipeSheetAway()
        pick(picture, design.label)
        until("home_header_picture") { it.home.headerPicture == design.name }
        assertEquals(design.label, valueOf(picture))

        // home_layout: the sections editor, as it was (a tap hides a section).
        val first = stored.home.layout.first()
        assertTrue(first.visible)
        compose.onNode(hasText(first.titled()) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and hasAnyAncestor(hasTestTag(SECTIONS))).performClick()
        until("home_layout") { !it.home.layout.first().visible && it.home.layout.size == d.home.layout.size }
        compose.onNode(hasText(t(R.string.add_a_section)) and hasAnyAncestor(hasTestTag(SECTIONS))).assertIsDisplayed()

        // home_shortcuts: chips, numbered in the order they were picked.
        chip(HomeShortcut.SEARCH.label)
        until("home_shortcuts") { it.home.shortcuts == d.home.shortcuts + HomeShortcut.SEARCH }
        chip("1  " + HomeShortcut.LIKED.label)
        until("home_shortcuts") { it.home.shortcuts == d.home.shortcuts - HomeShortcut.LIKED + HomeShortcut.SEARCH }
        // home_recent_tiles
        slide(recentAlbums, 5f)
        until("home_recent_tiles") { it.home.recentTiles == 5 }
        slide(recentAlbums, 0f)
        until("home_recent_tiles") { it.home.recentTiles == 0 }
        assertEquals(t(R.string.off), valueOf(recentAlbums))

        // auto_add_sections
        tap(addNew)
        until("auto_add_sections") { it.autoAddSections != d.autoAddSections }
        // home_hide_kinds: two chips over the one stored set, every combination.
        chip(t(R.string.videos))
        until("home_hide_kinds") { it.home.hideKinds == setOf(HomeSections.KIND_VIDEOS) }
        chip(t(R.string.podcasts))
        until("home_hide_kinds") { it.home.hideKinds == setOf(HomeSections.KIND_VIDEOS, HomeSections.KIND_PODCASTS) }
        chip(t(R.string.videos))
        until("home_hide_kinds") { it.home.hideKinds == setOf(HomeSections.KIND_PODCASTS) }
        assertEquals(t(R.string.podcasts), valueOf(hide))
        // quick_picks_source, quick_picks_radio
        choose(quickPicksFrom, QuickPicksSource.YOUTUBE.label)
        until("quick_picks_source") { it.behavior.quickPicksSource == QuickPicksSource.YOUTUBE }
        tap(quickRadio)
        until("quick_picks_radio") { it.quickPicksRadio != d.quickPicksRadio }
        // speed_dial_yours_first, then speed_dial_youtube_only (which leaves none of yours to put first: its row goes).
        tap(yoursFirst)
        until("speed_dial_yours_first") { it.behavior.speedDialYoursFirst }
        tap(youTubeOnly)
        until("speed_dial_youtube_only") { it.speedDialYouTubeOnly }
        assertFalse(hasRow(yoursFirst))
        // home_customize_button
        tap(customizeButton)
        until("home_customize_button") { it.home.customizeButton != d.home.customizeButton }

        assertFits("everything changed")
        assertEquals(listOf(5, 1, 3, 6), sizes())
        // Every row differs but "Speed dial: your plays first" (hidden): 5 + 2 (the tiles' chips and the slider) + 6.
        assertEquals(13, changedRows())

        // Reset this page lists each of them and the sections, and puts them all back.
        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 15, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith(t(R.string.sections)) })
        until("the reset") {
            it.home.header == d.home.header && it.home.headerHeight == d.home.headerHeight && it.home.headerPicture == d.home.headerPicture && it.greetingText == d.greetingText &&
                it.greetingShowName == d.greetingShowName && it.home.layout == d.home.layout && it.home.shortcuts == d.home.shortcuts && it.home.recentTiles == d.home.recentTiles &&
                it.autoAddSections == d.autoAddSections && it.home.hideKinds == d.home.hideKinds && it.quickPicksRadio == d.quickPicksRadio &&
                it.speedDialYouTubeOnly == d.speedDialYouTubeOnly && it.home.customizeButton == d.home.customizeButton && it.behavior == d.behavior
        }
        assertEquals(0, changedRows())
        assertEquals(listOf(3, 1, 3, 7), sizes())
    }

    /** 1.8.4 review: on "Hide from Home" a ticked chip means hidden, and TalkBack says so (it said "Shown"). */
    @Test fun hideFromHomeChipsTellTalkBackATickMeansHidden() {
        container.settings.setHomeHideKind(HomeSections.KIND_VIDEOS, true)
        until("home_hide_kinds") { it.home.hideKinds == setOf(HomeSections.KIND_VIDEOS) }
        showAt(Routes.CATALOGUE)
        fun state(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(t(R.string.hidden), state(t(R.string.videos)))
        assertEquals(t(R.string.shown), state(t(R.string.podcasts)))
    }

    /** home_playlists: the row opens the sheet the playlists are pinned in. */
    @Test fun pinnedPlaylistsOpenTheirSheet() {
        showAt(Routes.CATALOGUE)
        tap(pinned)
        compose.onNode(hasText(t(R.string.shown_as_tiles_after_the_shortcuts))).assertIsDisplayed()
    }

    /** With the tiles' section hidden on Home, the group says why its rows are faint, behind its ⓘ; they still work. */
    @Test fun theTilesStillWorkWhileTheirSectionIsHidden() {
        val layout = AppSettings().home.layout.map { if (it.key == HomeSections.GREETING) it.copy(visible = false) else it }
        container.settings.setHomeLayout(layout)
        until("hidden") { s -> s.home.layout.any { it.key == HomeSections.GREETING && !it.visible } }
        showAt(Routes.CATALOGUE)
        assertFits("tiles hidden")
        chip(HomeShortcut.SEARCH.label)
        until("home_shortcuts") { HomeShortcut.SEARCH in it.home.shortcuts }
    }

    private fun open(route: String) {
        compose.runOnUiThread { com.vinz.music.ui.Navigator(navController!!, openPlayer = {}).go(route) }
        compose.waitUntil(5_000) { route() == Routes.CATALOGUE }
        compose.waitForIdle()
    }

    /**
     * Settings' search: "Speed dial: your plays first" is found under Customize Home, in Options, and its result's
     * route names that group. With a `find` argument on the screen's destination the page opens there (a short window
     * only shows Options once scrolled to); opened plainly it starts at its top.
     */
    @Test @Config(qualifiers = "w360dp-h700dp-xhdpi") fun aSearchResultOpensThePageAtItsGroup() {
        val e = SettingsIndex.entries.single { it.title == yoursFirst }
        assertEquals(t(R.string.customize_home), e.pageTitle)
        assertEquals(t(R.string.options), e.group)
        assertEquals(Routes.CATALOGUE + "?find=" + t(R.string.options), e.opens)
        showAt(ELSEWHERE, findInRoute = true)
        open(Routes.CATALOGUE)
        assertTrue(hasRow(style))
        assertFalse("Options is on screen without scrolling", hasRow(yoursFirst))
        compose.runOnUiThread { navController!!.popBackStack() }
        compose.waitForIdle()
        // Opened by the result (as Settings' search does: the navigator, with the entry's route).
        open(e.opens!!)
        assertTrue("the page opened at Options", hasRow(yoursFirst))
        assertFalse(hasRow(style))
    }

    /** As the app's graph has the screen today (plain "catalogue"): the result's route still opens it, at its top. */
    @Test @Config(qualifiers = "w360dp-h700dp-xhdpi") fun withoutTheArgumentTheResultStillOpensThePage() {
        showAt(ELSEWHERE)
        open(SettingsIndex.entries.single { it.title == yoursFirst }.opens!!)
        assertTrue(hasRow(style))
        assertFalse(hasRow(yoursFirst))
    }

    /** The screen given the group itself (what a caller that knows it can pass). */
    @Test @Config(qualifiers = "w360dp-h700dp-xhdpi") fun givenAGroupItOpensThere() {
        over = { it }
        compose.setContent {
            val controller = androidx.navigation.compose.rememberNavController()
            androidx.compose.runtime.CompositionLocalProvider(
                com.vinz.music.ui.theme.LocalContainer provides container,
                com.vinz.music.ui.LocalNavigator provides com.vinz.music.ui.Navigator(controller, openPlayer = {}),
            ) {
                com.vinz.music.ui.theme.VinzTheme(AppSettings(), artworkColor = null) { com.vinz.music.ui.screens.CatalogueScreen(0.dp, find = t(R.string.options)) }
            }
        }
        compose.waitForIdle()
        assertTrue("the page opened at Options", hasRow(yoursFirst))
        assertFalse(hasRow(style))
    }

    @Test fun everySearchLineNamesAGroupThePageDraws() {
        showAt(Routes.CATALOGUE)
        val drawn = groupTitles().toSet()
        val mine = SettingsIndex.entries.filter { it.route?.substringBefore('?') == Routes.CATALOGUE }
        assertTrue("its rows are listed (${mine.size})", mine.size >= 14)
        for (e in mine) {
            assertEquals(e.title, SettingsPageId.HOME_LIBRARY, e.page)
            assertEquals(e.title, t(R.string.customize_home), e.pageTitle)
            assertTrue("${e.title} is in \"${e.group}\", which the page doesn't draw", e.group in drawn)
            assertTrue("${e.title} opens at its group: ${e.opens}", e.opens!!.endsWith("?find=" + android.net.Uri.encode(e.group)))
        }
        // The old names of the two switches still find the chips.
        assertTrue(SettingsIndex.search("leave off podcast sections").any { it.title == hide })
        assertTrue(SettingsIndex.search("customize home quick picks from").any { it.title == quickPicksFrom })
    }

    @Test fun atLargeTextNothingIsCutOff() {
        showAt(Routes.CATALOGUE, fontScale = 1.5f)
        assertFits("large text")
        assertNothingCutOff("font scale 1.5", t(R.string.customize_home))
    }

    private fun drawn(name: String, glass: Boolean, performance: Boolean = false, over: (AppSettings) -> AppSettings) {
        // The page shows the settings as stored (Performance mode too), so that one is stored.
        if (performance) {
            container.settings.setPerformanceMode(true)
            until("performance") { it.performanceMode }
        }
        showAt(Routes.CATALOGUE, glass = glass, over = over)
        val file = save("customize-home", name)
        assertTrue(file.path, file.length() > 10_000)
        assertFits(name)
        assertNothingCutOff(name, t(R.string.customize_home))
    }

    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnLight() = drawn("customize-home-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnDark() = drawn("customize-home-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnPerformance() = drawn("customize-home-performance", false, performance = true) { it.copy(themeMode = ThemeMode.DARK) }

    /** With a picture on top (the group's longest state). */
    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun drawnWithAPicture() {
        container.settings.setHomeHeader(HomeHeader.PICTURE)
        until("picture") { it.home.header == HomeHeader.PICTURE }
        drawn("customize-home-dark-picture", true) { it.copy(themeMode = ThemeMode.DARK) }
    }
}
