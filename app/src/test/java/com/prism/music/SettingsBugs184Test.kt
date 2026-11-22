package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.vinz.music.data.HistoryPause
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.MixPrefs
import com.vinz.music.data.prefs.BleedFit
import com.vinz.music.data.prefs.LyricsExtraPlace
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.navigation.compose.rememberNavController
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.screens.lookStepLine
import com.vinz.music.ui.screens.settings.LsLanguageRows
import com.vinz.music.ui.screens.settings.PlayerCoverRows
import com.vinz.music.ui.screens.settings.PlayerProgressRows
import com.vinz.music.ui.screens.settings.SettingsGroup
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4's settings bug round (plan §1), the rows on the pages themselves; the two about Settings' search are in
 * [SettingsIndexTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h3000dp-xxhdpi", application = android.app.Application::class)
class SettingsBugs184Test {
    @get:Rule val compose = createComposeRule()

    /** For the cases that draw a page's rows: they ask the container for the settings' setters. */
    private val made = lazy { AppContainer(org.robolectric.RuntimeEnvironment.getApplication()) }

    @org.junit.After fun close() = made.closeAfterTest()

    private fun page(name: String) = File("src/main/java/com/vinz/music/ui/screens/$name").readText()

    /** SettingsScreen.kt and every page's file under `ui/screens/settings/` (1.8.4: one file a page). */
    private fun settingsPages() = listOf(page("SettingsScreen.kt")) +
        File("src/main/java/com/vinz/music/ui/screens/settings").listFiles().orEmpty().filter { it.extension == "kt" }.sortedBy { it.name }.map { it.readText() }

    /**
     * The one settings file that draws [marker]. A page's file also holds its search lines (`SettingEntry(...)`, 1.8.4),
     * which name a row without drawing it: those lines are left out.
     */
    private fun pageWith(marker: String) = settingsPages().map { src -> src.lines().filterNot { "SettingEntry(" in it }.joinToString("\n") }.single { marker in it }

    private fun show(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
        compose.setContent { VinzTheme(settings, artworkColor = null) { Column(Modifier.width(412.dp)) { content() } } }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** The page's rows as they are on their page: in a group's card, with a container of their own and [settings]. */
    private fun showRows(settings: () -> AppSettings, rows: @Composable () -> Unit) {
        compose.setContent {
            val s = settings()
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides made.value, LocalNavigator provides nav) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s) { Column(Modifier.width(412.dp)) { SettingsGroup(null) { rows() } } }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The kit row titled [title]: how many there are, and of which type. */
    private fun rowsTitled(title: String) = compose.onAllNodes(hasAnyOfTags(SettingsTags.rowTypes) and hasAnyDescendant(hasText(title)), useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasAnyOfTags(tags: Set<String>) = SemanticsMatcher("one of $tags") { it.config.getOrNull(SemanticsProperties.TestTag) in tags }

    /** Looks → History: a step's line was English in every language ("… · tap to undo" written in the code). */
    @Test fun aLookStepsLineComesFromTheStrings() {
        // The page that draws the steps (LooksPage.kt) and the file the line's words come from.
        for (file in listOf("LookSettings.kt", "settings/LooksPage.kt")) {
            assertFalse("$file still writes the line itself", Regex("\"[^\"\\n]*tap to [^\"\\n]*\"").containsMatchIn(page(file)))
        }
        assertTrue("the Looks page draws a step's line through it", "value = lookStepLine(ago, newest = i == 0)" in page("settings/LooksPage.kt"))
        assertEquals(tr(R.string.look_step_tap_to_undo, "5 minutes ago"), lookStepLine("5 minutes ago", newest = true))
        assertEquals(tr(R.string.look_step_tap_to_go_back, "5 minutes ago"), lookStepLine("5 minutes ago", newest = false))
        assertTrue(lookStepLine("5 minutes ago", newest = true).startsWith("5 minutes ago"))
        assertTrue(lookStepLine("5 minutes ago", true) != lookStepLine("5 minutes ago", false))
    }

    /** Player → Progress bar and times: "Crossfade on the progress bar" only does anything with Crossfade on. */
    @Test fun theCrossfadeOnTheBarChoiceShowsOnlyWithCrossfadeOn() {
        val off = AppSettings(mix = MixPrefs(on = false))
        val on = AppSettings(mix = MixPrefs(on = true))
        var s by androidx.compose.runtime.mutableStateOf(off)
        showRows({ s }) { PlayerProgressRows() }
        val title = tr(R.string.crossfade_on_the_progress_bar)
        assertEquals("with Crossfade off", 0, shown(title))
        // The group's other rows are there either way.
        assertEquals(1, shown(tr(R.string.progress_bar_color)))
        s = on
        compose.waitForIdle()
        assertEquals("with Crossfade on", 1, shown(title))
        assertEquals("a switch", listOf(SettingsTags.ROW_SWITCH), rowsTitled(title).map { it.config[SemanticsProperties.TestTag] })
        // And Settings' search says what to turn on first.
        val line = SettingsIndex.entries.single { it.title == title }
        assertTrue(line.needs != null && !line.needs!!.on(off) && line.needs!!.on(on))
    }

    /** Player → Cover: the "Fill the screen / Whole cover" chips have a label (they are a row of their own, named). */
    @Test fun theCoverFitChipsAreLabelled() {
        showRows({ AppSettings(fullBleedArtwork = true) }) { PlayerCoverRows() }
        val title = tr(R.string.how_the_cover_fits)
        val row = rowsTitled(title)
        assertEquals("one row named $title", listOf(SettingsTags.ROW_CHOICE), row.map { it.config[SemanticsProperties.TestTag] })
        BleedFit.entries.forEach { fit ->
            assertEquals(fit.label, 1, compose.onAllNodes(hasText(fit.label) and hasAnyAncestor(SemanticsMatcher("the row") { it.id == row.single().id }), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        // And BleedFit's chips are drawn nowhere else on the settings pages, labelled or bare.
        assertEquals(1, settingsPages().sumOf { it.split("BleedFit.entries").size - 1 })
        assertTrue("ChoiceRow(tr(R.string.how_the_cover_fits), BleedFit.entries" in pageWith("BleedFit.entries"))
    }

    /** Lyrics & Sing → Language and translations: the "Under the line / Over the line" chips have a label. */
    @Test fun theTranslationPlaceChipsAreLabelled() {
        val asVinzComes = AppSettings()
        showRows({ asVinzComes }) { LsLanguageRows(asVinzComes) }
        val title = tr(R.string.translation_place)
        val row = rowsTitled(title)
        assertEquals("one row named $title", listOf(SettingsTags.ROW_CHOICE), row.map { it.config[SemanticsProperties.TestTag] })
        LyricsExtraPlace.entries.forEach { place ->
            assertEquals(place.label, 1, compose.onAllNodes(hasText(place.label) and hasAnyAncestor(SemanticsMatcher("the row") { it.id == row.single().id }), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        assertEquals("drawn once, by the labelled row", 1, settingsPages().sumOf { it.split("LyricsExtraPlace.entries").size - 1 })
    }
    /** The hub's line for Recommendations & Replay: nothing from another page (the content country is on Language now). */
    @Test fun recommendationsLineIsFromItsOwnPage() {
        val plain = SettingsSection.LIBRARY.summary(AppSettings())
        val german = SettingsSection.LIBRARY.summary(AppSettings(contentCountry = "DE"))
        assertFalse("the content country is on the Language page: $german", "Germany" in german || "DE" in german)
        assertEquals(plain, german)
        // What it does say is the page's own: history paused shows.
        val paused = AppSettings().let { it.copy(behavior = it.behavior.copy(historyPausedUntil = HistoryPause.FOREVER)) }
        assertFalse(tr(R.string.hist_paused_title) in plain)
        assertTrue(tr(R.string.hist_paused_title) in SettingsSection.LIBRARY.summary(paused))
    }

    /**
     * Downloads & data → Mobile data: "Load ahead on mobile data" (since 1.8.4 the Load ahead chip of "Use mobile data
     * for") sent people to Playback & sound in its ⓘ; Load ahead is on this page, in the same group.
     */
    @Test fun loadAheadOnMobileDatasInfoNamesTheRightPlace() {
        val downloads = pageWith("MobileUse.LOAD_AHEAD -> tr(R.string.")
        val name = Regex("""MobileUse\.LOAD_AHEAD -> tr\(R\.string\.(\w+)\)""").find(downloads.substringAfter("private fun usesInfo("))!!.groupValues[1]
        val info = tr(R.string::class.java.getField(name).getInt(null))
        assertTrue("the ⓘ's text was read ($name)", info.length > 20 && !info.startsWith("#"))
        assertFalse("it names Playback & sound: $info", tr(R.string.playback_sound) in info)
        // Load ahead's own row is in the Mobile data group, right above the row that holds the chip.
        val group = downloads.substringAfter("group(tr(R.string.mobile_data)) {").substringBefore("\n        }\n")
        assertTrue("the Mobile data group was read", group.length in 1..3000)
        val loadAhead = group.indexOf("tr(R.string.load_ahead), Preload.entries")
        val uses = group.indexOf("tr(R.string.dd_use_mobile_data_for)")
        assertTrue("Load ahead, then what may use mobile data: $loadAhead, $uses", loadAhead in 0 until uses)
        // The old switch's name still finds the row.
        assertTrue(SettingsIndex.search(tr(R.string.load_ahead_on_mobile_data)).any { it.page == com.vinz.music.ui.screens.settings.SettingsPageId.DOWNLOADS_DATA })
    }
}