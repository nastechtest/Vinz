package com.vinz.music

import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.OpticalSizeValue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324: "Regular glass looks clear; Clear glass doesn't really let more through." The settings only said "Regular is
 * fuller, Clear lets more through". What each draws (ui/theme/Glass.kt): Regular is Haze's regular glass, which
 * blurs what's behind it; Clear is Haze's clear glass, which keeps it sharp under a light tint. The words now say so,
 * and this keeps them true if a newer Haze changes its styles.
 */
class GlassKindsTest {
    /** Read every authored blur radius through Haze's public API, not internal getters or toString output. */
    private fun blurs(optics: GlassOptics): List<Float> = when (val radius = optics.blurRadius) {
        is OpticalSizeValue.Fixed -> listOf(radius.value.value)
        is OpticalSizeValue.Responsive -> radius.points.map { it.value.value }
        else -> error("Unsupported glass blur radius: $radius")
    }

    @Test fun clearKeepsWhatsBehindSharpAndRegularBlursIt() {
        val regular = blurs(GlassOptics.regular)
        val clear = blurs(GlassOptics.clear)
        assertTrue("Clear barely blurs ($clear dp)", clear.max() <= 2f)
        assertTrue("Regular blurs ($regular dp)", regular.min() >= 10f)
    }

    @Test fun theSettingSaysWhatEachDoes() {
        val info = tr(R.string.glass_kinds_info)
        assertTrue(info, info.contains("Regular blurs") && info.contains("Clear keeps it sharp"))
    }
}
