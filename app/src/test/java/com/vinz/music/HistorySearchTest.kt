package com.vinz.music

import androidx.room.Room
import com.vinz.music.data.HistorySearch
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.toEntity
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Library → History's search (#469): the whole stored history, by title, artist or album, without capitals or accents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistorySearchTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PrismDatabase::class.java).allowMainThreadQueries().build()
    @After fun close() = db.close()
    private val dao = db.plays()

    private val halo = Song("halo", "Halo", listOf(ArtistRef("Beyoncé")), album = AlbumRef("I Am... Sasha Fierce"), durationSec = 260)
    private val joga = Song("joga", "Jóga", listOf(ArtistRef("Björk")), album = AlbumRef("Homogenic"), durationSec = 300)
    private val acdc = Song("tnt", "T.N.T.", listOf(ArtistRef("AC/DC")), album = AlbumRef("High Voltage"), durationSec = 214)

    private fun find(q: String, limit: Int = 300) = runBlocking {
        HistorySearch.rows(dao, HistorySearch.match(HistorySearch.Index(dao.playedSongNames(0)), q), limit)
    }

    @Test fun byTitleArtistOrAlbumWithoutCapitalsOrAccents() = runBlocking {
        db.songs().upsertAll(listOf(halo.toEntity(), joga.toEntity(), acdc.toEntity()))
        var t = 1_000_000L
        for (s in listOf(halo, joga, acdc, halo)) dao.insert(PlayEventEntity(songId = s.id, timestamp = t++, playedMs = 60_000))
        assertEquals(listOf("halo", "halo"), find("HALO").map { it.song.id })
        assertEquals("the artist, without the accent", listOf("halo", "halo"), find("beyonce").map { it.song.id })
        assertEquals("the album", listOf("joga"), find("homogenic").map { it.song.id })
        assertEquals("the title's accent typed", listOf("joga"), find("jóga").map { it.song.id })
        assertEquals("björk without the umlaut", listOf("joga"), find("Bjork").map { it.song.id })
        assertEquals("punctuation as spaces", listOf("tnt"), find("ac dc").map { it.song.id })
        assertEquals("every word, anywhere", listOf("halo", "halo"), find("sasha halo").map { it.song.id })
        assertTrue(find("nothing like it").isEmpty())
        // Newest first, each play with its own row.
        val plays = find("halo")
        assertTrue(plays[0].playedAt > plays[1].playedAt)
        assertTrue(plays[0].eventId != plays[1].eventId)
    }

    @Test fun findsPlaysBeyondTheFirstPage() = runBlocking {
        db.songs().upsertAll(listOf(halo.toEntity(), joga.toEntity()))
        // Halo long ago, then 1,000 plays of Jóga: History shows 300 at first.
        dao.insert(PlayEventEntity(songId = halo.id, timestamp = 1L, playedMs = 60_000))
        for (i in 0 until 1_000) dao.insert(PlayEventEntity(songId = joga.id, timestamp = 10L + i, playedMs = 60_000))
        assertEquals(listOf("halo"), find("halo").map { it.song.id })
        assertEquals("only as many as asked for", 300, find("joga").size)
    }

    /** Years of listening: 60,000 plays of 3,000 songs. The names are read once; each keystroke is a match and one read. */
    @Test fun aLargeHistoryStaysQuick() = runBlocking {
        val songs = (0 until 3_000).map { i -> Song("s$i", "Song number $i ${if (i % 100 == 0) "Café" else ""}", listOf(ArtistRef("Artist ${i % 400}")), album = AlbumRef("Album ${i % 700}"), durationSec = 200) }
        db.songs().upsertAll(songs.map { it.toEntity() })
        db.runInTransaction {
            runBlocking { for (i in 0 until 60_000) dao.insert(PlayEventEntity(songId = "s${(i * 7919) % 3_000}", timestamp = 1_000_000L + i, playedMs = 60_000)) }
        }
        val searcher = HistorySearch.Searcher(dao)
        val version = Any()
        val first = System.nanoTime()
        searcher.find("cafe", 300, version)
        val withIndex = (System.nanoTime() - first) / 1_000_000
        val times = listOf("c", "ca", "caf", "cafe", "artist 12", "album 6", "song number 2999").map { q ->
            val t = System.nanoTime(); val rows = searcher.find(q, 300, version); ((System.nanoTime() - t) / 1_000_000) to rows.size
        }
        println("HistorySearch, 60,000 plays / 3,000 songs: first search (reads the names) $withIndex ms; then ${times.map { it.first }} ms for ${times.map { it.second }} rows")
        assertEquals(300, searcher.find("cafe", 300, version).size)
        assertTrue("a keystroke's search took ${times.maxOf { it.first }} ms", times.maxOf { it.first } < 1_500)
    }
}
