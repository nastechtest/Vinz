package com.vinz.music

import com.vinz.music.playback.PlayedOut
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.7, plan item 10: which flush is "the next song going on" for the effects that keep their state across it. */
class PlayedOutTest {
    private var t = -5_000_000_000L // the clock's zero is anywhere, also below 0
    private val played = PlayedOut({ t }, withinNanos = 2_000_000_000L)

    @Test fun aFlushWithNothingPlayedOutIsASeek() = assertFalse(played.goesOn())

    @Test fun theFlushRightAfterThePlayingOutGoesOn() {
        played.mark(); t += 3_000_000
        assertTrue(played.goesOn())
    }

    @Test fun aFlushLongAfterTheQueueEndedStartsAfresh() {
        played.mark(); t += 60_000_000_000L
        assertFalse(played.goesOn())
    }

    @Test fun onceUsedItIsASeekAgain() {
        played.mark(); played.clear()
        assertFalse(played.goesOn())
    }
}