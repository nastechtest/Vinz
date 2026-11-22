package com.vinz.music

import com.vinz.music.ui.screens.settings.IconLine
import com.vinz.music.ui.screens.settings.iconLineLabel
import com.vinz.music.ui.screens.settings.iconLineToMerged
import com.vinz.music.ui.screens.settings.iconLineToStored
import com.vinz.music.ui.screens.settings.iconLines
import com.vinz.music.ui.theme.IconWeight
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, plan §2.5 on Text & icons: icon weight + rounded are one row. Every combination of the two saved values maps
 * to the row's choice and back unchanged, and the row offers every one of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TextIconsMergeTest {
    @Test fun everyWeightAndRoundedCombinationRoundTrips() {
        var combinations = 0
        for (weight in IconWeight.entries) for (rounded in listOf(true, false)) {
            val stored = weight to rounded
            val merged = iconLineToMerged(stored)
            assertEquals("$stored", stored, iconLineToStored(merged))
            assertEquals("the row offers $stored", 1, iconLines.count { it == merged })
            combinations++
        }
        assertEquals(6, combinations)
        // And the other way: every choice the row offers is a stored pair that reads back as itself.
        assertEquals(6, iconLines.size)
        assertEquals(6, iconLines.toSet().size)
        for (line in iconLines) assertEquals(line, iconLineToMerged(iconLineToStored(line)))
    }

    @Test fun everyChoiceReadsDifferently() {
        assertEquals(6, iconLines.map { iconLineLabel(it) }.toSet().size)
        assertEquals("Regular, rounded ends", iconLineLabel(IconLine(IconWeight.REGULAR, true)))
        assertEquals("Thin, square ends", iconLineLabel(IconLine(IconWeight.THIN, false)))
    }
}
