package com.vinz.music

import android.content.ContextWrapper
import android.content.Intent
import com.vinz.music.data.prefs.EqSettings
import com.vinz.music.playback.AudioEffects
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #519: Vinz's platform effects (equalizer, bass boost, loudness) are asked of Android only once the session has a
 * track on it, and only when the settings ask for them. Made on a session with nothing playing yet, as they were when
 * the service started, Android kept three of them in its register for good whenever Vinz's process ended before
 * anything played (measured on the A16, `EqEffect519AppTest`), until the register was full and every effect was refused.
 *
 * Plain JUnit: Android's effect classes are the build's stand-ins here (they make nothing), so what's checked is what
 * Vinz asks for and when, through [AudioEffects.trace].
 */
class AudioEffectsTrackTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val context = object : ContextWrapper(null) {
        override fun sendBroadcast(intent: Intent?) {}
        override fun getPackageName() = "com.vinz.music.test"
    }

    private fun effects(announce: Boolean = true) = AudioEffects(context, executor, announce).also { e -> e.trace = { events += it } }

    /** Waits until the effects thread has done everything handed to it so far. */
    private fun settle() { executor.submit {}.get(5, TimeUnit.SECONDS) }

    private fun made() = events.filter { it.startsWith("make") }

    @After fun stop() { executor.shutdownNow() }

    /** On, with a band moved: asks for the equalizer. */
    private val ON = EqSettings(enabled = true, bands = List(10) { if (it == 5) 3f else 0f })

    @Test fun nothingIsMadeOnASessionWithNoTrack() {
        val e = effects()
        e.attach(42)
        e.apply(EqSettings(enabled = true, bands = List(10) { 3f }, bassBoost = 500, loudnessGainMb = 300, preampDb = -2f))
        settle()
        assertEquals("asked of Android before any track: $events", emptyList<String>(), made())
        assertTrue("other engines told about a session with nothing on it: $events", events.none { it.startsWith("open") })
    }

    @Test fun theEffectsAreMadeOnceTheTrackIs() {
        val e = effects()
        e.attach(42)
        e.apply(EqSettings(enabled = true, bands = List(10) { 2f }, bassBoost = 500, loudnessGainMb = 300))
        e.trackReady()
        settle()
        assertEquals("open 42", events.first())
        // The equalizer (DynamicsProcessing, or the platform Equalizer on the build's stand-in SDK level), bass, loudness;
        // no platform virtualizer any more (it was only ever made to be switched off).
        val kinds = made().map { it.split(' ')[1] }
        assertTrue("no equalizer made: $events", kinds.any { it == "dynamics" || it == "equalizer" })
        assertTrue("no bass boost made: $events", "bass" in kinds)
        assertTrue("no loudness made: $events", "loudness" in kinds)
        assertTrue("a virtualizer made: $events", "virtualizer" !in kinds)
        assertTrue("made on another session: $events", made().all { it.endsWith(" 42") })
        // Further changes use the ones made: nothing new is asked for.
        val before = made().size
        repeat(5) { i -> e.apply(EqSettings(enabled = true, preampDb = -i.toFloat())) }
        e.trackReady()
        settle()
        assertEquals("made again: $events", before, made().size)
    }

    @Test fun withTheEqualizerOffNoEffectIsMade() {
        val e = effects()
        e.attach(42)
        e.apply(EqSettings(enabled = false))
        e.trackReady()
        settle()
        assertEquals("asked of Android with the equalizer off: $events", emptyList<String>(), made())
        // Switched on later, while playing: made then.
        e.apply(ON)
        settle()
        assertTrue("not made when switched on: $events", made().isNotEmpty())
    }

    @Test fun aTrackReportedBeforeTheSessionCountsForIt() {
        // Media3 hands the session to listeners a moment after it's made; the track can come first.
        val e = effects()
        e.apply(ON)
        e.trackReady()
        settle()
        assertEquals(emptyList<String>(), made())
        e.attach(42)
        settle()
        assertTrue("not made on the session once known: $events", made().isNotEmpty() && made().all { it.endsWith(" 42") })
    }

    @Test fun aNewSessionWaitsForItsOwnTrack() {
        val e = effects()
        e.apply(ON)
        e.attach(42)
        e.trackReady()
        settle()
        events.clear()
        e.attach(43)
        e.apply(EqSettings(enabled = true, preampDb = -3f))
        settle()
        assertEquals("the old session not closed: $events", listOf("close 42"), events.toList())
        e.trackReady()
        settle()
        assertTrue("not made on the new session: $events", made().isNotEmpty() && made().all { it.endsWith(" 43") })
    }

    private fun kinds() = made().map { it.split(' ')[1] }
    private val eqKinds = setOf("dynamics", "equalizer")

    /** A live session with these settings; what was asked of Android. */
    private fun playing(s: EqSettings, normalizationDb: Float = 0f): AudioEffects = effects().also { e ->
        e.attach(42)
        if (normalizationDb != 0f) e.setNormalization(normalizationDb)
        e.apply(s)
        e.trackReady()
        settle()
    }

    @Test fun eachEffectOnlyWhenItIsAskedFor() {
        // Only bass boost: no equalizer, no loudness.
        playing(EqSettings(enabled = true, bassBoost = 600))
        assertEquals("bass only: $events", listOf("bass"), kinds())
        events.clear()
        // Only loudness.
        playing(EqSettings(enabled = true, loudnessGainMb = 400))
        assertEquals("loudness only: $events", listOf("loudness"), kinds())
        events.clear()
        // A band moved: the equalizer, and nothing else.
        playing(EqSettings(enabled = true, bands = List(10) { if (it == 3) 4f else 0f }))
        assertTrue("band: $events", kinds().size == 1 && kinds().single() in eqKinds)
        events.clear()
        // The preamp alone is the equalizer's input gain.
        playing(EqSettings(enabled = true, preampDb = -3f))
        assertTrue("preamp: $events", kinds().size == 1 && kinds().single() in eqKinds)
        events.clear()
        // On but flat, nothing moved: nothing at all.
        playing(EqSettings(enabled = true))
        assertEquals("flat: $events", emptyList<String>(), kinds())
        events.clear()
        // A normalization gain with the equalizer off: only the equalizer (its input gain), not bass or loudness.
        playing(EqSettings(enabled = false, bassBoost = 600, loudnessGainMb = 400), normalizationDb = -2f)
        assertTrue("normalization: $events", kinds().size == 1 && kinds().single() in eqKinds)
        events.clear()
        // Off, with values left on the sliders: nothing.
        playing(EqSettings(enabled = false, bands = List(10) { 5f }, bassBoost = 600, loudnessGainMb = 400, preampDb = 2f))
        assertEquals("off: $events", emptyList<String>(), kinds())
    }

    @Test fun oneMadeStaysAndOthersComeWhenAsked() {
        val e = playing(EqSettings(enabled = true, bassBoost = 600))
        assertEquals(listOf("bass"), kinds())
        // Bass back to 0: the bass boost stays (switched off), nothing new.
        e.apply(EqSettings(enabled = true))
        settle()
        assertEquals(listOf("bass"), kinds())
        // Loudness asked for now: made then, once.
        e.apply(EqSettings(enabled = true, loudnessGainMb = 200))
        e.apply(EqSettings(enabled = true, loudnessGainMb = 300))
        settle()
        assertEquals(listOf("bass", "loudness"), kinds())
    }

    @Test fun refusedWhenAndroidMakesNoneOfWhatsAsked() {
        val e = effects()
        e.refuseForTest = { true }
        e.attach(42)
        e.apply(EqSettings(enabled = true, bands = List(10) { 2f }, bassBoost = 300))
        e.trackReady()
        settle()
        assertTrue("not reported refused: $events", e.refused.value)
        assertTrue(e.activeSummary.contains("refused"))
        // Nothing asked any more: not refused.
        e.apply(EqSettings(enabled = false))
        settle()
        assertEquals(false, e.refused.value)
        // Android making them again (the phone restarted, or room freed): asked once more, made, not refused.
        e.refuseForTest = null
        e.apply(EqSettings(enabled = true, bands = List(10) { 2f }))
        settle()
        assertEquals(false, e.refused.value)
    }

    @Test fun notRefusedWhenSomeOfItIsMade() {
        val e = effects()
        e.refuseForTest = { it == "bass" }
        e.attach(42)
        e.apply(EqSettings(enabled = true, bands = List(10) { 2f }, bassBoost = 300))
        e.trackReady()
        settle()
        assertEquals("one effect missing isn't Android refusing them all: $events", false, e.refused.value)
    }

    @Test fun crossfadesTailIsNotAnnounced() {
        val e = effects(announce = false)
        e.apply(ON)
        e.attach(7)
        e.trackReady()
        e.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("the tail's session announced: $events", events.none { it.startsWith("open") || it.startsWith("close") })
        assertTrue(made().isNotEmpty())
    }
}
