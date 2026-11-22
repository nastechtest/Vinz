package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.AudioQuality
import com.vinz.music.data.prefs.NORMALIZE_TARGETS
import com.vinz.music.ui.screens.SoundChoices
import com.vinz.music.ui.screens.TrimSilence
import com.vinz.music.ui.screens.settings.Loudness
import com.vinz.music.ui.screens.settings.StreamingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, plan §2.5 on Sound & quality: a row that is one face over two stored settings shows every combination that
 * could be stored before, and writes back exactly what it read (nobody's settings change by opening the page).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SoundQualityMergeTest {
    /** Wi-Fi + mobile data quality → "Streaming quality": all 3 × 3 pairs. */
    @Test fun streamingQualityKeepsEveryPair() {
        val seen = HashSet<StreamingQuality>()
        for (wifi in AudioQuality.entries) for (mobile in AudioQuality.entries) {
            val merged = StreamingQuality.toMerged(wifi, mobile)
            assertEquals("$wifi, $mobile", wifi to mobile, StreamingQuality.toStored(merged))
            assertTrue("$wifi, $mobile has a face of its own", seen.add(merged))
            // The row says both, each by its own name.
            assertEquals(tr(R.string.sound_streaming_quality_value, wifi.label, mobile.label), merged.text)
        }
        assertEquals(9, seen.size)
        assertEquals(9, seen.map { it.text }.toSet().size)
        // The default is what a fresh install plays.
        val d = AppSettings()
        assertEquals(StreamingQuality(AudioQuality.HIGH, AudioQuality.HIGH), StreamingQuality.toMerged(d.audioQuality, d.cellularQuality))
    }

    /** Normalize + its level → "Same loudness for every song": on or off × each of the 5 levels. */
    @Test fun loudnessKeepsTheSwitchAndTheLevel() {
        val levels = NORMALIZE_TARGETS.map { it.first }
        assertEquals(5, levels.size)
        assertEquals(listOf(Loudness.OFF) + levels, Loudness.options)
        for (on in listOf(true, false)) for (level in levels) {
            val merged = Loudness.toMerged(on, level)
            // Off keeps the level for when it's turned on again.
            assertEquals("$on, $level", on to level, Loudness.toStored(merged, targetBefore = level))
            assertEquals(if (on) level else Loudness.OFF, merged)
            assertTrue(merged in Loudness.options)
        }
        // Picking a level turns it on at that level, whatever was stored; picking Off leaves the level.
        assertEquals(true to -8, Loudness.toStored(-8, targetBefore = -23))
        assertEquals(false to -23, Loudness.toStored(Loudness.OFF, targetBefore = -23))
        // Every choice has its own name, and "Off" is not a level's.
        assertEquals(6, Loudness.options.map { Loudness.label(it) }.toSet().size)
        assertEquals(tr(R.string.off), Loudness.label(Loudness.OFF))
        assertNotEquals(Loudness.label(-14), Loudness.label(-16))
        // A level that isn't one of the five (from a backup) still has a name.
        assertEquals("-12 LUFS", Loudness.label(-12))
    }

    /** Skip silence for songs + for podcasts → one choice (1.8.0; #325): all 2 × 2. */
    @Test fun skipSilenceKeepsAllFour() {
        val seen = HashSet<TrimSilence>()
        for (songs in listOf(false, true)) for (episodes in listOf(false, true)) {
            val s = AppSettings(skipSilence = songs).let { it.copy(sound = it.sound.copy(skipSilenceEpisodes = episodes)) }
            val merged = SoundChoices.trimSilence(s)
            assertEquals("$songs, $episodes", songs to episodes, SoundChoices.trimSilenceValues(merged))
            seen += merged
        }
        assertEquals(TrimSilence.entries.toSet(), seen)
    }
}
