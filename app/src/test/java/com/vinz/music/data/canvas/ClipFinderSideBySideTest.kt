package com.vinz.music.data.canvas

import com.vinz.music.data.Heatmap
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 1.8.7: the finder asks what needs only the video's id side by side (its type with its player answer, its "most
 * replayed" curve with its storyboard's sheets) and still looks at the answers in the old order, so the rows are the
 * same. Each fake answer here waits until its partner has been asked: asked one after another, they would never come.
 */
class ClipFinderSideBySideTest {
    private val song = Song("song1", "Dai Dai", listOf(ArtistRef("Shakira")), durationSec = 240)
    private fun moving(n: Int) = List(n) { i -> Grid(IntArray(Grid.W * Grid.H) { c -> 60 + ((c * 7 + i * 37) % 120) }) }
    private fun spec(id: String) = "https://i.ytimg.com/sb/$id/storyboard3_L\$L/\$N.jpg?sqp=x|48#27#100#10#10#0#default#rs\$a|160#90#120#5#5#2000#M\$M#rs\$b"

    /** Every answer waits for its partner's question; [type], [playerFails] and [heat] shape the video. */
    private class PairedNet(
        val type: String? = "MUSIC_VIDEO_TYPE_OMV", val playerFails: Boolean = false, val heat: Heatmap? = null,
        val grids: List<Grid>, val spec: String,
    ) : ClipFinder.Net {
        val playerAsked = CompletableDeferred<Unit>()
        val typeAsked = CompletableDeferred<Unit>()
        val heatAsked = CompletableDeferred<Unit>()
        val framesAsked = CompletableDeferred<Unit>()
        override suspend fun findVideo(song: Song) = "v1"
        override suspend fun videoType(videoId: String): String? { typeAsked.complete(Unit); playerAsked.await(); return type }
        override suspend fun player(videoId: String): PlayerInfo {
            playerAsked.complete(Unit); typeAsked.await()
            if (playerFails) error("no answer")
            return PlayerInfo("OK", "Shakira - Dai Dai (Official Video)", 240_000, storyboardSpec = spec)
        }
        override suspend fun frames(board: Storyboard): ClipFinder.Frames { framesAsked.complete(Unit); heatAsked.await(); return ClipFinder.Frames(grids, 1234) }
        override suspend fun heatmap(videoId: String): Heatmap? { heatAsked.complete(Unit); framesAsked.await(); return heat }
    }

    @Test fun theTypeAndThePlayerAnswerAndTheCurveAndTheSheetsAreAskedSideBySide() = runBlocking {
        val net = PairedNet(grids = moving(120), spec = spec("v1"))
        val row = withTimeout(5_000) { ClipFinder(net).find(song, null, 1080, now = 5) }
        assertEquals("v1", row.videoId)
        assertTrue(row.endMs > row.startMs)
    }

    @Test fun theRowIsTheSameAsAskedOneAfterAnother() = runBlocking {
        // The same video on a network that answers at once, one question at a time: the same row.
        val heat = Heatmap(2_400, List(100) { i -> if (i in 60..64) 1f else 0.1f })
        val paired = withTimeout(5_000) { ClipFinder(PairedNet(heat = heat, grids = moving(120), spec = spec("v1"))).find(song, null, 1080, now = 5) }
        val plain = ClipFinder(object : ClipFinder.Net {
            override suspend fun findVideo(song: Song) = "v1"
            override suspend fun videoType(videoId: String) = "MUSIC_VIDEO_TYPE_OMV"
            override suspend fun player(videoId: String) = PlayerInfo("OK", "Shakira - Dai Dai (Official Video)", 240_000, storyboardSpec = spec("v1"))
            override suspend fun frames(board: Storyboard) = ClipFinder.Frames(moving(120), 1234)
            override suspend fun heatmap(videoId: String) = heat
        }).find(song, null, 1080, now = 5)
        assertEquals(plain, paired)
    }

    @Test fun aVideoRefusedForItsTypeIsNotFailedByItsPlayerAnswer() = runBlocking {
        // The song's video is not marked official; the other one searched is not either, and its player answer fails.
        // As before, that other video's "no" is for its type (its answer was never needed), and the song's own video
        // is taken as it is.
        val net = object : ClipFinder.Net {
            override suspend fun findVideo(song: Song) = "v1"
            override suspend fun otherVideos(song: Song, not: Set<String>) = listOf("o1")
            override suspend fun videoType(videoId: String) = "MUSIC_VIDEO_TYPE_UGC"
            override suspend fun player(videoId: String): PlayerInfo =
                if (videoId == "o1") error("no answer") else PlayerInfo("OK", "Shakira - Dai Dai (Official Video)", 240_000, storyboardSpec = spec(videoId))
            override suspend fun frames(board: Storyboard) = ClipFinder.Frames(moving(120), 1234)
            override suspend fun heatmap(videoId: String): Heatmap? = null
        }
        val row = ClipFinder(net).find(song, null, 1080, now = 5)
        assertEquals("v1", row.videoId)
    }

    @Test fun aPlayerAnswerThatFailsStillFailsTheFind() = runBlocking {
        try {
            withTimeout(5_000) { ClipFinder(PairedNet(playerFails = true, grids = moving(120), spec = spec("v1"))).find(song, null, 1080, now = 5) }
            fail("a network failure is thrown, so the old row stays")
        } catch (e: IllegalStateException) {
            assertEquals("no answer", e.message)
        }
    }
}
