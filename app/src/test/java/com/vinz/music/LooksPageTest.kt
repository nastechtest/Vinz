package com.vinz.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.vinz.music.data.looks.BuiltInLooks
import com.vinz.music.data.looks.LookSchedule
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.LOOKS_NOW_TAG
import com.vinz.music.ui.screens.settings.LOOKS_STATE_TAG
import com.vinz.music.ui.screens.settings.LooksPage
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.looksLookKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Looks rebuilt with the kit (plan §2.3, §2.4, §2.7): six groups, the Looks themselves as cards
 * inside them and everything else a kit row; the day's and night's Look picked in a sheet; "Customize by tapping" with
 * History; "Reset everything" under ⋯ after the confirm it always had. The window is tall so the whole page is there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h6000dp-xhdpi")
class LooksPageTest : ThemeLooksPageHarness() {
    private fun page() = show { LooksPage(0.dp, onSearch = {}) }

    private val history = "History and editing"
    private val groupNames = listOf("Vinz's Looks", "Your Looks", "From the community", "Get a Look", "Day and night", history)

    private fun listed() = textsOf(compose.onNodeWithTag(SettingsTags.PAGE_LIST, useUnmergedTree = true).fetchSemanticsNode())

    @Test fun withDefaultsItIsSixGroups() {
        page()
        assertEquals("Looks", compose.onNodeWithTag(SettingsTags.PAGE_TITLE).fetchSemanticsNode().config[SemanticsProperties.Text].first().text)
        val groups = groups()
        assertEquals(groupNames, groups.map { it.first })
        // The Looks are cards (the page's content); every setting and action is a kit row.
        assertEquals(
            listOf(
                emptyList(), emptyList(),
                listOf("Browse community Looks", "Add your Look"),
                listOf("Scan a Look's QR code", "Paste a Look code", "Open a Look file"),
                listOf("Change Look by time of day"),
                listOf("Customize by tapping"),
            ),
            groups.map { g -> g.second.map { titleOf(it) } },
        )
        assertBudgets()
        // Vinz's own Looks are all there to pick.
        val drawn = listed()
        val today = com.vinz.music.data.looks.Seasons.today()
        val season = com.vinz.music.data.looks.Seasons.current(today)?.let { BuiltInLooks.of(it.lookId) }
        for (look in BuiltInLooks.all.filter { com.vinz.music.data.looks.Seasons.shown(it.id, today) && it.id != season?.id }) assertTrue(look.name, look.name in drawn)
        // W7 (1.8.7.6): the season's Look, in its month, is on its own card only, not in the grid as well.
        if (season != null) {
            assertTrue(tr(R.string.for_the_season, season.name) in drawn)
            assertFalse("${season.name} is in the grid too", season.name in drawn)
        }
        // What isn't there yet says so, as state (not as rows).
        assertEquals(
            listOf(tr(R.string.looks_you_save_show_here_save), tr(R.string.your_last_changes_to_the_look, com.vinz.music.data.looks.LookStore.MAX_STEPS)),
            compose.onAllNodes(hasTestTag(LOOKS_STATE_TAG), useUnmergedTree = true).fetchSemanticsNodes().map { textsOf(it).single() },
        )
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.PREVIEW)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // The old button is under ⋯ now.
        assertFalse(tr(R.string.reset_look_layout_to_defaults) in drawn)
        assertEquals(emptyList<String>(), looksLookKeys)
    }

    /** §2.7: what a Look is sits behind the ⓘ of the card that says which Look is on. */
    @Test fun theLooksParagraphIsBehindTheInfoMark() {
        page()
        val paragraph = tr(R.string.a_look_keeps_colors_type_icons)
        assertFalse(paragraph in listed())
        val now = textsOf(compose.onNodeWithTag(LOOKS_NOW_TAG, useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("$now", now.first().startsWith("You're on"))
        assertTrue(tr(R.string.save_as_a_look) in now)
        compose.onNode(hasContentDescription(tr(R.string.more_about, now.first())), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(paragraph in listed())
    }

    @Test fun inDarkLightAndPerformanceModeItDrawsTheSame() {
        container.settings.setThemeMode(ThemeMode.DARK)
        await { stored.themeMode == ThemeMode.DARK }
        page()
        assertEquals(groupNames, groups().map { it.first })
        container.settings.setThemeMode(ThemeMode.LIGHT)
        await { stored.themeMode == ThemeMode.LIGHT }
        assertEquals(groupNames, groups().map { it.first })
        assertBudgets()
        container.settings.setPerformanceMode(true)
        await { stored.performanceMode }
        assertEquals(listOf("") + groupNames, groups().map { it.first })
        tap("Performance mode is on")
        await { !stored.performanceMode }
        assertEquals(groupNames, groups().map { it.first })
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.looks.setSchedule(LookSchedule(on = true, sun = false))
        show(fontScale = 1.5f) { LooksPage(0.dp, onSearch = {}) }
        assertTrue(rows().size >= 12)
        assertNoRowTextIsCutOff()
    }

    @Test fun dayAndNightPicksItsLooksInASheet() {
        page()
        assertFalse(hasRow("By day") || hasRow("By night") || hasRow("Switch"))
        tap("Change Look by time of day")
        await("the schedule") { container.looks.schedule.value.on }
        val group = { groups()[4].second.map { titleOf(it) } }
        assertEquals(listOf("Change Look by time of day", "By day", "By night", "Switch"), group())
        // On, the switch's row says what comes next.
        assertTrue(valueOf("Change Look by time of day"), valueOf("Change Look by time of day").startsWith("Next: "))
        assertEquals(SettingsTags.ROW_PICK, tagOf(row("By day").fetchSemanticsNode()))

        val other = BuiltInLooks.all.first { it.id != container.looks.schedule.value.night && it.id != container.looks.schedule.value.day }
        tap("By night")
        assertTrue("every Look is offered: ${sheetOptions()}", sheetOptions().containsAll(BuiltInLooks.all.map { it.name }))
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(other.name)).performClick()
        await("the night's Look") { container.looks.schedule.value.night == other.id }
        sheetGone()
        assertEquals(other.name, valueOf("By night"))
        val day = BuiltInLooks.all.first { it.id != other.id && it.id != container.looks.schedule.value.day }
        pick("By day", day.name)
        await("the day's Look") { container.looks.schedule.value.day == day.id }
        sheetGone()

        chip("Switch", tr(R.string.set_times))
        await { !container.looks.schedule.value.sun }
        assertEquals(listOf("Change Look by time of day", "By day", "By night", "Switch", "Day starts at", "Night starts at"), group())
        assertTrue(valueOf("Day starts at").isNotBlank() && valueOf("Night starts at").isNotBlank())
        assertBudgets()
        tap("Change Look by time of day")
        await { !container.looks.schedule.value.on }
        assertEquals(listOf("Change Look by time of day"), group())
    }

    @Test fun customizeByTappingIsWithHistory() {
        page()
        assertEquals(history, groups().first { g -> g.second.any { titleOf(it) == "Customize by tapping" } }.first)
        assertFalse(container.customizing.value)
        tap("Customize by tapping")
        await("Customize by tapping") { container.customizing.value }
        tap("Customize by tapping")
        await { !container.customizing.value }
    }

    @Test fun historyListsTheStepsAndClearsThem() {
        runBlocking { container.looks.apply(BuiltInLooks.all.first { it.id != container.looks.currentId.value }) }
        await("a step in the history") { container.looks.steps.value.isNotEmpty() }
        page()
        val step = container.looks.steps.value.first()
        val rows = groups()[5].second
        assertEquals(listOf(step.label, "Clear history", "Customize by tapping"), rows.map { titleOf(it) }.let { listOf(it.first()) + it.takeLast(2) })
        assertEquals(SettingsTags.ROW_LINK, tagOf(rows.first()))
        tap("Clear history")
        compose.onNodeWithText(tr(R.string.clear_the_look_s_history)).assertExists()
        assertTrue("nothing is cleared before the confirm", container.looks.steps.value.isNotEmpty())
        compose.onNode(hasText(tr(R.string.clear)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        await("the history to clear") { container.looks.steps.value.isEmpty() }
        assertFalse(hasRow("Clear history"))
    }

    private fun openResetEverything() {
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        // No "Reset this page" here: a Look is every look setting.
        assertEquals(0, compose.onAllNodes(hasTestTag(SettingsTags.RESET_ITEM)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Reset everything").performClick()
        compose.waitForIdle()
    }

    @Test fun resetEverythingIsUnderTheMenuAfterItsConfirm() {
        container.settings.setReduceMotion(true)
        await { stored.reduceMotion }
        page()
        openResetEverything()
        // The confirm the old "Reset look & layout" button had.
        compose.onNodeWithText(tr(R.string.reset_look_layout)).assertExists()
        compose.onNodeWithText(tr(R.string.font_text_size_corners_spacing_navigation)).assertExists()
        compose.onNode(hasText(tr(R.string.cancel)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue("Cancel resets nothing", stored.reduceMotion)
        openResetEverything()
        compose.onNode(hasText(tr(R.string.reset)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        await("the look to reset") { !stored.reduceMotion }
    }

    @Test fun getALookOpensTheScanner() {
        show(routes = true) { LooksPage(0.dp, onSearch = {}) }
        tap("Scan a Look's QR code")
        await("the scanner") { nav?.currentDestination?.route == Routes.LOOK_SCAN }
    }

    @Test fun oldLinksAndSearchLandOnTheGroups() {
        val p = SettingsPageId.LOOKS
        assertEquals(SettingsTarget(p), SettingsMap.resolve("looks"))
        assertEquals(SettingsTarget(p, history), SettingsMap.resolve("looks", tr(R.string.history)))
        assertEquals(SettingsTarget(p, "Day and night"), SettingsMap.resolve("looks", tr(R.string.day_and_night)))
        val lines = SettingsIndex.entries.filter { it.page == p && !it.isPage }
        for (e in lines) assertTrue("${e.title} › ${e.group}", e.group in groupNames)
        val tapping = lines.single { it.title == "Customize by tapping" }
        assertEquals(history, tapping.group)
        assertEquals(Routes.settings("looks", history), tapping.opens)
        assertTrue(SettingsIndex.search("customize by tapping").first().page == p)
        assertTrue(SettingsIndex.search("undo").any { it.page == p && it.title == "History" })
    }

    // ------------------------------------------------------------------ Drawn for real (PNGs in app/build/settings-pages/looks/)

    private fun shot(name: String, glass: Boolean, scroll: Int? = null) {
        show(glass = glass) { LooksPage(0.dp, onSearch = {}) }
        if (scroll != null) { compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(scroll); compose.waitForIdle() }
        assertEquals("360 dp wide at xxhdpi", 1080, save("looks", name).width)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderLight() {
        container.settings.setThemeMode(ThemeMode.LIGHT); await { stored.themeMode == ThemeMode.LIGHT }
        shot("light", glass = true)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderDark() {
        container.settings.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        shot("dark", glass = true)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderDarkScrolled() {
        container.settings.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        container.looks.setSchedule(LookSchedule(on = true))
        // The title, the preview's room, the jump chips, the "now" card, Vinz's, yours, the community, Get a Look: Day and night.
        shot("dark-scrolled", glass = true, scroll = 8)
    }

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun renderPerformance() {
        container.settings.setThemeMode(ThemeMode.DARK); container.settings.setPerformanceMode(true)
        await { stored.themeMode == ThemeMode.DARK && stored.performanceMode }
        shot("performance", glass = false)
    }

    /** The whole page at once, with Day and night on, to look every row over. */
    @Config(qualifiers = "w360dp-h4600dp-xhdpi")
    @Test fun renderWholePageDark() {
        container.settings.setThemeMode(ThemeMode.DARK); await { stored.themeMode == ThemeMode.DARK }
        container.looks.setSchedule(LookSchedule(on = true, sun = false))
        show(glass = true) { LooksPage(0.dp, onSearch = {}) }
        save("looks", "whole-dark")
    }
}
