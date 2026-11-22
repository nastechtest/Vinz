package com.vinz.music

import androidx.room.Room
import com.vinz.music.data.HistoryRemoval
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** "Remove from history" (#469): one play out (not the song's others), Undo puts back exactly the same rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistoryRemovalTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    @After fun close() = db.close()
    private val plays = db.plays()

    private val song = Song("a", "A song", listOf(ArtistRef("Someone")), durationSec = 200)
    private val other = Song("b", "Another", listOf(ArtistRef("Someone")), durationSec = 200)

    private fun seed() = runBlocking {
        db.songs().upsertAll(listOf(song.toEntity(), other.toEntity()))
        plays.insert(PlayEventEntity(songId = "a", timestamp = 1_000, playedMs = 50_000, source = "LIBRARY"))
        plays.insert(PlayEventEntity(songId = "b", timestamp = 2_000, playedMs = 60_000))
        plays.insert(PlayEventEntity(songId = "a", timestamp = 3_000, playedMs = 70_000, source = "smart", excluded = true))
        // "Last played" as the song's last start, a moment before its play was kept.
        db.songs().touch("a", 2_900)
        db.songs().touch("b", 1_900)
    }

    private fun all() = runBlocking { plays.events(0, Long.MAX_VALUE) }

    @Test fun deletingOnePlayLeavesTheSongsOthers() = runBlocking {
        seed()
        val latestA = all().last { it.songId == "a" }
        assertEquals(1, plays.deleteById(latestA.id))
        assertEquals(listOf(1_000L, 2_000L), all().map { it.timestamp })
        assertEquals("the song's other play stays", 1, all().count { it.songId == "a" })
    }

    @Test fun deleteByIdsAndReinsertRestoresExactly() = runBlocking {
        seed()
        val before = all()
        val gone = before.filter { it.songId == "a" }
        assertEquals(2, plays.deleteByIds(gone.map { it.id }))
        assertEquals(listOf("b"), all().map { it.songId })
        plays.reinsert(gone)
        assertEquals("same ids, times, lengths, sources and marks", before, all())
    }

    @Test fun removingTheLatestPlayMovesLastPlayedBack() = runBlocking {
        seed()
        val latestA = all().last { it.songId == "a" }
        val removed = HistoryRemoval.remove(db, listOf(latestA.id))!!
        assertEquals(1, removed.count)
        assertEquals("to the play before it", 1_000L, db.songs().get("a")!!.lastPlayed)
        assertEquals("another song is untouched", 1_900L, db.songs().get("b")!!.lastPlayed)
        // Recently played (newest first) now puts the other song first.
        assertEquals(listOf("b", "a"), db.songs().recentFlow(10).first().map { it.id })
        // Its only other play too: none left, so it's out of Recently played.
        HistoryRemoval.remove(db, listOf(all().first { it.songId == "a" }.id))
        assertEquals(0L, db.songs().get("a")!!.lastPlayed)
        assertEquals(listOf("b"), db.songs().recentFlow(10).first().map { it.id })
    }

    @Test fun undoPutsBackThePlaysAndLastPlayed() = runBlocking {
        seed()
        val before = all()
        val removed = HistoryRemoval.remove(db, before.filter { it.songId == "a" }.map { it.id })!!
        assertEquals(0L, db.songs().get("a")!!.lastPlayed)
        HistoryRemoval.restore(db, removed)
        assertEquals(before, all())
        assertEquals(2_900L, db.songs().get("a")!!.lastPlayed)
    }

    @Test fun anOlderPlayLeavesLastPlayedAlone() = runBlocking {
        seed()
        HistoryRemoval.remove(db, listOf(all().first { it.songId == "a" }.id))
        assertEquals("its latest play is still there", 2_900L, db.songs().get("a")!!.lastPlayed)
        // Started again after its latest play (a skip): that start stays.
        db.songs().touch("b", 5_000)
        HistoryRemoval.remove(db, listOf(all().first { it.songId == "b" }.id))
        assertEquals(5_000L, db.songs().get("b")!!.lastPlayed)
        assertNull(HistoryRemoval.remove(db, listOf(999L)))
    }
}
