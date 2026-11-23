package com.vinz.music

import com.vinz.music.data.lyrics.LyricLine
import com.vinz.music.data.lyrics.LyricWord
import com.vinz.music.ui.player.sungChars
import com.vinz.music.ui.player.sweepStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.7: a word-timed line is drawn again only when its singing point moved. The value the drawing reads stands still
 * before the line, in the rests between words and once the line is sung, and moves while a word is sung.
 */
class SweepStepTest {
    private val line = LyricLine(
        1_000, "Kinda dream",
        words = listOf(LyricWord(1_000, 1_400, "Kinda "), LyricWord(2_000, 2_600, "dream")),
    )
    private val offsets = listOf(0 to 6, 6 to 11)
    private fun at(ms: Long) = sweepStep(sungChars(line, offsets, ms))
    private fun distinct(from: Long, to: Long) = (from..to step 11).map(::at).distinct().size

    @Test fun itStandsStillWhereNothingIsSung() {
        assertEquals("before the line", 1, distinct(0, 999))
        assertEquals("the rest between two words", 1, distinct(1_400, 1_999))
        assertEquals("after the line", 1, distinct(2_600, 60_000))
        assertEquals(0f, at(500))
        assertEquals(6f, at(1_700))
        assertEquals(11f, at(9_000))
    }

    /** The line on the player moves about 30 times a second, staying with the song. */
    @Test fun theCalmClockKeepsWithTheSong() {
        // The first reading, and a seek: taken as they are.
        assertEquals(5_000.0, com.vinz.music.ui.player.calmClock(0.0, 5_000, -1.0, 1f), 0.0)
        assertEquals(90_000.0, com.vinz.music.ui.player.calmClock(5_000.0, 90_000, 33.0, 1f), 0.0)
        // The player's own place comes in uneven steps (here: stuck for 100 ms, then caught up): the clock glides on,
        // never further than a few ms from where the song is, and never backwards.
        var shown = 10_000.0; var real = 10_000.0; var worst = 0.0
        repeat(300) { i ->
            real += 33.0
            val reported = if (i % 6 < 3) (real - (i % 3) * 33.0).toLong() else real.toLong()
            val next = com.vinz.music.ui.player.calmClock(shown, reported, 33.0, 1f)
            assertTrue("never backwards", next >= shown)
            shown = next
            worst = maxOf(worst, kotlin.math.abs(shown - real))
        }
        assertTrue("within 40 ms of the song, was $worst", worst < 40.0)
        // At 1.5x it moves 1.5 times as fast.
        assertEquals(1_049.5, com.vinz.music.ui.player.calmClock(1_000.0, 1_050, 33.0, 1.5f), 0.2)
        assertEquals(33L, com.vinz.music.ui.player.CALM_LYRIC_MS)
    }

    @Test fun itMovesWhileAWordIsSung() {
        // One frame apart (11 ms at 90 Hz) inside a word: a new picture nearly every frame.
        assertTrue(distinct(1_000, 1_399) > 30)
        assertTrue(at(1_200) > at(1_100))
        // Steps finer than a pixel: 1/64 of a letter.
        assertEquals(0f, (at(1_133) * 64) % 1f)
    }
}
