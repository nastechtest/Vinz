package com.vinz.music

import com.vinz.music.playback.RemoteVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure mapping and key-repeat checks, independent of Android or a cast device. */
class RemoteVolumeMathTest {
    @Test fun everyStepRoundTripsAndClamps() {
        for (step in 0..RemoteVolume.STEPS) {
            assertEquals(step, RemoteVolume.shown(RemoteVolume.level(step)))
        }
        assertEquals(0, RemoteVolume.level(-1))
        assertEquals(100, RemoteVolume.level(26))
        assertEquals(0, RemoteVolume.shown(-1))
        assertEquals(25, RemoteVolume.shown(101))
        assertEquals(0, RemoteVolume.stepped(0, -1))
        assertEquals(100, RemoteVolume.stepped(100, 1))
    }

    @Test fun eachPressChangesTheShownStepForEverySpeakerLevel() {
        for (level in 0..100) {
            val step = RemoteVolume.shown(level)
            assertEquals(minOf(25, step + 1), RemoteVolume.shown(RemoteVolume.stepped(level, 1)))
            assertEquals(maxOf(0, step - 1), RemoteVolume.shown(RemoteVolume.stepped(level, -1)))
        }
    }

    @Test fun firstPressAlwaysCountsAndRepeatsRespectGap() {
        assertTrue(RemoteVolume.takesKey(0, 1_010, 1_000))
        assertFalse(RemoteVolume.takesKey(1, 1_099, 1_000))
        assertTrue(RemoteVolume.takesKey(2, 1_100, 1_000))
        var last = -1_000L
        var level = 0
        var elapsed = 0L
        for (repeat in 0..200) {
            if (RemoteVolume.takesKey(repeat, elapsed, last)) {
                last = elapsed
                level = RemoteVolume.stepped(level, 1)
            }
            if (level == 100) break
            elapsed += 50
        }
        assertEquals(2_400L, elapsed)
    }
}
