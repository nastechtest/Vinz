package com.vinz.music

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import com.prism.music.data.db.PlayEventEntity
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.prism.music.data.db.toEntity
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.LibraryScreen
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Library → History (#469): a search through the whole of this phone's history (not only the 300 plays shown first),
 * and taking one play out of it, with Undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = Application::class)
class HistoryTabTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made
    @org.junit.After fun close() = made.closeAfterTest()

    private val now = System.currentTimeMillis()
    private val filler = Song("fill", "Filler tune", listOf(ArtistRef("Someone")), album = AlbumRef("Padding"), durationSec = 200)
    private val halo = Song("halo", "Halo", listOf(ArtistRef("Beyoncé")), album = AlbumRef("I Am... Sasha Fierce"), durationSec = 260)

    /** 340 plays of a filler song, and before them (beyond the first 300 shown) [haloPlays] of Halo. */
    private fun seed(haloPlays: Int = 3) = runBlocking {
        c.db.songs().upsertAll(listOf(filler.toEntity(), halo.toEntity()))
        repeat(haloPlays) { c.db.plays().insert(PlayEventEntity(songId = halo.id, timestamp = now - 3_600_000L * (400 + it * 30), playedMs = 200_000, source = "LIBRARY")) }
        repeat(340) { c.db.plays().insert(PlayEventEntity(songId = filler.id, timestamp = now - 60_000L * (it + 1), playedMs = 120_000)) }
    }

    private fun show() {
        val s = AppSettings().let { it.copy(library = it.library.copy(startTab = "HISTORY")) }
        compose.setContent {
            val nav = Navigator(androidx.navigation.compose.rememberNavController(), openPlayer = {})
            VinzTheme(s, artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c, LocalNavigator provides nav) { LibraryScreen(bottomPadding = 0.dp) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Filler tune").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun typingFiltersTheWholeHistory() {
        seed()
        show()
        // Not among the 300 plays shown first.
        compose.waitForIdle()
        assert(compose.onAllNodesWithText("Halo").fetchSemanticsNodes().isEmpty())
        // Without capitals or the accent, by the artist.
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("BEYONCE")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Halo").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Filler tune").fetchSemanticsNodes().isEmpty() }
    }

    /** Halo and Jóga by turns, Halo last: no two plays in a row fold into one. */
    private fun seedTurns() = runBlocking {
        val joga = Song("joga", "Jóga", listOf(ArtistRef("Björk")), durationSec = 300)
        c.db.songs().upsertAll(listOf(halo.toEntity(), joga.toEntity()))
        for (i in 9 downTo 0) c.db.plays().insert(PlayEventEntity(songId = if (i % 2 == 0) halo.id else joga.id, timestamp = now - 60_000L * (i + 1), playedMs = 120_000))
    }

    private fun events() = runBlocking { c.db.plays().events(0, Long.MAX_VALUE) }

    private fun showTurns() {
        val s = AppSettings().let { it.copy(library = it.library.copy(startTab = "HISTORY")) }
        compose.setContent {
            val nav = Navigator(androidx.navigation.compose.rememberNavController(), openPlayer = {})
            VinzTheme(s, artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides c, LocalNavigator provides nav) { LibraryScreen(bottomPadding = 0.dp) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Halo").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theRowsMenuOffersRemoveFromHistory() {
        seedTurns()
        showTurns()
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove from history").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun removingAPlayShowsUndoAndUndoPutsItBack() {
        seedTurns()
        showTurns()
        val before = events()
        val latest = before.maxBy { it.timestamp }
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove from history").fetchSemanticsNodes().isNotEmpty() }
        // Low in the sheet, which is a window of its own (a touch there doesn't reach it under Robolectric): its click action.
        compose.onAllNodesWithText("Remove from history")[0].performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        // That play only: the song's other plays stay.
        compose.waitUntil(5_000) { events().size == before.size - 1 }
        assertEquals(before - latest, events())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Undo")[0].performClick()
        compose.waitUntil(5_000) { events().size == before.size }
        assertEquals("the same rows back", before, events())
    }

    @Test fun nothingFoundSaysWhatWasTyped() {
        seed()
        show()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("zzqx")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("zzqx", substring = true)).fetchSemanticsNodes().size >= 2 }
    }
}
