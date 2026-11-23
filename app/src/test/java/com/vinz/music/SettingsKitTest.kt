package com.vinz.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import com.vinz.music.ui.screens.settings.PickCards
import com.vinz.music.ui.screens.settings.PickCardsRow
import com.vinz.music.ui.screens.settings.RowsSheet
import androidx.compose.ui.semantics.Role
import com.vinz.music.ui.screens.settings.SettingsPageId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.screens.settings.ChoiceRow
import com.vinz.music.ui.screens.settings.LanguageRegionContent
import com.vinz.music.ui.screens.settings.LinkRow
import com.vinz.music.ui.screens.settings.PageReset
import com.vinz.music.ui.screens.settings.PickListSheet
import com.vinz.music.ui.screens.settings.PickRow
import com.vinz.music.ui.screens.settings.PickSheet
import com.vinz.music.ui.screens.settings.SettingsCardGlass
import com.vinz.music.ui.screens.settings.SettingsGroup
import com.vinz.music.ui.screens.settings.SettingsPageScaffold
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.ShowChipsRow
import com.vinz.music.ui.screens.settings.SliderRow
import com.vinz.music.ui.screens.settings.SwitchRow
import com.vinz.music.ui.screens.settings.resetLine
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, the settings kit (plan §2.2, §2.4, §2.9): the five row types with their tags, values and heights, the grouped
 * card (plain in Performance mode), the page scaffold (Reset this page, jump chips, the pinned preview) and the pick
 * sheets, on a 360 dp phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class SettingsKitTest {
    @get:Rule val compose = createComposeRule()

    private fun show(settings: AppSettings = AppSettings(themeMode = ThemeMode.DARK), fontScale: Float = 1f, frosted: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                VinzTheme(settings, artworkColor = null) {
                    CompositionLocalProvider(LocalFrosted provides frosted) {
                        Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()

    @Composable
    private fun EveryRow() {
        SettingsGroup("Rows") {
            SwitchRow("Gapless albums", true, value = "On for albums") {}
            PickRow("Layout", "Classic") {}
            SliderRow("Crossfade", 6f, 0f..12f, { "${it.toInt()} s" }) {}
            ChoiceRow("Theme", listOf("Light", "Dark", "Auto"), "Dark", { it }) {}
            ShowChipsRow("In the queue", listOf(ShowOption("Covers", true) {}, ShowOption("Durations", false) {}, ShowOption("Numbers", true) {}))
            LinkRow("Equalizer", value = "Flat") {}
        }
    }

    @Test fun everyRowCarriesItsTypeAndItsValue() {
        show { EveryRow() }
        fun values(tag: String) = tagged(tag).map { it.config[SettingsRowValue] }
        assertEquals(listOf("On for albums"), values(SettingsTags.ROW_SWITCH))
        assertEquals(listOf("Classic"), values(SettingsTags.ROW_PICK))
        assertEquals(listOf("6 s"), values(SettingsTags.ROW_SLIDER))
        assertEquals(listOf("Dark", "Covers · Numbers"), values(SettingsTags.ROW_CHOICE))
        assertEquals(listOf("Flat"), values(SettingsTags.ROW_LINK))
        // Five types and nothing else: every row in the card is one of them.
        assertEquals(6, SettingsTags.rowTypes.sumOf { tagged(it).size })
        assertEquals(5, SettingsTags.rowTypes.size)
        // The value line is on screen, not only in the tag.
        compose.onNodeWithText("On for albums", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Classic", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("6 s", useUnmergedTree = true).assertExists()
    }

    @Test fun aRowIs64WithAValueLineAnd56Without() {
        show {
            SettingsGroup(null) {
                SwitchRow("Skip silence", false) {}
                SwitchRow("Normalize", true, value = "Quieter songs come up") {}
                PickRow("Layout", "Classic") {}
                LinkRow("Licenses") {}
                LinkRow("Equalizer", value = "Flat") {}
            }
        }
        val switches = compose.onAllNodes(hasTestTag(SettingsTags.ROW_SWITCH))
        switches[0].assertHeightIsEqualTo(56.dp)
        switches[1].assertHeightIsEqualTo(64.dp)
        compose.onNodeWithTag(SettingsTags.ROW_PICK).assertHeightIsEqualTo(64.dp)
        val links = compose.onAllNodes(hasTestTag(SettingsTags.ROW_LINK))
        links[0].assertHeightIsEqualTo(56.dp)
        links[1].assertHeightIsEqualTo(64.dp)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    @Test fun atLargeTextARowGrowsAndNothingIsCutOff() {
        val title = "Keep the screen on while the lyrics are showing on the player"
        val value = "Only while a song is playing and Vinz is the app in front"
        show(fontScale = 1.5f) { SettingsGroup(null) { PickRow(title, value) {} } }
        val row = compose.onNodeWithTag(SettingsTags.ROW_PICK).fetchSemanticsNode()
        val tall = with(compose.density) { row.size.height.toDp() }
        assertTrue("the row grew past 64 dp: $tall", tall > 64.dp)
        for (text in listOf(title, value)) {
            val l = layoutOf(text)
            assertTrue("\"$text\" wraps", l.lineCount >= 2)
            assertFalse("\"$text\" is cut off", l.hasVisualOverflow)
            assertFalse(l.isLineEllipsized(l.lineCount - 1))
        }
    }

    @Test fun theChangedDotFollowsItsFlag() {
        show {
            SettingsGroup(null) {
                PickRow("Background", "Blurred cover", changed = true) {}
                PickRow("Layout", "Classic") {}
            }
        }
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.CHANGED_DOT), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(listOf(true, false), tagged(SettingsTags.ROW_PICK).map { it.config[SettingsRowChanged] })
    }

    // ---------------------------------------------------------------- the card

    /** The card's own color in the middle, drawn over red: glass lets the red through, the plain card doesn't. */
    private fun cardOverRed(settings: AppSettings): Triple<Boolean, Int, Int> {
        var plain = Color.Unspecified
        show(settings, frosted = true) {
            plain = MaterialTheme.colorScheme.surfaceContainerHigh
            Box(Modifier.fillMaxWidth().background(Color.Red)) { SettingsGroup(null) { Spacer(Modifier.fillMaxWidth().height(48.dp)) } }
        }
        val card = compose.onNodeWithTag(SettingsTags.GROUP_CARD)
        val shot = card.captureToImage().asAndroidBitmap()
        return Triple(card.fetchSemanticsNode().config[SettingsCardGlass], shot.getPixel(shot.width / 2, shot.height / 2), plain.toArgb())
    }

    @Test fun performanceModeGivesThePlainCard() {
        val (glass, pixel, plain) = cardOverRed(AppSettings(themeMode = ThemeMode.DARK, performanceMode = true))
        assertFalse("no glass in Performance mode", glass)
        assertEquals("a solid surface, nothing of the page shows through", plain, pixel)
    }

    @Test fun onAFrostedPageTheCardIsGlass() {
        val (glass, pixel, plain) = cardOverRed(AppSettings(themeMode = ThemeMode.DARK))
        assertTrue(glass)
        assertNotEquals("the page shows through the glass", plain, pixel)
    }

    // ---------------------------------------------------------------- the page

    private val groups = listOf("Layout", "Progress bar", "Cover", "On it", "Lock screen")

    @Composable
    private fun Page(
        count: Int, state: LazyListState, reset: PageReset? = null, strips: MutableList<Boolean>? = null, preview: Boolean = false,
    ) {
        SettingsPageScaffold(
            "Player", bottomPadding = 800.dp, showBack = false, listState = state, reset = reset, onSearch = {},
            preview = if (preview) ({ strip -> strips?.add(strip); Box(Modifier.fillMaxSize()) }) else null,
        ) {
            groups.take(count).forEach { g ->
                group(g) { repeat(5) { i -> SwitchRow("$g option ${i + 1}", i % 2 == 0) {} } }
            }
        }
    }

    @Test fun resetThisPageListsWhatChangesBeforeItResets() {
        var reset = 0
        val changes = listOf(resetLine("Background", "Blurred cover", "Cover colors"), resetLine("Ring on the play button", "Off", "On"))
        show { Page(2, LazyListState(), PageReset(changes) { reset++ }) }
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(listOf("Background: Blurred cover → Cover colors", "Ring on the play button: Off → On"), listed)
        compose.onNodeWithText("Reset Player?").assertExists()
        assertEquals("nothing is reset before the confirm", 0, reset)
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(1, reset)
        assertEquals("the confirm is gone", 0, compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().size)
    }

    @Test fun cancellingTheResetChangesNothing() {
        var reset = 0
        show { Page(2, LazyListState(), PageReset(listOf(resetLine("Layout", "Compact", "Classic"))) { reset++ }) }
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, reset)
    }

    @Test fun withNothingChangedResetCantBeTapped() {
        show { Page(2, LazyListState(), PageReset(emptyList()) {}) }
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).assertIsNotEnabled()
    }

    @Test fun aJumpChipScrollsToItsGroup() {
        val state = LazyListState()
        show { Page(5, state) }
        assertEquals(0, state.firstVisibleItemIndex)
        compose.onNode(hasText("On it") and hasAnyAncestor(hasTestTag(SettingsTags.JUMP_CHIPS))).performClick()
        compose.waitForIdle()
        // The list: the title, the chips, then the groups; "On it" is the fourth group.
        assertEquals(2 + 3, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
        compose.onNodeWithText("On it option 1", useUnmergedTree = true).assertExists()
    }

    /**
     * 1.8.4 review: a page opened at a group (a search result) lands there once. Scrolled away, it leaves and comes back
     * (Back from a screen it opened, a rotation: its saved state is kept, as the navigator keeps it) where it was
     * scrolled to, not at the group again.
     */
    @Test fun aPageOpenedAtAGroupLandsThereOnlyOnce() {
        var shown by mutableStateOf(true)
        var state: LazyListState? = null
        show {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            if (shown) holder.SaveableStateProvider("page") {
                val s = androidx.compose.foundation.lazy.rememberLazyListState().also { state = it }
                SettingsPageScaffold("Player", bottomPadding = 800.dp, showBack = false, listState = s, find = "On it") {
                    groups.forEach { g -> group(g) { repeat(5) { i -> SwitchRow("$g option ${i + 1}", i % 2 == 0) {} } } }
                }
            }
        }
        // The title, the chips, then the groups; "On it" is the fourth group.
        assertEquals(2 + 3, state!!.firstVisibleItemIndex)
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(0)
        compose.waitForIdle()
        assertEquals(0, state!!.firstVisibleItemIndex)
        shown = false
        compose.waitForIdle()
        shown = true
        compose.waitForIdle()
        assertEquals("back on the page, it stays where it was scrolled to", 0, state!!.firstVisibleItemIndex)
    }

    @Test fun fewerThanFourGroupsGetNoJumpChips() {
        show { Page(3, LazyListState()) }
        assertEquals(0, tagged(SettingsTags.JUMP_CHIPS).size)
    }

    @Test fun thePinnedPreviewShrinksToAStripAndATapOpensIt() {
        val strips = ArrayList<Boolean>()
        show { Page(3, LazyListState(), strips = strips, preview = true) }
        compose.onNodeWithTag(SettingsTags.PREVIEW).assertHeightIsEqualTo(200.dp)
        assertEquals(false, strips.last())
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(3)
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.PREVIEW).assertHeightIsEqualTo(72.dp)
        assertEquals("the preview is told it's a strip", true, strips.last())
        // Still there, under the bar.
        compose.onNodeWithTag(SettingsTags.PREVIEW).assertExists()
        compose.onNodeWithTag(SettingsTags.PREVIEW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsTags.PREVIEW).assertHeightIsEqualTo(200.dp)
        assertEquals(false, strips.last())
    }

    // ---------------------------------------------------------------- pick sheets

    private val styles = listOf("Line", "Wave", "Thick", "Steps")

    @Test fun aTapInThePickSheetAppliesAndCloses() {
        var style by mutableStateOf("Line")
        var open by mutableStateOf(true)
        show {
            if (open) PickSheet("Progress bar style", styles, style, { it }, { style = it }, { open = false }) { Box(Modifier.fillMaxWidth().height(34.dp)) }
        }
        val options = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION))
        assertEquals(4, options.fetchSemanticsNodes().size)
        assertEquals("the current pick is the marked one", listOf(true, false, false, false), options.fetchSemanticsNodes().map { it.config[SemanticsProperties.Selected] })
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Wave")).performClick()
        compose.waitUntil(5_000) { !open }
        assertEquals("Wave", style)
    }

    @Test fun swipingThePickSheetAwayChangesNothing() {
        var style by mutableStateOf("Line")
        var picks = 0
        var open by mutableStateOf(true)
        show {
            if (open) PickSheet("Progress bar style", styles, style, { it }, { style = it; picks++ }, { open = false }) { Box(Modifier.fillMaxWidth().height(34.dp)) }
        }
        compose.onNodeWithTag(SettingsTags.SHEET).performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { !open }
        assertEquals("Line", style)
        assertEquals(0, picks)
    }

    @Test fun theListSheetSearchesAndPicks() {
        val countries = listOf("Austria", "Belgium", "Germany", "Japan", "Norway")
        var country by mutableStateOf("Japan")
        var open by mutableStateOf(true)
        show {
            if (open) PickListSheet("Content country", countries, country, { it }, { country = it }, { open = false }, searchable = true)
        }
        assertEquals(5, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        compose.onNode(hasSetTextAction()).performTextInput("ger")
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Germany")).performClick()
        compose.waitUntil(5_000) { !open }
        assertEquals("Germany", country)
    }

    // ---------------------------------------------------------------- what the 17 pages found (kit fixes)

    /** A line drawn wider than the room its text was given (`didOverflowWidth` also says so of one-line text that fits). */
    private fun tooWide(l: TextLayoutResult) = (0 until l.lineCount).any { l.getLineRight(it) - l.getLineLeft(it) > l.size.width + 1f }

    /** A page's gutters: its cards are 328 dp wide on a 360 dp phone. */
    @Composable
    private fun OnAPage(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = 16.dp)) { SettingsGroup(null, content = content) }

    /**
     * 1.8.4 review: a save that stores what was there already (Player's progress bar thickness snaps to 1× near it) leaves
     * the value as it was, and the slider went on showing where it was let go. Once the save has landed it shows what's
     * stored; a save that stores what it was let go at shows that.
     */
    @Test fun aSliderWhoseSaveSnapsBackShowsTheStoredValue() {
        var stored by mutableStateOf(1f)
        show {
            OnAPage {
                SliderRow("Progress bar thickness", stored, 0.6f..2f, { String.format(java.util.Locale.ROOT, "%.2f", it) }) {
                    stored = if (it in 0.93f..1.07f) 1f else it
                }
            }
        }
        fun let(at: Float) {
            compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(at) }
            compose.waitForIdle()
        }
        fun shown() = tagged(SettingsTags.ROW_SLIDER).single().config[SettingsRowValue]
        fun settles(to: String) {
            try { compose.waitUntil(3_000) { shown() == to } } catch (e: Throwable) { throw AssertionError("the slider shows ${shown()}, not $to", e) }
        }
        let(1.05f)
        assertEquals(1f, stored)
        settles("1.00")
        let(1.5f)
        assertEquals(1.5f, stored)
        settles("1.50")
        // (And it stays there once the save has had its time.)
        Thread.sleep(600)
        compose.waitForIdle()
        assertEquals("1.50", shown())
    }

    @Test fun showChipsWithNothingTickedSayNone() {
        var covers by mutableStateOf(false)
        show { OnAPage { ShowChipsRow("In the queue", listOf(ShowOption("Covers", covers) { covers = it }, ShowOption("Durations", false) {})) } }
        assertEquals("the row's value", listOf("None"), tagged(SettingsTags.ROW_CHOICE).map { it.config[SettingsRowValue] })
        // 1.8.7.6 (W39): not as a line over the chips, which already show that none is on ("When saving: None" above
        // chips that are off read like one more setting).
        assertEquals(0, compose.onAllNodes(hasText("None"), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Covers").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Covers"), tagged(SettingsTags.ROW_CHOICE).map { it.config[SettingsRowValue] })
        assertEquals(0, compose.onAllNodes(hasText("None"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun aLongChipGrowsToTwoLinesAtLargeTextAndIsNotCutOff() {
        val shown = "Song and artist names in the bar"
        val choice = "Only while a song is playing"
        show(fontScale = 1.5f) {
            OnAPage {
                ShowChipsRow("Show", listOf(ShowOption(shown, true) {}, ShowOption("Covers", false) {}, ShowOption("Durations", true) {}))
                ChoiceRow("Keep the screen on", listOf("Never", choice, "Always"), "Never", { it }) {}
            }
        }
        for (text in listOf(shown, choice)) {
            val l = layoutOf(text)
            assertTrue("\"$text\" wraps inside its chip (lines: ${l.lineCount})", l.lineCount >= 2)
            assertFalse("\"$text\" ends in an ellipsis", l.isLineEllipsized(l.lineCount - 1))
            assertFalse("\"$text\" is cut off", l.didOverflowHeight || tooWide(l))
            val chip = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(SettingsTags.ROW_CHOICE))).fetchSemanticsNode()
            assertTrue("the chip grew past one line's 36 dp", with(compose.density) { chip.size.height.toDp() } > 36.dp)
            assertTrue("and stays inside the card", chip.boundsInRoot.right <= with(compose.density) { (360.dp - 32.dp).toPx() })
        }
        // A short chip is as it was.
        compose.onNodeWithText("Covers").assertHeightIsEqualTo(36.dp)
    }

    /** The options of a Choice row (each carries Selected), left to right, top to bottom. */
    private fun choiceOptions(title: String) = compose.onAllNodes(
        hasAnyAncestor(hasTestTag(SettingsTags.ROW_CHOICE) and androidx.compose.ui.test.hasAnyDescendant(hasText(title))) and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
    ).fetchSemanticsNodes()

    @Test fun aCapsuleWhoseWordsDontFitBecomesWrappingChips() {
        // Each within the capsule's old rule (four words, 12 letters at most, 30 together) and too wide for a quarter of it.
        val wide = listOf("WWWWWWW", "MMMMMMM", "WMWMWMW", "MWMWMWM")
        val words = listOf("Standard", "Balanced", "Compact", "Minimal")
        show(fontScale = 1.25f) {
            OnAPage {
                ChoiceRow("Wide letters", wide, wide[0], { it }) {}
                ChoiceRow("Density", words, words[0], { it }) {}
            }
        }
        for (text in wide + words) {
            val l = layoutOf(text)
            assertFalse("\"$text\" is cut off in the capsule", l.isLineEllipsized(0) || tooWide(l))
        }
        assertTrue("the wide ones wrapped onto more lines", choiceOptions("Wide letters").map { it.boundsInRoot.top }.distinct().size > 1)
    }

    @Test fun wordsThatFitStayOneCapsule() {
        val options = listOf("Off", "Cover", "Video", "Both")
        show { OnAPage { ChoiceRow("Edge to edge", options, "Off", { it }) {} } }
        val segments = choiceOptions("Edge to edge")
        assertEquals(4, segments.size)
        assertEquals("one line", 1, segments.map { it.boundsInRoot.top }.distinct().size)
        assertEquals("four equal shares", 1, segments.map { it.size.width }.distinct().size)
        // The shares fill the capsule: the card's 328 dp less the row's gutters and the capsule's rim.
        val whole = with(compose.density) { segments.sumOf { it.size.width }.toDp() }
        assertTrue("$whole", whole > 280.dp)
    }

    @Test fun aControlInAPickCardsExampleDoesNotSwallowTheTap() {
        var picked = ""
        var inner = 0
        show {
            PickCards("Progress bar style", styles, "Line", { it }, null, 2, 0.8f, {
                Box(Modifier.fillMaxWidth().height(80.dp).clickable { inner++ })
            }) { picked = it }
        }
        // A tap in the middle of the example, where the example's own control is.
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Wave")).performTouchInput { click(Offset(centerX, 40.dp.toPx())) }
        compose.waitForIdle()
        assertEquals("the example's control wasn't tapped", 0, inner)
        assertEquals("the card took the tap", "Wave", picked)
    }

    @Test fun everyPageTitleFitsTheBarOnA360Phone() {
        var title by mutableStateOf("")
        var scale by mutableStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                VinzTheme(AppSettings(themeMode = ThemeMode.DARK), artworkColor = null) {
                    Box(Modifier.width(360.dp)) {
                        SettingsPageScaffold(title, 0.dp, onBack = {}, onSearch = {}, reset = PageReset(emptyList()) {}) {
                            group(null, key = "g") { SwitchRow("Row", true) {} }
                        }
                    }
                }
            }
        }
        val pages = SettingsPageId.entries.map { it.title }
        assertEquals("the 17 pages", 17, pages.size)
        // And Customize Home, a page of its own under Home & Library.
        val titles = pages + tr(R.string.customize_home)
        val cut = ArrayList<String>()
        val shrunk = ArrayList<String>()
        for (s in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) for (t in titles) {
            scale = s; title = t
            compose.waitForIdle()
            // The bar's title: the node with the page's name that isn't the large title in the list.
            val node = compose.onAllNodes(hasText(t), useUnmergedTree = true).fetchSemanticsNodes()
                .single { it.config.getOrNull(SemanticsProperties.TestTag) != SettingsTags.PAGE_TITLE }
            val out = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
            val l = out.first()
            if (l.lineCount != 1 || l.isLineEllipsized(0) || tooWide(l)) cut += "\"$t\" at $s"
            // Between the back button and the search and ⋯ buttons, not under them.
            val (left, right) = with(compose.density) { 54.dp.toPx() to (360.dp - 104.dp).toPx() }
            if (node.boundsInRoot.left < left || node.boundsInRoot.right > right) cut += "\"$t\" at $s is under a button"
            if (s == 1f && l.layoutInput.style.fontSize.value < 14f) shrunk += t
            if (l.layoutInput.style.fontSize.value < 15.95f) println("bar title \"$t\" at $s: ${"%.1f".format(l.layoutInput.style.fontSize.value)} sp")
        }
        assertEquals("cut off in the bar", emptyList<String>(), cut)
        assertEquals("at normal text no title is shrunk below 14 sp", emptyList<String>(), shrunk)
    }

    /** A tap on the current pick ("Line") in one sheet; returns (picks, repicks) once the sheet has closed. */
    private fun tapTheCurrentPick(list: Boolean, asked: Boolean): Pair<Int, Int> {
        var picks = 0
        var repicks = 0
        var open by mutableStateOf(true)
        show {
            val again: (() -> Unit)? = if (asked) ({ repicks++ }) else null
            if (open && !list) PickSheet("Progress bar style", styles, "Line", { it }, { picks++ }, { open = false }, onRepick = again) { Box(Modifier.fillMaxWidth().height(34.dp)) }
            if (open && list) PickListSheet("Progress bar style", styles, "Line", { it }, { picks++ }, { open = false }, onRepick = again)
        }
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Line")).performClick()
        compose.waitUntil(5_000) { !open }
        return picks to repicks
    }

    @Test fun aTapOnTheCurrentCardCallsOnRepick() = assertEquals(0 to 1, tapTheCurrentPick(list = false, asked = true))
    @Test fun aTapOnTheCurrentListLineCallsOnRepick() = assertEquals(0 to 1, tapTheCurrentPick(list = true, asked = true))

    /** Not asked for: a tap on the current pick only closes the sheet, as before. */
    @Test fun withoutOnRepickTheCurrentPickOnlyCloses() = assertEquals(0 to 0, tapTheCurrentPick(list = true, asked = false))

    @Test fun aPickCardsRowCanSayMoreThanThePicksLabel() {
        var repicks = 0
        show {
            OnAPage {
                PickCardsRow("Accent", listOf("Auto", "Custom"), "Custom", { it }, value = "Custom · #FF8800", onRepick = { repicks++ }, onPick = {}) { Box(Modifier.fillMaxWidth().height(34.dp)) }
                PickCardsRow("Layout", listOf("Classic", "Compact"), "Classic", { it }, onPick = {}) { Box(Modifier.fillMaxWidth().height(34.dp)) }
            }
        }
        assertEquals(listOf("Custom · #FF8800", "Classic"), tagged(SettingsTags.ROW_PICK).map { it.config[SettingsRowValue] })
        compose.onNodeWithText("Custom · #FF8800", useUnmergedTree = true).assertExists()
        // Its sheet still marks the pick by its label, and a tap on it reaches onRepick.
        compose.onAllNodes(hasTestTag(SettingsTags.ROW_PICK))[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val custom = compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Custom"))
        assertEquals(true, custom.fetchSemanticsNode().config[SemanticsProperties.Selected])
        custom.performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, repicks)
    }

    @Test fun aRowsSheetHoldsRowsThatWorkAndSwipesAway() {
        var open by mutableStateOf(true)
        var wifi by mutableStateOf(false)
        var taps = 0
        show {
            if (open) RowsSheet("Streaming quality", { open = false }, info = "What Vinz asks YouTube Music for.") {
                SwitchRow("Higher on Wi-Fi", wifi) { wifi = it }
                PickRow("On mobile data", "Normal") { taps++ }
                LinkRow("Clear the cache", action = true) { taps++ }
            }
        }
        val sheet = hasAnyAncestor(hasTestTag(SettingsTags.SHEET))
        assertEquals("one sheet, tagged as the pick sheets are", 1, tagged(SettingsTags.SHEET).size)
        assertTrue("its title is a heading", compose.onNode(hasText("Streaming quality") and sheet).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        // The rows are the kit's, inside the sheet, and they work.
        assertEquals(3, compose.onAllNodes(sheet and (hasTestTag(SettingsTags.ROW_SWITCH) or hasTestTag(SettingsTags.ROW_PICK) or hasTestTag(SettingsTags.ROW_LINK))).fetchSemanticsNodes().size)
        compose.onNode(hasTestTag(SettingsTags.ROW_SWITCH) and sheet).performClick()
        compose.onNode(hasTestTag(SettingsTags.ROW_PICK) and sheet).performClick()
        compose.waitForIdle()
        assertTrue(wifi)
        assertEquals(1, taps)
        assertTrue("a row doesn't close the sheet", open)
        compose.onNodeWithTag(SettingsTags.SHEET).performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { !open }
    }

    @Test fun anActionRowIsAButtonWithoutAChevron() {
        var taps = 0
        show {
            OnAPage {
                LinkRow("Licenses") {}
                LinkRow("Back up now", value = "Last: yesterday", icon = com.vinz.music.ui.theme.VinzIcons.Settings, action = true) { taps++ }
            }
        }
        val rows = tagged(SettingsTags.ROW_LINK)
        assertEquals(listOf(Role.Button, Role.Button), rows.map { it.config[SemanticsProperties.Role] })
        assertEquals(listOf("", "Last: yesterday"), rows.map { it.config[SettingsRowValue] })
        // Same heights as any link; the chevron is what's gone: the action's title line runs on to the row's end gutter.
        val links = compose.onAllNodes(hasTestTag(SettingsTags.ROW_LINK))
        links[0].assertHeightIsEqualTo(56.dp)
        links[1].assertHeightIsEqualTo(64.dp)
        fun pixels(i: Int): Int {
            // What's drawn in the chevron's place (18 dp before the row's 16 dp end gutter, in the middle of the row),
            // against the row's own ground beside it.
            val shot = links[i].captureToImage().asAndroidBitmap()
            val (wide, gutter, half) = with(compose.density) { Triple(34.dp.roundToPx(), 16.dp.roundToPx(), 10.dp.roundToPx()) }
            var n = 0
            for (y in shot.height / 2 - half until shot.height / 2 + half) {
                val ground = shot.getPixel(shot.width - gutter / 2, y)
                for (x in shot.width - wide until shot.width - gutter) if (shot.getPixel(x, y) != ground) n++
            }
            return n
        }
        assertTrue("a link has its chevron", pixels(0) > 0)
        assertEquals("an action has none", 0, pixels(1))
        links[1].performClick()
        assertEquals(1, taps)
    }

    @Test fun theCountrySheetOpensWithAutomaticAtItsTop() {
        show { LanguageRegionContent(0.dp, AppLanguage.PHONE, "", {}, {}, showBack = false) }
        compose.onAllNodes(hasTestTag(SettingsTags.ROW_PICK))[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val first = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().first()
        assertEquals("Automatic (follow the phone)", first.config[SemanticsProperties.Text].joinToString { it.text })
        assertEquals(true, first.config[SemanticsProperties.Selected])
        val search = compose.onNodeWithTag(SettingsTags.SHEET_SEARCH).fetchSemanticsNode().boundsInWindow
        assertEquals("whole, right under the search field", search.bottom + with(compose.density) { 8.dp.toPx() }, first.boundsInWindow.top, 1.5f)
    }

    // ---------------------------------------------------------------- the sample page

    @Test fun languageAndRegionShowsBothValuesAndPicksACountry() {
        var country by mutableStateOf("DE")
        var language = "unset"
        show { LanguageRegionContent(0.dp, AppLanguage.PHONE, country, { language = it }, { country = it }, showBack = false) }
        val rows = tagged(SettingsTags.ROW_PICK)
        assertEquals(2, rows.size)
        assertTrue("the language row says it follows the phone: ${rows[0].config[SettingsRowValue]}", rows[0].config[SettingsRowValue].startsWith("Automatic ("))
        assertEquals("Germany", rows[1].config[SettingsRowValue])
        assertEquals(listOf(false, true), rows.map { it.config[SettingsRowChanged] })
        // One group, no header that repeats the page.
        assertEquals(1, tagged(SettingsTags.GROUP).size)

        // (The row's own tap: a click at its middle would land on the ⓘ, which sits right after this short title.)
        compose.onAllNodes(hasTestTag(SettingsTags.ROW_PICK))[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("norw")
        compose.waitForIdle()
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Norway")).performClick()
        compose.waitUntil(5_000) { country == "NO" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isEmpty() }
        assertEquals("Norway", tagged(SettingsTags.ROW_PICK)[1].config[SettingsRowValue])
        assertEquals("the language was left alone", "unset", language)

        // Reset this page lists the country (and only it) and puts it back to Automatic.
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(1, listed.size)
        assertTrue(listed[0], listed[0].startsWith("Content country: Norway → Automatic ("))
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals("", country)
        assertEquals(listOf(false, false), tagged(SettingsTags.ROW_PICK).map { it.config[SettingsRowChanged] })
    }
}
