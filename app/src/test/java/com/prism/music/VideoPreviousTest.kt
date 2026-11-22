package com.vinz.music

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.Song
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.playback.replaceKeepingOrder
import com.vinz.music.playback.toMediaItem
import com.vinz.music.playback.videoModeId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #455 (1.8.4.1): a song switched to its music video, then Next, then Previous: Previous goes back to that song. It only
 * started the playing one over, however often it was tapped. On a real player, with the swaps the playback service
 * makes: the song becomes its video in place, and once the next one plays the video goes back to the song, so returning
 * to it doesn't land in its video.
 */
@RunWith(RobolectricTestRunner::class)
class VideoPreviousTest {
    private val app get() = ApplicationProvider.getApplicationContext<VinzApp>()
    private lateinit var player: ExoPlayer
    private val songs = (0 until 5).map { Song("s$it", "Song $it") }

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        // As the playback service sets it up.
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        player.setMediaItems(songs.map { it.toMediaItem() }, 0, 0)
    }

    @After fun tearDown() = player.release()

    /**
     * What the playback service does to swap a queue item for another form of the same song. It was Media3's own
     * replaceMediaItem: shuffled, the song watched as its video then went to the end of the play order (Previous had
     * nothing before the playing song, or the wrong one).
     */
    private fun swap(index: Int, item: MediaItem) = player.replaceKeepingOrder(index, item)

    /** Video on the playing song, Next, the video back to its song (as the service does on the move), then Previous. */
    private fun videoNextPrevious() {
        val first = player.currentMediaItemIndex
        swap(first, songs[first].toMediaItem("v$first"))
        assertEquals("the song plays as its video", "v$first", player.currentMediaItem?.videoModeId)

        player.seekToNext()
        val second = player.currentMediaItemIndex
        assertTrue("Next moved on", second != first)
        swap(first, songs[first].toMediaItem(null))

        assertEquals("Previous goes back to the song that played as its video", first, player.previousMediaItemIndex)
        player.seekToPrevious()
        assertEquals("Previous went back a song, not to the start of this one", "s$first", player.currentMediaItem?.mediaId)
        assertEquals("it's back as the song", null, player.currentMediaItem?.videoModeId)
    }

    @Test fun shuffledPreviousGoesBackToTheSongWatchedAsItsVideo() {
        player.shuffleModeEnabled = true
        // Shuffled: 0 plays first, then 3.
        player.setShuffleOrder(HistoryShuffleOrder(intArrayOf(0, 3, 1, 4, 2)) { player.currentMediaItemIndex })
        videoNextPrevious()
        assertEquals("the play order is as it was", listOf(0, 3, 1, 4, 2), order())
    }

    @Test fun shuffledFromTheMiddleOfTheOrder() {
        player.shuffleModeEnabled = true
        player.setShuffleOrder(HistoryShuffleOrder(intArrayOf(2, 4, 0, 1, 3)) { player.currentMediaItemIndex })
        player.seekTo(4, 0)
        videoNextPrevious()
        assertEquals(listOf(2, 4, 0, 1, 3), order())
    }

    /** "Keep playing videos" lines the next song up as its video: it stays the next one, even as the queue's last. */
    @Test fun shuffledTheNextSongLinedUpAsItsVideoStaysNext() {
        player.shuffleModeEnabled = true
        player.setShuffleOrder(HistoryShuffleOrder(intArrayOf(0, 4, 1, 3, 2)) { player.currentMediaItemIndex })
        swap(4, songs[4].toMediaItem("v4"))
        assertEquals(4, player.nextMediaItemIndex)
        assertEquals(listOf(0, 4, 1, 3, 2), order())
    }

    @Test fun inOrderPreviousGoesBackToTheSongWatchedAsItsVideo() {
        player.seekTo(2, 0)
        videoNextPrevious()
    }

    /**
     * 1.8.5: every in-place swap the playback service makes keeps the play order: a live stream lined up as one, a
     * video's cover from its song, and "Songs, not videos" swapping a coming video for its song used Media3's own
     * replaceMediaItem, which sends the item to the end of a shuffled order.
     */
    @Test fun thePlaybackServiceSwapsOnlyWithReplaceKeepingOrder() {
        val source = java.io.File("src/main/java/com/vinz/music/playback/PlaybackService.kt").readText()
        val bare = Regex("""\.replaceMediaItem\(""").findAll(source).map { source.substring(0, it.range.first).count { c -> c == '\n' } + 1 }.toList()
        assertEquals("PlaybackService swaps an item in place with Media3's replaceMediaItem at line(s) $bare", emptyList<Int>(), bare)
    }

    /** The swap keeps the order when the item swapped is one coming up later in a shuffled queue (not the playing one). */
    @Test fun shuffledSwappingALaterSongKeepsItsPlace() {
        player.shuffleModeEnabled = true
        player.setShuffleOrder(HistoryShuffleOrder(intArrayOf(0, 3, 1, 4, 2)) { player.currentMediaItemIndex })
        swap(1, Song("s1song", "Song 1 (the song)").toMediaItem())
        assertEquals(listOf(0, 3, 1, 4, 2), order())
        player.seekToNext(); player.seekToNext()
        assertEquals("s1song", player.currentMediaItem?.mediaId)
        assertEquals(3, player.previousMediaItemIndex)
    }

    private fun order(): List<Int> {
        val t = player.currentTimeline
        return generateSequence(t.getFirstWindowIndex(true).takeIf { it >= 0 }) { i ->
            t.getNextWindowIndex(i, androidx.media3.common.Player.REPEAT_MODE_OFF, true).takeIf { it >= 0 }
        }.take(t.windowCount).toList()
    }
}
