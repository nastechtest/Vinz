package com.vinz.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.db.LyricsDao
import com.prism.music.data.db.LyricsEntity
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.innertube.YouTubeMusic
import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.LyricWord
import com.vinz.music.data.lyrics.LyricsRepository
import com.vinz.music.data.lyrics.SongMatch
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.LyricsSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.7 §6.1: the wrong song's lyrics (#301's kind, PR #494 by NeeL21YT). Each source's hit has to be the song by its
 * title, artist and length ([SongMatch]), and word lyrics that share under 40% of their lines with the song's line-timed
 * lyrics are another song's. The answers are made-up fixtures in each source's own shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LyricMatchTest {
    private val settings = AppSettings()
    private val repo = LyricsRepository(OkHttpClient(), object : LyricsDao {
        override suspend fun get(songId: String, source: String): LyricsEntity? = null
        override suspend fun put(e: LyricsEntity) {}
        override suspend fun clearMisses() {}
        override suspend fun allFor(songId: String) = emptyList<LyricsEntity>()
    }, YouTubeMusic(InnerTube(OkHttpClient(), { settings })), { settings })

    private fun json(s: String): JsonElement = InnerTube.json.parseToJsonElement(s)
    private fun arr(s: String) = json(s) as JsonArray
    private fun song(title: String, artist: String, sec: Int) = Song("x", title, listOf(ArtistRef(artist)), null, sec)
    private fun ids(list: List<JsonElement>) = list.map { (it as kotlinx.serialization.json.JsonObject)["id"].toString().trim('"') }

    // ---- The shared check

    @Test fun theRightSongWithSmallTitleDifferencesPasses() {
        val queen = song("Don't Stop Me Now - Remastered 2011", "Queen", 209)
        assertTrue(SongMatch.fits(queen, "Don’t Stop Me Now", "Queen", 210.0))
        assertTrue(SongMatch.fits(song("IV. Sweatpants (feat. Jason Martin)", "Childish Gambino", 200), "IV. Sweatpants", "Childish Gambino", null))
        assertTrue(SongMatch.fits(song("Café del Mar", "Energy 52", 300), "CAFE DEL MAR", "Energy 52 & Someone", 302.0))
        assertTrue("full-width letters", SongMatch.fits(song("Lemon", "米津玄師", 255), "Ｌｅｍｏｎ", "米津玄師", 255.0))
        assertTrue("romanized title, same artist and length", SongMatch.fits(song("Yoru ni Kakeru", "YOASOBI", 261), "夜に駆ける", "YOASOBI", 261.0))
        assertTrue("simplified and traditional forms", SongMatch.fits(song("紅豆", "王菲", 256), "红豆", "王菲", 256.0))
        assertTrue("the artist in another script", SongMatch.fits(song("Lemon", "Kenshi Yonezu", 255), "Lemon", "米津玄師", 255.0))
    }

    /** Found by the coverage run (LyricCoverageLiveTest): right songs the first version of the check refused. */
    @Test fun rightSongsTheCoverageRunFound() {
        // YouTube Music writes Mandarin names in traditional characters, the sources in simplified ones.
        assertTrue(SongMatch.fits(song("告白氣球", "周杰倫", 216), "告白气球", "周杰伦", 215.0))
        assertTrue(SongMatch.fits(song("因為愛情 - Because Of Love", "Eason Chan", 217), "因为爱情", "陈奕迅", 216.0))
        // A classical piece is filed under its own name.
        val nessun = Song("x", "Turandot, Act 3: \"Nessun dorma\" (Live)", listOf(ArtistRef("Luciano Pavarotti"), ArtistRef("Giacomo Puccini")), null, 205)
        assertTrue(SongMatch.fits(nessun, "Nessun dorma", "Luciano Pavarotti", 204.0))
        assertTrue(SongMatch.fits(nessun, "Turandot / Act 3 - Puccini: Turandot / Act 3 - \"Nessun dorma!\" (Live)", "Luciano Pavarotti", 205.0))
        assertFalse(SongMatch.fits(nessun, "Vesti la giubba", "Luciano Pavarotti", 205.0))
        // A cover's untimed words under the song's own title (Bini's and LyricsPlus' "Pretender" by Tokyo Arrow).
        val pretender = song("Pretender", "Official Hige Dandism", 327)
        assertFalse(SongMatch.fits(pretender, "Pretender", "Tokyo Arrow", 325.0))
        assertTrue(SongMatch.sameWordsUntimed(pretender, "Pretender", 325.0))
        assertFalse(SongMatch.sameWordsUntimed(pretender, "Pretender", 234.0))
        val bini = arr("""[{"track_name":"Pretender","artist_name":"Tokyo Arrow","duration":325,"timing_type":"none","lyricsUrl":"https://x/1"},
            {"track_name":"Pretender","artist_name":"Yudai","duration":330,"timing_type":"word","lyricsUrl":"https://x/2"}]""")
        assertEquals("https://x/1", repo.biniPick(bini, pretender).let { (it as kotlinx.serialization.json.JsonObject)["lyricsUrl"].toString().trim('"') })
        assertNull("a timed cover isn't taken", repo.biniPick(JsonArray(bini.drop(1)), pretender))
    }

    @Test fun anotherSongOfTheSameLengthIsRefused() {
        val queen = song("Don't Stop Me Now", "Queen", 209)
        assertFalse(SongMatch.fits(queen, "Bohemian Rhapsody", "Queen", 209.0))
        assertFalse("same title, someone else", SongMatch.fits(queen, "Don't Stop Me Now", "McFly", 209.0))
        assertFalse("a title that only starts the same", SongMatch.fits(song("Love", "Taylor Swift", 235), "Love Story", "Taylor Swift", 235.0))
        assertFalse(SongMatch.fits(song("紅豆", "王菲", 256), "传奇", "王菲", 256.0))
        assertFalse("too long", SongMatch.fits(queen, "Don't Stop Me Now", "Queen", 240.0))
    }

    // ---- Each source

    @Test fun kuGouCandidates() {
        val c = arr("""[
            {"id":"1","accesskey":"a","song":"Nothing Personal","singer":"KAIRO","duration":172000},
            {"id":"2","accesskey":"b","song":"Movie Star","singer":"KAIRO","duration":172000},
            {"id":"3","accesskey":"c","song":"Nothing Personal","singer":"AX!A","duration":172000}]""")
        assertEquals(listOf("2"), ids(repo.kuGouCandidates(c, song("Movie Star", "KAIRO", 172))))
        assertEquals(listOf("1"), ids(repo.kuGouCandidates(c, song("Nothing Personal (feat. Someone)", "KAIRO", 171))))
        assertEquals(emptyList<String>(), ids(repo.kuGouCandidates(c, song("Different Song", "KAIRO", 172))))
    }

    @Test fun netEase() {
        val songs = arr("""[
            {"id":1,"name":"红豆","artists":[{"name":"王菲"}],"duration":256000},
            {"id":2,"name":"传奇","artists":[{"name":"王菲"}],"duration":256000},
            {"id":3,"name":"Other","alias":["紅豆 (Live)"],"artists":[{"name":"王菲"}],"duration":300000}]""")
        assertEquals(listOf("1"), ids(repo.netEaseMatches(songs, song("紅豆", "王菲", 256))))
        assertEquals(listOf("2"), ids(repo.netEaseMatches(songs, song("传奇", "王菲", 255))))
        assertEquals(emptyList<String>(), ids(repo.netEaseMatches(songs, song("匆匆那年", "王菲", 256))))
    }

    @Test fun lrcLib() {
        fun hit(track: String, artist: String, d: Int) = """{"trackName":"$track","artistName":"$artist","duration":$d,"syncedLyrics":"[00:01.00]x"}"""
        val s = song("São Paulo", "The Weeknd", 239)
        // The exact lookup's nearest recording is someone else's: refused, and so is it in the search.
        assertNull(repo.pickLrcLib(s, json(hit("São Paulo", "Some Other Artist", 239)), null))
        assertNull(repo.pickLrcLib(s, null, arr("[${hit("Starboy", "The Weeknd", 239)}]")))
        val right = json(hit("Sao Paulo", "The Weeknd, Anitta", 240))
        assertEquals(right, repo.pickLrcLib(s, null, JsonArray(listOf(json(hit("São Paulo", "Other", 239)), right))))
    }

    @Test fun bini() {
        val r = arr("""[{"track_name":"Other Song","artist_name":"Artist","duration":200,"timing_type":"word","lyricsUrl":"https://x/1"},
            {"track_name":"My Song (Remastered)","artist_name":"Artist","duration":201,"timing_type":"line","lyricsUrl":"https://x/2"}]""")
        assertEquals("https://x/2", repo.biniPick(r, song("My Song", "Artist", 200)).let { (it as kotlinx.serialization.json.JsonObject)["lyricsUrl"].toString().trim('"') })
        // Its first result of another title used to be taken when none had the song's.
        assertNull(repo.biniPick(JsonArray(r.take(1)), song("My Song", "Artist", 200)))
    }

    @Test fun genius() {
        val hits = arr("""[{"result":{"title":"Love Story","primary_artist_names":"Taylor Swift","url":"u1"}},
            {"result":{"title":"Love Story (Taylor’s Version)","primary_artist_names":"Taylor Swift","url":"u2"}}]""")
        assertNull(repo.geniusPick(hits, song("Love", "Taylor Swift", 235)))
        assertEquals("u2", repo.geniusPick(JsonArray(hits.drop(1)), song("Love Story (Taylor's Version)", "Taylor Swift", 235))
            .let { (it as kotlinx.serialization.json.JsonObject)["url"].toString().trim('"') })
    }

    /** Unison (and LyricsPlus, Better Lyrics) say what they found in their own fields: another song's is refused. */
    @Test fun unisonsOwnTitleIsChecked() = runBlocking {
        var answer = """{"success":true,"data":{"song":"Another Song","artist":"Someone Else","duration":200,"lyrics":"[00:01.00]Hello\n[00:03.00]World"}}"""
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val s = AppSettings()
        val r = LyricsRepository(http, object : LyricsDao {
            override suspend fun get(songId: String, source: String): LyricsEntity? = null
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = emptyList<LyricsEntity>()
        }, YouTubeMusic(InnerTube(http, { s })), { s })
        val mine = Song("unison1", "My Song", listOf(ArtistRef("Me")), null, 200)
        assertNull(r.fetch(mine, LyricsSource.UNISON))
        answer = """{"success":true,"data":{"song":"My Song","artist":"Me","duration":200,"lyrics":"[00:01.00]Hello\n[00:03.00]World"}}"""
        assertNotNull(r.fetch(mine, LyricsSource.UNISON, force = true))
    }

    // ---- Word lyrics against the song's line-timed lyrics

    private val texts = listOf(
        "I walked along the river", "the lights were coming on", "and every window glowing", "was singing me a song",
        "so take me where the night goes", "and leave the day behind", "I'll follow every heartbeat", "until the end of time",
    )
    private val other = listOf(
        "Cold wind upon the mountain", "a fire burning low", "the wolves are out tonight", "and nowhere left to go",
        "my brother on the border", "my sister by the sea", "we'll gather in the morning", "and then we will be free",
    )

    private fun lines(t: List<String>) = t.mapIndexed { i, x -> LyricLine(10_000L + i * 4_000L, x, 10_000L + (i + 1) * 4_000L) }
    private fun words(t: List<String>) = t.mapIndexed { i, x ->
        val start = 10_000L + i * 4_000L
        var at = start
        LyricLine(start, x, start + 3_000, x.split(" ").mapIndexed { j, w -> LyricWord(at, at + 180 + j * 40, "$w ").also { at += 230 + j * 40 } })
    }

    @Test fun linesInCommon() {
        assertEquals(1.0, SongMatch.linesInCommon(words(texts), lines(texts))!!, 0.01)
        // A slip here and there ("gonna"), a line more or less: still the song.
        val slips = texts.mapIndexed { i, t -> if (i % 3 == 0) "$t oh" else t }.drop(1)
        assertTrue(SongMatch.linesInCommon(words(slips), lines(texts))!! >= SongMatch.MIN_LINES_IN_COMMON)
        assertTrue(SongMatch.linesInCommon(words(other), lines(texts))!! < SongMatch.MIN_LINES_IN_COMMON)
        // A romanized copy can't be compared: no verdict.
        assertNull(SongMatch.linesInCommon(words(listOf("夢を見ていた", "月光", "花火の夜", "君の声")), lines(texts.take(4))))
        assertNull(SongMatch.linesInCommon(words(texts.take(2)), lines(texts)))
        // Lines broken in other places: two run together, or one in halves.
        val merged = texts.chunked(2).map { it.joinToString(" ") }
        assertTrue(SongMatch.linesInCommon(words(merged), lines(texts))!! >= 0.9)
        assertTrue(SongMatch.linesInCommon(words(texts), lines(merged))!! >= 0.9)
    }

    /** The coverage run: Mandarin word lyrics in simplified characters against YouTube Music's traditional ones. */
    @Test fun traditionalAndSimplifiedAreTheSameLines() {
        val simp = listOf("我们说过的话", "时间会让你忘记", "风吹过东边的门", "梦里还有你的声音", "爱是一个长长的梦", "没有人听见我的心")
        val trad = listOf("我們說過的話", "時間會讓你忘記", "風吹過東邊的門", "夢裡還有你的聲音", "愛是一個長長的夢", "沒有人聽見我的心")
        val other = listOf("山上的雪很白", "河水向东流去", "孩子在门口等着", "妈妈做好了饭菜", "天黑了快回家", "明天又是新的一天")
        assertTrue(SongMatch.linesInCommon(words(simp), lines(trad))!! >= 0.9)
        assertTrue(SongMatch.linesInCommon(words(other), lines(trad))!! < SongMatch.MIN_LINES_IN_COMMON)
    }

    @Test fun anotherSongsWordLyricsAreDropped() = runBlocking {
        val track = Song("otherWords1", "Test Song", listOf(ArtistRef("Test Artist")), durationSec = 60)
        val later = 2_000_000_000_000L
        fun s(ms: Long) = "%d.%03d".format(ms / 1000, ms % 1000)
        val ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" itunes:timing=\"Word\"><body><div>" + words(other).joinToString("") { l ->
            "<p begin=\"${s(l.timeMs)}\" end=\"${s(l.endMs)}\">" + l.words.joinToString(" ") { w -> "<span begin=\"${s(w.startMs)}\" end=\"${s(w.endMs)}\">${w.text.trim()}</span>" } + "</p>"
        } + "</div></body></tt>"
        val lrc = lines(texts).joinToString("\n") { l -> "[%02d:%02d.%02d]%s".format(l.timeMs / 60_000, l.timeMs / 1000 % 60, l.timeMs % 1000 / 10, l.text) }
        val rows = listOf(
            LyricsEntity(track.id, LyricsSource.APPLE.name, true, ttml, later),
            LyricsEntity(track.id, LyricsSource.LRCLIB.name, true, lrc, later),
        )
        val dao = object : LyricsDao {
            override suspend fun get(songId: String, source: String) = rows.firstOrNull { it.songId == songId && it.source == source }
            override suspend fun put(e: LyricsEntity) {}
            override suspend fun clearMisses() {}
            override suspend fun allFor(songId: String) = rows.filter { it.songId == songId }
        }
        val st = AppSettings(lyrics = com.vinz.music.data.prefs.LyricsPrefs(order = listOf(LyricsSource.APPLE, LyricsSource.LRCLIB)), preferSynced = true)
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lyrics_other_words_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val r = LyricsRepository(OkHttpClient(), dao, YouTubeMusic(InnerTube(OkHttpClient(), { st })), { st }).apply { wordShifts = prefs }
        val got = r.auto(track)!!
        assertEquals("the line-timed lyrics show, not the other song's words", LyricsSource.LRCLIB, got.source)
        assertNull(r.fetch(track, LyricsSource.APPLE))
        assertEquals(LyricsSource.LRCLIB, r.savedOnly(track)!!.source)
    }
}
