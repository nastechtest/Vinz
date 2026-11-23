package com.vinz.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.Song
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #476 (1.8.7.5), on the real [com.vinz.music.playback.PlayerConnection] with a real player: songs picked with Select
 * many (the selection bar gives no queue source) and sent with Play next or Add to queue while nothing plays become the
 * queue, in the picked order, and start; it used to play the first picked song only. With something playing, as before.
 */
@RunWith(RobolectricTestRunner::class)
class PickedSongsQueue476Test {
    private val app get() = ApplicationProvider.getApplicationContext<VinzApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    private fun songs(vararg ids: String) = ids.map { Song(it, "Song $it") }
    private fun queueIds() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
    }

    @After fun tearDown() {
        c.player.testPlayer = null
        player.release()
    }

    @Test fun playNextWithNothingPlayingQueuesEveryPickedSongInThePickedOrder() {
        c.player.playNext(songs("c", "a", "b"), null)
        assertEquals(listOf("c", "a", "b"), queueIds())
        assertEquals("the first picked song plays", 0, player.currentMediaItemIndex)
        assertEquals(true, player.playWhenReady)
    }

    @Test fun addToQueueWithNothingPlayingQueuesEveryPickedSongInThePickedOrder() {
        c.player.addToQueue(songs("z", "x", "y"), null)
        assertEquals(listOf("z", "x", "y"), queueIds())
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(true, player.playWhenReady)
    }

    @Test fun oneSongWithNothingPlayingStillStartsAsASingleSong() {
        c.player.playNext(songs("one"), null)
        assertEquals(listOf("one"), queueIds())
        assertEquals(QueueKind.SINGLE, c.queue.source.value.kind)
    }

    @Test fun withSomethingPlayingPickedSongsGoInAsBefore() {
        c.player.playQueue(songs("p0", "p1", "p2"), 0, QueueSource(QueueKind.PLAYLIST, "A playlist", originId = "PLtest"))
        c.player.playNext(songs("n1", "n2"), null)
        assertEquals(listOf("p0", "n1", "n2", "p1", "p2"), queueIds())
        c.player.addToQueue(songs("q1", "q2"), null)
        assertEquals(listOf("p0", "n1", "n2", "p1", "p2", "q1", "q2"), queueIds())
        assertEquals("the list it plays from stays", QueueKind.PLAYLIST, c.queue.source.value.kind)
    }
}
