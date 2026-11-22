package com.vinz.music.ui.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.7.6 (#528): how the stand-still at a loop's restart is read from the frames' times (the in-app test's yardstick). */
class CanvasWrapTest {
    private fun ms(vararg t: Long) = LongArray(t.size) { t[it] * 1_000_000 }

    private fun loop(startMs: Long, endMs: Long, fileStartMs: Long) =
        com.vinz.music.data.canvas.VideoClip("v", startMs = startMs, endMs = endMs, height = 720, refined = true, fileStartMs = fileStartMs)

    /**
     * The A16's loop of the test file: its key frame at 2066.667 ms of the file is kept as 2066 ms. Asked from 2066.000 ms
     * the player started at the key frame before (0.067 ms) and restarted its decoder each time round: the start is asked
     * a little past the millisecond, before the next frame (2100 ms), and the loop is said to start on a key frame.
     */
    @Test fun aLoopIsCutFromItsKeyFrameAndGoesRoundWithoutARestart() {
        val cut = loopCut(loop(startMs = 12_066, endMs = 16_066, fileStartMs = 10_000))
        assertEquals(true, cut.startsAtKeyFrame)
        assertTrue("on or past the key frame (2066.667 ms): ${cut.startUs}", cut.startUs >= 2_066_667)
        assertTrue("before the next frame (2100 ms): ${cut.startUs}", cut.startUs < 2_100_000)
        assertEquals(6_066_000, cut.endUs)
    }

    /** One millisecond either way (the start and the file's start are both rounded down) still finds the key frame. */
    @Test fun aMillisecondOffEitherWayStillLandsOnTheKeyFrame() {
        for (keyUs in listOf(2_066_999L, 2_067_000L, 2_065_001L)) {
            val cut = loopCut(loop(startMs = 2_066, endMs = 6_066, fileStartMs = 0))
            assertTrue("$keyUs: on or past it", cut.startUs >= keyUs)
            assertTrue("$keyUs: before the next frame at 60 a second", cut.startUs < keyUs + 16_666)
        }
    }

    @Test fun aLoopFromTheFilesOwnStartIsNotCut() {
        val cut = loopCut(loop(startMs = 10_000, endMs = 15_000, fileStartMs = 10_000))
        assertEquals(0L, cut.startUs)
        assertEquals(5_000_000L, cut.endUs)
        assertEquals(true, cut.startsAtKeyFrame)
    }

    /**
     * A16, "Flowers" (1.8.7.5's code): the loop ran straight on (frames 56 to 80 ms apart at the restart) but its first
     * frame's word came before the word that it went round, and the kept first frame stood over the moving loop for
     * 1.5 s every time round. Loops that run straight on get no kept frame.
     */
    @Test fun noKeptFrameGoesUpOverALoopThatRunsStraightOn() {
        assertEquals("an animated cover", false, restartNeedsCover(null))
        assertEquals("a loop cut on a key frame", false, restartNeedsCover(loop(startMs = 12_066, endMs = 16_066, fileStartMs = 10_000)))
        assertEquals("a loop from the file's start", false, restartNeedsCover(loop(startMs = 10_000, endMs = 15_000, fileStartMs = 10_000)))
    }

    @Test fun aTinyLoopStillEndsAfterItStarts() {
        val cut = loopCut(loop(startMs = 2_000, endMs = 2_001, fileStartMs = 0))
        assertTrue(cut.endUs > cut.startUs)
    }

    @Test fun theLongestGapAroundEachRestartAgainstTheUsualOne() {
        // 30 frames a second, a 400 ms hole at the restart at 2 s, an even run elsewhere.
        val frames = (0L..1_960L step 33).toList() + (2_360L..5_000L step 33).toList()
        val g = wrapGaps(ms(*frames.toLongArray()), ms(2_000), aroundMs = 1_000)
        assertEquals(1, g.atWrapsMs.size)
        assertEquals(2_360.0 - 1_947.0, g.atWrapsMs[0], 0.5)
        assertEquals(33.0, g.typicalMs, 0.5)
        assertEquals(33.0, g.longestElsewhereMs, 0.5)
    }

    @Test fun aRestartTooCloseToTheRecordsEndsIsNotCounted() {
        val frames = ms(*(0L..3_000L step 33).toList().toLongArray())
        assertEquals("a second of frames is wanted on each side", emptyList<Double>(), wrapGaps(frames, ms(500, 2_900), aroundMs = 1_000).atWrapsMs)
        assertEquals(1, wrapGaps(frames, ms(1_500), aroundMs = 1_000).atWrapsMs.size)
    }

    @Test fun framesUnderTheKeptFirstFrameChangeNothingOnScreen() {
        // Frames every 100 ms; the kept frame stands over the video from 250 to 620 ms.
        val frames = ms(0, 100, 200, 300, 400, 500, 600, 700, 800)
        val seen = visibleChanges(frames, listOf(250_000_000L to true, 620_000_000L to false))
        assertArrayEquals(ms(0, 100, 200, 250, 620, 700, 800), seen)
    }

    @Test fun aKeptFrameThatGoesUpAndStaysIsOneChange() {
        val seen = visibleChanges(ms(0, 100, 200, 300), listOf(150_000_000L to true, 160_000_000L to true))
        assertArrayEquals(longArrayOf(0, 100_000_000L, 150_000_000L), seen)
    }
}
