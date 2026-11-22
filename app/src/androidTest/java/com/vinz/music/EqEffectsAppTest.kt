package com.vinz.music

import android.media.audiofx.AudioEffect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.prefs.EqSettings
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #519 ("Equalizer not working"): with the equalizer on, Vinz asks Android for no effect before a track plays on its
 * session (made on an empty session, Android kept them for good whenever the process ended before anything played, until
 * its register of effects was full and every effect was refused), and once a song plays, the equalizer is there: made,
 * in Vinz's control, switched on, with the bands as set. Read from Vinz's effects and Android's register
 * (`dumpsys media.audio_policy`). The sound itself isn't measured here (EqEffect519AppTest has the why).
 */
@RunWith(AndroidJUnit4::class)
class EqEffectsAppTest {
    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runBlocking { c.settings.setEq(EqSettings()) }
    }

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun waitFor(what: String, ms: Long, done: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!done()) {
            if (SystemClock.elapsedRealtime() > until) throw AssertionError("never: $what")
            Thread.sleep(100)
        }
    }

    /** Lines of Android's register of effects on [session]. */
    private fun registered(session: Int): List<String> {
        val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys media.audio_policy")
            .use { java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }
        return dump.lines().filter { it.contains("Session: $session;") }
    }

    /** The running service's AudioEffects, and one of its effects by field name (debug build: not minified). */
    private fun effect(name: String): AudioEffect? {
        val conn = c.player
        val service = conn.javaClass.getDeclaredField("service").apply { isAccessible = true }.get(conn) ?: return null
        var k: Class<*>? = service.javaClass
        var effects: Any? = null
        while (k != null && effects == null) {
            effects = runCatching { k.getDeclaredField("effects").apply { isAccessible = true }.get(service) }.getOrNull()
            k = k.superclass
        }
        effects ?: return null
        return runCatching { effects.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(effects) as AudioEffect? }.getOrNull()
    }

    @Test fun theEqualizerIsMadeWithTheTrackAndNotBefore() {
        Fixture.ready()
        val eq = EqSettings(enabled = true, preset = "Custom", bands = List(10) { if (it == 5) -6f else 0f }, bassBoost = 400)
        runBlocking { c.settings.setEq(eq) }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Vinz eq effects tone", 40, hz = 1000.0, rate = 44_100, channels = 2)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val song = c.localMusic.tracks.value.first { it.song.title == "Vinz eq effects tone" }.song

        // Vinz is up, its player has its session, nothing has played: no effect asked for on that session.
        waitFor("the player's session", 10_000) { onMain { c.player.player?.audioSessionId ?: 0 } != 0 }
        val session = onMain { c.player.player!!.audioSessionId }
        Thread.sleep(1_500) // the effects thread has had its turn
        assertEquals("effects on session $session before anything played", emptyList<String>(), registered(session))
        assertEquals(null, effect("dynamics") ?: effect("equalizer"))

        instrumentation.runOnMainSync { c.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "EQ effects test")) }
        waitFor("the tone playing", 20_000) { onMain { c.player.currentSong.value?.id == song.id && c.player.isPlaying.value } }
        waitFor("the equalizer made", 5_000) { (effect("dynamics") ?: effect("equalizer")) != null }
        val made = effect("dynamics") ?: effect("equalizer")
        assertNotNull(made)
        assertTrue("the equalizer isn't Vinz's to control", made!!.hasControl())
        waitFor("the equalizer on", 5_000) { made.enabled }
        if (made is android.media.audiofx.DynamicsProcessing) {
            assertEquals(-6f, made.getPreEqBandByChannelIndex(0, 5).gain, 0.01f)
        }
        val bass = effect("bass") as android.media.audiofx.BassBoost?
        assertNotNull("no bass boost", bass)
        assertTrue("bass boost off", bass!!.enabled)
        assertTrue("nothing in Android's register for session $session", registered(session).isNotEmpty())
    }
}
