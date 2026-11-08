package com.vinz.music

import com.vinz.music.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class LyricsGapProvenanceTest {
    @Test fun `lrc explicit blank retains timing and lrc blank provenance`() {
        val gap = Lrc.parse("[00:00.00]start\n[00:10.00]\n[00:20.00]end").single { it.isGap }
        assertEquals(10_000L, gap.timeMs); assertEquals(20_000L, gap.endMs); assertEquals(GapProvenance.LRC_BLANK, gap.gapProvenance)
    }
    @Test fun `lrc inferred intro retains timing and is not verified`() {
        val gap = Lrc.parse("[00:10.00]first line").first()
        assertEquals(0L, gap.timeMs); assertEquals(10_000L, gap.endMs); assertEquals(GapProvenance.INFERRED_INTRO, gap.gapProvenance); assertNotEquals(GapProvenance.VERIFIED_INSTRUMENTAL, gap.gapProvenance)
    }
    @Test fun `ttml inferred timing gap retains timing and is not verified`() {
        val lines = Ttml.parse("<tt><body><p begin=\"0s\" end=\"2s\">one</p><p begin=\"10s\" end=\"12s\">two</p></body></tt>")
        val gap = lines.single { it.isGap }
        assertEquals(2_000L, gap.timeMs); assertEquals(10_000L, gap.endMs); assertEquals(GapProvenance.INFERRED_TIMING, gap.gapProvenance)
    }
    @Test fun `word format inferred timing gap retains timing and is not verified`() {
        val lines = WordFormat.parse(WordFormat.Kind.QRC, "[0,1000]one(0,1000)\n[8000,1000]two(8000,1000)")
        val gap = lines.single { it.isGap }
        assertEquals(1_000L, gap.timeMs); assertEquals(8_000L, gap.endMs); assertEquals(GapProvenance.INFERRED_TIMING, gap.gapProvenance)
    }
    @Test fun `plain blank lines remain unknown not verified`() { assertEquals(GapProvenance.UNKNOWN, Lrc.plainLines("one\n\ntwo").single { it.isGap }.gapProvenance) }
    @Test fun `no parser gap is verified`() {
        val lrc = Lrc.parse("[00:10.00]line"); val ttml = Ttml.parse("<tt><body><p begin=\"0s\" end=\"1s\">a</p><p begin=\"8s\" end=\"9s\">b</p></body></tt>")
        assertTrue((lrc + ttml).none { it.gapProvenance == GapProvenance.VERIFIED_INSTRUMENTAL })
    }
    @Test fun `inferred parser gaps cannot schedule a credit`() {
        val lines = Ttml.parse("<tt><body><p begin=\"0s\" end=\"1s\">a</p><p begin=\"8s\" end=\"9s\">b</p></body></tt>")
        assertTrue(BrandCreditSchedule.create("id", lines).slots.isEmpty())
    }
}
