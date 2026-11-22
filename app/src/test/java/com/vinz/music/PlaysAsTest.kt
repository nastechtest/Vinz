package com.vinz.music

import androidx.media3.exoplayer.offline.Download
import com.vinz.music.data.SongVersions
import com.vinz.music.data.meta.PairSource
import com.vinz.music.data.meta.PlaysAs
import com.vinz.music.data.meta.PlaysAsEntry
import com.vinz.music.data.meta.PlaysAsRules
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.NextResult
import com.vinz.music.data.model.Song
import com.vinz.music.download.DownloadInfo
import com.vinz.music.download.forRow
import com.vinz.music.download.rowDownloaded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 1.8.7 §3.2: one answer to "what plays for this row" ([PlaysAs]). For one row every reader gives the same answer, a
 * pairing learned after a download marks the row downloaded (#315's open part), and the listener's pick always wins.
 */
class PlaysAsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val video = Song("vidV0000001", "Night Drive (Official Video)", listOf(ArtistRef("TheBandVEVO")), null, 250, "https://i.ytimg.com/vi/vidV0000001/hq.jpg", isVideo = true)
    private val song = Song("sngS0000001", "Night Drive", listOf(ArtistRef("The Band", "UCband")), AlbumRef("Roads", "MPREb_roads"), 212, "https://lh3.googleusercontent.com/roads")
    private val other = Song("sngS0000002", "Night Drive (Live)", listOf(ArtistRef("The Band")), AlbumRef("Live", "MPREb_live"), 260, "https://lh3.googleusercontent.com/live")
    private val pick = SongVersions.Pick("pckP0000001", "Someone · Covers · 3:30", Song("pckP0000001", "Night Drive (Cover)", listOf(ArtistRef("Someone")), AlbumRef("Covers"), 210, "https://lh3/cover"))

    private var clock = 1_000L
    private fun plays(file: java.io.File? = null, picks: Map<String, SongVersions.Pick> = emptyMap()) =
        PlaysAs(file, CoroutineScope(Job()), pickOf = { picks[it] }, now = { clock++ }, io = Dispatchers.Unconfined, writeDelayMs = 0)

    private fun done(s: Song) = DownloadInfo(s, Download.STATE_COMPLETED, 100f, 1_000)

    @Test fun precedenceStrongerAlwaysWeakerNeverEqualByTime() {
        fun e(source: PairSource, at: Long) = PlaysAsEntry(video.id, song.id, song, source, at)
        val order = listOf(PairSource.DOWNLOAD, PairSource.ALBUM_PAGE, PairSource.VIDEO_MATCH, PairSource.COVER)
        for ((i, strong) in order.withIndex()) for (weak in order.drop(i + 1)) {
            assertTrue("$strong over $weak", PlaysAsRules.replaces(e(weak, 5), e(strong, 1)))
            assertFalse("$weak never over $strong, even newer", PlaysAsRules.replaces(e(strong, 1), e(weak, 9)))
        }
        // YouTube's own pairings are equally strong: the newer one stands.
        assertTrue(PlaysAsRules.replaces(e(PairSource.ALBUM_PAGE, 1), e(PairSource.RADIO, 2)))
        assertFalse(PlaysAsRules.replaces(e(PairSource.PLAYLIST, 2), e(PairSource.RADIO, 1)))
        assertTrue(PlaysAsRules.replaces(null, e(PairSource.COVER, 0)))
        assertTrue("the listener's pick is the strongest of all", PairSource.entries.all { it == PairSource.LISTENER_PICK || it.strength < PairSource.LISTENER_PICK.strength })
    }

    /** The test the plan asks for: one row, every reader, one answer. */
    @Test fun forOneRowEveryReaderGivesTheSameAnswer() = runBlocking {
        for (teach in listOf<(PlaysAs) -> Unit>(
            { it.learn(video.id, song, PairSource.ALBUM_PAGE) },
            { it.learn(video.id, song, PairSource.PLAYLIST) },
            { it.learn(video.id, song, PairSource.RADIO) },
            { it.learn(video.id, song, PairSource.VIDEO_MATCH) },
            { it.setDownloaded(mapOf(video.id to song.id)) { id -> song.takeIf { it.id == id } } },
        )) {
            val p = plays()
            teach(p)
            val a = p.answer(video)
            // Playback: what the tap, the next video in the queue and a download put in the row's place, asked nothing.
            assertEquals(song, p.songForVideo(video) { error("YouTube isn't asked: the pairing is known") })
            assertEquals(song, a.queued)
            assertEquals(song.id, a.sound)
            assertEquals(video.id, a.video)
            // History, likes and the lyrics lookup take the song that goes into the queue; the session's history knows
            // the video by it too.
            assertEquals(song.id, a.queued.id)
            assertEquals(video.id, p.videoOf(song.id))
            // Downloads and the downloaded mark: the row is kept as its song, and shows as downloaded when the song is.
            val kept = p.keptAs(everywhere = true)
            assertEquals(song.id, kept[video.id])
            val downloads = mapOf(song.id to done(song))
            assertEquals(done(song), downloads.forRow(video.id, kept))
            assertTrue(rowDownloaded(video, downloads, kept) { false })
            // Covers: as the song it shows the song; playing as itself (a reader that doesn't swap) it shows the song's cover and album.
            assertEquals(song, a.shown)
            val asItself = p.answer(video, swap = false)
            assertEquals(video.id, asItself.queued.id)
            assertEquals(song.thumbnail, asItself.shown.thumbnail)
            assertEquals(song.album, asItself.shown.album)
            assertEquals(song, p.coverSong(video.id))
        }
    }

    /** #315's open part: the song was downloaded first, the row's pairing came later (an album page, a playlist). */
    @Test fun aPairingLearnedAfterADownloadMarksTheRowDownloaded() {
        val p = plays()
        val downloads = mapOf(song.id to done(song))
        p.setDownloaded(emptyMap()) { null }
        // What the old code had: only stand-ins, written when the row itself was downloaded. Not downloaded.
        assertFalse(rowDownloaded(video, downloads, p.downloaded.value.mapValues { it.value.sound }) { false })
        assertFalse(rowDownloaded(video, downloads, p.keptAs(everywhere = true)) { false })
        p.learn(video.id, song, PairSource.PLAYLIST)
        assertTrue(rowDownloaded(video, downloads, p.keptAs(everywhere = true)) { false })
        // With videos playing as themselves (#390: "Radios"), the row plays, and downloads, its own sound: not the song's.
        assertFalse(rowDownloaded(video, downloads, p.keptAs(everywhere = false)) { false })
        // A row downloaded as its song (a stand-in) counts either way, as it always did.
        p.setDownloaded(mapOf(video.id to song.id)) { song }
        assertTrue(rowDownloaded(video, downloads, p.keptAs(everywhere = false)) { false })
    }

    @Test fun theListenersPickAlwaysWins() = runBlocking {
        for (source in listOf(PairSource.DOWNLOAD, PairSource.ALBUM_PAGE, PairSource.PLAYLIST, PairSource.RADIO, PairSource.VIDEO_MATCH, PairSource.COVER)) {
            val p = plays(picks = mapOf(video.id to pick))
            if (source == PairSource.DOWNLOAD) p.setDownloaded(mapOf(video.id to song.id)) { song } else p.learn(video.id, song, source)
            val a = p.answer(video)
            assertEquals("$source", PairSource.LISTENER_PICK, a.source)
            assertEquals("the row stays itself ($source)", video, a.queued)
            assertEquals("and plays the pick ($source)", pick.id, a.sound)
            assertEquals(pick.song!!.title, a.shown.title)
            assertEquals(pick.song.thumbnail, a.shown.thumbnail)
            assertTrue(p.picked(video.id))
            assertNull("playback doesn't swap it ($source)", p.songFor(video))
            assertNull(p.songForVideo(video) { error("not asked: the pick plays") })
            assertFalse("it downloads as itself ($source)", video.id in p.keptAs(everywhere = true))
            assertEquals(PairSource.LISTENER_PICK, p.answer(video, swap = false).source)
        }
        // A pick for the song a video plays as plays for the video too.
        val p = plays(picks = mapOf(song.id to pick))
        p.learn(video.id, song, PairSource.ALBUM_PAGE)
        val a = p.answer(video)
        assertEquals(song.id, a.queued.id)
        assertEquals(pick.id, a.sound)
        assertEquals(pick.song!!.title, a.shown.title)
    }

    @Test fun aWeakerSourceNeverTakesAStrongerOnesPlace() {
        val p = plays()
        p.learn(video.id, song, PairSource.ALBUM_PAGE)
        p.learn(video.id, other, PairSource.VIDEO_MATCH)
        p.learn(video.id, other, PairSource.COVER)
        assertEquals(song, p.songFor(video))
        // YouTube pairing it again elsewhere (as strong, newer) stands.
        p.learn(video.id, other, PairSource.RADIO)
        assertEquals(other, p.songFor(video))
        // What's downloaded is what plays.
        p.setDownloaded(mapOf(video.id to song.id)) { song }
        assertEquals(song, p.songFor(video))
        assertEquals(PairSource.DOWNLOAD, p.answer(video).source)
        // A stand-in kept before songs were kept with it borrows the learned song when it's the same one, else gives way.
        p.setDownloaded(mapOf(video.id to other.id)) { null }
        assertEquals(other, p.songFor(video))
        // A cover-only pairing never makes the row play as the song.
        val q = plays()
        q.learn(video.id, song, PairSource.COVER)
        assertNull(q.songFor(video))
        assertEquals(video.id, q.answer(video).sound)
        assertEquals(song.thumbnail, q.answer(video).shown.thumbnail)
        assertTrue(q.keptAs(everywhere = true).isEmpty())
        // ...and a found match takes its place.
        q.learn(video.id, song, PairSource.VIDEO_MATCH)
        assertEquals(song, q.songFor(video))
    }

    @Test fun onlyVideosFromYouTubeAreSwapped() {
        val p = plays()
        for (row in listOf(video.copy(isLive = true), video.copy(isEpisode = true), video.copy(isUpload = true), video.copy(isVideo = false))) {
            p.learn(row.id, song, PairSource.ALBUM_PAGE)
            assertEquals(row, p.answer(row).queued)
            assertNull(p.songFor(row))
        }
    }

    @Test fun aMatchFoundIsKeptAndAskedOnce() = runBlocking {
        val p = plays()
        var asked = 0
        assertEquals(song, p.songForVideo(video) { asked++; song })
        assertEquals(song, p.songForVideo(video) { asked++; other })
        assertEquals(1, asked)
        assertEquals(PairSource.VIDEO_MATCH, p.answer(video).source)
        // Nothing found: nothing kept, asked again next time.
        val unknown = video.copy(id = "vidV0000002")
        assertNull(p.songForVideo(unknown) { asked++; null })
        assertNull(p.songForVideo(unknown) { asked++; null })
        assertEquals(3, asked)
    }

    @Test fun keptOnThePhoneAndReadBack() = runBlocking {
        val f = PlaysAs.fileIn(tmp.root)
        val p = plays(f)
        p.learn(video.id, song, PairSource.ALBUM_PAGE)
        p.learn("vidV0000003", other, PairSource.COVER)
        p.setDownloaded(mapOf("vidV0000004" to song.id)) { song }
        p.blocked["sngS0000009"] = "vidV0000009"
        assertTrue(f.exists())
        val again = plays(f)
        again.awaitLoaded()
        assertEquals(song, again.songFor(video))
        assertEquals(other, again.coverSong("vidV0000003"))
        assertNull("stand-ins are the downloads' own (kept with them)", again.entry("vidV0000004"))
        assertTrue("a blocked song's video is for this run only", again.blocked.isEmpty())
        assertEquals(p.learned.value, again.learned.value)
    }

    @Test fun whatDoesntReadIsLeftOutAndOldMatchesAreFoundAgain() = runBlocking {
        val f = PlaysAs.fileIn(tmp.root)
        f.parentFile!!.mkdirs()
        val songJson = com.vinz.music.data.innertube.InnerTube.json.encodeToString(Song.serializer(), song)
        f.writeText(
            """{"entries":[
              {"row":"v1","sound":"${song.id}","song":$songJson,"source":"ALBUM_PAGE","at":1},
              {"row":"v2","sound":"${song.id}","song":$songJson,"source":"VIDEO_MATCH","at":2,"v":0},
              {"row":"v3","sound":"${song.id}","song":$songJson,"source":"VIDEO_MATCH","at":3,"v":${PlaysAs.MATCHER}},
              {"row":"v4","sound":"${song.id}","source":"SOMETHING_NEW","at":4},
              {"row":"v5","sound":"${song.id}","source":"DOWNLOAD","at":5},
              {"nonsense":true}
            ]}""",
        )
        val p = plays(f)
        p.awaitLoaded()
        assertEquals(setOf("v1", "v3"), p.learned.value.keys)
        // A file that doesn't read at all is an empty store, not a crash.
        f.writeText("{not json")
        assertTrue(plays(f).learned.value.isEmpty())
    }

    @Test fun theWeakestAndOldestGoFirstPastTheCap() {
        val p = plays()
        p.learn(video.id, song, PairSource.ALBUM_PAGE)
        val older = (0 until 1_000).map { "v%010d".format(it) to song }
        val newer = (1_000..PlaysAs.MAX).map { "v%010d".format(it) to song }
        p.learnAll(older, PairSource.VIDEO_MATCH)
        p.learnAll(newer, PairSource.VIDEO_MATCH)
        assertTrue(p.learned.value.size <= PlaysAs.MAX)
        assertEquals("YouTube's own pairing stays", song, p.songFor(video))
        assertTrue("the newer matches stay", newer.all { it.first in p.learned.value })
        assertTrue("older ones went", older.any { it.first !in p.learned.value })
    }

    /** Radios and mixes: YouTube's pairings either way round, a video with its song or a song with its video. */
    @Test fun aRadiosPairingsBothWays() {
        val v2 = Song("vidV0000002", "Other (Official Video)", isVideo = true)
        val s2 = Song("sngS0000002", "Other")
        val r = NextResult(
            songs = listOf(video, s2, Song("lone", "Alone")),
            continuation = null, lyricsBrowseId = null,
            counterparts = mapOf(video.id to song.id, s2.id to v2.id),
            counterpartSongs = mapOf(video.id to song, s2.id to v2),
        )
        assertEquals(listOf(video.id to song, v2.id to s2), r.songPairs())
    }
}
