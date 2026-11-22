package com.vinz.music

import com.vinz.music.data.prefs.HomeSections
import com.vinz.music.ui.screens.HomeHiddenKind
import com.vinz.music.ui.screens.hideKindsToMerged
import com.vinz.music.ui.screens.hideKindsToStored
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.4, plan §2.11: Customize Home's "Hide from Home" chips (Videos · Podcasts) stand in for the two "leave off"
 * switches over the one stored set (`home_hide_kinds`). Every combination that could be stored is read into the chips
 * and written back unchanged.
 */
class CustomizeHomeMergeTest {
    @Test fun hideFromHomeKeepsEveryCombination() {
        val both = listOf(false, true)
        val seen = HashSet<Set<HomeHiddenKind>>()
        for (videos in both) for (podcasts in both) {
            val stored = setOfNotNull(HomeSections.KIND_VIDEOS.takeIf { videos }, HomeSections.KIND_PODCASTS.takeIf { podcasts })
            val merged = hideKindsToMerged(stored)
            assertEquals("$stored", videos, HomeHiddenKind.VIDEOS in merged)
            assertEquals("$stored", podcasts, HomeHiddenKind.PODCASTS in merged)
            assertEquals("$stored", stored, hideKindsToStored(merged))
            assertEquals("$stored", stored, hideKindsToStored(merged, before = stored))
            seen += merged
        }
        assertEquals(4, seen.size)
    }

    /** The chips are stored under the names the switches used, and a kind they don't know stays in the set. */
    @Test fun theStoredNamesAreTheSwitches() {
        assertEquals(listOf(HomeSections.KIND_VIDEOS, HomeSections.KIND_PODCASTS), HomeHiddenKind.entries.map { it.key })
        assertEquals(setOf("LATER", HomeSections.KIND_PODCASTS), hideKindsToStored(setOf(HomeHiddenKind.PODCASTS), before = setOf("LATER", HomeSections.KIND_VIDEOS)))
        assertEquals(emptySet<HomeHiddenKind>(), hideKindsToMerged(setOf("LATER")))
    }
}
