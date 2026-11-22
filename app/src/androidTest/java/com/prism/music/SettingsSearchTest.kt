package com.vinz.music

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings search (#73): a word finds the setting, the result opens its page, and the setting there works. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    /** 1.8.0: Crossfade is one choice (Off / Automatic / Fixed length); its old name still finds it. */
    @Test fun aSearchOpensTheSettingAndItWorks() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("crossfade songs")
        compose.clickWhenFound(androidx.compose.ui.test.hasText("Crossfade") and androidx.compose.ui.test.hasClickAction())
        // Its page (Playback since 1.8.4, opened at "Song to song"): the search box is gone and the choice is there,
        // as the Crossfade row's chips.
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.clickWhenFound(settingsChip("Fixed length", row = "Crossfade"))
        compose.waitUntil(5_000) { Fixture.container.settings.current.let { it.mix.on && !it.mix.auto } }
        val s = Fixture.container.settings.current
        assertTrue("Crossfade on with a fixed length (mix=${s.mix.on}, mixAuto=${s.mix.auto})", s.mix.on && !s.mix.auto)
    }

    /** #209 (1.7.9): a slider described in words also shows its number (Lyrics & Sing → Text since 1.8.4). */
    @Test fun slidersShowTheirNumber() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("line spacing")
        compose.clickWhenFound(androidx.compose.ui.test.hasText("Line spacing") and androidx.compose.ui.test.hasClickAction())
        compose.waitForText("As usual · 100%")
    }

    @Test fun aWordWithNoSettingSaysSo() {
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("zzqx")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Crossfade songs")).fetchSemanticsNodes().isEmpty() }
    }
}

/**
 * Home's picture opens Settings (there's no Settings tab by default): the root, with its search field.
 * [name]: the Settings button's name in the language Vinz is in.
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openSettings(name: String = "Settings") {
    waitUntil(15_000) { onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
    onAllNodesWithContentDescription(name).onFirst().performClick()
    waitUntil(10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
}

// ---------------------------------------------------------------------------- Settings since 1.8.4
// The root lists 17 pages in five groups, each one tap away (no pages of pages). A page is groups of rows of five
// kinds; choices that need an example, or are a list, open in a sheet.

/** A page's row on the Settings root. */
fun settingsRootRow(page: SettingsPageId): SemanticsMatcher =
    hasTestTag(SettingsTags.ROW_LINK) and androidx.compose.ui.test.hasText(page.title) and hasClickAction()

/** Settings → [page], from anywhere Home's Settings button shows. [name]: as [openSettings]'s. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openSettingsPage(page: SettingsPageId, name: String = "Settings") {
    openSettings(name)
    openFromSettingsRoot(page)
}

/** On the Settings root: taps [page]'s row and waits for the page (it opens at its top, its large title showing). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openFromSettingsRoot(page: SettingsPageId) {
    clickWhenFound(settingsRootRow(page), 15_000)
    waitUntil(10_000) { onAllNodes(hasTestTag(SettingsTags.PAGE_TITLE) and androidx.compose.ui.test.hasText(page.title)).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/**
 * After Settings was opened again by its button: it comes back on the page it was left on (tabs keep their pages) or on
 * the root. Waits for either, and from the root opens [page].
 */
fun androidx.compose.ui.test.junit4.ComposeTestRule.backOnSettingsPage(page: SettingsPageId) {
    // The root has the search field; a page has the search button in its bar.
    fun onRoot() = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    fun onPage() = onAllNodes(hasTestTag(SettingsTags.PAGE_SEARCH)).fetchSemanticsNodes().isNotEmpty()
    waitUntil(10_000) { onAllNodes(hasTestTag(SettingsTags.PAGE_LIST)).fetchSemanticsNodes().isNotEmpty() && (onRoot() || onPage()) }
    if (onRoot()) clickWhenFound(settingsRootRow(page), 15_000)
    waitForIdle()
}

/**
 * A row that is tapped as a whole (a switch, a pick or a link), by its title. Not a jump chip, a group's heading or a
 * search result of the same name: those carry no row tag.
 */
fun settingsRow(title: String): SemanticsMatcher =
    androidx.compose.ui.test.hasText(title) and hasClickAction() and
        (hasTestTag(SettingsTags.ROW_SWITCH) or hasTestTag(SettingsTags.ROW_PICK) or hasTestTag(SettingsTags.ROW_LINK))

/** A chip of a choice row or of a row of show-chips; [row]: only in the row with that title. */
fun settingsChip(label: String, row: String? = null): SemanticsMatcher {
    val inRow = if (row == null) hasTestTag(SettingsTags.ROW_CHOICE) else hasTestTag(SettingsTags.ROW_CHOICE) and hasAnyDescendant(androidx.compose.ui.test.hasText(row))
    return androidx.compose.ui.test.hasText(label) and hasClickAction() and hasAnyAncestor(inRow)
}

/** An option of the sheet a pick row opened (a card or a list line). */
fun sheetOption(label: String): SemanticsMatcher = hasTestTag(SettingsTags.SHEET_OPTION) and androidx.compose.ui.test.hasText(label)

/** Whether a settings sheet is open. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.settingsSheetOpen(): Boolean = onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isNotEmpty()

/** Taps the pick row titled [row] and waits for its sheet. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openSettingsSheet(row: String, timeoutMs: Long = 10_000) {
    clickWhenFound(settingsRow(row), timeoutMs)
    waitUntil(timeoutMs) { settingsSheetOpen() }
}

/** Taps the pick row titled [row] and, in its sheet, [option]; the sheet closes itself after a pick. */
fun androidx.compose.ui.test.junit4.ComposeTestRule.pickInSheet(row: String, option: String, timeoutMs: Long = 10_000) {
    openSettingsSheet(row, timeoutMs)
    clickWhenFound(sheetOption(option), timeoutMs)
    waitUntil(timeoutMs) { !settingsSheetOpen() }
}

/**
 * Lyrics & Sing → Sources: the switch of "Your music files" in the order of the lyric sources, the one control for
 * reading lyrics out of the listener's own files since 1.8.4 (the page's own tag, `lyricsSing:source:<source>`).
 */
const val EMBEDDED_SOURCE_SWITCH = "lyricsSing:source:EMBEDDED"

/** Opens the ⓘ of the row or group titled [title] (explanations are behind it since 1.8.4, never a visible note). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.openSettingsInfo(title: String, timeoutMs: Long = 10_000) =
    clickWhenFound(androidx.compose.ui.test.hasContentDescription(tr(R.string.more_about, title)) and hasClickAction(), timeoutMs)

/** Scrolls the settings page until a node matching [matcher] is built (a page builds only the groups on screen). */
fun androidx.compose.ui.test.junit4.ComposeTestRule.scrollSettingsTo(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
        if (System.currentTimeMillis() > end) throw AssertionError("Not on this settings page: ${matcher.description}")
        onAllNodes(hasTestTag(SettingsTags.PAGE_LIST)).fetchSemanticsNodes().indices.forEach { i ->
            runCatching { onAllNodes(hasTestTag(SettingsTags.PAGE_LIST))[i].performScrollToNode(matcher) }
        }
        waitForIdle()
    }
}

fun androidx.compose.ui.test.junit4.ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) =
    waitUntil(timeoutMs) { onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
