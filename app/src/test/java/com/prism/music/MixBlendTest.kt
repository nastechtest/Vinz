package com.vinz.music

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.playback.EXTRA_VIDEO
import com.vinz.music.playback.MixEngine
import com.vinz.music.playback.MixState
import com.vinz.music.playback.Tempo
import com.vinz.music.playback.videoModeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.math.abs

/**
 * 1.8.7 (plan 5.4): the crossfade engine driven through whole blends, lining up, blending and settling included, against a
 * pair of fake players that play on with the clock (the main one and every second player the engine makes). Every way
 * out of a blend (it finishes, a skip, a seek, a pause, an error, a queue edit, repeat one, video mode, Mix turned off)
 * ends with the main player at full volume and its own speed, and no second player left alive. Plan 5.4 adds the steps
 * themselves in order (beat matching's settling included), the engine holding on to nothing after each way out, and
 * nothing left running after a release.
 *
 * The blend: a 200 s song, a 6 s blend at 194 s, the second player made at 180 s and playing along from 189 s.
 */
@RunWith(RobolectricTestRunner::class)
class MixBlendTest {
    private val context = ApplicationProvider.getApplicationContext<VinzApp>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = MixState()
    private val prefs = context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)

    private val songMs = 200_000L
    private fun item(id: String, video: String? = null) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(songMs)
            .setExtras(Bundle().apply { if (video != null) putString(EXTRA_VIDEO, video) }).build()).build()

    private val ending = item("ending")
    private val next = item("next")
    private val third = item("third")

    private fun now() = SystemClock.elapsedRealtime()

    /** A player that plays on with the clock: where it is, how loud, and what was done to it. */
    private inner class Fake(val label: String, startState: Int) {
        var items = listOf<MediaItem>()
        var index = 0
        private var base = 0L
        private var since = now()
        var playWhenReady = true
            set(v) { hold(); field = v }
        var state = startState
            set(v) { hold(); field = v }
        var speed = 1f
            set(v) { hold(); field = v }
        var volume = 1f
        var released = false
        var repeat = Player.REPEAT_MODE_OFF
        /** Every volume set: when, and to what. */
        val volumes = ArrayList<Pair<Long, Float>>()
        /** Every seek: from where (index, ms) to where. */
        val seeks = ArrayList<Seek>()

        fun duration(): Long = items.getOrNull(index)?.mediaMetadata?.durationMs ?: C.TIME_UNSET
        private fun running() = playWhenReady && state == Player.STATE_READY && !released
        fun position(): Long {
            val p = if (running()) base + ((now() - since) * speed).toLong() else base
            val d = duration()
            return if (d == C.TIME_UNSET) p else minOf(p, d)
        }
        private fun hold() { base = position(); since = now() }
        private fun ended() = state == Player.STATE_READY && duration() != C.TIME_UNSET && position() >= duration()
        fun playbackState() = when {
            released -> Player.STATE_IDLE
            ended() -> Player.STATE_ENDED
            else -> state
        }
        fun isPlaying() = running() && !ended()
        fun moveTo(i: Int, ms: Long) {
            seeks += Seek(index, position(), i, ms, now())
            index = i; base = ms; since = now()
        }
        val mediaId: String? get() = items.getOrNull(index)?.mediaId

        val player: ExoPlayer = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> label
                "isPlaying" -> isPlaying()
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "getPlaybackState" -> playbackState()
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex" -> index
                "getMediaItemCount" -> items.size
                "getDuration" -> duration()
                "getCurrentPosition" -> position()
                // As ExoPlayer's: repeat one doesn't change what's next (BasePlayer navigates as if it were off).
                "getNextMediaItemIndex" -> if (index + 1 < items.size) index + 1 else C.INDEX_UNSET
                "getMediaItemAt" -> items[args!![0] as Int]
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; volumes += now() to volume; null }
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "setPlaybackParameters" -> { speed = (args!![0] as PlaybackParameters).speed; null }
                "getRepeatMode" -> repeat
                "seekTo" -> {
                    if (args!!.size == 2) moveTo(args[0] as Int, args[1] as Long) else moveTo(index, args[0] as Long)
                    null
                }
                "setMediaItem" -> { items = listOf(args!![0] as MediaItem); index = 0; null }
                "prepare" -> { state = Player.STATE_READY; null }
                "release" -> { playWhenReady = false; released = true; null }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as ExoPlayer
    }

    /** The skip fade (MixEngine.SKIP_FADE_MS). */
    private val SKIP_FADE_MS = 150L

    private data class Seek(val fromIndex: Int, val fromMs: Long, val toIndex: Int, val toMs: Long, val time: Long)

    private val main = Fake("main", Player.STATE_READY)
    private val tails = ArrayList<Fake>()
    private var mixOn = true
    private var beats = false
    private var settled = 0

    private lateinit var engine: MixEngine

    @Before
    fun start() {
        prefs.edit().clear().commit()
        main.items = listOf(ending, next, third)
        main.moveTo(0, 178_000)
        main.seeks.clear()
        engine = MixEngine(
            main = main.player,
            scope = scope,
            tempo = context.container.mixTempo,
            mixMs = { if (mixOn) 6_000L else null },
            auto = { false },
            beatMatch = { beats },
            // As the service's: no blend into or out of video mode.
            allowed = { from, to -> from.videoModeId == null && to.videoModeId == null },
            scanFromPhone = {},
            buildTail = { Fake("tail ${tails.size}", Player.STATE_IDLE).also { tails += it }.player },
            onSettled = { settled++ },
            state = state,
        )
        engine.start()
    }

    @After
    fun stop() {
        engine.release()
        scope.cancel()
    }

    private fun run(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs on in small steps until [done] (at most [maxMs]); fails if it never comes. */
    private fun runUntil(what: String, maxMs: Long = 30_000, done: () -> Boolean) {
        var waited = 0L
        while (!done()) {
            assertTrue("never: $what", waited < maxMs)
            run(10); waited += 10
        }
    }

    private val handOver get() = main.seeks.firstOrNull { it.fromIndex == 0 && it.toIndex != 0 }

    /** Into the lead-in: the second player plays along (muted), the main player still on the ending song. */
    private fun toLeadIn(atMs: Long = 191_000) {
        runUntil("the second player plays along") { tails.singleOrNull()?.isPlaying() == true }
        runUntil("the ending song at $atMs ms") { main.position() >= atMs }
        assertNull(handOver)
    }

    /** Into the blend, [intoMs] past the hand-over. */
    private fun toBlend(intoMs: Long = 3_000) {
        runUntil("the hand-over") { handOver != null }
        assertEquals("next", main.mediaId)
        runUntil("$intoMs ms into the blend") { main.position() >= intoMs }
        assertTrue(engine.blending)
        assertTrue(main.volume in 0.2f..0.95f)
        assertTrue(tails.single().volume in 0.2f..0.95f)
    }

    /** Every way out: the main player at full volume and its own speed, no second player alive, nothing under way. */
    private fun assertAllBack() {
        assertEquals("main volume", 1f, main.volume)
        assertEquals("main speed", 1f, main.speed)
        for (t in tails) assertTrue("${t.label} left alive", t.released)
        assertFalse("still blending", engine.blending)
        assertNull(state.blend.value)
        for ((_, v) in main.volumes + tails.flatMap { it.volumes }) assertTrue("volume $v", v in 0f..1f)
        // Inside the engine too: back to waiting, nothing of the blend kept (no plan, no second player, no catch-up timer).
        assertEquals("phase", "IDLE", field("phase").toString())
        assertNull("plan kept", field("plan"))
        assertNull("second player kept", field("tail"))
        assertNull("shifter kept", field("shifter"))
        assertEquals("catch-up timer", 0L, field("catchUpUntil"))
    }

    /** One of the engine's own fields (what it holds on to after a blend). */
    private fun field(name: String): Any? =
        MixEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)

    /** The engine's step now (IDLE, PREPARED, SYNCING, BLENDING, SETTLING, SKIP_FADE). */
    private fun phase() = field("phase").toString()

    /** Runs on in 10 ms steps for [ms], noting each step the engine goes through (once each time it changes). */
    private fun phasesOver(ms: Long, until: () -> Boolean = { false }): List<String> {
        val seen = arrayListOf(phase())
        var waited = 0L
        while (waited < ms && !until()) {
            run(10); waited += 10
            if (phase() != seen.last()) seen += phase()
        }
        return seen
    }

    /** Beat matching on: both songs scanned, steady, 120 and 124 BPM (the next one plays at 120/124 while they overlap). */
    private fun beatMatched() {
        beats = true
        prefs.edit()
            .putString("ending", Tempo(120.0, 5.0, 0, 200_000, 0, 198_000, fadeOutMs = 0, quietIntroMs = 0).encode())
            .putString("next", Tempo(124.0, 5.0, 0, 200_000, 0, 198_000, fadeOutMs = 0, quietIntroMs = 0).encode())
            .commit()
    }

    // --- Task 1: whole blends and every way out of them ---

    @Test
    fun aWholeBlendHandsOverOnTimeFadesBothWaysAndEndsAtFullVolume() {
        runUntil("the blend is done", maxMs = 40_000) { handOver != null && tails.all { it.released } && !engine.blending }
        val h = handOver!!
        assertEquals(1, h.toIndex)
        assertEquals(0L, h.toMs)
        assertTrue("handed over at ${h.fromMs} ms", h.fromMs in 193_850..194_200)
        assertEquals(1, tails.size)
        // The second player was on the ending song where the main one was when it started playing along.
        assertEquals("ending", tails.single().mediaId)
        // Over the blend the next song only comes up, and the ending song only goes down.
        val from = h.time
        val mainIn = main.volumes.filter { it.first > from }.map { it.second }
        val tailOut = tails.single().volumes.filter { it.first > from }.map { it.second }
        assertTrue(mainIn.size > 50)
        assertEquals(mainIn, mainIn.sorted())
        assertEquals(tailOut, tailOut.sortedDescending())
        assertEquals("next", main.mediaId)
        assertAllBack()
        assertEquals(1, settled)
    }

    @Test
    fun pausedMidBlendItWaitsAndFinishesOnceItPlaysAgain() {
        toLeadIn(); toBlend()
        val v = main.volume
        main.playWhenReady = false
        run(10_000)
        // Both wait (the ending song too), nothing released meanwhile.
        assertFalse(tails.single().isPlaying())
        assertFalse(tails.single().released)
        main.playWhenReady = true
        run(100)
        assertTrue(main.volume >= v - 0.05f)
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertAllBack()
    }

    @Test
    fun releasedMidBlendEverythingIsLetGo() {
        toLeadIn(); toBlend()
        engine.release()
        assertAllBack()
    }

    @Test
    fun mixTurnedOffMidBlendEverythingIsLetGo() {
        toLeadIn(); toBlend()
        mixOn = false
        run(1_500)
        assertAllBack()
    }

    @Test
    fun takenAwayInTheLeadInTheBlendIsLetGoAndPlannedAgain() {
        toLeadIn()
        main.moveTo(0, 100_000)
        run(300)
        assertAllBack()
        assertNull(handOver)
        main.moveTo(0, 178_000)
        runUntil("planned again") { tails.size == 2 }
        runUntil("the blend is done", maxMs = 40_000) { handOver != null && tails.all { it.released } && !engine.blending }
        assertAllBack()
    }

    @Test
    fun theNextSongChangedInTheLeadInTheBlendGoesIntoTheNewOne() {
        toLeadIn()
        main.items = listOf(ending, third, next)
        runUntil("the blend is done") { handOver != null && tails.all { it.released } && !engine.blending }
        assertEquals("third", main.mediaId)
        assertAllBack()
    }

    @Test
    fun nothingNextAnyMoreInTheLeadInTheSongPlaysOn() {
        toLeadIn()
        main.items = listOf(ending)
        run(500)
        assertAllBack()
        run(5_000)
        assertNull(handOver)
        assertEquals("ending", main.mediaId)
    }

    @Test
    fun theEndingSongTakenOutOfTheQueueMidBlendTheBlendGoesOn() {
        toLeadIn(); toBlend()
        // The current song's place moves (the one before it is gone): it's still the song being blended into.
        main.items = listOf(next, third)
        main.index = 0
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertEquals("next", main.mediaId)
        assertAllBack()
    }

    @Test
    fun theNextSongFailingMidBlendTheEndingSongPlaysOutAndAllComesBack() {
        toLeadIn(); toBlend()
        main.state = Player.STATE_IDLE
        runUntil("the ending song played out") { tails.single().released }
        assertEquals(1f, main.volume)
        assertFalse(engine.blending)
        // The service gets it going again.
        main.state = Player.STATE_READY
        run(500)
        assertAllBack()
    }

    // --- Task 2: the edge cases left after 1.8.4 ---

    /** Fails on the old code: the second player was left playing (muted) while the song stood stopped. */
    @Test
    fun aPlaybackErrorInTheLeadInLetsTheSecondPlayerGo() {
        toLeadIn()
        main.state = Player.STATE_IDLE
        run(300)
        assertAllBack()
        assertNull(handOver)
    }

    /** Fails on the old code: the blend stood still, the next song held below full for good and the second player alive. */
    @Test
    fun theEndingSongsPlayerFailingMidBlendBringsTheNextSongUp() {
        toLeadIn(); toBlend()
        val v = main.volume
        tails.single().state = Player.STATE_IDLE
        run(1_200)
        assertAllBack()
        // It came up from where it was, not with a jump.
        val after = main.volumes.takeLast(20).map { it.second }
        assertTrue(after.all { it >= v - 0.01f })
    }

    /** Fails on the old code: it moved on to the next song all the same. */
    @Test
    fun repeatOneSwitchedOnInTheLeadInStaysOnTheSong() {
        toLeadIn()
        main.repeat = Player.REPEAT_MODE_ONE
        run(300)
        assertAllBack()
        run(8_000)
        assertNull(handOver)
        assertEquals("ending", main.mediaId)
        assertNull(state.mark.value)
        assertEquals(1, tails.size)
    }

    /** Fails on the old code: as above, from before the second player plays along. */
    @Test
    fun repeatOneSwitchedOnAfterTheBlendWasGotReadyForStaysOnTheSong() {
        runUntil("the second player is made") { tails.size == 1 }
        main.repeat = Player.REPEAT_MODE_ONE
        run(300)
        assertAllBack()
        run(18_000)
        assertNull(handOver)
        assertEquals("ending", main.mediaId)
        assertEquals(1, tails.size)
    }

    /** Fails on the old code: the reading from before the pause put the song seconds on, and the hand-over came early. */
    @Test
    fun pausedWhileTheTwoAreLinedUpTheHandOverStillComesAtTheBlend() {
        // A window of readings has been taken by now (the second player started at 189 s).
        toLeadIn(atMs = 190_900)
        main.playWhenReady = false
        run(10_000)
        assertNull(handOver)
        main.playWhenReady = true
        runUntil("the hand-over") { handOver != null }
        val h = handOver!!
        assertTrue("handed over at ${h.fromMs} ms, the blend is at 194 000", h.fromMs >= 193_800)
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertAllBack()
    }

    /** Fails on the old code: the next song went back to silent (its fade-in started over), then jumped to full. */
    @Test
    fun theNextSongTakenBackMidBlendComesUpFromWhereItWas() {
        toLeadIn(); toBlend()
        val v = main.volume
        val at = now()
        main.moveTo(1, 0)
        run(1_000)
        val after = main.volumes.filter { it.first >= at }.map { it.second }
        assertTrue(after.isNotEmpty())
        assertTrue("went down to ${after.min()} from $v", after.all { it >= v - 0.01f })
        // No jump: up in small steps.
        assertTrue(after.zipWithNext().all { (a, b) -> b - a <= 0.2f })
        assertAllBack()
        assertNull(state.into.value)
    }

    /** Fails on the old code: the ending song was cut off dead wherever its fade was. */
    @Test
    fun aSkipMidBlendFadesTheEndingSongOutOverAMoment() {
        toLeadIn(); toBlend()
        val tail = tails.single()
        val tailWas = tail.volume
        val at = now()
        main.moveTo(2, 0)
        run(60)
        // Still fading: neither cut off nor at full.
        assertFalse("cut off at once", tail.released)
        assertTrue(tail.volume > 0f && tail.volume < tailWas)
        assertTrue(engine.blending)
        run(200)
        assertTrue(tail.released)
        // Faded all the way down before it went, within the fade's time.
        val out = tail.volumes.filter { it.first >= at }
        assertEquals(0f, out.last().second)
        assertTrue(out.last().first - at <= SKIP_FADE_MS + 60)
        assertTrue(out.map { it.second }.zipWithNext().all { (a, b) -> b <= a })
        // The song skipped to came up to full meanwhile, and stays.
        val up = main.volumes.filter { it.first >= at }.map { it.second }
        assertEquals(up, up.sorted())
        assertEquals("third", main.mediaId)
        assertAllBack()
        assertNull(state.into.value)
        run(3_000)
        assertAllBack()
    }

    /** Fails on the old code: the song in video mode was blended all the same. */
    @Test
    fun videoModeSwitchedOnInTheLeadInStopsTheBlend() {
        toLeadIn()
        main.items = listOf(item("ending", video = "ending-video"), next, third)
        run(300)
        assertAllBack()
        run(8_000)
        assertNull(handOver)
        assertEquals(1, tails.size)
    }

    /** Fails on the old code: as above, for the next song switched to video mode. */
    @Test
    fun theNextSongSwitchedToVideoModeInTheLeadInStopsTheBlend() {
        toLeadIn()
        main.items = listOf(ending, item("next", video = "next-video"), third)
        run(300)
        assertAllBack()
        run(8_000)
        assertNull(handOver)
    }

    // --- Plan 5.4: the blend itself, step by step, and the ways out not covered above ---

    @Test
    fun aWholeBlendGoesThroughEveryStepInOrder() {
        val seen = phasesOver(40_000) { handOver != null && tails.all { it.released } && phase() == "IDLE" }
        assertEquals(listOf("IDLE", "PREPARED", "SYNCING", "BLENDING", "IDLE"), seen)
        assertAllBack()
        assertEquals(1, settled)
    }

    @Test
    fun aBeatMatchedBlendPlaysAtTheEndingSongsTempoThenSettlesBackToItsOwn() {
        beatMatched()
        val speeds = ArrayList<Float>()
        val seen = phasesOver(45_000) {
            if (phase() == "BLENDING") speeds += main.speed
            handOver != null && tails.all { it.released } && phase() == "IDLE"
        }
        assertEquals(listOf("IDLE", "PREPARED", "SYNCING", "BLENDING", "SETTLING", "IDLE"), seen)
        // While they overlapped, the next song played at 120/124 most of the time (a few percent either way only while its
        // beats caught up).
        val matched = speeds.count { abs(it - 120f / 124f) < 0.001f }
        assertTrue("matched for $matched of ${speeds.size} steps: ${speeds.distinct()}", matched > speeds.size / 2)
        // On the ending song's beat: the hand-over at 194 s (a beat of 500 ms from 198 s), the next song from its first beat.
        val h = handOver!!
        assertEquals(0L, h.toMs)
        assertTrue("handed over at ${h.fromMs} ms", h.fromMs in 193_850..194_200)
        assertEquals("next", main.mediaId)
        assertAllBack()
        assertEquals(1, settled)
    }

    @Test
    fun skippedWhileTheSpeedSettlesItGoesStraightBackToItsOwnSpeed() {
        beatMatched()
        runUntil("settling", maxMs = 45_000) { phase() == "SETTLING" }
        assertTrue(main.speed != 1f)
        main.moveTo(2, 0)
        run(500)
        assertEquals("third", main.mediaId)
        assertAllBack()
        assertEquals(1, settled)
    }

    @Test
    fun skippedToTheNextSongInTheLeadInTheBlendIsLetGo() {
        toLeadIn()
        main.moveTo(1, 0)
        run(300)
        assertAllBack()
        assertEquals("next", main.mediaId)
        // Only the listener's own move: the engine didn't seek the main player anywhere after it.
        run(5_000)
        assertEquals(1, main.seeks.size)
        assertEquals(1, tails.size)
        assertAllBack()
    }

    @Test
    fun previousInTheLeadInStartsTheSongOverAndTheBlendComesAgainAtItsEnd() {
        toLeadIn()
        main.moveTo(0, 0)
        run(300)
        assertAllBack()
        assertEquals("ending", main.mediaId)
        // Played to the end again, it blends there.
        main.moveTo(0, 178_000)
        runUntil("the blend is done", maxMs = 40_000) { handOver != null && tails.size == 2 && tails.all { it.released } && !engine.blending }
        assertAllBack()
    }

    @Test
    fun previousMidBlendBackToTheEndingSongFadesTheSecondPlayerOut() {
        toLeadIn(); toBlend()
        main.moveTo(0, 0)
        run(60)
        assertEquals("SKIP_FADE", phase())
        run(300)
        assertEquals("ending", main.mediaId)
        assertAllBack()
        // The ending song from its start is planned afresh, not refused: nothing more is made until its end comes up.
        run(5_000)
        assertEquals(1, tails.size)
        assertAllBack()
    }

    @Test
    fun seekedOnInTheNextSongMidBlendTheBlendFinishesOnTheEndingSongsClock() {
        toLeadIn(); toBlend()
        main.moveTo(1, 60_000)
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertEquals("next", main.mediaId)
        assertAllBack()
    }

    @Test
    fun seekedPastTheBlendInTheLeadInItIsLetGo() {
        toLeadIn()
        main.moveTo(0, 199_000)
        run(500)
        assertAllBack()
        assertNull(handOver)
        assertEquals(1, tails.size)
    }

    @Test
    fun pausedMidBlendAndTheNextSongErrorsThenItStillComesBack() {
        toLeadIn(); toBlend()
        main.playWhenReady = false
        run(2_000)
        main.state = Player.STATE_IDLE
        main.playWhenReady = true
        run(2_000)
        main.state = Player.STATE_READY
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertAllBack()
    }

    @Test
    fun theSecondPlayerFailingInTheLeadInLetsItGo() {
        toLeadIn()
        tails.single().state = Player.STATE_IDLE
        run(300)
        assertAllBack()
        run(5_000)
        assertNull(handOver)
        assertEquals("ending", main.mediaId)
    }

    @Test
    fun theSongBlendedIntoTakenOutOfTheQueueMidBlendTheSecondPlayerFadesOut() {
        toLeadIn(); toBlend()
        // The current song gone from the queue: the player moves on to the one after it.
        main.items = listOf(ending, third)
        main.moveTo(1, 0)
        run(500)
        assertEquals("third", main.mediaId)
        assertAllBack()
        assertNull(state.into.value)
    }

    @Test
    fun theSongBlendedIntoReplacedMidBlendTheSecondPlayerFadesOut() {
        toLeadIn(); toBlend()
        main.items = listOf(ending, item("other"), third)
        run(500)
        assertEquals("other", main.mediaId)
        assertAllBack()
    }

    @Test
    fun theNextSongTakenOutInTheLeadInTheBlendGoesIntoTheOneAfter() {
        toLeadIn()
        main.items = listOf(ending, third)
        runUntil("the blend is done") { handOver != null && tails.all { it.released } && !engine.blending }
        assertEquals("third", main.mediaId)
        assertAllBack()
    }

    @Test
    fun repeatOneSwitchedOnMidBlendTheBlendFinishes() {
        toLeadIn(); toBlend()
        main.repeat = Player.REPEAT_MODE_ONE
        runUntil("the blend is done") { tails.single().released && !engine.blending }
        assertEquals("next", main.mediaId)
        assertAllBack()
    }

    @Test
    fun mixTurnedOffInTheLeadInEverythingIsLetGo() {
        toLeadIn()
        mixOn = false
        run(1_500)
        assertAllBack()
        run(5_000)
        assertNull(handOver)
    }

    @Test
    fun releasedInTheLeadInEverythingIsLetGoAndNothingRunsAfter() {
        toLeadIn()
        engine.release()
        assertAllBack()
        val volumes = main.volumes.size
        val seeks = main.seeks.size
        run(10_000)
        // Nothing left scheduled: no step ran after the release.
        assertEquals(volumes, main.volumes.size)
        assertEquals(seeks, main.seeks.size)
        assertEquals(1, tails.size)
        assertEquals(false, (field("loop") as kotlinx.coroutines.Job).isActive)
    }

    @Test
    fun releasedMidBlendNothingRunsAfter() {
        toLeadIn(); toBlend()
        engine.release()
        val volumes = main.volumes.size + tails.single().volumes.size
        run(10_000)
        assertEquals(volumes, main.volumes.size + tails.single().volumes.size)
        assertAllBack()
    }

    @Test
    fun castingStartedMidBlendEverythingIsLetGo() {
        toLeadIn(); toBlend()
        engine.stopBlend()
        assertAllBack()
        assertNull(state.into.value)
    }

    @Test
    fun releasedDuringTheSkipFadeEverythingIsLetGo() {
        toLeadIn(); toBlend()
        main.moveTo(2, 0)
        run(40)
        assertEquals("SKIP_FADE", phase())
        engine.release()
        assertAllBack()
    }
}
