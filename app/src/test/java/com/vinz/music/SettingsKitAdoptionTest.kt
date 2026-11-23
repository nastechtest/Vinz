package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.HistoryPause
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.NavLabels
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.QueueEnd
import com.vinz.music.ui.screens.settings.ChoiceRow
import com.vinz.music.ui.screens.settings.GesturesWork
import com.vinz.music.ui.screens.settings.SettingsGroup
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, the pages on the kit's newer pieces (helper "kit-adopt"): rows that do something here and now are the kit's
 * action rows (no chevron) while rows that open a screen keep theirs; a pause of history picked again starts again; the
 * pages' sheets of rows are the kit's [com.vinz.music.ui.screens.settings.RowsSheet] (no private sheet that isn't
 * listed); and the choices that are pick lists because their words don't fit one capsule still don't.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class SettingsKitAdoptionTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private val stored: AppSettings get() = container.settings.stored.value

    @Before fun emptyStore() = runBlocking { container.settings.editNow { it.clear() } }

    @After fun close() {
        runBlocking { runCatching { container.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    private fun show(page: SettingsPageId, over: (AppSettings) -> AppSettings = { it }) {
        compose.setContent {
            val controller = rememberNavController()
            val nav = Navigator(controller, openPlayer = {})
            val stored by container.settings.stored.collectAsState()
            val s = over(stored)
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s, LocalFrosted provides false) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavHost(controller, "page") { composable("page") { SettingsPageContent(page, 0.dp, onSearch = {}) } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rowIs(title: String): SemanticsMatcher =
        SemanticsMatcher("one of the kit's rows") { it.config.getOrNull(SemanticsProperties.TestTag) in SettingsTags.rowTypes } and
            (hasText(title) or hasAnyDescendant(hasText(title)))

    /**
     * What is drawn where a Link row's chevron goes (18 dp before the row's 16 dp end gutter, around the row's middle),
     * against the row's own ground just beside the gutter's edge: 0 when there is no chevron.
     */
    private fun chevronPixels(title: String): Int {
        val shot = compose.onNode(rowIs(title)).captureToImage().asAndroidBitmap()
        val (wide, gutter, half) = with(compose.density) { Triple(34.dp.roundToPx(), 16.dp.roundToPx(), 10.dp.roundToPx()) }
        var n = 0
        for (y in shot.height / 2 - half until shot.height / 2 + half) {
            val ground = shot.getPixel(shot.width - gutter / 2, y)
            for (x in shot.width - wide until shot.width - gutter) if (shot.getPixel(x, y) != ground) n++
        }
        return n
    }

    private fun assertActions(actions: List<String>, links: List<String>) {
        for (a in actions) {
            assertEquals("$a is a row of the kit's", SettingsTags.ROW_LINK, compose.onNode(rowIs(a)).fetchSemanticsNode().config[SemanticsProperties.TestTag])
            assertEquals("$a does something here and now: no chevron", 0, chevronPixels(a))
        }
        for (l in links) assertTrue("$l opens a screen: its chevron stays", chevronPixels(l) > 0)
    }

    @Test fun recommendationsActionsHaveNoChevron() {
        show(SettingsPageId.RECOMMENDATIONS_HISTORY)
        assertActions(
            actions = listOf(tr(R.string.make_my_mixes_again), tr(R.string.forget_skipped_suggestions)),
            links = listOf(tr(R.string.your_artists), tr(R.string.exclusions)),
        )
    }

    /** 1.8.7.6: Bring your history moved to Accounts & sharing; it still opens a screen, so its chevron stays. */
    @Test fun accountsHistoryLinkKeepsItsChevron() {
        show(SettingsPageId.ACCOUNTS_SHARING)
        assertActions(actions = emptyList(), links = listOf(tr(R.string.imp_title)))
    }

    @Test fun downloadsActionsHaveNoChevron() {
        show(SettingsPageId.DOWNLOADS_DATA)
        assertActions(actions = listOf(tr(R.string.clear_smart_downloads)), links = listOf(tr(R.string.manage_downloads)))
    }

    /**
     * Pause history picked again (its sheet's current line) starts the pause again from now: 20 minutes left of a pause
     * reads as "1 hour", and a tap on "1 hour" is an hour from now. (Before, a tap on the current line only closed the
     * sheet.)
     */
    @Test fun aPausePickedAgainStartsAgainFromNow() {
        val left = System.currentTimeMillis() + 20 * 60_000L
        container.settings.setHistoryPausedUntil(left)
        compose.waitUntil(5_000) { stored.behavior.historyPausedUntil == left }
        assertEquals(HistoryPause.Choice.HOUR, HistoryPause.choiceOf(left))
        show(SettingsPageId.RECOMMENDATIONS_HISTORY)
        compose.onNode(rowIs(tr(R.string.hist_pause))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val hour = compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(HistoryPause.Choice.HOUR.label))
        assertEquals("the current pick", true, hour.fetchSemanticsNode().config[SemanticsProperties.Selected])
        val tapped = System.currentTimeMillis()
        hour.performClick()
        compose.waitUntil(5_000) { stored.behavior.historyPausedUntil >= tapped + 3_600_000L }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * The pages' sheets of rows are the kit's RowsSheet. A page that still opens a sheet of its own is listed here with
     * why, so that a new private sheet is a decision, not an accident.
     */
    @Test fun noPrivateSheetOfRowsIsLeft() {
        val own = mapOf(
            "DownloadsDataPage.kt" to "Keep playlists downloaded: a searchable list of playlists with their checks, not rows",
            "TextIconsParts.kt" to "the font's sheet: the font cards and three rows under them, and it closes before opening a file or the installed fonts",
            "ThemeColorsPage.kt" to "Your own colors: the light's colour stops to edit and reorder, not rows",
            "PlayerParts.kt" to "UntitledSheet: RowsSheet without its title, for the buttons editor that heads itself",
        )
        val dir = File("src/main/java/com/vinz/music/ui/screens/settings")
        val kit = setOf("PickSheet.kt", "SettingsRows.kt", "SettingsPageScaffold.kt", "SettingsGroup.kt")
        val withSheets = dir.listFiles()!!.filter { it.name !in kit && "VinzSheet(" in it.readText() }.map { it.name }.toSet()
        assertEquals("pages that draw a sheet of their own (move it to RowsSheet, or list it here with why)", own.keys, withSheets)
        // And none keeps a copy of the kit's sheet or of its "None" for an empty Show row.
        for (f in dir.listFiles()!! + File("src/main/java/com/vinz/music/ui/screens/CatalogueScreen.kt")) {
            val text = f.readText()
            for (gone in listOf("PLAYER_SHEET", "fun PlayerSheet(", "fun ConnectSheet(", "noneWhenEmpty(", "noneValue(")) assertTrue("${f.name}: $gone", gone !in text)
        }
    }

    // ---------------------------------------------------------------- The sheets of rows, drawn (to look at)

    /** Opens [row]'s sheet on [page] (dark, a 360 dp phone) and saves it to `build/settings-pages/kit-adopt/<name>.png`. */
    private fun drawSheet(page: SettingsPageId, row: String, name: String) {
        show(page) { it.copy(themeMode = ThemeMode.DARK) }
        compose.onNode(rowIs(row)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        // The sheet's title is its heading.
        val title = compose.onNode(hasText(row) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasAnyAncestor(hasTestTag(SettingsTags.SHEET)))
        title.assertExists()
        val shot = compose.onNodeWithTag(SettingsTags.SHEET).captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/kit-adopt").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(name, shot.height > 100)
    }

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnStreamingQualitySheet() {
        // The row is there without lossless (with it, lossless picks the quality).
        container.settings.setLossless(false)
        compose.waitUntil(5_000) { !stored.lossless }
        drawSheet(SettingsPageId.SOUND_QUALITY, tr(R.string.sound_streaming_quality), "sound-streaming-quality")
    }

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnLibraryViewSheet() =
        drawSheet(SettingsPageId.HOME_LIBRARY, tr(R.string.hl_view), "home-library-view")

    // The page's tall window: the row is far down the lazy list.
    @Test fun drawnCountHistorySheet() =
        drawSheet(SettingsPageId.RECOMMENDATIONS_HISTORY, tr(R.string.imp_count_history_from), "recommendations-count-history")

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnSpotifySheet() {
        // Its row is "Spotify"; the sheet is "How to connect".
        show(SettingsPageId.ACCOUNTS_SHARING) { it.copy(themeMode = ThemeMode.DARK) }
        compose.onNode(rowIs(tr(R.string.spotify))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        val shot = compose.onNodeWithTag(SettingsTags.SHEET).captureToImage().asAndroidBitmap()
        File(File("build/settings-pages/kit-adopt").apply { mkdirs() }, "accounts-spotify-how-to-connect.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // The page's tall window: the row is far down the lazy list.
    @Test fun drawnLockScreenSheet() {
        container.settings.setLockScreen(true)
        compose.waitUntil(5_000) { stored.behavior.lockScreen }
        drawSheet(SettingsPageId.PLAYER, tr(R.string.settings_player_lock_options), "player-lock-screen-options")
    }

    // The page's tall window: the row is far down the lazy list.
    @Test fun drawnImmersiveSheet() {
        container.settings.setImmersivePlayer(true)
        compose.waitUntil(5_000) { stored.immersivePlayer }
        drawSheet(SettingsPageId.PLAYER, tr(R.string.settings_player_immersive_options), "player-immersive-options")
    }

    /**
     * The pick lists that stand in for chips because their words don't fit one capsule on a 360 dp phone: at normal
     * text they still don't (the kit's ChoiceRow would fall back to wrapping chips), so they stay pick lists.
     */
    @Test fun theChoicesLeftAsPickListsStillDontFitOneCapsule() {
        val choices: List<Pair<String, List<String>>> = listOf(
            tr(R.string.labels) to NavLabels.entries.map { it.label },
            tr(R.string.gb_gestures_work) to listOf(GesturesWork.COVER, GesturesWork.ANYWHERE, GesturesWork.NOT_LYRICS).map { it.label },
            tr(R.string.hist_pause) to HistoryPause.Choice.entries.map { it.label },
            tr(R.string.when_the_queue_ends) to QueueEnd.entries.map { it.label },
        )
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SettingsGroup(null) { choices.forEach { (title, words) -> ChoiceRow(title, words, words.first(), { it }) {} } }
                    }
                }
            }
        }
        compose.waitForIdle()
        for ((title, words) in choices) {
            val inRow = hasAnyAncestor(rowIs(title)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
            val bounds = compose.onAllNodes(inRow).fetchSemanticsNodes().map { it.boundsInRoot }
            assertEquals(title, words.size, bounds.size)
            // One capsule: every option as wide as the others, side by side on one line.
            val capsule = bounds.all { it.top == bounds[0].top } && bounds.all { kotlin.math.abs(it.width - bounds[0].width) < 1f }
            assertTrue("$title now fits one capsule: back to a Choice row", !capsule)
        }
    }
}
