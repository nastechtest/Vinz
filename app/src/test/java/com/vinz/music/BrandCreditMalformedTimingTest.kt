package com.vinz.music

import com.vinz.music.data.lyrics.*
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandCreditMalformedTimingTest {
    private fun gap(s: Long = 10_000, e: Long = 18_000) = LyricLine(s, "", e, isGap = true, gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)
    private fun slots(vararg lines: LyricLine) = BrandCreditSchedule.create("id", lines.toList()).slots
    @Test fun `negative foreground start invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(-1, "bad", 2)).isEmpty()) }
    @Test fun `zero length foreground invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(2, "bad", 2)).isEmpty()) }
    @Test fun `reversed foreground invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(3, "bad", 2)).isEmpty()) }
    @Test fun `negative word invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(1, "line", 2, words = listOf(LyricWord(-1, 1, "bad")))).isEmpty()) }
    @Test fun `zero length word invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(1, "line", 2, words = listOf(LyricWord(1, 1, "bad")))).isEmpty()) }
    @Test fun `reversed background word invalidates entire schedule`() { assertTrue(slots(gap(), LyricLine(1, "line", 2, background = listOf(LyricWord(4, 3, "bad")))).isEmpty()) }
    @Test fun `missing foreground end invalidates entire schedule`() {
        assertTrue(slots(gap(), LyricLine(2, "missing")).isEmpty())
    }
    @Test fun `missing gap end fails safely`() {
        assertTrue(slots(LyricLine(10_000, "", isGap = true,
            gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)).isEmpty())
    }
    @Test fun `malformed text bearing gap invalidates entire schedule`() {
        assertTrue(slots(gap(), LyricLine(3, "voice", 2, isGap = true)).isEmpty())
    }
    @Test fun `malformed gap itself is skipped`() { assertTrue(slots(gap(18_000, 10_000)).isEmpty()) }
    @Test fun `one malformed gap does not discard valid gap`() { assertTrue(slots(gap(18_000, 10_000), gap(30_000, 38_000)).isNotEmpty()) }
}
