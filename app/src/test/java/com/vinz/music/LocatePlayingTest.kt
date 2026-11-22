package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.components.CountingLazyListScope
import com.vinz.music.ui.components.LocatePlaying
import com.vinz.music.ui.components.LocatePlayingRow
import com.vinz.music.ui.components.LocateState
import com.vinz.music.ui.components.SongRowPlaces
import com.vinz.music.ui.components.locatePulse
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D54 (1.8.4), "Go to the playing song": in a list of 500 songs with the 380th playing, the action brings its row on
 * screen (above the mini player) and pulses it once; with Reduce motion it only goes there. The button above the mini
 * player shows only with its setting on, while that song is in the list and its row is out of sight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class LocatePlayingTest {
    @get:Rule val compose = createComposeRule()

    private val songs = (1..500).map { Song("s$it", "Song $it", listOf(ArtistRef("Someone"))) }
    private val playing = "s380"
    /** The room the mini player and the bar under it take at the bottom of a page. */
    private val mini = 140.dp
    private lateinit var locate: LocateState
    private lateinit var list: LazyListState

    private fun settings(button: Boolean, still: Boolean) = AppSettings().let { it.copy(reduceMotion = still, behavior = it.behavior.copy(locatePlaying = button)) }

    /** A page as a playlist's is built: a header and Play above the songs, the rows counted as the list is made. */
    private fun show(playingId: String?, button: Boolean, still: Boolean = false, shown: List<Song> = songs, playingVideoId: String? = null) {
        compose.setContent {
            VinzTheme(settings(button, still), artworkColor = null) {
                val state = remember { LocateState() }.also { locate = it }
                val lazy = rememberLazyListState().also { list = it }
                val places = remember { SongRowPlaces() }
                val at = LocatePlaying.indexIn(shown, playingId, playingVideoId)
                // As the pages do: the row found is the one gone to and pulsed (its own id, which a video row's isn't the song's).
                val rowId = LocatePlaying.rowId(shown, at, playingId)
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), lazy, contentPadding = PaddingValues(bottom = mini + 24.dp)) {
                        with(CountingLazyListScope(this)) {
                            item("header") { Box(Modifier.fillMaxWidth().height(300.dp)) }
                            item("play") { Box(Modifier.fillMaxWidth().height(76.dp)) }
                            places.start = added
                            itemsIndexed(shown, key = { _, s -> s.id }) { _, s ->
                                Box(Modifier.locatePulse(state, s.id).fillMaxWidth().height(64.dp).testTag(s.id))
                            }
                        }
                    }
                    LocatePlayingRow(state, lazy, inList = at >= 0, songId = rowId, bottomPadding = mini) { places.of(rowId, at) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun go() {
        val action = assertNotNull("the playing song is in the list, so there's somewhere to go", locate.go).let { locate.go!! }
        compose.runOnIdle { action() }
        compose.waitForIdle()
    }

    /** The row is whole on the page, above the mini player's room. */
    private fun assertInView(id: String) {
        val row = compose.onNodeWithTag(id).assertIsDisplayed().getUnclippedBoundsInRoot()
        val page = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("row $id is at ${row.top}..${row.bottom} of a ${page.height} page with $mini under the mini player", row.top >= 0.dp && row.bottom <= page.height - mini)
    }

    @Test fun theActionBringsRow380OnScreenAndPulsesItOnce() {
        show(playing, button = false)
        compose.onNodeWithTag(playing).assertDoesNotExist()
        go()
        assertInView(playing)
        assertEquals("it pulsed once", 1, locate.beat)
        assertNull("and the pulse is over", locate.pulsing)
        assertEquals("the header and Play are counted: song 380 is the list's item 381", 381, list.layoutInfo.visibleItemsInfo.first { it.key == playing }.index)
    }

    /**
     * Pages that share one state (Local files' sections in a pager): the page going out leaves after the page coming in
     * has offered its way there, and must not take that away (the top bar's button was gone for good).
     */
    @Test fun aPageLeavingAfterTheNextOneCameLeavesItsWayThere() {
        var pages by mutableStateOf(listOf("songs"))
        compose.setContent {
            VinzTheme(settings(button = false, still = false), artworkColor = null) {
                val state = remember { LocateState() }.also { locate = it }
                Box(Modifier.fillMaxSize()) {
                    for (p in pages) key(p) {
                        val lazy = rememberLazyListState()
                        LocatePlayingRow(state, lazy, inList = true, songId = playing, bottomPadding = mini) { 0 }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertNotNull(locate.go)
        pages = listOf("songs", "favourites"); compose.waitForIdle()
        pages = listOf("favourites"); compose.waitForIdle()
        assertNotNull("the section in view can still go to the playing song", locate.go)
        pages = emptyList(); compose.waitForIdle()
        assertNull("no section left, nowhere to go", locate.go)
    }

    @Test fun withReduceMotionItOnlyGoesThere() {
        show(playing, button = false, still = true)
        go()
        assertInView(playing)
        assertEquals("no pulse", 0, locate.beat)
    }

    @Test fun theButtonShowsWhileTheRowIsOutOfSightAndGoesThere() {
        show(playing, button = true)
        compose.onNodeWithTag("locatePlaying").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertInView(playing)
        // On screen now: nothing to find.
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        // Scrolled on past it, it's back (the song is above now).
        compose.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(450) } }
        compose.waitForIdle()
        compose.onNodeWithTag("locatePlaying").assertIsDisplayed()
    }

    @Test fun theButtonSitsAboveTheMiniPlayer() {
        show(playing, button = true)
        val button = compose.onNodeWithTag("locatePlaying").getUnclippedBoundsInRoot()
        val page = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("its foot at ${button.bottom} is above the mini player's room", button.bottom <= page.height - mini)
    }

    @Test fun noButtonWhenTheRowIsOnScreen() {
        show("s3", button = true)
        compose.onNodeWithTag("s3").assertIsDisplayed()
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        assertNotNull("the menu's entry is still there", locate.go)
    }

    @Test fun noButtonWithTheSettingOffButTheMenuEntryStays() {
        show(playing, button = false)
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        assertNotNull(locate.go)
    }

    @Test fun nothingWhenTheSongIsntInTheList() {
        show("another", button = true)
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        assertNull("no entry in the menu either", locate.go)
    }

    @Test fun nothingWhenNothingIsPlaying() {
        show(null, button = true)
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        assertNull(locate.go)
    }

    @Test fun aSearchThatLeavesTheSongOutTakesTheEntryAway() {
        // "Song 1…" as a search would leave them: 380 isn't one.
        show(playing, button = true, shown = songs.filter { it.title.startsWith("Song 1") })
        compose.onNodeWithTag("locatePlaying").assertDoesNotExist()
        assertNull(locate.go)
    }

    @Test fun itWorksOnAFilteredAndSortedList() {
        // Sorted the other way round and thinned to every other song: 380 is somewhere else, and still found.
        val shown = songs.filter { it.id.removePrefix("s").toInt() % 2 == 0 }.asReversed()
        show(playing, button = true, shown = shown)
        compose.onNodeWithTag("locatePlaying").assertIsDisplayed()
        go()
        assertInView(playing)
    }

    /**
     * A16, 1.8.4: a Vinz mix (Thanksgiving Dinner) lists "Give It To Me Baby" as its music video; Music videos → Songs in
     * radios plays the song instead, under the song's own id (the queue's counterparts: song id → video id). The list
     * didn't count it as playing: no "Go to the playing song", no button, no playing row.
     */
    @Test fun aVideoRowPlayedAsItsSongIsFoundGoneToAndPulsed() {
        val shown = songs.map { if (it.id == playing) it.copy(id = "v380") else it }
        assertEquals("the video's row", 379, LocatePlaying.indexIn(shown, playing, playingVideoId = "v380"))
        assertEquals("without the pairing it isn't found", -1, LocatePlaying.indexIn(shown, playing, playingVideoId = null))
        assertEquals("a row that is the song itself comes first", 2, LocatePlaying.indexIn(listOf(songs[0], shown[379], songs[379]), playing, "v380"))
        assertEquals("v380", LocatePlaying.rowId(shown, 379, playing))
        assertEquals("not in the list: the playing id", playing, LocatePlaying.rowId(shown, -1, playing))

        show(playing, button = true, shown = shown, playingVideoId = "v380")
        compose.onNodeWithTag("locatePlaying").assertIsDisplayed()
        go()
        assertInView("v380")
        assertEquals("it pulsed once", 1, locate.beat)
    }

    @Test fun whereARowIs() {
        // Not laid out: on the side its place is of the first row that is.
        assertEquals(-1, LocatePlaying.side(row = 5, first = 40, offset = null, size = 0, top = 0, bottom = 1000))
        assertEquals(1, LocatePlaying.side(row = 381, first = 40, offset = null, size = 0, top = 0, bottom = 1000))
        // Laid out: seen once its middle is between the page's bar and the mini player.
        assertEquals(0, LocatePlaying.side(row = 41, first = 40, offset = 300, size = 100, top = 0, bottom = 1000))
        assertEquals("mostly under the page's bar", -1, LocatePlaying.side(row = 40, first = 40, offset = 80, size = 100, top = 160, bottom = 1000))
        assertEquals("mostly behind the mini player", 1, LocatePlaying.side(row = 52, first = 40, offset = 960, size = 100, top = 0, bottom = 1000))
        assertTrue(LocatePlaying.offersButton(setting = true, inList = true, side = 1))
        assertTrue(LocatePlaying.offersButton(setting = true, inList = true, side = -1))
        assertTrue(!LocatePlaying.offersButton(setting = true, inList = true, side = 0))
        assertTrue(!LocatePlaying.offersButton(setting = false, inList = true, side = 1))
        assertTrue(!LocatePlaying.offersButton(setting = true, inList = false, side = 1))
    }

    @Test fun aSongsRowUnderHeadings() {
        val places = SongRowPlaces().apply { start = 4 }
        assertEquals(4 + 379, places.of("s380", 379))
        assertEquals(-1, places.of("s380", -1))
        assertEquals(-1, places.of(null, 3))
        // Grouped by genre: its first row, wherever the headings put it.
        places.grouped = mapOf("s380" to 17)
        assertEquals(17, places.of("s380", 379))
        assertEquals(-1, places.of("s9", 8))
    }
}
