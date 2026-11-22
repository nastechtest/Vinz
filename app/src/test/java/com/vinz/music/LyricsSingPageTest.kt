package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.LyricsSource
import com.vinz.music.data.prefs.LyricsStyle
import com.vinz.music.data.prefs.RomanizeMode
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.LyricsSingPage
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.index
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Lyrics & Sing rebuilt with the kit (plan §2.3–2.7, §2.11, the ledger's rows for the page): six
 * groups within the budgets, only the five row types (and the source list), every stored setting of the page with a
 * control that changes it, the lyrics font moved here, "Lyrics earlier / later" (#404), and the page drawn in light,
 * dark, Performance mode and at large text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h6000dp-mdpi", application = android.app.Application::class)
class LyricsSingPageTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    /**
     * The settings' store is one for the whole test run (its file outlives a test's own container), and these tests
     * change nearly every setting of the page: each starts from Vinz as it comes, and leaves it so.
     */
    private fun asVinzComes() = kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }

    @org.junit.Before fun fresh() = asVinzComes()
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    private val stored: AppSettings get() = container.settings.stored.value

    private fun show(fontScale: Float = 1f, frosted: Boolean = false) {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale)) {
                StoredSettings {
                    VinzTheme(com.vinz.music.ui.theme.LocalAppSettings.current, artworkColor = null) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            CompositionLocalProvider(LocalFrosted provides frosted) { LyricsSingPage(0.dp, onSearch = {}) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun until(what: String, condition: () -> Boolean) {
        try { compose.waitUntil(8_000) { condition() } } catch (e: Throwable) { throw AssertionError("Waited in vain: $what", e) }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Finding rows

    /** The source list isn't one of the five row types: a list in a group, counted as one row. */
    private val listTag = "lyricsSing:sourceOrder"
    private val rowTags = SettingsTags.rowTypes + listTag
    private val isRow = SemanticsMatcher("is a settings row") { it.config.getOrNull(SemanticsProperties.TestTag) in rowTags }

    /** A row's title: the first text in it (its value comes after, and may be another row's name: "Your colors"). */
    private fun firstText(n: SemanticsNode): String? =
        n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: n.children.firstNotNullOfOrNull { firstText(it) }

    private fun rowOf(title: String) = SemanticsMatcher("is the row \"$title\"") { it.config.getOrNull(SemanticsProperties.TestTag) in rowTags && firstText(it) == title }
    private fun row(title: String) = compose.onNode(rowOf(title), useUnmergedTree = true)
    private fun has(title: String) = compose.onAllNodes(rowOf(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun valueOf(title: String): String = row(title).fetchSemanticsNode().config[SettingsRowValue]

    private fun descendants(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + descendants(it) }

    private fun toggle(title: String) { row(title).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    private fun sheetOpen() = compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isNotEmpty()

    /** Opens the row's sheet and taps the option that says [option]. */
    private fun pick(title: String, option: String) {
        row(title).performSemanticsAction(SemanticsActions.OnClick)
        until("$title's sheet") { sheetOpen() }
        compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(option, substring = true)).onFirst().performClick()
        until("$title's sheet to close") { !sheetOpen() }
    }

    private fun chip(title: String, word: String) {
        compose.onAllNodes(hasText(word) and hasAnyAncestor(rowOf(title))).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun slide(title: String, to: Float) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(rowOf(title)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(to) }
        compose.waitForIdle()
    }

    /** In the color picker: types a color's code and uses it. */
    private fun useColor(hex: String) {
        until("the color picker") { compose.onAllNodes(hasText(tr(R.string.use_this_color))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement(hex)
        compose.waitForIdle()
        compose.onNode(hasText(tr(R.string.use_this_color))).performClick()
        until("the color picker to close") { compose.onAllNodes(hasText(tr(R.string.use_this_color))).fetchSemanticsNodes().isEmpty() }
    }

    // ------------------------------------------------------------------ Budgets

    private val groupTitles get() = listOf(
        tr(R.string.style), tr(R.string.text), tr(R.string.ls_color_and_effects), tr(R.string.ls_language_and_translations),
        tr(R.string.ls_sources_and_timing), tr(R.string.ls_sing_and_music_files),
    )

    @Test fun sixGroupsWithinTheBudgetsAndOnlyTheFiveRowTypes() {
        show()
        val groups = compose.onAllNodes(hasTestTag(SettingsTags.GROUP), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the six groups of the ledger", 6, groups.size)
        val headings = groups.map { g -> descendants(g).first { it.config.getOrNull(SemanticsProperties.Heading) != null }.config[SemanticsProperties.Text].first().text }
        assertEquals(groupTitles, headings)
        assertTrue("no group repeats the page's name", headings.none { it == SettingsPageId.LYRICS_SING.title })
        for ((g, name) in groups.zip(headings)) {
            val rows = descendants(g).filter { it.config.getOrNull(SemanticsProperties.TestTag) in rowTags }
            assertTrue("$name has ${rows.size} rows", rows.size in 1..7)
            for (r in rows) {
                val tag = r.config[SemanticsProperties.TestTag]
                val says = r.config.getOrNull(SettingsRowValue).orEmpty()
                val title = descendants(r).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
                // A switch or a link may go without a value; a row of show-chips with nothing ticked says nothing (the kit's).
                val showChips = tag == SettingsTags.ROW_CHOICE && descendants(r).any { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Checkbox }
                if (tag in setOf(SettingsTags.ROW_PICK, SettingsTags.ROW_SLIDER, SettingsTags.ROW_CHOICE) && !showChips) assertTrue("$name › $title says its value", says.isNotBlank())
            }
        }
        // The preview is pinned, and the page has its jump chips.
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.PREVIEW)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Every line of the page's search names one of its groups.
        assertTrue(SettingsPageId.LYRICS_SING.index.all { it.group in groupTitles })
    }

    // ------------------------------------------------------------------ Nothing missing

    private class Case(val key: String, val read: (AppSettings) -> Any?, val act: () -> Unit)

    /** Every stored key the ledger gives the page, with what its row does. In the page's order. */
    private fun cases(): List<Case> {
        val language = java.util.Locale.getDefault().displayLanguage
        return listOf(
            // Style
            Case("lyrics_style", { it.lyricsLook.style }) { pick(tr(R.string.start_from), LyricsStyle.KARAOKE.label) },
            Case("lyrics_motion", { it.lyricsLook.motion }) { pick(tr(R.string.animation), tr(R.string.typewriter)) },
            Case("lyrics_word_by_word", { it.lyricsLook.wordByWord }) { toggle(tr(R.string.word_by_word_on_every_line)) },
            Case("lyrics_background", { it.lyricsLook.background }) { pick(tr(R.string.background), LyricsBackground.DARK.label) },
            Case("lyrics_card", { it.lyricsLook.card }) { pick(tr(R.string.background), tr(R.string.bold_card)) },
            Case("lyrics_bg_color2", { it.lyricsLook.bgColor2 }) {
                pick(tr(R.string.background), LyricsBackground.COLOR.label)
                until("Your colors' row") { has(tr(R.string.your_colors)) }
                pick(tr(R.string.your_colors), tr(R.string.bottom_color))
                useColor("#336699")
            },
            Case("lyrics_bg_color", { it.lyricsLook.bgColor }) { pick(tr(R.string.your_colors), tr(R.string.top_color)); useColor("#224466") },
            Case("lyrics_strip_align", { it.lyrics.stripAlign }) { pick(tr(R.string.the_line_on_the_player), tr(R.string.centered)) },
            Case("lyrics_strip_two_lines", { it.lyrics.stripTwoLines }) { pick(tr(R.string.the_line_on_the_player), tr(R.string.ls_two_lines, tr(R.string.centered))) },
            Case("lyrics_gap_mark", { it.lyricsLook.gapMark }) { pick(tr(R.string.instrumental_breaks), tr(R.string.music_note)) },
            // Text
            Case("lyrics_font", { it.lyrics.font }) { pick(tr(R.string.lyrics_font), com.vinz.music.data.prefs.FontChoice.SYSTEM.label) },
            Case("lyrics_scale", { it.lyrics.scale }) { slide(tr(R.string.lyrics_size), 1.2f) },
            Case("lyrics_align", { it.lyrics.align }) { chip(tr(R.string.alignment), tr(R.string.center)) },
            // Written with the weight (Bold card's "at least Bold" ends once a weight is picked).
            Case("lyrics_bold_lines", { it.lyricsLook.boldLines }) { chip(tr(R.string.weight), tr(R.string.bold)) },
            Case("lyrics_weight", { it.lyricsLook.weight }) { chip(tr(R.string.weight), tr(R.string.thin)) },
            Case("lyrics_spacing", { it.lyricsLook.spacing }) { slide(tr(R.string.line_spacing), 1.5f) },
            Case("lyrics_uppercase", { it.lyricsLook.uppercase }) { toggle(tr(R.string.capitals)) },
            Case("lyrics_position", { it.lyricsLook.position }) { pick(tr(R.string.where_the_sung_line_sits), tr(R.string.in_the_middle)) },
            // Color and effects
            Case("lyrics_sung_color", { it.lyricsLook.sungColor }) { chip(tr(R.string.the_line_being_sung), tr(R.string.accent)) },
            Case("lyrics_sung_custom", { it.lyricsLook.sungCustom }) { chip(tr(R.string.the_line_being_sung), tr(R.string.your_color)); useColor("#44AA66") },
            Case("lyrics_other_color", { it.lyricsLook.otherColor }) { chip(tr(R.string.the_other_lines), tr(R.string.accent)) },
            Case("lyrics_other_custom", { it.lyricsLook.otherCustom }) { chip(tr(R.string.the_other_lines), tr(R.string.your_color)); useColor("#AA4466") },
            Case("lyrics_others_alpha", { it.lyricsLook.othersAlpha }) { slide(tr(R.string.other_lines_brightness), 0.9f) },
            Case("lyrics_blur_amount", { it.lyricsLook.blurAmount }) { slide(tr(R.string.focus_blur), 0f) },
            // The older on/off key is written by the same slider.
            Case("lyrics_blur", { it.lyrics.blur }) { slide(tr(R.string.focus_blur), if (stored.lyrics.blur) 0f else 1.5f) },
            Case("lyrics_ahead", { it.lyricsLook.ahead }) { chip(tr(R.string.lines_to_come), tr(R.string.three)) },
            Case("lyrics_halo", { it.lyricsLook.halo }) { chip(tr(R.string.effects), tr(R.string.glow)) },
            Case("lyrics_shadow", { it.lyricsLook.shadow }) { chip(tr(R.string.effects), tr(R.string.shadow)) },
            Case("lyrics_progress_line", { it.lyrics.progressLine }) { chip(tr(R.string.effects), tr(R.string.progress_line)) },
            Case("lyrics_backing_size", { it.lyricsLook.backingSize }) { slide(tr(R.string.background_vocals), 0.8f) },
            // Language and translations
            Case("lyrics_translate", { it.lyrics.translate }) { pick(tr(R.string.translate_into), if (stored.lyrics.translate) tr(R.string.off) else tr(R.string.german)) },
            Case("lyrics_translate_to", { it.lyrics.translateTo }) { pick(tr(R.string.translate_into), tr(R.string.korean)) },
            Case("romanize_lyrics", { it.romanize }) { pick(tr(R.string.romanize_lyrics), if (stored.romanize == RomanizeMode.INSTEAD) tr(R.string.under_each_line) else tr(R.string.instead)) },
            Case("romanize_all", { it.romanizeAll }) {
                val mode = if (stored.romanize == RomanizeMode.OFF) RomanizeMode.UNDER else stored.romanize
                // The same mode with the other "my own language too".
                val with = mode.label + " · " + tr(R.string.lyrics_too, language)
                if (stored.romanizeAll) {
                    row(tr(R.string.romanize_lyrics)).performSemanticsAction(SemanticsActions.OnClick)
                    until("Romanize's sheet") { sheetOpen() }
                    val plain = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(mode.label)).fetchSemanticsNodes().size
                    assertEquals("one option for the mode alone", 1, plain)
                    compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(mode.label)).performClick()
                    until("the sheet to close") { !sheetOpen() }
                } else pick(tr(R.string.romanize_lyrics), with)
            },
            Case("lyrics_extra_place", { it.lyricsLook.extraPlace }) { chip(tr(R.string.translation_place), tr(R.string.over_the_line)) },
            Case("lyrics_extra_size", { it.lyricsLook.extraSize }) { slide(tr(R.string.translation_size), 1.3f) },
            Case("lyrics_extra_color", { it.lyricsLook.extraColor }) { chip(tr(R.string.translation_color), tr(R.string.accent)) },
            Case("lyrics_extra_italic", { it.lyricsLook.extraItalic }) { toggle(tr(R.string.translation_in_italics)) },
            // Sources and timing
            Case("prefer_synced", { it.preferSynced }) { toggle(tr(R.string.prefer_karaoke_synced_lyrics)) },
            Case("skip_censored", { it.skipCensored }) { toggle(tr(R.string.uncensor_lyrics)) },
            Case("lyrics_order", { it.lyrics.order }) {
                // 1.8.4 §2.8: the row's arrows went; its grip's TalkBack move does it.
                compose.reorderAction(tr(R.string.drag, stored.lyrics.order.first().label), tr(R.string.move_down))
            },
            Case("lyrics_off", { it.lyrics.off }) { sourceSwitch(stored.lyrics.order.first { it != LyricsSource.EMBEDDED }) },
            Case("embedded_lyrics", { it.behavior.embeddedLyrics }) { sourceSwitch(LyricsSource.EMBEDDED) },
            Case("lyrics_source_button", { it.lyrics.sourceButton }) { toggle(tr(R.string.lyrics_source_button)) },
            Case("lyrics_offset_ms", { it.behavior.lyricsOffsetMs }) { slide(tr(R.string.ls_offset), 120f) },
            // Sing and music files (the two Sing rows show where Sing's engine runs; the test phone's processor has none)
            Case("lyrics_file_words", { it.lyrics.fileWords }) { chip(tr(R.string.ls_when_saving), tr(R.string.ls_word_timings)) },
            Case("lyrics_file_translation", { it.lyrics.fileTranslation }) { chip(tr(R.string.ls_when_saving), tr(R.string.ls_translation)) },
        ) + if (com.vinz.music.data.ModelStore.Model.singRuntime() == null) emptyList() else listOf(
            Case("sing_always_ready", { it.singAlwaysReady }) { toggle(tr(R.string.get_songs_ready_while_sing_is)) },
            Case("sing_cache_mb", { it.singCacheMb }) { slide(tr(R.string.room_for_sung_songs), 2048f) },
        )
    }

    /** The switch of [src]'s row in the source list. */
    private fun sourceSwitch(src: LyricsSource) {
        compose.onNode(hasTestTag("lyricsSing:source:${src.name}"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test fun everyStoredSettingOfThePageHasARowThatChangesIt() {
        show()
        val missing = ArrayList<String>()
        for (case in cases()) {
            val before = case.read(stored)
            try {
                case.act()
                compose.waitUntil(8_000) { case.read(stored) != before }
                compose.waitForIdle()
            } catch (e: Throwable) {
                missing += "${case.key} (${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()})"
            }
        }
        assertTrue("Stored settings of Lyrics & Sing with no row that changes them: $missing", missing.isEmpty())
    }

    // ------------------------------------------------------------------ §2.6, §2.7 and the new row

    @Test fun theSourceButtonStaysAndMusicFilesAreReadFromTheSourceList() {
        show()
        // Its only control anywhere: kept, a switch, its explanation behind the ⓘ.
        val button = row(tr(R.string.lyrics_source_button)).fetchSemanticsNode()
        assertEquals(SettingsTags.ROW_SWITCH, button.config[SemanticsProperties.TestTag])
        // A look setting (a Look carries it): the page's Reset puts it back by its key too.
        assertTrue("lyrics_source_button" in com.vinz.music.ui.screens.settings.lyricsSingLookKeys)
        val words = ArrayList<String>().also { all -> descendants(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()).forEach { n -> n.config.getOrNull(SemanticsProperties.Text)?.forEach { all += it.text } } }
        assertFalse("the button's explanation is behind its ⓘ", tr(R.string.lyrics_source_button_info) in words)
        assertFalse("Sing's note is behind an ⓘ", tr(R.string.sing_doesn_t_run_on_this) in words)
        // "Read lyrics from your music files" has no row of its own…
        assertFalse(tr(R.string.read_lyrics_from_your_music_files) in words)
        // …because the source list on this page has its switch.
        assertTrue(LyricsSource.EMBEDDED in stored.lyrics.order)
        assertTrue(LyricsSource.EMBEDDED.label in words)
        val before = stored.behavior.embeddedLyrics
        sourceSwitch(LyricsSource.EMBEDDED)
        until("the files' switch") { stored.behavior.embeddedLyrics != before }
        // The size is the lyrics' by name (the app's is "App text size", on Text & icons).
        assertTrue(has(tr(R.string.lyrics_size)))
        assertFalse(has(tr(R.string.text_size)))
        // The lyrics font is here now.
        assertEquals(SettingsTags.ROW_PICK, row(tr(R.string.lyrics_font)).fetchSemanticsNode().config[SemanticsProperties.TestTag])
        assertEquals(tr(R.string.app_font), valueOf(tr(R.string.lyrics_font)))
    }

    @Test fun lyricsEarlierOrLaterIsASliderOfTenMsSteps() {
        show()
        val title = tr(R.string.ls_offset)
        assertEquals(SettingsTags.ROW_SLIDER, row(title).fetchSemanticsNode().config[SemanticsProperties.TestTag])
        assertEquals(tr(R.string.ls_offset_none), valueOf(title))
        val range = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(rowOf(title))).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(-500f..500f, range.range)
        assertEquals("10 ms a step", 99, range.steps)
        slide(title, 120f)
        until("the offset") { stored.behavior.lyricsOffsetMs == 120 }
        assertEquals(tr(R.string.ls_offset_earlier, 120), valueOf(title))
        slide(title, -80f)
        until("the offset") { stored.behavior.lyricsOffsetMs == -80 }
        assertEquals(tr(R.string.ls_offset_later, 80), valueOf(title))
        // Reset this page puts it back, and says so first.
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        compose.waitForIdle()
        val lines = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }
        assertTrue("$lines", lines.any { title in it && tr(R.string.ls_offset_none) in it })
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        until("the reset") { stored.behavior.lyricsOffsetMs == 0 }
    }

    // ------------------------------------------------------------------ Drawn

    @Config(sdk = [34], qualifiers = "w412dp-h9000dp-xhdpi", application = android.app.Application::class)
    @Test fun atLargeTextNothingIsCutOff() {
        show(fontScale = 1.5f)
        val cut = ArrayList<String>()
        for (r in compose.onAllNodes(isRow, useUnmergedTree = true).fetchSemanticsNodes()) {
            for (n in descendants(r)) {
                val text = n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: continue
                val out = ArrayList<TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
                val l = out.firstOrNull() ?: continue
                // (Not hasVisualOverflow: a text that wraps is measured at the row's width and then sized to its words, which
                // counts as "wider than its box" without a letter being cut.)
                if (l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1)) cut += text
            }
        }
        assertTrue("Cut off at 150% text: $cut", cut.isEmpty())
        assertEquals(6, compose.onAllNodes(hasTestTag(SettingsTags.GROUP), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun save(name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/lyrics-sing").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }

    private fun rendered(name: String, frosted: Boolean, before: () -> Unit) {
        before()
        show(frosted = frosted)
        val shot = save(name)
        assertEquals("360 dp wide at xxhdpi", 1080, shot.width)
        assertEquals(6, compose.onAllNodes(hasTestTag(SettingsTags.GROUP), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun theme(mode: ThemeMode) {
        container.settings.setThemeMode(mode)
        until("the theme") { stored.themeMode == mode }
    }

    @Config(sdk = [34], qualifiers = "w360dp-h4600dp-xxhdpi", application = android.app.Application::class)
    @Test fun drawnLight() = rendered("light", frosted = true) { theme(ThemeMode.LIGHT) }

    @Config(sdk = [34], qualifiers = "w360dp-h4600dp-xxhdpi", application = android.app.Application::class)
    @Test fun drawnDark() = rendered("dark", frosted = true) { theme(ThemeMode.DARK) }

    @Config(sdk = [34], qualifiers = "w360dp-h4600dp-xxhdpi", application = android.app.Application::class)
    @Test fun drawnInPerformanceMode() = rendered("performance", frosted = false) {
        theme(ThemeMode.DARK)
        container.settings.setPerformanceMode(true)
        until("Performance mode") { stored.performanceMode }
    }

    /** A phone's screen, scrolled: the preview is a strip under the bar. */
    @Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi", application = android.app.Application::class)
    @Test fun drawnScrolledWithThePreviewAsAStrip() {
        theme(ThemeMode.DARK)
        show(frosted = true)
        save("dark-top")
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performScrollToIndex(5)
        compose.waitForIdle()
        save("dark-scrolled")
        val preview = compose.onNodeWithTag(SettingsTags.PREVIEW).fetchSemanticsNode()
        assertEquals("the strip", 72.dp, with(compose.density) { preview.size.height.toDp() })
    }

    @Test fun theRootLineSaysTheStyle() {
        val line = SettingsPageId.LYRICS_SING.let { com.vinz.music.ui.screens.settings.lyricsSingValue(AppSettings(), true) }
        assertTrue(line, LyricsStyle.VINZ.label in line)
        assertNotEquals("", line)
    }
}
