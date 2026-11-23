package com.vinz.music.data.canvas

import com.prism.music.data.db.CanvasClipDao
import com.prism.music.data.db.CanvasClipEntity
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.8.7: the wait for a song's loop, step by step from the player asking to the loop's first frame drawn
 * ([CanvasTimes]): every step has its own milliseconds and they add up to the whole wait.
 */
class CanvasTimesTest {
    @get:Rule val tmp = TemporaryFolder()

    private var nowMs = 0L
    private fun times() = CanvasTimes { nowMs * 1_000_000 }

    @Test fun theStepsAddUpToTheWholeWait() {
        val t = times()
        val run = t.asked("s1")
        nowMs = 2_400; run.step("cover")
        nowMs = 2_400; run.step("wait")
        nowMs = 2_410; run.step("find")
        nowMs = 3_200; run.step("fetch"); run.note("fetch", "network: answer 210 ms")
        nowMs = 5_600; run.step("decode")
        nowMs = 5_612; run.step("write")
        t.handed(run, "file:///a.mp4#1-2")
        nowMs = 5_650; t.step("file:///a.mp4#1-2", "handed")
        nowMs = 5_950; t.step("file:///a.mp4#1-2", "ready")
        assertNull("nothing ended yet", t.last)
        nowMs = 5_970; t.shown("file:///a.mp4#1-2")
        assertEquals(
            "5970 ms to shown: cover 2400, wait 0, find 10, fetch 790 (network: answer 210 ms), decode 2400, write 12, handed 38, ready 300, drawn 20",
            t.last,
        )
        // The numbers in the line add up to its total.
        val parts = Regex("(\\w+) (\\d+)(?: \\(|,|$)").findAll(t.last!!.substringAfter(": ")).sumOf { it.groupValues[2].toLong() }
        assertEquals(5_970L, parts)
        // Shown again (the same loop in a second place on the screen): the line stays.
        nowMs = 9_000; t.shown("file:///a.mp4#1-2")
        assertTrue(t.last!!.startsWith("5970 ms"))
    }

    @Test fun aSecondPartTriedAddsToItsStepsAndAnAskBeforeItShowsCarriesOn() {
        val t = times()
        val run = t.asked("s1")
        nowMs = 100; run.step("fetch"); run.note("decode", "close-up")
        nowMs = 1_100; run.step("decode")
        nowMs = 1_300; run.step("fetch")
        nowMs = 2_300; run.step("decode"); run.note("decode", "calm")
        assertSame("asked again before it showed: the same wait", run, t.asked("s1"))
        run.end("no loop")
        assertEquals("2300 ms to no loop: fetch 300 (x2), decode 2000 (x2) (close-up; then calm)", t.last)
        // Ended: a new ask is a new wait; and a run begun over a minute ago is not carried on either.
        val next = t.asked("s1")
        assertTrue(next !== run)
        nowMs += 61_000
        assertTrue(t.asked("s1") !== next)
    }

    // ------------------------------------------------------------------ Through the repository

    private val song = Song("song1", "Dai Dai", listOf(ArtistRef("Shakira")), durationSec = 240)

    private class MemoryDao : CanvasClipDao {
        val rows = LinkedHashMap<String, CanvasClipEntity>()
        override suspend fun get(songId: String) = rows[songId]
        override suspend fun put(e: CanvasClipEntity) { rows[e.songId] = e }
        override suspend fun delete(songId: String) { rows.remove(songId) }
        override suspend fun clearGuesses() { rows.clear() }
        override suspend fun count() = rows.size
    }

    private object NoNet : ClipFinder.Net {
        override suspend fun findVideo(song: Song): String? = error("no network")
        override suspend fun videoType(videoId: String): String? = error("no network")
        override suspend fun player(videoId: String): PlayerInfo = error("no network")
        override suspend fun frames(board: Storyboard): ClipFinder.Frames = error("no network")
        override suspend fun heatmap(videoId: String): com.vinz.music.data.Heatmap? = error("no network")
    }

    @Test fun theRepositoryCountsEachStepOfThePlayersWait() = runBlocking {
        val files = ClipFiles(File(tmp.root, "recent"), File(tmp.root, "saved"), { 100L shl 20 })
        val t = files.temp(false); t.writeBytes(ByteArray(64)); files.keep(t, "v1", 1080, 137, 60_000, 72_000, false, false)
        val dao = MemoryDao().apply {
            rows[song.id] = clipRow(song.id, VideoClip("v1", 60_000, 68_000, 1080), null, null, now = 1, videoType = OFFICIAL, itag = 137, otherParts = listOf(120_000))
        }
        val r = ClipRepository(dao, ClipFinder(NoNet), ClipFetcher(OkHttpClient(), { error("no network in this test") }, files)).apply {
            refiner = ClipRefiner { _, loop -> ClipRefine.Refined(loop.startMs + 7_000, null, false, emptyList(), 0) }
        }
        val run = r.times.asked(song.id)
        val art = r.artworkFor(song, 1080, saved = false, allowed = { false }, run = run)
        assertNotNull(art)
        assertNull("not shown yet", r.lastTimes)
        val sofar = run.line()
        listOf("cover", "wait", "find", "fetch", "decode", "write").forEach { assertTrue("$it in $sofar", Regex("\\b$it \\d+").containsMatchIn(sofar)) }
        assertTrue(sofar, sofar.contains("fetch") && sofar.contains("(kept)"))
        assertTrue(sofar, sofar.contains("(calm)"))
        // The loop's player is handed the address the repository gave, and its first frame ends the wait.
        r.times.step(art!!.url, "handed"); r.times.step(art.url, "ready"); r.times.shown(art.url)
        val line = r.lastTimes!!
        assertTrue(line, Regex("^\\d+ ms to shown: cover \\d+, wait \\d+, find \\d+, fetch \\d+ \\(kept\\), decode \\d+ \\(calm\\), write \\d+, handed \\d+, ready \\d+, drawn \\d+$").matches(line))
        // A song with no loop ends its wait there.
        dao.rows.clear()
        val none = r.times.asked("other")
        assertNull(r.artworkFor(song.copy(id = "other"), 1080, saved = false, allowed = { false }, run = none))
        assertTrue(r.lastTimes!!, r.lastTimes!!.contains("to no loop"))
    }
}
