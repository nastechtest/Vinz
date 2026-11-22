package com.vinz.music

import androidx.media3.common.Player
import com.vinz.music.playback.FaceDown
import com.vinz.music.playback.FaceDownDetector
import com.vinz.music.playback.FaceDownDetector.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.5, "Pause when face down": the decisions ([FaceDownDetector]) on made-up accelerometer samples, and [FaceDown]
 * wired to a fake sensor and a fake player: it listens only while the setting is on and music plays (or a face-down
 * pause can still be resumed), pauses once for a phone laid down, plays on when it's picked up within five minutes, and
 * never resumes the listener's own pause.
 */
class FaceDownTest {
    // ------------------------------------------------------------------ The detector

    private val down = floatArrayOf(0.3f, -0.2f, -9.7f)
    private val up = floatArrayOf(0.1f, 4f, 8.8f)
    private val inHand = floatArrayOf(0.5f, 6.5f, 7.2f)

    /** Feeds [xyz] every 200 ms (SENSOR_DELAY_NORMAL) from [from] for [ms]; returns the actions, and the time after. */
    private fun FaceDownDetector.feed(xyz: FloatArray, from: Long, ms: Long, playing: () -> Boolean): Pair<List<Action>, Long> {
        val out = ArrayList<Action>()
        var t = from
        while (t < from + ms) { out += sample(xyz[0], xyz[1], xyz[2], t, playing()); t += 200 }
        return out.filter { it != Action.NONE } to t
    }

    @Test fun laidSteadyFaceDownItPausesOnce() {
        val d = FaceDownDetector()
        var playing = true
        val (a1, t1) = d.feed(inHand, 0, 1_000) { playing }
        assertEquals(emptyList<Action>(), a1)
        // Down for a second: not yet.
        val (a2, t2) = d.feed(down, t1, 1_000) { playing }
        assertEquals(emptyList<Action>(), a2)
        // Past 1.2 s: once.
        val (a3, t3) = d.feed(down, t2, 400) { playing }
        assertEquals(listOf(Action.PAUSE), a3)
        playing = false
        val (a4, _) = d.feed(down, t3, 60_000) { playing }
        assertEquals("lying there doesn't pause again", emptyList<Action>(), a4)
    }

    @Test fun aBriefFlipDoesntPause() {
        val d = FaceDownDetector()
        var t = d.feed(inHand, 0, 1_000) { true }.second
        // Turned over in the hand: face down for 0.8 s at a time, then up again.
        repeat(5) {
            val (a, t2) = d.feed(down, t, 800) { true }
            assertEquals(emptyList<Action>(), a)
            t = d.feed(inHand, t2, 400) { true }.second
        }
    }

    @Test fun walkingWithItFaceDownDoesntPause() {
        val d = FaceDownDetector()
        var t = d.feed(inHand, 0, 1_000) { true }.second
        // Screen down in a bag on the shoulder: each step jolts the pull well past gravity's.
        repeat(20) { i ->
            val jolt = if (i % 2 == 0) -12.5f else -9.6f
            assertEquals(Action.NONE, d.sample(0.4f, 0.6f, jolt, t, true))
            t += 200
        }
    }

    @Test fun tiltedOnASlopeIsntFaceDown() {
        val d = FaceDownDetector()
        val t = d.feed(inHand, 0, 1_000) { true }.second
        // Screen down but propped at ~25° (along its length): not lying flat.
        assertEquals(emptyList<Action>(), d.feed(floatArrayOf(0f, 4.2f, -8.6f), t, 5_000) { true }.first)
    }

    @Test fun alreadyFaceDownWhenListeningStartsDoesntPause() {
        val d = FaceDownDetector()
        d.restart()
        // Music started (earbuds) on a phone lying face down: it plays.
        assertEquals(emptyList<Action>(), d.feed(down, 0, 10_000) { true }.first)
        // Picked up and laid down again: now it pauses.
        val t = d.feed(inHand, 10_000, 600) { true }.second
        assertEquals(listOf(Action.PAUSE), d.feed(down, t, 1_600) { true }.first)
    }

    @Test fun pickedUpWithinFiveMinutesItPlaysOn() {
        val d = FaceDownDetector()
        var playing = true
        var t = d.feed(inHand, 0, 600) { playing }.second
        val (a, t2) = d.feed(down, t, 1_600) { playing }
        assertEquals(listOf(Action.PAUSE), a)
        playing = false
        t = d.feed(down, t2, 4 * 60_000L) { playing }.second
        // A glance (0.4 s) isn't a pick-up.
        val (glance, t3) = d.feed(up, t, 400) { playing }
        assertEquals(emptyList<Action>(), glance)
        t = d.feed(down, t3, 2_000) { playing }.second
        assertEquals(listOf(Action.RESUME), d.feed(up, t, 800) { playing }.first)
    }

    @Test fun afterFiveMinutesItDoesntPlayOn() {
        val d = FaceDownDetector()
        val t = d.feed(inHand, 0, 600) { true }.second
        val (a, t2) = d.feed(down, t, 1_600) { true }
        assertEquals(listOf(Action.PAUSE), a)
        val t3 = d.feed(down, t2, FaceDownDetector.RESUME_WITHIN_MS + 1_000) { false }.second
        assertFalse(d.waiting(t3))
        assertEquals(emptyList<Action>(), d.feed(up, t3, 5_000) { false }.first)
    }

    @Test fun theListenersOwnPauseIsNeverResumed() {
        val d = FaceDownDetector()
        // Paused by hand, then the phone laid down and picked up: nothing (it wasn't playing to pause).
        var t = d.feed(inHand, 0, 600) { false }.second
        t = d.feed(down, t, 3_000) { false }.second
        assertEquals(emptyList<Action>(), d.feed(up, t, 2_000) { false }.first)

        // Face-down pause, then the listener pressed play and pause themselves: picking it up leaves it paused.
        val (a, t2) = d.feed(down, d.feed(inHand, t + 2_000, 600) { true }.second, 1_600) { true }
        assertEquals(listOf(Action.PAUSE), a)
        d.listenerActed()
        assertEquals(emptyList<Action>(), d.feed(up, t2, 2_000) { false }.first)
    }

    // ------------------------------------------------------------------ Wired: the fake sensor and player

    private class FakeTap : FaceDown.SensorTap {
        var onSample: ((Float, Float, Float) -> Unit)? = null
        var starts = 0
        var stops = 0
        val on get() = onSample != null
        override fun start(onSample: (Float, Float, Float) -> Unit): Boolean { starts++; this.onSample = onSample; return true }
        override fun stop() { stops++; onSample = null }
    }

    /** A player: its pause and play report back through the listener, as Media3's do. */
    private class FakePlayer : FaceDown.Controls {
        lateinit var listener: Player.Listener
        override var playing = false
        override var blocked = false
        var pauses = 0
        var plays = 0
        override fun pause() { pauses++; set(false) }
        override fun play() { plays++; set(true) }
        /** The listener's own press (or ours, through [pause] / [play]). */
        fun set(p: Boolean) {
            if (p == playing) return
            playing = p
            listener.onPlayWhenReadyChanged(p, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener.onIsPlayingChanged(p)
        }
    }

    private class Rig(on: Boolean) {
        var setting = on
        var now = 0L
        val tap = FakeTap()
        val player = FakePlayer()
        var pending: Pair<Long, () -> Unit>? = null
        var cancels = 0
        /** Counts cancels but keeps the check waiting, so a released FaceDown is also seen to ignore one that runs. */
        val face = FaceDown(tap, { setting }, player, { now }, object : FaceDown.Later {
            override fun run(ms: Long, block: () -> Unit) { pending = now + ms to block }
            override fun cancel() { cancels++ }
        })
        init { player.listener = face }

        fun feed(xyz: FloatArray, ms: Long) {
            val until = now + ms
            while (now < until) { tap.onSample?.invoke(xyz[0], xyz[1], xyz[2]); now += 200 }
        }

        /** Time passes with no samples (the sensor off); a check due meanwhile runs. */
        fun pass(ms: Long) {
            now += ms
            pending?.let { (at, block) -> if (at <= now) { pending = null; block() } }
        }
    }

    @Test fun settingOffNeverRegisters() {
        val r = Rig(on = false)
        r.face.update()
        r.player.set(true)
        r.player.set(false)
        r.player.set(true)
        r.face.update()
        assertEquals("the switch off: the sensor is never asked for", 0, r.tap.starts)
        assertFalse(r.face.listening)
    }

    @Test fun listensOnlyWhilePlaying() {
        val r = Rig(on = true)
        r.face.update()
        assertEquals("on, but nothing playing: not listening", 0, r.tap.starts)
        r.player.set(true)
        assertTrue(r.tap.on)
        r.player.set(false)
        assertFalse("the listener paused: stopped", r.tap.on)
        r.player.set(true)
        assertTrue(r.tap.on)
        // The setting switched off while playing: stopped at once.
        r.setting = false
        r.face.update()
        assertFalse(r.tap.on)
        assertEquals(r.tap.starts, r.tap.stops)
    }

    @Test fun wiredPauseAndPickUp() {
        val r = Rig(on = true)
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(1, r.player.pauses)
        assertFalse(r.player.playing)
        assertTrue("still listening, to play on when picked up", r.tap.on)
        r.feed(down, 60_000)
        r.feed(up, 800)
        assertEquals(1, r.player.plays)
        assertTrue(r.player.playing)
        assertTrue(r.tap.on)
    }

    @Test fun wiredFiveMinutesRunOutStopsListening() {
        val r = Rig(on = true)
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(1, r.player.pauses)
        r.pass(FaceDownDetector.RESUME_WITHIN_MS + 2_000)
        assertFalse("five minutes on: the sensor is let go", r.tap.on)
        r.feed(up, 2_000)
        assertEquals(0, r.player.plays)
    }

    @Test fun wiredTheListenersPressEndsTheClaim() {
        val r = Rig(on = true)
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(1, r.player.pauses)
        // The listener pressed play (earbuds) and paused again: it's theirs now.
        r.player.set(true)
        r.player.set(false)
        assertFalse(r.tap.on)
        r.feed(up, 2_000)
        assertEquals(0, r.player.plays)
        // Another queue started while face-down paused: not resumed either.
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(2, r.player.pauses)
        r.face.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        r.feed(up, 2_000)
        assertEquals(0, r.player.plays)
    }

    /**
     * Review (1.8.5): the setting switched off lets the [FaceDown] go ([FaceDown.release]; the service stops telling it of
     * the player), and switched on again makes a new one. The old one's five-minute check, still waiting from a face-down
     * pause, ran later and took the sensor again for it, so it kept listening (and pausing) with nobody to stop it.
     */
    @Test fun aReleasedOneDoesNothingMore() {
        val r = Rig(on = true)
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(1, r.player.pauses)
        assertTrue("a check waits for the five minutes to run out", r.pending != null)
        // Off: let go. On again at once (the service would make a new one; this one isn't told of the player any more).
        r.setting = false
        r.face.release()
        assertFalse(r.tap.on)
        assertEquals("the waiting check is dropped", 1, r.cancels)
        r.setting = true
        // The listener plays on with the phone in hand meanwhile.
        r.player.playing = true
        val starts = r.tap.starts
        // The old check comes due (this rig runs it even after a release).
        r.pass(FaceDownDetector.RESUME_WITHIN_MS + 2_000)
        assertEquals("the released one doesn't take the sensor again", starts, r.tap.starts)
        assertFalse(r.tap.on)
        r.face.update()
        r.face.onIsPlayingChanged(true)
        assertEquals("nothing wakes it", starts, r.tap.starts)
        assertFalse(r.face.listening)
    }

    /** A sample already on its way when it's let go (the sensor's thread) doesn't pause either. */
    @Test fun aReleasedOneIgnoresALateSample() {
        val r = Rig(on = true)
        r.player.set(true)
        r.feed(inHand, 600)
        r.feed(down, 1_000)
        val late = r.tap.onSample!!
        r.face.release()
        late(inHand[0], inHand[1], inHand[2])
        repeat(10) { late(down[0], down[1], down[2]); r.now += 200 }
        assertEquals(0, r.player.pauses)
    }

    @Test fun nothingWhileCastingCallingOrInTheCar() {
        val r = Rig(on = true)
        r.player.set(true)
        r.player.blocked = true
        r.feed(inHand, 600)
        r.feed(down, 5_000)
        assertEquals(0, r.player.pauses)
        // Back on the phone: the phone has to be laid down anew.
        r.player.blocked = false
        r.feed(down, 5_000)
        assertEquals(0, r.player.pauses)
        r.feed(inHand, 600)
        r.feed(down, 1_600)
        assertEquals(1, r.player.pauses)
    }
}
