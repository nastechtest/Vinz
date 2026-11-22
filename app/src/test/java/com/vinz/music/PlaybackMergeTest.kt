package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.MixPrefs
import com.vinz.music.data.prefs.AutoplaySource
import com.vinz.music.ui.screens.Crossfade
import com.vinz.music.ui.screens.QueueEnd
import com.vinz.music.ui.screens.SoundChoices
import com.vinz.music.ui.screens.VideoPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.4, Playback's rows that are one face over two stored settings (merged in 1.8.0, #210, and kept as they are by
 * the rebuilt page): every combination that can be stored has its choice, and the choice writes back what it read.
 * Playback has no new merge of plan §2.5; this pins the three it shows, read through [SoundChoices] as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackMergeTest {
    private val both = listOf(false, true)

    /** autoplay × its source (2 × 3) → When the queue ends. Off keeps the source. */
    @Test fun whenTheQueueEnds() {
        for (on in both) for (source in AutoplaySource.entries) {
            val merged = SoundChoices.queueEnd(AppSettings(autoplay = on, autoplaySource = source))
            val (backOn, backSource) = SoundChoices.queueEndValues(merged)
            assertEquals("$on, $source", on, backOn)
            // "Stop" writes no source: the stored one stays.
            assertEquals("$on, $source", source, backSource ?: source)
            if (!on) assertEquals(QueueEnd.STOP, merged)
        }
        assertEquals(QueueEnd.entries.toSet(), (AutoplaySource.entries.map { SoundChoices.queueEnd(AppSettings(autoplay = true, autoplaySource = it)) } + QueueEnd.STOP).toSet())
    }

    /**
     * radioSongsOnly × songsEverywhere (2 × 2) → Music videos. "Everywhere" only counts with "Songs, not videos" on
     * (it was a row under that switch), so off + everywhere reads as Play them, as it played.
     */
    @Test fun musicVideos() {
        for (radios in both) for (everywhere in both) {
            val merged = SoundChoices.videoPolicy(AppSettings(radioSongsOnly = radios, songsEverywhere = everywhere))
            val (backRadios, backEverywhere) = SoundChoices.videoPolicyValues(merged)
            assertEquals("$radios, $everywhere", radios, backRadios)
            if (radios) assertEquals("$radios, $everywhere", everywhere, backEverywhere) else assertEquals(VideoPolicy.PLAY, merged)
        }
    }

    /** mix × mixAuto (2 × 2) → Crossfade. Off keeps whether the length was automatic. */
    @Test fun crossfade() {
        for (on in both) for (auto in both) {
            val merged = SoundChoices.crossfade(AppSettings(mix = MixPrefs(on = on, auto = auto)))
            val (backOn, backAuto) = SoundChoices.crossfadeValues(merged)
            assertEquals("$on, $auto", on, backOn)
            assertEquals("$on, $auto", auto, backAuto ?: auto)
            if (!on) assertEquals(Crossfade.OFF, merged)
        }
    }
}
