package com.prism.music.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The light "From the cover" (#123): which of a cover's swatches make a light. */
class CoverLightTest {
    private fun hue(c: Int) = androidx.compose.ui.graphics.Color(c).toHsl().first

    @Test
    fun aGreyCoverGivesNoLight() {
        assertNull(SpectrumPresets.fromCover(listOf(0xFF808080.toInt(), 0xFF202020.toInt(), 0xFFF0F0F0.toInt())))
    }

    @Test
    fun nearlyTheSameColorCountsOnce() {
        // Two reds a few degrees apart and one blue: two stops, not three.
        val stops = SpectrumPresets.fromCover(listOf(0xFFE53935.toInt(), 0xFFE84B3C.toInt(), 0xFF1E88E5.toInt()))
        assertEquals(2, stops?.size)
    }

    @Test
    fun stopsRunInHueOrder() {
        val stops = SpectrumPresets.fromCover(listOf(0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFDD835.toInt()))!!
        val hues = stops.map(::hue)
        assertTrue("in hue order: $hues", hues.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(4, stops.size)
    }

    @Test
    fun theCoversColorsMakeTheLight() {
        SpectrumPresets.coverStops = listOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt())
        val light = SpectrumPresets.light(SpectrumPresets.COVER, emptyList(), bands = false)
        assertTrue(light !== SpectrumPresets.PRISM_LIGHT)
        SpectrumPresets.coverStops = emptyList()
        assertTrue(SpectrumPresets.light(SpectrumPresets.COVER, emptyList(), bands = false) === SpectrumPresets.PRISM_LIGHT)
    }
}
