package com.vinz.music

import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.lyrics.LyricsRepository
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1.8.7 §6.2, missing lyrics: a "not found" is kept for a day, and network trouble used to be kept as one (three sources
 * swallowed it), so going offline hid a song's lyrics for a day; and one hung source held every one after it. With a
 * fake client: trouble and offline keep nothing; a slow first source doesn't keep the second's lyrics from showing.
 */
class LyricsMissTest {
    private val song = Song("missTest1", "My Song", listOf(ArtistRef("Me")), null, 200)
    private val lrclibAnswer = """{"trackName":"My Song","artistName":"Me","duration":200,"syncedLyrics":"[00:01.00]Hello there\n[00:04.00]General Kenobi"}"""

    /** What the fake network does with each request: an answer, or an exception to throw. */
    private var network: (Request) -> Response = { throw IOException("offline") }
    private val asked = CopyOnWriteArrayList<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> asked += chain.request().url.host; network(chain.request()) }).build()

    private fun ok(req: Request, body: String, code: Int = 200) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private val rows = HashMap<String, LyricsEntity>()
    private val dao = object : LyricsDao {
        override suspend fun get(songId: String, source: String) = rows["$songId|$source"]
        override suspend fun put(e: LyricsEntity) { rows["${e.songId}|${e.source}"] = e }
        override suspend fun clearMisses() {}
        override suspend fun allFor(songId: String) = rows.values.filter { it.songId == songId && it.content.isNotEmpty() }
    }
    private var settings = AppSettings(lyrics = com.vinz.music.data.prefs.LyricsPrefs(order = listOf(LyricsSource.APPLE, LyricsSource.LRCLIB)), preferSynced = true)
    private val repo = LyricsRepository(http, dao, YouTubeMusic(InnerTube(http, { settings })), { settings })

    @Test fun networkTroubleIsNotKeptAsAMiss() = runBlocking {
        for (src in listOf(LyricsSource.LRCLIB, LyricsSource.APPLE, LyricsSource.LYRICSPLUS)) {
            assertNull(repo.fetch(song, src))
            assertNull("$src's failure was kept as \"not found\"", rows["${song.id}|${src.name}"])
        }
        // A server in trouble isn't a "no" either.
        network = { ok(it, "busy", 503) }
        assertNull(repo.fetch(song, LyricsSource.LRCLIB))
        assertTrue(rows.isEmpty())
        // Back online: the lyrics come, not a day-old "not found".
        network = { req -> if (req.url.host == "lrclib.net" && req.url.encodedPath == "/api/get") ok(req, lrclibAnswer) else ok(req, "{}", 404) }
        val l = repo.fetch(song, LyricsSource.LRCLIB)
        assertNotNull(l)
        assertTrue(l!!.synced)
    }

    @Test fun offlineNothingIsAskedOrKept() = runBlocking {
        repo.online = { false }
        network = { req -> ok(req, lrclibAnswer) }
        assertNull(repo.auto(song))
        assertTrue("no service asked offline: $asked", asked.isEmpty())
        assertTrue(rows.isEmpty())
        // A saved answer still serves offline.
        repo.online = { true }
        assertNotNull(repo.fetch(song, LyricsSource.LRCLIB))
        repo.online = { false }
        assertEquals(LyricsSource.LRCLIB, repo.auto(song)?.source)
    }

    @Test fun aSlowFirstSourceDoesNotHoldTheSecond() = runBlocking {
        network = { req ->
            when (req.url.host) {
                // Better Lyrics hangs (each of its two mirrors) far past the deadline.
                "api.betterlyrics.org", "lyrics-api.boidu.dev" -> { Thread.sleep(15_000); ok(req, "{}") }
                "lrclib.net" -> if (req.url.encodedPath == "/api/get") ok(req, lrclibAnswer) else ok(req, "[]")
                else -> ok(req, "{}", 404)
            }
        }
        repo.deadlineMs = 3_000
        val t0 = System.currentTimeMillis()
        val l = repo.auto(song)
        val took = System.currentTimeMillis() - t0
        assertEquals(LyricsSource.LRCLIB, l?.source)
        assertTrue("took $took ms", took < 6_000)
    }
}
