package com.vinz.music

import com.vinz.music.ui.ChromeScrollAccumulator
import com.vinz.music.ui.SplitChromeBounds
import com.vinz.music.ui.SplitChromeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android-free fitting and interpolation guardrails for the phone chrome. */
class ChromeGeometryTest {
    @Test fun scrollProgressIsBoundedAndRejectsNonFiniteInput() {
        val scroll = ChromeScrollAccumulator(travelPx = 72f)
        assertEquals(0.5f, scroll.consume(-36f, enabled = true), 0.0001f)
        assertEquals(1f, scroll.consume(-360f, enabled = true), 0.0001f)
        assertEquals(1f, scroll.consume(Float.NaN, enabled = true), 0.0001f)
        assertEquals(1f, scroll.consume(Float.POSITIVE_INFINITY, enabled = true), 0.0001f)
        assertEquals(0.75f, scroll.consume(18f, enabled = true), 0.0001f)
        assertEquals(0f, scroll.consume(-30f, enabled = false), 0.0001f)
        assertEquals(0f, ChromeScrollAccumulator(Float.NaN).progress, 0.0001f)
    }

    @Test fun fittingSupportsTwoToFiveTabsAndRejectsUnsafeInputs() {
        // NavTab.MAX is six; all normal supported split counts up to five fit at 360 dp.
        for (tabs in 2..5) assertTrue("$tabs tabs", SplitChromeGeometry.from(360f, 1f, tabs).enabled)
        // Five uses two equal 144 dp side footprints, a 64 dp player and two 4 dp gaps.
        assertTrue(SplitChromeGeometry.from(360f, 1.3f, 5).enabled)
        assertFalse(SplitChromeGeometry.from(359f, 1f, 5).enabled)
        assertFalse(SplitChromeGeometry.from(360f, 1.31f, 4).enabled)
        assertFalse(SplitChromeGeometry.from(360f, Float.NaN, 4).enabled)
        assertFalse(SplitChromeGeometry.from(360f, 0f, 4).enabled)
        assertFalse(SplitChromeGeometry.from(0f, 1f, 4).enabled)
        assertFalse(SplitChromeGeometry.from(Float.POSITIVE_INFINITY, 1f, 4).enabled)
        assertFalse(SplitChromeGeometry.from(360f, 1f, 1).enabled)
        assertFalse(SplitChromeGeometry.from(360f, 1f, 7).enabled)
    }

    @Test fun compactOddGroupsAreCentredDisjointAndKeep48DpTargetsAtDensity() {
        val split = SplitChromeGeometry.from(360f, 1f, 5)
        assertTrue(split.enabled)
        assertEquals(2, split.leftTabs)
        assertEquals(3, split.rightTabs)

        // 360 dp at 3x density: every value passed to bounds is pixels.
        val compact = split.bounds(width = 1080, playerHeight = 192, tabHeight = 156, compact = 1f, gap = 12)
        assertEquals(432, compact.left.width) // 3 tab footprints × 48 dp × 3 density
        assertEquals(432, compact.right.width)
        assertEquals(192, compact.player.width) // 64 dp × 3 density
        assertEquals(444, compact.player.x) // exact centre, including odd tab counts
        assertEquals(compact.left.y, compact.player.y)
        assertEquals(compact.right.y, compact.player.y)
        assertNoCompactOverlap(compact)

        // The left group has 144 px of deliberate group padding: parent must keep its two tab
        // targets at 144 px rather than distributing them across the whole 432 px footprint.
        assertEquals(144, compact.left.width - split.leftTabs * 48 * 3)
        assertEquals(0, compact.right.width - split.rightTabs * 48 * 3)
    }

    @Test fun expandedUsesFullWidthByTabCountAndHasVerticalClearance() {
        val split = SplitChromeGeometry.from(360f, 1f, 5)
        val expanded = split.bounds(width = 360, playerHeight = 64, tabHeight = 52, compact = 0f, gap = 4)
        assertEquals(144, expanded.left.width) // 2/5 of available width
        assertEquals(216, expanded.right.width) // 3/5 of available width
        assertEquals(360, expanded.left.width + expanded.right.width)
        assertEquals(360, expanded.player.width)
        assertTrue(expanded.player.y + expanded.player.height <= expanded.left.y)
        assertTrue(expanded.player.y + expanded.player.height <= expanded.right.y)
    }

    @Test fun intermediateAndBadBoundsInputsRemainFiniteAndBounded() {
        val split = SplitChromeGeometry.from(360f, 1f, 4)
        for (progress in listOf(Float.NaN, -1f, .5f, 2f)) {
            val bounds = split.bounds(width = 360, playerHeight = 64, tabHeight = 52, compact = progress, gap = 4)
            assertBoundsAreBounded(bounds, 360)
        }
        val zero = split.bounds(width = -1, playerHeight = -1, tabHeight = -1, compact = Float.NaN, gap = -1)
        assertBoundsAreBounded(zero, 0)
    }

    private fun assertNoCompactOverlap(bounds: SplitChromeBounds) {
        assertTrue(bounds.left.x + bounds.left.width <= bounds.player.x)
        assertTrue(bounds.player.x + bounds.player.width <= bounds.right.x)
    }

    private fun assertBoundsAreBounded(bounds: SplitChromeBounds, width: Int) {
        assertTrue(bounds.hostHeight >= 0)
        listOf(bounds.left, bounds.player, bounds.right).forEach { rect ->
            assertTrue(rect.x >= 0)
            assertTrue(rect.y >= 0)
            assertTrue(rect.width >= 0)
            assertTrue(rect.height >= 0)
            assertTrue(rect.x + rect.width <= width)
            assertTrue(rect.y + rect.height <= bounds.hostHeight)
        }
    }
}
