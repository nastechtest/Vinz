package com.vinz.music

import com.vinz.music.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class BrandCreditVocalSafetyTest {
    private fun gap() = LyricLine(10_000, "", 18_000, isGap = true, gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)
    private fun schedule(vararg lines: LyricLine) = BrandCreditSchedule.create("id", lines.toList()).slots
    @Test fun `foreground line overlapping gap rejects it`() { assertTrue(schedule(gap(), LyricLine(9_000, "voice", 10_001)).isEmpty()) }
    @Test fun `foreground line inside gap rejects it`() { assertTrue(schedule(gap(), LyricLine(12_000, "voice", 13_000)).isEmpty()) }
    @Test fun `foreground line touching gap edge is safe`() { assertEquals(1, schedule(gap(), LyricLine(1_000, "voice", 10_000)).size) }
    @Test fun `word inside gap rejects it even when line range is elsewhere`() { assertTrue(schedule(gap(), LyricLine(1_000, "voice", 2_000, words = listOf(LyricWord(12_000, 13_000, "late")))).isEmpty()) }
    @Test fun `background word inside gap rejects it`() { assertTrue(schedule(gap(), LyricLine(1_000, "voice", 2_000, background = listOf(LyricWord(12_000, 13_000, "back")))).isEmpty()) }
    @Test fun `word extending across gap entry rejects it`() { assertTrue(schedule(gap(), LyricLine(1_000, "voice", 2_000, words = listOf(LyricWord(9_999, 10_001, "cross")))).isEmpty()) }
    @Test fun `background extending across gap exit rejects it`() { assertTrue(schedule(gap(), LyricLine(1_000, "voice", 2_000, background = listOf(LyricWord(17_999, 18_001, "cross")))).isEmpty()) }
    @Test fun `nonblank mislabeled gap is still foreground vocals`() {
        assertTrue(schedule(gap(), LyricLine(12_000, "voice", 13_000, isGap = true)).isEmpty())
    }
    @Test fun `background carried by a gap row blocks another gap`() {
        assertTrue(schedule(gap(), LyricLine(1_000, "", 2_000, isGap = true,
            background = listOf(LyricWord(12_000, 13_000, "back")))).isEmpty())
    }
    @Test fun `gap carrying any words is not eligible`() { assertTrue(schedule(gap().copy(words = listOf(LyricWord(12_000, 13_000, "x")))).isEmpty()) }
}
