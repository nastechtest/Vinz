package com.vinz.music

import com.vinz.music.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class BrandCreditProvenanceTest {
    private fun gap(provenance: GapProvenance, text: String = "") = LyricLine(10_000, text, 18_000, isGap = true, gapProvenance = provenance)
    @Test fun `verified synthetic gap is eligible`() { assertEquals(1, BrandCreditSchedule.create("id", listOf(gap(GapProvenance.VERIFIED_INSTRUMENTAL))).slots.size) }
    @Test fun `unknown gap is never eligible`() { assertTrue(BrandCreditSchedule.create("id", listOf(gap(GapProvenance.UNKNOWN))).slots.isEmpty()) }
    @Test fun `lrc blank gap is never eligible`() { assertTrue(BrandCreditSchedule.create("id", listOf(gap(GapProvenance.LRC_BLANK))).slots.isEmpty()) }
    @Test fun `inferred intro gap is never eligible`() { assertTrue(BrandCreditSchedule.create("id", listOf(gap(GapProvenance.INFERRED_INTRO))).slots.isEmpty()) }
    @Test fun `inferred timing gap is never eligible`() { assertTrue(BrandCreditSchedule.create("id", listOf(gap(GapProvenance.INFERRED_TIMING))).slots.isEmpty()) }
    @Test fun `verified gap with visible text is never eligible`() { assertTrue(BrandCreditSchedule.create("id", listOf(gap(GapProvenance.VERIFIED_INSTRUMENTAL, "not silent"))).slots.isEmpty()) }
    @Test fun `ordinary lyric line provenance defaults unknown`() { assertEquals(GapProvenance.UNKNOWN, LyricLine(0, "words", 1_000).gapProvenance) }
}
