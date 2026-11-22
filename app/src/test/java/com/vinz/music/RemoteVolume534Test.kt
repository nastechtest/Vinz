package com.vinz.music

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.playback.LyricsOverlayPlayer
import com.vinz.music.playback.RemoteVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #534 (1.8.7.6): casting to a Google speaker, one press of a volume key showed one step on Android's volume panel and
 * then jumped, and a held key ran to full or silent. The session said the speaker had 100 steps while a press moved 4.
 * Now what the session shows and what a press does are the same step.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteVolume534Test {
    private class Speaker(override var volume: Int?) : LyricsOverlayPlayer.Remote {
        override fun setVolume(v: Int) { volume = v }
    }

    private fun player(speaker: Speaker) =
        LyricsOverlayPlayer(ExoPlayer.Builder(ApplicationProvider.getApplicationContext<android.app.Application>()).build()).also { it.remote = speaker }

    @Test fun onePressIsOneShownStep() {
        for (start in 0..100) {
            val speaker = Speaker(start)
            val p = player(speaker)
            val before = p.deviceVolume
            p.increaseDeviceVolume(0)
            p.remoteChanged()
            assertEquals("up from $start", minOf(before + 1, RemoteVolume.STEPS), p.deviceVolume)
            p.decreaseDeviceVolume(0)
            p.remoteChanged()
            assertEquals("and down again from $start", minOf(before + 1, RemoteVolume.STEPS) - 1, p.deviceVolume)
            p.release()
        }
    }

    @Test fun theSessionCountsInThePhonesKindOfSteps() {
        val speaker = Speaker(50)
        val p = player(speaker)
        assertEquals(RemoteVolume.STEPS, p.deviceInfo.maxVolume)
        assertTrue(p.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        p.setDeviceVolume(RemoteVolume.STEPS, 0)
        assertEquals(100, speaker.volume)
        p.setDeviceVolume(0, 0)
        assertEquals(0, speaker.volume)
        p.release()
    }

    @Test fun everyStepComesBackAsItself() {
        for (step in 0..RemoteVolume.STEPS) assertEquals(step, RemoteVolume.shown(RemoteVolume.level(step)))
        assertEquals(100, RemoteVolume.stepped(100, 1))
        assertEquals(0, RemoteVolume.stepped(0, -1))
    }

    @Test fun aHeldKeyClimbsNoFasterThanAStepPerGap() {
        assertTrue(RemoteVolume.takesKey(repeat = 0, now = 1_000, lastAt = 990))
        assertFalse(RemoteVolume.takesKey(repeat = 1, now = 1_050, lastAt = 1_000))
        assertTrue(RemoteVolume.takesKey(repeat = 2, now = 1_100, lastAt = 1_000))
        // Silent to full with the key held: 25 steps, so about two and a half seconds.
        var at = 0L; var level = 0; var last = -1_000L
        for (repeat in 0..200) {
            if (RemoteVolume.takesKey(repeat, at, last)) { last = at; level = RemoteVolume.stepped(level, 1) }
            if (level == 100) break
            at += 50
        }
        assertTrue("took $at ms", at in 2_000..3_000)
    }
}
