package com.vinz.music

import com.vinz.music.data.lyrics.BrandCreditSchedule
import com.vinz.music.data.lyrics.GapProvenance
import com.vinz.music.data.lyrics.LyricLine
import org.junit.Assert.*
import org.junit.Test

class BrandCreditBoundaryTest {
    private fun gap(s: Long, e: Long) = LyricLine(s, "", e, isGap = true, gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)
    @Test fun `start boundary is active`() { val x = BrandCreditSchedule.create("id", listOf(gap(0, 6_000))).slots.single(); assertNotNull(BrandCreditSchedule.create("id", listOf(gap(0, 6_000))).activeAt("id", x.startMs)) }
    @Test fun `end boundary is exclusive`() { val s = BrandCreditSchedule.create("id", listOf(gap(0, 6_000))); assertNull(s.activeAt("id", s.slots.single().endMs)) }
    @Test fun `one millisecond before end is active`() { val s = BrandCreditSchedule.create("id", listOf(gap(0, 6_000))); assertNotNull(s.activeAt("id", s.slots.single().endMs - 1)) }
    @Test fun `negative position is inactive`() { assertNull(BrandCreditSchedule.create("id", listOf(gap(0, 6_000))).activeAt("id", -1)) }
    @Test fun `wrong song identity is inactive`() { assertNull(BrandCreditSchedule.create("id", listOf(gap(0, 6_000))).activeAt("other", 2_000)) }
    @Test fun `blank song id creates no slots`() { assertTrue(BrandCreditSchedule.create(" ", listOf(gap(0, 6_000))).slots.isEmpty()) }
    @Test fun `near max value gap stays bounded`() { val end = Long.MAX_VALUE; val s = BrandCreditSchedule.create("id", listOf(gap(end - 8_000, end))).slots.single(); assertTrue(s.startMs >= s.gapStartMs); assertTrue(s.endMs <= s.gapEndMs); assertEquals(4_000L, s.endMs - s.startMs) }
}
