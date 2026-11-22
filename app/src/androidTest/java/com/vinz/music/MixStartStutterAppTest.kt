package com.vinz.music

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.prefs.EqSettings
import com.vinz.music.playback.AudioDiagnostics
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Plan 5.1 (#409, #448): "a small stutter about 15 s before the end of a song" with Crossfade or the DJ on. That's where
 * Mix's second player starts: it's made and loads the song's ending 14 s before the blend, and starts playing along (muted,
 * to get in step) 5 s before it (Mix.kt: LEAD_MS, PREPARE_MS). This measures the MAIN player, the one being heard, from 3 s
 * before to 3 s after each of those two moments, and over a quiet stretch before them to compare with.
 *
 * No network: two 40 s tones on the phone (44.1 kHz stereo, 440 and 660 Hz), queued (not an album, so they blend), with
 * Crossfade on at a fixed 8 s: the blend at 32 s, the second player made at about 18 s and playing along from 27 s. The
 * phone's media volume must be 0 (the orchestrator mutes it). [secondPlayerOnTheMainSession] is the usual case (equalizer
 * off: the second player shares the main player's audio session and its effects); [secondPlayerOnItsOwnSession] has the
 * equalizer on, flat, which gives the second player its own session (PlaybackService.buildTailPlayer).
 *
 * Measured for the main player in each window:
 * - `underruns`: Media3's onAudioUnderrun (from Android's own count for the track since Media3 1.11), the number asserted;
 * - `trackUnderruns`: AudioTrack.getUnderrunCount()'s rise, read straight from the main player's track (-1: not readable);
 * - `playoutLostMax`/`playoutLostNet`: from the track's AudioTimestamp, how much less sound came out than time went by,
 *   the most in one step (~20 ms apart) and over the window (a gap anywhere after the app, which no underrun count sees);
 * - `positionLagMax`/`positionLostNet`: the player's position against the clock (read on the main thread every 10 ms);
 * - `playbackLateMax`: the longest the main player's playback thread kept a 10 ms tick waiting (its doSomeWork busy or
 *   starved); `mainLateMax`: the same for the app's main thread;
 * - `dropped`: audio buffers the decoder dropped or skipped; `allUnderruns`: Playback diagnostics' count (both players);
 * - `trackRemade`: the main player's audio track made or released.
 *
 * One line, tag MixStutter. A measurement: it runs only when asked for, `-e mixStutter 1`.
 */
@RunWith(AndroidJUnit4::class)
@Measurement
@androidx.annotation.OptIn(UnstableApi::class)
class MixStartStutterAppTest {
    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container

    /** The equalizer and "Keep an album's songs whole" as they were, put back after (a run without the data cleared). */
    private var eqBefore = EqSettings()
    private var keepAlbumsBefore = false

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        runBlocking { c.settings.setMix(false); c.settings.setEq(eqBefore); c.settings.setMixKeepAlbums(keepAlbumsBefore) }
    }

    /** Equalizer off: the second player on the main player's audio session (what most listeners have). */
    @Test fun secondPlayerOnTheMainSession() = measure(eq = false)

    /** Equalizer on (flat): the second player on its own audio session, with its own copy of the equalizer. */
    @Test fun secondPlayerOnItsOwnSession() = measure(eq = true)

    private fun <T> onMain(read: () -> T): T {
        var out: Any? = null
        instrumentation.runOnMainSync { out = read() }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun waitFor(what: String, ms: Long, done: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!onMain(done)) {
            if (SystemClock.elapsedRealtime() > until) throw AssertionError("never: $what")
            Thread.sleep(50)
        }
    }

    private fun measure(eq: Boolean) {
        Fixture.ready()
        eqBefore = c.settings.current.eq
        keepAlbumsBefore = c.settings.current.mix.keepAlbums
        runBlocking {
            c.settings.setMix(true)
            c.settings.setMixAuto(false)
            c.settings.setMixSeconds(BLEND_S)
            c.settings.setMixBeatMatch(false)
            c.settings.setMixKeepAlbums(false)
            c.settings.setEq(EqSettings(enabled = eq))
        }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tones += Fixture.tone("Vinz stutter tone A", SONG_S, hz = 440.0, rate = 44_100, channels = 2)
        tones += Fixture.tone("Vinz stutter tone B", SONG_S, hz = 660.0, rate = 44_100, channels = 2)
        Fixture.launch()
        val local = c.localMusic
        runBlocking { local.scan() }
        val a = local.tracks.value.first { it.song.title == "Vinz stutter tone A" }.song
        val b = local.tracks.value.first { it.song.title == "Vinz stutter tone B" }.song
        val wallStart = System.currentTimeMillis()
        instrumentation.runOnMainSync { c.player.playQueue(listOf(a, b), 0, QueueSource(QueueKind.OTHER, "Mix stutter test")) }
        // Repeat off: a copy left on "repeat one" by another test played the first tone again and again (A16, 2026-11-15).
        repeat(3) { if (c.player.repeatMode.value != androidx.media3.common.Player.REPEAT_MODE_OFF) { instrumentation.runOnMainSync { c.player.cycleRepeat() }; Thread.sleep(700) } }
        waitFor("tone A playing", 20_000) { c.player.currentSong.value?.id == a.id && c.player.isPlaying.value }

        val exo = onMain { c.player.player }
        assertNotNull("the playback service's player", exo)
        val probe = onMain { Probe(exo!!, a.id).also { it.start() } }
        // Through the second player being made and playing along, to the hand-over into tone B.
        waitFor("the blend into tone B", (SONG_S + 20) * 1000L) { c.player.currentSong.value?.id == b.id }
        Thread.sleep(1_000)
        onMain { probe.stop() }
        probe.join()
        val blended = c.mixState.into.value?.songId == b.id

        // The two moments, from the engine's own lines in logcat (VinzMix); else from the blend's mark on the bar.
        val toElapsed = SystemClock.elapsedRealtime() - System.currentTimeMillis()
        val lines = mixLines(wallStart)
        val madeLine = lines.firstOrNull { (_, m) -> m.startsWith("planned ") && m.contains(a.id) }
        val alongLine = lines.firstOrNull { (at, m) -> m.startsWith("the tail starts playing along") && at >= (madeLine?.first ?: 0L) }
        val fromLog = madeLine != null && alongLine != null
        val markMs = probe.markStartMs
        val made = madeLine?.first?.plus(toElapsed) ?: markMs?.let { probe.reached(it - MADE_BEFORE_BLEND_MS) }
        val along = alongLine?.first?.plus(toElapsed) ?: markMs?.let { probe.reached(it - PLAYS_ALONG_BEFORE_BLEND_MS) }
        assertNotNull("when the second player was made (no VinzMix line, no mark)", made)
        assertNotNull("when the second player started playing along", along)

        val windows = listOf(
            probe.window("quiet", made!! - 8_000, made - 4_000),
            probe.window("made", made - 3_000, made + 3_000),
            probe.window("playsAlong", along!! - 3_000, along + 3_000),
        )
        val summary = "eq=${if (eq) "on (own session)" else "off (shared session)"} moments=${if (fromLog) "logcat" else "mark"} " +
            "made at ${probe.positionAt(made)} ms, plays along at ${probe.positionAt(along)} ms, blended=$blended; " +
            windows.joinToString("; ") { it.line }
        Log.i(TAG, summary)

        assertTrue("the tones blended ($summary)", blended)
        for (w in windows.drop(1)) {
            assertEquals("main player underruns around '${w.name}' ($summary)", 0, w.underruns)
            assertTrue("main player's track underruns around '${w.name}' ($summary)", w.trackUnderruns <= 0)
        }
    }

    /** MixEngine's VinzMix lines since [sinceWallMs]: (wall clock ms, message). */
    private fun mixLines(sinceWallMs: Long): List<Pair<Long, String>> {
        val pfd = instrumentation.uiAutomation.executeShellCommand("logcat -d -v epoch -s VinzMix:I")
        val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        val line = Regex("""^\s*(\d+)\.(\d{3})\s.*?VinzMix\s*:\s(.*)$""")
        return text.lines().mapNotNull { l ->
            val m = line.find(l) ?: return@mapNotNull null
            val at = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong()
            if (at >= sinceWallMs) at to m.groupValues[3] else null
        }
    }

    private class Window(val name: String, val underruns: Int, val trackUnderruns: Int, val line: String)

    /** A tick every [everyMs] on [looper]: how late it ran (its thread busy, or not given the CPU). */
    private class Ticker(looper: Looper, private val everyMs: Long, private val onTick: (at: Long, lateMs: Long) -> Unit) : Runnable {
        private val handler = Handler(looper)
        @Volatile private var on = false
        private var due = 0L

        fun start() { on = true; due = SystemClock.uptimeMillis() + everyMs; handler.postAtTime(this, due) }
        fun stop() { on = false; handler.removeCallbacks(this) }

        override fun run() {
            if (!on) return
            val now = SystemClock.uptimeMillis()
            onTick(SystemClock.elapsedRealtime(), now - due)
            due = now + everyMs
            handler.postAtTime(this, due)
        }
    }

    private class Pos(val at: Long, val ms: Long, val counting: Boolean)
    private class Late(val at: Long, val ms: Long)
    private class Count(val at: Long, val n: Long)
    private class TrackRead(val at: Long, val track: Int, val underruns: Int, val frames: Long, val nanos: Long, val rate: Int)

    /** What's recorded of the main player [exo] while tone [songId] plays. Made, started and stopped on the main thread. */
    private inner class Probe(private val exo: ExoPlayer, private val songId: String) {
        val positions: MutableList<Pos> = Collections.synchronizedList(ArrayList())
        val mainLate: MutableList<Late> = Collections.synchronizedList(ArrayList())
        val playbackLate: MutableList<Late> = Collections.synchronizedList(ArrayList())
        val dropped: MutableList<Count> = Collections.synchronizedList(ArrayList())
        val allUnderruns: MutableList<Count> = Collections.synchronizedList(ArrayList())
        val tracks: MutableList<TrackRead> = Collections.synchronizedList(ArrayList())
        val underrunsAt: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val trackEventsAt: MutableList<Long> = Collections.synchronizedList(ArrayList())
        @Volatile var markStartMs: Long? = null

        private val audioRenderer: Renderer? = (0 until exo.rendererCount).firstOrNull { exo.getRendererType(it) == C.TRACK_TYPE_AUDIO }?.let(exo::getRenderer)

        private val listener = object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                underrunsAt += SystemClock.elapsedRealtime()
            }
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                trackEventsAt += SystemClock.elapsedRealtime()
            }
            override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                trackEventsAt += SystemClock.elapsedRealtime()
            }
        }

        private val main = Ticker(Looper.getMainLooper(), 10) { at, late ->
            mainLate += Late(at, late)
            val counting = exo.isPlaying && exo.playbackParameters.speed == 1f && exo.currentMediaItem?.mediaId == songId
            positions += Pos(at, exo.currentPosition, counting)
            exo.audioDecoderCounters?.let { d -> d.ensureUpdated(); dropped += Count(at, (d.droppedBufferCount + d.skippedOutputBufferCount).toLong()) }
            c.mixState.mark.value?.takeIf { it.songId == songId }?.let { markStartMs = it.startMs }
        }

        private val playback = Ticker(exo.playbackLooper, 10) { at, late -> playbackLate += Late(at, late) }

        @Volatile private var sampling = true
        private val sampler = Thread({
            val ts = AudioTimestamp()
            while (sampling) {
                val at = SystemClock.elapsedRealtime()
                allUnderruns += Count(at, AudioDiagnostics.session.snapshot().underruns.toLong())
                audioTrack()?.let { t ->
                    runCatching {
                        val got = t.getTimestamp(ts)
                        tracks += TrackRead(at, System.identityHashCode(t), t.underrunCount, if (got) ts.framePosition else -1, if (got) ts.nanoTime else -1, t.sampleRate)
                    }
                }
                Thread.sleep(20)
            }
        }, "MixStutterSampler")

        fun start() {
            exo.addAnalyticsListener(listener)
            main.start()
            playback.start()
            sampler.start()
        }

        fun stop() {
            main.stop()
            playback.stop()
            sampling = false
            exo.removeAnalyticsListener(listener)
        }

        fun join() = sampler.join(2_000)

        /** The main player's AudioTrack now, through Media3's own fields (renderer → sink → output → track); null if unreadable. */
        private fun audioTrack(): AudioTrack? = runCatching {
            var sink: Any? = field(audioRenderer ?: return null, "audioSink")
            while (sink != null && sink.javaClass.name != "androidx.media3.exoplayer.audio.DefaultAudioSink") sink = field(sink, "sink")
            var output: Any? = field(sink ?: return null, "audioOutput")
            while (output != null && output.javaClass.name == "androidx.media3.exoplayer.audio.ForwardingAudioOutput") output = field(output, "audioOutput")
            output?.javaClass?.getMethod("getAudioTrack")?.invoke(output) as AudioTrack?
        }.getOrNull()

        private fun field(of: Any, name: String): Any? {
            var k: Class<*>? = of.javaClass
            while (k != null) {
                runCatching { return k.getDeclaredField(name).apply { isAccessible = true }.get(of) }
                k = k.superclass
            }
            return null
        }

        /** When the main player first played to [positionMs] of the tone (elapsed ms), or null. */
        fun reached(positionMs: Long): Long? = synchronized(positions) { positions.firstOrNull { it.counting && it.ms >= positionMs }?.at }

        /** Where in the tone the main player was at [at] (elapsed ms). */
        fun positionAt(at: Long): Long = synchronized(positions) { positions.lastOrNull { it.at <= at }?.ms ?: -1 }

        fun window(name: String, from: Long, to: Long): Window {
            fun <T> inside(list: MutableList<T>, at: (T) -> Long) = synchronized(list) { list.filter { at(it) in from..to } }
            val underruns = inside(underrunsAt) { it }.size
            val remade = inside(trackEventsAt) { it }.size
            val reads = inside(tracks) { it.at }
            // Android's own underrun count, per track (a track made again starts at 0).
            val trackUnderruns = if (reads.isEmpty()) -1 else reads.groupBy { it.track }.values.sumOf { r -> r.maxOf { it.underruns } - r.minOf { it.underruns } }
            // Sound out against time: between two timestamps of the same track, the time gone by less the frames played.
            var lostMax = 0.0
            var lostNet = 0.0
            val stamped = reads.filter { it.nanos > 0 }
            for ((x, y) in stamped.zipWithNext()) {
                if (x.track != y.track || y.nanos <= x.nanos || y.rate <= 0) continue
                val lost = (y.nanos - x.nanos) / 1e6 - (y.frames - x.frames) * 1000.0 / y.rate
                lostMax = maxOf(lostMax, lost)
                lostNet += lost
            }
            val pos = inside(positions) { it.at }.filter { it.counting }
            var lagMax = 0L
            for ((x, y) in pos.zipWithNext()) lagMax = maxOf(lagMax, (y.at - x.at) - (y.ms - x.ms))
            val lostByPosition = if (pos.size < 2) 0L else (pos.last().at - pos.first().at) - (pos.last().ms - pos.first().ms)
            val playbackLateMax = inside(playbackLate) { it.at }.maxOfOrNull { it.ms } ?: -1
            val mainLateMax = inside(mainLate) { it.at }.maxOfOrNull { it.ms } ?: -1
            fun rise(list: MutableList<Count>) = inside(list) { it.at }.let { if (it.isEmpty()) -1 else it.last().n - it.first().n }
            val line = "$name[${positionAt(from)}..${positionAt(to)} ms]: underruns=$underruns trackUnderruns=$trackUnderruns " +
                "playoutLostMax=%.1f playoutLostNet=%.1f positionLagMax=$lagMax positionLostNet=$lostByPosition ".format(java.util.Locale.US, lostMax, lostNet) +
                "playbackLateMax=$playbackLateMax mainLateMax=$mainLateMax dropped=${rise(dropped)} allUnderruns=${rise(allUnderruns)} " +
                "trackRemade=$remade trackReads=${reads.size}"
            return Window(name, underruns, trackUnderruns, line)
        }
    }

    private companion object {
        const val TAG = "MixStutter"
        const val SONG_S = 40
        const val BLEND_S = 8
        /** Mix.kt: the second player is made LEAD_MS + PREPARE_MS + 3 s before the blend, and plays along from LEAD_MS before it. */
        const val MADE_BEFORE_BLEND_MS = 14_000L
        const val PLAYS_ALONG_BEFORE_BLEND_MS = 5_000L
    }
}
