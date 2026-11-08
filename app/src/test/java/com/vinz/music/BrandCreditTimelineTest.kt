package com.vinz.music

import com.vinz.music.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class BrandCreditTimelineTest {
    private fun gap(s: Long, e: Long) = LyricLine(s, "", e, isGap = true, gapProvenance = GapProvenance.VERIFIED_INSTRUMENTAL)
    @Test fun `seek into a slot selects it`() { val s = BrandCreditSchedule.create("track", listOf(gap(0, 6_000))); val slot = s.slots.single(); assertEquals(slot, s.activeAt("track", slot.startMs + 100)) }
    @Test fun `seek outside a slot is inactive`() { val s = BrandCreditSchedule.create("track", listOf(gap(0, 6_000))); assertNull(s.activeAt("track", 100)) }
    @Test fun `repeating same position yields same slot`() { val s = BrandCreditSchedule.create("track", listOf(gap(0, 6_000))); val p = s.slots.single().startMs; assertEquals(s.activeAt("track", p), s.activeAt("track", p)) }
    @Test fun `schedule is recording scoped`() { val s = BrandCreditSchedule.create("one", listOf(gap(0, 6_000))); assertNull(s.activeAt("two", s.slots.single().startMs)) }
    @Test fun `slots are chronological after longest first selection`() { val s = BrandCreditSchedule.create("track", listOf(gap(30_000, 39_000), gap(0, 8_000), gap(15_000, 23_000))); assertEquals(listOf(0L, 15_000L, 30_000L), s.slots.map { it.gapStartMs }) }
    @Test fun `non overlapping slots never overlap`() { val s = BrandCreditSchedule.create("track", listOf(gap(0, 8_000), gap(10_000, 18_000))); assertTrue(s.slots.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) }
    @Test fun `selected slots cannot be cleared through a mutable cast`() {
        val schedule = BrandCreditSchedule.create("track", listOf(gap(0, 6_000)))
        val error = runCatching { (schedule.slots as MutableList).clear() }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals(1, schedule.slots.size)
    }
    @Test fun `changed lyric evidence requires a fresh schedule`() {
        val previous = BrandCreditSchedule.create("track", listOf(gap(0, 6_000)))
        val revised = BrandCreditSchedule.create("track", listOf(gap(0, 6_000), LyricLine(2_000, "voice", 3_000)))
        assertEquals(1, previous.slots.size)
        assertTrue(revised.slots.isEmpty())
    }
    @Test fun `new song builds its own schedule`() {
        val first = BrandCreditSchedule.create("first", listOf(gap(0, 6_000)))
        val second = BrandCreditSchedule.create("second", listOf(gap(10_000, 16_000)))
        assertNull(first.activeAt("second", first.slots.single().startMs))
        assertEquals(second.slots.single(), second.activeAt("second", second.slots.single().startMs))
    }
    @Test fun `schedule snapshots caller list`() { val lines = mutableListOf(gap(0, 6_000)); val s = BrandCreditSchedule.create("track", lines); lines.clear(); assertEquals(1, s.slots.size) }
}
