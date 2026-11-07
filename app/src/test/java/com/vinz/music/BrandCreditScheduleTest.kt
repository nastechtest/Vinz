package com.vinz.music

import com.vinz.music.data.lyrics.BrandCreditSchedule
import com.vinz.music.data.lyrics.GapProvenance
import com.vinz.music.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandCreditScheduleTest {
    private fun verified(start: Long, end: Long) = LyricLine(start, "", end, isGap = true,
        gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)

    @Test fun `selects three longest eligible gaps`() {
        val slots = BrandCreditSchedule.create("song", listOf(verified(0, 6_000), verified(10_000, 19_000), verified(30_000, 38_000), verified(50_000, 57_000))).slots
        assertEquals(listOf(10_000L, 30_000L, 50_000L), slots.map { it.gapStartMs })
    }

    @Test fun `ties are selected chronologically independent of source order`() {
        val slots = BrandCreditSchedule.create("song", listOf(verified(30_000, 38_000), verified(10_000, 18_000), verified(20_000, 28_000), verified(40_000, 48_000))).slots
        assertEquals(listOf(10_000L, 20_000L, 30_000L), slots.map { it.gapStartMs })
    }

    @Test fun `duplicate gaps create one slot`() {
        val slots = BrandCreditSchedule.create("song", listOf(verified(10_000, 18_000), verified(10_000, 18_000))).slots
        assertEquals(1, slots.size)
    }

    @Test fun `competing overlapping gaps create at most one slot`() {
        val slots = BrandCreditSchedule.create("song", listOf(verified(10_000, 20_000), verified(12_000, 21_000), verified(30_000, 38_000))).slots
        assertEquals(2, slots.size)
        assertEquals(listOf(10_000L, 30_000L), slots.map { it.gapStartMs })
    }

    @Test fun `six second gap is eligible`() {
        assertEquals(1, BrandCreditSchedule.create("song", listOf(verified(0, 6_000))).slots.size)
    }

    @Test fun `gap shorter than six seconds is rejected`() {
        assertTrue(BrandCreditSchedule.create("song", listOf(verified(0, 5_999))).slots.isEmpty())
    }

    @Test fun `slot is four seconds with at least five hundred millisecond margins`() {
        val slot = BrandCreditSchedule.create("song", listOf(verified(1_000, 9_000))).slots.single()
        assertEquals(4_000L, slot.endMs - slot.startMs)
        assertTrue(slot.startMs - slot.gapStartMs >= 500L)
        assertTrue(slot.gapEndMs - slot.endMs >= 500L)
    }

    @Test fun `input order and values are not mutated`() {
        val lines = mutableListOf(verified(20_000, 28_000), verified(1_000, 9_000))
        val original = lines.toList()
        BrandCreditSchedule.create("song", lines)
        assertEquals(original, lines)
    }
}
