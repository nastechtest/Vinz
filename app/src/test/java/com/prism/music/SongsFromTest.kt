package com.vinz.music

import com.vinz.music.data.PlaylistRead
import com.vinz.music.data.PlaylistSongs
import com.vinz.music.data.SongsFrom
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.CollectionKind
import com.vinz.music.data.model.CollectionPage
import com.vinz.music.data.model.Song
import com.vinz.music.data.readPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library → Songs' "From" (1.8.3, #382): liked songs plus chosen playlists, once each; the choice kept as text; whole
 * playlists read once and kept.
 */
class SongsFromTest {
    private fun s(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist")))

    @Test fun likedFirstThenEachPlaylistOnceEach() {
        val liked = listOf(s("a"), s("b"))
        val gym = listOf(s("c"), s("a"), s("d"))
        val road = listOf(s("d"), s("e"))
        val from = SongsFrom(true, listOf("gym", "road"))
        assertEquals(listOf("a", "b", "c", "d", "e"), SongsFrom.merge(liked, listOf(gym, road), from).map { it.id })
        assertEquals("without liked songs", listOf("c", "a", "d", "e"), SongsFrom.merge(liked, listOf(gym, road), from.copy(liked = false)).map { it.id })
        assertEquals("a playlist with repeats", listOf("c"), SongsFrom.merge(emptyList(), listOf(listOf(s("c"), s("c"))), SongsFrom(false, listOf("x"))).map { it.id })
    }

    @Test fun theChoiceIsKeptAsText() {
        val from = SongsFrom(false, listOf("PLabc", "vinz_123"))
        assertEquals(from, SongsFrom.decode(from.encode()))
        assertEquals(SongsFrom(true, listOf("PLabc")), SongsFrom.decode(SongsFrom(true, listOf("PLabc")).encode()))
        assertTrue("nothing kept: liked songs, as before", SongsFrom.decode(null).isDefault)
        assertTrue(SongsFrom.decode("").isDefault)
        assertTrue("nothing at all chosen falls back to liked songs", SongsFrom.decode("|").isDefault)
        assertEquals("repeats and blanks dropped", listOf("a", "b"), SongsFrom.decode("liked|a,,b,a").playlists)
        assertFalse(SongsFrom(true, listOf("a")).isDefault)
    }

    @Test fun aPlaylistIsReadOnceUntilRefreshed() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs { id -> reads++; if (id == "bad") error("offline") else PlaylistRead(listOf(s("$id-1")), complete = true) }
        assertEquals("x-1", lists.songs("x")!!.single().id)
        lists.songs("x")
        assertEquals(1, reads)
        lists.refresh("x")
        lists.songs("x")
        assertEquals(2, reads)
        assertNull("a playlist that won't load shows nothing, no crash", lists.songs("bad"))
    }

    // #473 (based on PR #474 by Irobrine): a big playlist must not stay short for the session.

    @Test fun aReadThatStoppedShortIsReadAgainNextTime() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs { id ->
            reads++
            if (reads == 1) PlaylistRead(listOf(s("$id-1")), complete = false)
            else PlaylistRead(listOf(s("$id-1"), s("$id-2"), s("$id-3")), complete = true)
        }
        assertEquals("the page that came shows meanwhile", listOf("x-1"), lists.songs("x")!!.map { it.id })
        assertFalse("but it isn't kept as the whole playlist", lists.isComplete("x"))
        assertEquals("the rest comes next time", listOf("x-1", "x-2", "x-3"), lists.songs("x")!!.map { it.id })
        assertTrue(lists.isComplete("x"))
        lists.songs("x")
        assertEquals("then it's kept whole", 2, reads)
    }

    @Test fun everyPageOfAPlaylistIsRead() = runBlocking {
        val read = readPlaylist(page(listOf("a", "b"), continuation = "next"), page = { token ->
            assertEquals("next", token)
            chunk(listOf("c", "d"), next = null)
        }, retryMs = 0)
        assertEquals(listOf("a", "b", "c", "d"), read.songs.map { it.id })
        assertTrue(read.complete)
    }

    @Test fun aPageThatFailsIsAskedAgain() = runBlocking {
        var tries = 0
        val read = readPlaylist(page(listOf("a"), continuation = "next"), page = {
            if (++tries < 3) throw IOException("busy")
            chunk(listOf("b"), next = null)
        }, retryMs = 0)
        assertEquals("a page lost once doesn't leave the rest out", listOf("a", "b"), read.songs.map { it.id })
        assertTrue(read.complete)
        assertEquals(3, tries)
    }

    @Test fun aPageThatNeverComesLeavesTheReadShortAndUnkept() = runBlocking {
        var tries = 0
        val read = readPlaylist(page(listOf("a"), continuation = "next"), page = { tries++; throw IOException("offline") }, retryMs = 0)
        assertEquals(listOf("a"), read.songs.map { it.id })
        assertFalse("shown, but not as the whole playlist", read.complete)
        assertEquals("asked three times before giving up", 3, tries)
        val lists = PlaylistSongs { read }
        lists.songs("x")
        assertFalse(lists.isComplete("x"))
    }

    @Test fun leavingTheScreenStopsTheRead() = runBlocking {
        try {
            readPlaylist(page(listOf("a"), continuation = "next"), page = {
                throw CancellationException("the screen was left")
            }, retryMs = 0)
            throw AssertionError("the read should have been cancelled, not returned short")
        } catch (_: CancellationException) {
            // Passed on: the caller keeps nothing, so the next visit reads it all again.
        }
    }

    @Test fun leavingTheScreenDoesNotLeaveAShortListKept() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs { _ ->
            reads++
            // The first read is cancelled on its second page (the screen was left).
            readPlaylist(page(listOf("a"), continuation = "next"), page = {
                if (reads == 1) throw CancellationException("the screen was left")
                chunk(listOf("b"), next = null)
            }, retryMs = 0)
        }
        try {
            lists.songs("x")
            throw AssertionError("the read should have been cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse("the cancelled read kept nothing", lists.isComplete("x"))
        assertEquals(listOf("a", "b"), lists.songs("x")!!.map { it.id })
        assertEquals(2, reads)
    }

    /**
     * Review (1.8.5, #473 by another route): a page whose rows all failed to read but that still points to the next one
     * ended the read, kept as the whole playlist for the session.
     */
    @Test fun aPageOfUnreadableRowsDoesntEndThePlaylist() = runBlocking {
        val read = readPlaylist(page(listOf("a"), continuation = "p2"), page = { token ->
            when (token) {
                "p2" -> chunk(emptyList(), next = "p3")
                "p3" -> chunk(listOf("b", "c"), next = null)
                else -> error("asked for $token")
            }
        }, retryMs = 0)
        assertEquals(listOf("a", "b", "c"), read.songs.map { it.id })
        assertTrue(read.complete)
    }

    @Test fun anEmptyLastPageEndsTheRead() = runBlocking {
        val read = readPlaylist(page(listOf("a"), continuation = "p2"), page = { chunk(emptyList(), next = null) }, retryMs = 0)
        assertEquals(listOf("a"), read.songs.map { it.id })
        assertTrue(read.complete)
    }

    @Test fun emptyPagesWithNoEndLeaveTheReadShortAndUnkept() = runBlocking {
        var asked = 0
        val read = readPlaylist(page(listOf("a"), continuation = "p1"), page = { asked++; chunk(emptyList(), next = "p${asked + 1}") }, retryMs = 0)
        assertEquals(listOf("a"), read.songs.map { it.id })
        assertFalse("read again next time, not kept short", read.complete)
        assertTrue("a few empty pages, then it gives up ($asked)", asked in 2..5)
    }

    @Test fun aSwappedSongShowsAfterARefresh() = runBlocking {
        // Same count, different songs: "b" removed and "d" added. A refresh must show the new list, not a kept one.
        var now = listOf("a", "b", "c")
        val lists = PlaylistSongs { _ -> readPlaylist(page(now, continuation = null), page = { error("one page only") }, retryMs = 0) }
        assertEquals(listOf("a", "b", "c"), lists.songs("x")!!.map { it.id })
        now = listOf("a", "c", "d")
        lists.refresh("x")
        assertEquals(listOf("a", "c", "d"), lists.songs("x")!!.map { it.id })
    }

    private fun chunk(songs: List<String>, next: String?) = YouTubeMusic.PlaylistChunk(songs.map(::s), next, emptyMap())

    private fun page(songs: List<String>, continuation: String?) = CollectionPage(
        id = "playlist", kind = CollectionKind.PLAYLIST, title = "Playlist", subtitle = "", thumbnail = null,
        songs = songs.map(::s), continuation = continuation,
    )
}
