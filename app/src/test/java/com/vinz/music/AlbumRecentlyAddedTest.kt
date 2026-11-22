package com.vinz.music

import android.content.Context
import com.vinz.music.data.AlbumFirstSeen
import com.vinz.music.data.model.AlbumItem
import com.vinz.music.ui.screens.AlbumSort
import com.vinz.music.ui.screens.albumsRecentlyAdded
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.8.4 (D56): Library → Albums by "Recently added". YouTube's list has no dates, so Vinz keeps when it first saw each
 * album; the newest are first, and albums seen together (all of them, the first time) keep the library's order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AlbumRecentlyAddedTest {
    private fun album(id: String) = AlbumItem(id, "Album $id", "Album • Someone", null, null)
    private fun prefs(name: String) = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun ids(list: List<AlbumItem>) = list.joinToString(",") { it.id }

    @Test fun theNewestIsFirst() {
        val albums = listOf(album("a"), album("b"), album("c"))
        assertEquals("b,c,a", ids(albumsRecentlyAdded(albums, emptyMap(), mapOf("a" to 100L, "b" to 300L, "c" to 200L))))
    }

    @Test fun albumsAddedTogetherKeepTheLibrarysOrder() {
        val albums = listOf(album("c"), album("a"), album("d"), album("b"))
        assertEquals("d,c,a,b", ids(albumsRecentlyAdded(albums, emptyMap(), mapOf("a" to 100L, "b" to 100L, "c" to 100L, "d" to 500L))))
    }

    @Test fun onesWithNoTimeGoLastInTheirOrder() {
        val albums = listOf(album("x"), album("a"), album("y"))
        assertEquals("a,x,y", ids(albumsRecentlyAdded(albums, emptyMap(), mapOf("a" to 100L))))
    }

    @Test fun anAlbumSavedOnThePhoneGoesByWhenItWasSaved() {
        val albums = listOf(album("phone"), album("a"), album("b"))
        assertEquals("b,phone,a", ids(albumsRecentlyAdded(albums, saved = mapOf("phone" to 200L), seen = mapOf("a" to 100L, "b" to 300L))))
    }

    @Test fun theFirstRunSeesThemAllAtOnceAndALaterOneIsNewer() {
        var now = 1_000L
        val seen = AlbumFirstSeen(prefs("first-run")) { now }
        val library = listOf(album("c"), album("a"), album("b"))
        seen.note(library.map { it.id })
        // All "seen at upgrade": YouTube's order stays.
        assertEquals("c,a,b", ids(albumsRecentlyAdded(library, emptyMap(), seen.times.value)))
        now = 2_000L
        // YouTube lists the new one wherever it likes; it's first here.
        val later = listOf(album("c"), album("a"), album("new"), album("b"))
        seen.note(later.map { it.id })
        assertEquals("new,c,a,b", ids(albumsRecentlyAdded(later, emptyMap(), seen.times.value)))
        assertEquals("the ones there before keep their time", 1_000L, seen.times.value["a"])
    }

    @Test fun theTimesAreKeptBetweenStarts() {
        var now = 5L
        AlbumFirstSeen(prefs("kept")) { now }.apply { note(listOf("MPREb_one", "MPREb_two")); now = 9L; note(listOf("MPREb_three")) }
        assertEquals(mapOf("MPREb_one" to 5L, "MPREb_two" to 5L, "MPREb_three" to 9L), AlbumFirstSeen(prefs("kept")).times.value)
    }

    @Test fun aListCutShortForgetsNothingButAnAlbumTakenOutIsNewAgain() {
        var now = 10L
        val seen = AlbumFirstSeen(prefs("short")) { now }
        seen.note(listOf("a", "b", "c"))
        now = 20L
        seen.note(listOf("a")) // a page failed: b and c aren't in this list
        seen.note(listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 10L, "b" to 10L, "c" to 10L), seen.times.value)
        seen.forget("b")
        now = 30L
        seen.note(listOf("a", "b", "c"))
        assertEquals(30L, seen.times.value["b"])
    }

    @Test fun whatsWrittenReadsBackAndRubbishIsLeftOut() {
        val times = mapOf("MPREb_x" to 1_731_000_000_000L, "odd\tid" to 2L)
        assertEquals(times, AlbumFirstSeen.decode(AlbumFirstSeen.encode(times)))
        assertEquals(mapOf("ok" to 3L), AlbumFirstSeen.decode("ok\t3\nno-time\n\tnope\nbad\tx\n"))
        assertEquals(emptyMap<String, Long>(), AlbumFirstSeen.decode(null))
    }

    @Test fun theChosenSortIsSavedByNameAndOldOnesStillRead() {
        // By name: these are what phones have stored. New sorts go at the end; none is renamed.
        assertEquals(listOf("LIBRARY", "DOWNLOADED", "MOST", "TITLE", "ARTIST", "ADDED"), AlbumSort.entries.map { it.name })
        assertEquals(AlbumSort.ADDED, AlbumSort.of(AlbumSort.ADDED.name))
        for (old in listOf("LIBRARY", "DOWNLOADED", "MOST", "TITLE", "ARTIST")) assertEquals(old, AlbumSort.of(old).name)
        assertEquals(AlbumSort.LIBRARY, AlbumSort.of(null))
        assertEquals("a name from a newer version", AlbumSort.LIBRARY, AlbumSort.of("SOMETHING_NEWER"))
        assertEquals("Recently added", AlbumSort.ADDED.label)
    }
}
