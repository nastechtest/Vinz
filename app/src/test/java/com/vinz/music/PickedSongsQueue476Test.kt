package com.vinz.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.Song
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression coverage for Prism #476, adapted for Vinz's queue implementation. */
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

    @Test fun playNextStartsAllPickedSongsInOrder() {
        c.player.playNext(songs("c", "a", "b"), null)
        assertEquals(listOf("c", "a", "b"), queueIds())
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
        assertEquals(QueueKind.OTHER, c.queue.source.value.kind)
    }

    @Test fun addToQueueStartsAllPickedSongsInOrder() {
        c.player.addToQueue(songs("z", "x", "y"), null)
        assertEquals(listOf("z", "x", "y"), queueIds())
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
        assertEquals(QueueKind.OTHER, c.queue.source.value.kind)
    }

    @Test fun singleSongStillStartsWithItsRadio() {
        c.player.playNext(songs("one"), null)
        assertEquals(listOf("one"), queueIds())
        assertEquals(QueueKind.SINGLE, c.queue.source.value.kind)
        assertEquals("one", c.queue.source.value.seedId)
        player.clearMediaItems()
        c.player.addToQueue(songs("two"), null)
        assertEquals(listOf("two"), queueIds())
        assertEquals(QueueKind.SINGLE, c.queue.source.value.kind)
        assertEquals("two", c.queue.source.value.seedId)
    }

    @Test fun suppliedSourceIsPreservedEvenForOneSong() {
        val source = QueueSource(QueueKind.PLAYLIST, "A playlist", originId = "PLtest")
        c.player.addToQueue(songs("one"), source)
        assertEquals(listOf("one"), queueIds())
        assertEquals(source, c.queue.source.value)
    }

    @Test fun emptySelectionDoesNothing() {
        c.player.playNext(emptyList(), null)
        c.player.addToQueue(emptyList(), null)
        assertTrue(queueIds().isEmpty())
    }

    @Test fun existingQueueStillInsertsNextAndAppends() {
        c.player.playQueue(songs("p0", "p1", "p2"), 0, QueueSource(QueueKind.PLAYLIST, "A playlist", originId = "PLtest"))
        c.player.playNext(songs("n1", "n2"), null)
        assertEquals(listOf("p0", "n1", "n2", "p1", "p2"), queueIds())
        c.player.addToQueue(songs("q1", "q2"), null)
        assertEquals(listOf("p0", "n1", "n2", "p1", "p2", "q1", "q2"), queueIds())
        assertEquals(QueueKind.PLAYLIST, c.queue.source.value.kind)
    }
}
