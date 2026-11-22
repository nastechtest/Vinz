package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.vinz.music.data.Uploads
import com.vinz.music.data.innertube.Parser
import com.vinz.music.data.model.AlbumItem
import com.vinz.music.data.model.ArtistItem
import com.vinz.music.data.model.Shelf
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.prefs.SectionStyle
import com.vinz.music.ui.components.rowKeys
import com.vinz.music.ui.screens.HomeEntry
import com.vinz.music.ui.screens.StyledEntries
import com.vinz.music.ui.screens.extraShelves
import com.vinz.music.ui.screens.shelfKey
import com.vinz.music.ui.theme.VinzIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.7 (plan 8.3), #330's kind beyond Search: a lazy list refuses a key twice ("Key … was already used") and closes the
 * page. Lists whose rows come from YouTube or from what the listener brought in can hold the same thing twice: each row
 * now has a key of its own ([rowKeys]), or the repeat is left out where it comes in (Uploads, Home's extra shelves).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ListDuplicateKeysTest {
    @get:Rule val compose = createComposeRule()

    private val episode = Song("ep1", "Episode one")
    private val other = Song("ep2", "Episode two")

    @Test fun keyedByTheIdAloneTheListClosed() {
        val repeated = listOf(episode, other, episode)
        val error = runCatching {
            compose.setContent {
                LazyColumn(Modifier.size(360.dp, 640.dp)) { items(repeated, key = { "fe:" + it.id }) { Text(it.title) } }
            }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertTrue("the same key twice is refused (the crash): $error", error?.message.orEmpty().contains("was already used"))
    }

    /** Podcasts' search and Episodes for later, the artist picker, the History tab: keyed as they are now. */
    @Test fun aRepeatedRowShowsTwice() {
        val repeated = listOf(episode, other, episode)
        val keys = rowKeys(repeated) { "fe:" + it.id }
        compose.setContent {
            LazyColumn(Modifier.size(360.dp, 640.dp)) { items(repeated.size, key = { keys[it] }) { i -> Text(repeated[i].title) } }
        }
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodesWithText("Episode one").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Episode two").fetchSemanticsNodes().size)
    }

    @Test fun eachRowKeepsItsKeyAsOthersComeAndGo() {
        val keys = rowKeys(listOf("a", "b", "a", "a", "c")) { it }
        assertEquals(listOf("a", "b", "a#2", "a#3", "c"), keys)
        assertEquals("all different", keys.size, keys.toSet().size)
        // A list without repeats is keyed as before, so nothing else about it changes.
        assertEquals(listOf("x", "y"), rowKeys(listOf("x", "y")) { it })
        // Another row going doesn't move a row's key (a key by its place would, and animations would follow the place).
        assertEquals(listOf("a", "a#2", "a#3", "c"), rowKeys(listOf("a", "a", "a", "c")) { it })
        assertEquals(listOf("b", "c"), rowKeys(listOf("b", "c")) { it })
        // An id that already reads like a count doesn't make a key twice.
        val odd = rowKeys(listOf("a", "a#2", "a")) { it }
        assertEquals(odd.size, odd.toSet().size)
    }

    /** Home's sections of Vinz's own entries (moods from YouTube, mixes, genres), in every style. */
    @Test fun aSectionWithAnEntryTwiceShowsInEveryStyle() {
        fun entry(key: String, title: String) = HomeEntry(key, title, icon = VinzIcons.Mood) {}
        val entries = listOf(entry("mood|p1", "Chill"), entry("mood|p2", "Focus"), entry("mood|p1", "Chill"))
        val styles = listOf(SectionStyle.CAROUSEL, SectionStyle.GRID, SectionStyle.LIST, SectionStyle.HERO)
        compose.setContent {
            Column { styles.forEach { s -> StyledEntries(s, entries) } }
        }
        compose.waitForIdle()
        assertTrue("every style shows the repeated entry", compose.onAllNodesWithText("Chill").fetchSemanticsNodes().size >= styles.size)
    }

    /** Uploads: YouTube's rows of albums and artists, and what was kept of them, each once. */
    @Test fun uploadsListEachAlbumOnce() {
        val a = AlbumItem("FEmusic_library_privately_owned_release_detailA", "Demo tape", "", null)
        val b = AlbumItem("FEmusic_library_privately_owned_release_detailB", "Live at home", "", null)
        val whole = Parser.UploadRows(listOf(a, b, a), rows = 3, next = null)
        assertEquals(listOf(a, b), Uploads.settled(whole, null) { emptyList() })
        val partway = Parser.UploadRows(listOf(a), rows = 1, next = "token")
        assertEquals(listOf(a, b), Uploads.settled(partway, listOf(b, a)) { emptyList() })
        val artist = ArtistItem("FEmusic_library_privately_owned_artist_detailX", "Me", "", null)
        assertEquals(listOf(artist), Uploads.settled(null, listOf(artist, artist)) { emptyList() })
    }

    /** Home's shelves added on their own, keyed by title as a key has it: two that read the same are one row. */
    @Test fun homeAddsAShelfOfTheSameTitleOnce() {
        val songs = listOf(SongItem(Song("s1", "One")))
        val shelves = listOf(Shelf("Mixed for you", items = songs), Shelf("Mixed for You ", items = songs), Shelf("Covers", items = songs))
        val extra = extraShelves(shelves, emptySet(), emptyList(), null, null, null, emptySet())
        val keys = extra.map { shelfKey(it.title) }
        assertEquals(listOf(shelfKey("Mixed for you"), shelfKey("Covers")), keys)
    }
}
