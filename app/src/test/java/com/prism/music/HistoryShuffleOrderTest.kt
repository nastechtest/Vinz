package com.prism.music

import androidx.media3.common.C
import androidx.media3.exoplayer.source.ShuffleOrder
import com.prism.music.playback.HistoryShuffleOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shuffled, Previous goes back to the song played last, whatever is added to the queue meanwhile. */
class HistoryShuffleOrderTest {
    private fun ShuffleOrder.list(): List<Int> =
        generateSequence(getFirstIndex().takeIf { it != C.INDEX_UNSET }) { getNextIndex(it).takeIf { n -> n != C.INDEX_UNSET } }.toList()

    /** Five songs playing 2, 0, 4, 1, 3; the queue's first (0) is playing, so 2 has been heard. */
    private var current = 0
    private val start = HistoryShuffleOrder(intArrayOf(2, 0, 4, 1, 3)) { current }

    /** Shuffled, a song dragged in the queue moves in the play order; what's been heard stays where it is. */
    @Test
    fun aDraggedSongMovesInThePlayOrder() {
        // Playing 0 (2 heard): coming up 4, 1, 3. Drag 3 up above 4, then 4 down below 1.
        val up = start.placed(3, beside = 4, after = false)
        assertEquals(listOf(2, 0, 3, 4, 1), up.list())
        assertEquals(listOf(2, 0, 3, 1, 4), up.placed(4, beside = 1, after = true).list())
        // Previous from the playing song is still the one heard before it.
        assertEquals(2, up.getPreviousIndex(0))
        // Nothing to move beside: unchanged.
        assertEquals(start.list(), start.placed(3, beside = 9, after = true).list())
    }

    @Test
    fun songsAddedAtTheEndGoAfterEverything() {
        val added = start.cloneAndInsert(5, 2)
        assertEquals(listOf(2, 0, 4, 1, 3, 5, 6), added.list())
        // Previous from the playing song is still the one heard before it.
        assertEquals(2, added.getPreviousIndex(0))
    }

    @Test
    fun playNextComesStraightAfterThePlayingSong() {
        // Inserted at 1 (just after 0 in the queue): the queue's later songs move up one.
        val added = HistoryShuffleOrder(intArrayOf(2, 0, 4, 1, 3, 5, 6)) { current }.cloneAndInsert(1, 1)
        assertEquals(listOf(3, 0, 1, 5, 2, 4, 6, 7), added.list())
    }

    @Test
    fun songsInsertedBeforeThePlayingOneGoAtTheEnd() {
        // Inserted at the very start of the queue: everything moves up one, and the order stays.
        val added = start.cloneAndInsert(0, 1)
        assertEquals(listOf(3, 1, 5, 2, 4, 0), added.list())
    }

    @Test
    fun jumpingToASongMakesItNext() {
        val jumped = start.playingNext(0, 3)
        assertEquals(listOf(2, 0, 3, 4, 1), jumped.list())
        assertEquals(0, jumped.getPreviousIndex(3))
    }

    @Test
    fun removingAndMovingKeepTheOrder() {
        assertEquals(listOf(1, 3, 0, 2), start.cloneAndRemove(0, 1).list())
        // The queue's first song moved to the end: 0 becomes 4, the rest move down one.
        assertEquals(listOf(1, 4, 3, 0, 2), start.cloneAndMove(0, 1, 4).list())
    }

    @Test
    fun aNewQueueStartsFromItsFirstSong() {
        val set = start.cloneAndSet(6, 3)
        assertEquals(3, set.getFirstIndex())
        assertEquals((0 until 6).toSet(), set.list().toSet())
    }
}
