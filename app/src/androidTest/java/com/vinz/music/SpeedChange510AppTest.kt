package com.vinz.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.db.toEntity
import com.vinz.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #510 (1.8.7.5, reported on a Motorola g04): changing the speed stopped the song. Here a song that is whole on the
 * phone plays, the speed is changed the way the player's Speed sheet changes it, several times, and the song has to
 * keep playing and keep moving after each change. No network: a silent WAV in the song cache.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SpeedChange510AppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    @After fun end() {
        runCatching { instrumentation.runOnMainSync { c.player.setMusicSpeed(1f); c.player.stop() } }
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 8_000
        val samples = rate * seconds
        val data = java.io.ByteArrayOutputStream()
        fun le32(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = data.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        data.write("RIFF".toByteArray()); le32(36 + samples * 2); data.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        data.write("data".toByteArray()); le32(samples * 2); data.write(ByteArray(samples * 2))
        return data.toByteArray()
    }

    private fun cached(song: Song) {
        val spec = DataSpec.Builder().setUri("vinz://audio/${song.id}").setKey(song.id).build()
        CacheWriter(CacheDataSource(c.playerCache, ByteArrayDataSource(wav(180))), spec, null, null).cache()
    }

    private fun position() = onMain { c.player.position }

    @Test fun theSongKeepsPlayingThroughSpeedChanges() {
        instrumentation.uiAutomation.executeShellCommand("cmd media_session volume --stream 3 --set 0").close()
        Fixture.ready()
        val song = Fixture.songs(1).single()
        runBlocking { c.db.songs().upsert(song.toEntity()) }
        cached(song)
        Fixture.launch()
        compose.waitForTab("Library")
        instrumentation.runOnMainSync { c.player.playSingle(song) }
        val started = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < started && position() < 2_000) Thread.sleep(200)
        assertTrue("the song plays before any change (at ${position()} ms)", position() >= 2_000)

        for (speed in listOf(1.25f, 0.75f, 2f, 0.5f, 1f)) {
            instrumentation.runOnMainSync { c.player.setMusicSpeed(speed) }
            Thread.sleep(1_500)   // the change has settled
            val from = position()
            Thread.sleep(4_000)
            val moved = position() - from
            assertEquals("still this song at $speed×", song.id, c.player.currentSong.value?.id)
            assertTrue("still playing at $speed×", c.player.isPlaying.value)
            // It keeps moving. The pace isn't asserted: the test song is 8 kHz, and seconds of it wait in the audio buffer,
            // so a change is heard (and counted) late: an emulator still moved at 2× four seconds into 0.5×.
            assertTrue("keeps moving at $speed× (moved $moved ms in 4 s)", moved > 1_500)
        }
    }
}
