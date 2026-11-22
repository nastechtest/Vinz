package com.vinz.music

import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vinz.music.data.prefs.EqSettings
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #519 ("Equalizer not working"): plays a tone through the real playback chain and reads what the equalizer, preamp,
 * bass boost and loudness do to it, on the phone. A measurement (`-e eqCheck 1`); one line per step, tag Eq519.
 *
 * Read for each step: the state of Vinz's effect objects (session, control, enabled), the effect chain Android's audio
 * server holds for the track's session (`dumpsys media.audio_flinger`), and a platform Visualizer's peak and RMS on the
 * same session. The phone's media volume stays at 0: whether the Visualizer still sees the sound then is part of what
 * this finds out.
 */
@RunWith(AndroidJUnit4::class)
@Measurement
@androidx.annotation.OptIn(UnstableApi::class)
class EqEffect519AppTest {
    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = Fixture.container
    private var visualizer: Visualizer? = null

    @After fun cleanUp() {
        runCatching { visualizer?.release() }
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
        while (!onMain(done)) {
            if (SystemClock.elapsedRealtime() > until) throw AssertionError("never: $what")
            Thread.sleep(50)
        }
    }

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { java.io.FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }

    private fun log(line: String) {
        // Logcat cuts long lines: split them.
        line.chunked(3000).forEach { Log.i(TAG, it) }
    }

    /** Vinz's AudioEffects in the running service, by reflection (debug build). */
    private fun effectsObject(): Any? = runCatching {
        val conn = c.player
        val f = conn.javaClass.getDeclaredField("service").apply { isAccessible = true }
        val service = f.get(conn) ?: return null
        service.javaClass.superclass.let { cls ->
            var k: Class<*>? = service.javaClass
            while (k != null) {
                runCatching { return k.getDeclaredField("effects").apply { isAccessible = true }.get(service) }
                k = k.superclass
            }
        }
        null
    }.getOrNull()

    private fun effectsState(): String {
        val e = effectsObject() ?: return "effects: not found"
        fun field(name: String) = runCatching { e.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(e) }.getOrNull()
        val parts = mutableListOf("session=${field("sessionId")}")
        for (name in listOf("dynamics", "equalizer", "bass", "virtualizer", "loudness")) {
            val fx = field(name) as? AudioEffect
            parts += if (fx == null) "$name=null" else {
                val control = runCatching { fx.hasControl() }.getOrElse { "x:${it.javaClass.simpleName}" }
                val enabled = runCatching { fx.enabled }.getOrElse { "x:${it.javaClass.simpleName}" }
                val extra = when (fx) {
                    is android.media.audiofx.BassBoost -> " strength=${runCatching { fx.roundedStrength }.getOrNull()} supported=${runCatching { fx.strengthSupported }.getOrNull()}"
                    is android.media.audiofx.LoudnessEnhancer -> " gain=${runCatching { fx.targetGain }.getOrNull()}"
                    is android.media.audiofx.DynamicsProcessing -> runCatching {
                        val eq = fx.getPreEqByChannelIndex(0)
                        " inGain=${fx.getInputGainByChannelIndex(0)} bands=" + (0 until eq.bandCount).joinToString(",") { "%.0f".format(eq.getBand(it).gain) }
                    }.getOrElse { " read:${it.javaClass.simpleName}" }
                    else -> ""
                }
                "$name(id=${runCatching { fx.id }.getOrNull()} control=$control enabled=$enabled$extra)"
            }
        }
        return parts.joinToString(" ")
    }

    /** The audio server's view of Vinz's tracks and the effect chains on their sessions. */
    private fun serverState(session: Int): String {
        val dump = shell("dumpsys media.audio_flinger")
        val pid = android.os.Process.myPid()
        val lines = dump.lines()
        val keep = StringBuilder()
        // Tracks of this process (the pid column) and every "Effect Chain"/"Session" block naming the session.
        for ((i, l) in lines.withIndex()) {
            if (l.contains(" $pid ") && (l.contains("Track") || l.trim().startsWith("Y") || l.trim().startsWith("N") || l.contains("$session"))) keep.append("T: ").append(l.trim()).append('\n')
        }
        // The chain on the session, in order: each effect's name, state and flags.
        val at = lines.indexOfFirst { it.contains("effects for session $session") }
        if (at >= 0) for (l in lines.subList(at, minOf(lines.size, at + 120))) {
            val t = l.trim()
            if (t.isNotEmpty() && !t.contains("Type     Id Active")) keep.append("E: ").append(t.take(200)).append('\n')
        }
        shell("mkdir -p /sdcard/vinz-tests")
        java.io.File(Fixture.context.getExternalFilesDir(null), "eq519-af-${SystemClock.elapsedRealtime()}.txt").writeText(dump)
        return keep.toString()
    }

    private fun visualizerOn(session: Int): Visualizer? = runCatching {
        Visualizer(session).apply {
            captureSize = Visualizer.getCaptureSizeRange()[1]
            measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            enabled = true
        }
    }.onFailure { log("visualizer on session $session failed: $it") }.getOrNull()

    /** Peak and RMS (mB) from the Visualizer over [ms], the median of reads every 100 ms; plus the FFT's loudest bin. */
    private fun level(v: Visualizer?, ms: Long = 2_500): String {
        v ?: return "no visualizer"
        val peaks = ArrayList<Int>(); val rmss = ArrayList<Int>()
        val m = Visualizer.MeasurementPeakRms()
        val until = SystemClock.elapsedRealtime() + ms
        var fftNote = ""
        while (SystemClock.elapsedRealtime() < until) {
            if (v.getMeasurementPeakRms(m) == Visualizer.SUCCESS) { peaks += m.mPeak; rmss += m.mRms }
            Thread.sleep(100)
        }
        runCatching {
            val fft = ByteArray(v.captureSize)
            if (v.getFft(fft) == Visualizer.SUCCESS) {
                var best = 0; var bestMag = -1.0
                for (k in 1 until fft.size / 2) {
                    val re = fft[2 * k].toDouble(); val im = fft[2 * k + 1].toDouble()
                    val mag = re * re + im * im
                    if (mag > bestMag) { bestMag = mag; best = k }
                }
                fftNote = " fftBin=$best mag=${"%.0f".format(bestMag)}"
            }
            val wave = ByteArray(v.captureSize)
            if (v.getWaveForm(wave) == Visualizer.SUCCESS) {
                val dev = wave.map { (it.toInt() and 0xff) - 128 }
                fftNote += " waveRange=${dev.min()}..${dev.max()}"
            }
        }
        fun med(l: List<Int>) = l.sorted().getOrNull(l.size / 2)
        return "peak=${med(peaks)} rms=${med(rmss)} n=${rmss.size}$fftNote"
    }

    private fun step(name: String, eq: EqSettings, v: Visualizer?, session: Int) {
        runBlocking { c.settings.setEq(eq) }
        Thread.sleep(1_500)
        val lvl = level(v)
        log("STEP $name | eq=$eq | $lvl | ${effectsState()}")
    }

    @Test fun equalizerChangesTheSound() {
        Fixture.ready()
        runBlocking { c.settings.setEq(EqSettings()) }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, android.Manifest.permission.RECORD_AUDIO)
        tones += Fixture.tone("Vinz eq tone 1k", 90, hz = 1000.0, rate = 44_100, channels = 2)
        tones += Fixture.tone("Vinz eq tone 60", 90, hz = 60.0, rate = 44_100, channels = 2)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val k1 = c.localMusic.tracks.value.first { it.song.title == "Vinz eq tone 1k" }.song
        val low = c.localMusic.tracks.value.first { it.song.title == "Vinz eq tone 60" }.song
        log("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} sdk ${android.os.Build.VERSION.SDK_INT}; " +
            "music volume ${shell("cmd media_session volume --stream 3 --get").trim().replace('\n', ' ')}")
        log("effects on this phone: " + AudioEffect.queryEffects().joinToString("; ") { "${it.name} (${it.implementor}) type=${it.type}" })
        log("before playing: ${registry()}")

        instrumentation.runOnMainSync { c.player.playQueue(listOf(k1, low), 0, QueueSource(QueueKind.OTHER, "EQ check")) }
        repeat(3) { if (c.player.repeatMode.value != androidx.media3.common.Player.REPEAT_MODE_OFF) { instrumentation.runOnMainSync { c.player.cycleRepeat() }; Thread.sleep(700) } }
        waitFor("1k tone playing", 20_000) { c.player.currentSong.value?.id == k1.id && c.player.isPlaying.value }
        Thread.sleep(1_500)
        val session = onMain { c.player.player!!.audioSessionId }
        log("player session $session; ${effectsState()}")
        visualizer = visualizerOn(session)
        log("server (EQ off):\n" + serverState(session))

        val flatOn = EqSettings(enabled = true)
        step("off", EqSettings(), visualizer, session)
        step("on-flat", flatOn, visualizer, session)
        step("1k-cut-12", flatOn.copy(preset = "Custom", bands = List(10) { if (it == 5) -12f else 0f }), visualizer, session)
        step("1k-boost+12", flatOn.copy(preset = "Custom", bands = List(10) { if (it == 5) 12f else 0f }), visualizer, session)
        step("preamp-12", flatOn.copy(preampDb = -12f), visualizer, session)
        step("loudness+15", flatOn.copy(loudnessGainMb = 1500), visualizer, session)
        runBlocking { c.settings.setEq(flatOn.copy(preset = "Custom", bands = List(10) { if (it == 5) -12f else 0f })) }
        Thread.sleep(1_000)
        log("server (EQ on, 1k cut):\n" + serverState(session))
        step("off-again", EqSettings(), visualizer, session)

        // The 60 Hz tone: bass boost and the 62 Hz band.
        instrumentation.runOnMainSync { c.player.next() }
        waitFor("60 Hz tone playing", 20_000) { c.player.currentSong.value?.id == low.id && c.player.isPlaying.value }
        Thread.sleep(1_500)
        val session2 = onMain { c.player.player!!.audioSessionId }
        if (session2 != session) { runCatching { visualizer?.release() }; visualizer = visualizerOn(session2) }
        step("60-off", EqSettings(), visualizer, session2)
        step("60-on-flat", flatOn, visualizer, session2)
        step("60-bass1000", flatOn.copy(bassBoost = 1000), visualizer, session2)
        step("60-band62+12", flatOn.copy(preset = "Custom", bands = List(10) { if (it == 1) 12f else 0f }), visualizer, session2)
        step("60-band62-12", flatOn.copy(preset = "Custom", bands = List(10) { if (it == 1) -12f else 0f }), visualizer, session2)
        log("server (60 Hz, band cut):\n" + serverState(session2))
        step("60-off-again", EqSettings(), visualizer, session2)
        log("after: ${registry()}")
    }

    /** The audio policy's register of effects: how many, and the memory they hold of its budget (512 KB on AOSP). */
    private fun registry(): String {
        val dump = shell("dumpsys media.audio_policy")
        val total = Regex("Total Effects memory: (\\d+) KB").find(dump)?.groupValues?.get(1)
        val count = Regex("- Effects \\((\\d+)\\)").find(dump)?.groupValues?.get(1)
        val noIo = Regex("Attached to I/O handle: 0;").findAll(dump).count()
        return "registry: $count effects, $total KB, $noIo on no output"
    }

    /**
     * Whether an effect made on a session with no track yet (what Vinz does as its service starts, and for Crossfade's
     * second player) stays in the audio policy's register after it is released, and the same with a track playing.
     */
    @Test fun effectRegistryAfterRelease() {
        val am = Fixture.context.getSystemService(android.media.AudioManager::class.java)
        log("start: ${registry()}")
        repeat(3) { i ->
            val s = am.generateAudioSessionId()
            val made = runCatching {
                listOf(
                    android.media.audiofx.DynamicsProcessing(0, s, null), android.media.audiofx.BassBoost(0, s),
                    android.media.audiofx.LoudnessEnhancer(s),
                )
            }.onFailure { log("no track, round $i: could not make the effects: $it") }.getOrNull()
            log("no track, round $i, made on $s: ${registry()}")
            made?.forEach { it.release() }
            Thread.sleep(300)
            log("no track, round $i, released: ${registry()}")
        }
        // The two orders: the effects released before the track ([trackLast]), or the track first (as when the player is
        // released before the effects thread gets to them).
        for ((i, trackLast) in listOf(0 to true, 1 to true, 2 to false, 3 to false)) {
            val s = am.generateAudioSessionId()
            val rate = 48_000
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
                .setSessionId(s).setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(rate * 4).build()
            track.play()
            track.write(ShortArray(rate * 2), 0, rate * 2) // a second of silence, so the track is live on an output
            val made = runCatching {
                listOf(android.media.audiofx.DynamicsProcessing(0, s, null), android.media.audiofx.BassBoost(0, s), android.media.audiofx.LoudnessEnhancer(s))
            }.onFailure { log("with a track, round $i: could not make the effects: $it") }.getOrNull()
            log("with a track, round $i (track released ${if (trackLast) "last" else "first"}), made on $s: ${registry()}")
            if (trackLast) { made?.forEach { it.release() }; track.stop(); track.release() }
            else { track.stop(); track.release(); Thread.sleep(300); log("  track released: ${registry()}"); made?.forEach { it.release() } }
            Thread.sleep(300)
            log("with a track, round $i, released: ${registry()}")
        }
    }

    /**
     * Effects made on a session with no track (as Vinz's service makes them when it starts, before anything plays) and
     * never released: the process then ends with them (the orchestrator stops it after the test). Read the register with
     * `adb shell dumpsys media.audio_policy` afterwards: whether they outlive the process.
     */
    @Test fun effectsLeftAtProcessDeathWithNoTrack() {
        val am = Fixture.context.getSystemService(android.media.AudioManager::class.java)
        log("start: ${registry()}")
        val s = am.generateAudioSessionId()
        kept.addAll(listOf(
            android.media.audiofx.DynamicsProcessing(0, s, null), android.media.audiofx.BassBoost(0, s),
            android.media.audiofx.Virtualizer(0, s), android.media.audiofx.LoudnessEnhancer(s),
        ))
        log("made on $s and kept: ${registry()}")
    }

    /**
     * Effects made while a track plays on the session, the track then released (as when the player stops), the effects
     * kept until the process ends 10 s later. Read the register afterwards, as for [effectsLeftAtProcessDeathWithNoTrack].
     */
    @Test fun effectsLeftAtProcessDeathAfterTheTrack() {
        val am = Fixture.context.getSystemService(android.media.AudioManager::class.java)
        log("start: ${registry()}")
        val s = am.generateAudioSessionId()
        val rate = 48_000
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setSessionId(s).setTransferMode(android.media.AudioTrack.MODE_STREAM).setBufferSizeInBytes(rate * 4).build()
        track.play()
        track.write(ShortArray(rate * 2), 0, rate * 2)
        kept.addAll(listOf(android.media.audiofx.DynamicsProcessing(0, s, null), android.media.audiofx.BassBoost(0, s), android.media.audiofx.LoudnessEnhancer(s)))
        log("made on $s with a track: ${registry()}")
        track.stop(); track.release()
        Thread.sleep(10_000)
        log("track released 10 s ago, effects kept: ${registry()}")
    }

    /**
     * Whether the platform's DynamicsProcessing changes the sound at all here: Vinz's own effects are let go of, a
     * Visualizer is put on the session first and a DynamicsProcessing after it (a later "insert last" effect goes in front
     * of an earlier one, so the Visualizer then reads what comes out of it).
     */
    @Test fun dynamicsProcessingChangesTheTone() {
        Fixture.ready()
        runBlocking { c.settings.setEq(EqSettings()) }
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, android.Manifest.permission.RECORD_AUDIO)
        tones += Fixture.tone("Vinz eq tone 1k", 60, hz = 1000.0, rate = 44_100, channels = 2)
        Fixture.launch()
        runBlocking { c.localMusic.scan() }
        val k1 = c.localMusic.tracks.value.first { it.song.title == "Vinz eq tone 1k" }.song
        instrumentation.runOnMainSync { c.player.playQueue(listOf(k1), 0, QueueSource(QueueKind.OTHER, "EQ check")) }
        waitFor("1k tone playing", 20_000) { c.player.currentSong.value?.id == k1.id && c.player.isPlaying.value }
        Thread.sleep(1_000)
        val session = onMain { c.player.player!!.audioSessionId }
        // Vinz's own effects off the session.
        effectsObject()!!.let { e -> e.javaClass.getMethod("release", Boolean::class.javaPrimitiveType).invoke(e, false) }
        Thread.sleep(1_000)
        log("vinz's effects released: ${effectsState()} ${registry()}")
        visualizer = visualizerOn(session)
        log("visualizer alone: ${level(visualizer)}")
        val dp = android.media.audiofx.DynamicsProcessing(
            1, session,
            android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, true, 10, false, 0, false, 0, true,
            ).build(),
        )
        kept += dp
        fun set(label: String, band5: Float, gain: Float, on: Boolean) {
            val eq = android.media.audiofx.DynamicsProcessing.Eq(true, true, 10)
            for (i in 0 until 10) eq.setBand(i, android.media.audiofx.DynamicsProcessing.EqBand(true, com.vinz.music.playback.EqPresets.frequencies[i], if (i == 5) band5 else 0f))
            dp.setPreEqAllChannelsTo(eq)
            dp.setInputGainAllChannelsTo(gain)
            dp.enabled = on
            Thread.sleep(800)
            log("own DP $label (control ${dp.hasControl()}, enabled ${dp.enabled}): ${level(visualizer)}")
        }
        set("off", 0f, 0f, false)
        set("flat", 0f, 0f, true)
        set("1k band -12", -12f, 0f, true)
        set("1k band +12", 12f, 0f, true)
        set("input -12", 0f, -12f, true)
        set("off again", 0f, 0f, false)
        log("server:\n" + serverState(session))
        dp.release(); kept.remove(dp)
    }

    private companion object {
        const val TAG = "Eq519"
        /** Held past the test, so they're only let go of when the process ends. */
        val kept = ArrayList<Any>()
    }
}
