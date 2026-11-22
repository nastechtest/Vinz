package com.vinz.music.playback

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import com.vinz.music.data.prefs.EqSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

object EqPresets {
    val frequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val presets: Map<String, List<Float>> = linkedMapOf(
        "Flat" to List(10) { 0f },
        "Bass Boost" to listOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Bass Reducer" to listOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f),
        "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -1f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Rock" to listOf(4.5f, 3.5f, 2f, 0f, -1f, -1f, 1f, 2.5f, 3.5f, 4f),
        "Hip-Hop" to listOf(5f, 4.5f, 2f, 3f, -1f, -1f, 1f, -0.5f, 1.5f, 2.5f),
        "Electronic" to listOf(4f, 3.5f, 1f, 0f, -2f, 2f, 0.5f, 1f, 4f, 4.5f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 3f),
        "Classical" to listOf(4f, 3f, 2.5f, 2f, -1f, -1f, 0f, 2f, 3f, 3.5f),
        "Acoustic" to listOf(4f, 3.5f, 2.5f, 1f, 2f, 1.5f, 3f, 3.5f, 3f, 2f),
        "Late Night" to listOf(-3f, -2f, -1f, 0f, 1f, 2f, 1f, 0f, -2f, -4f),
        "Loudness" to listOf(5f, 3f, 0f, 0f, -2f, 0f, -1f, -4f, 4f, 1f),
    )

    /**
     * A preset's name in the listener's language (1.7.9). The stored name stays English, it's the choice itself; the
     * listener's own presets keep the names they gave them.
     */
    fun label(name: String): String = when (name) {
        "Flat" -> com.vinz.music.R.string.eq_flat
        "Bass Boost" -> com.vinz.music.R.string.eq_bass_boost
        "Bass Reducer" -> com.vinz.music.R.string.eq_bass_reducer
        "Treble Boost" -> com.vinz.music.R.string.eq_treble_boost
        "Vocal" -> com.vinz.music.R.string.eq_vocal
        "Pop" -> com.vinz.music.R.string.eq_pop
        "Rock" -> com.vinz.music.R.string.eq_rock
        "Hip-Hop" -> com.vinz.music.R.string.eq_hip_hop
        "Electronic" -> com.vinz.music.R.string.eq_electronic
        "Jazz" -> com.vinz.music.R.string.eq_jazz
        "Classical" -> com.vinz.music.R.string.eq_classical
        "Acoustic" -> com.vinz.music.R.string.eq_acoustic
        "Late Night" -> com.vinz.music.R.string.eq_late_night
        "Loudness" -> com.vinz.music.R.string.eq_loudness
        "Custom" -> com.vinz.music.R.string.eq_custom
        else -> null
    }?.let { com.vinz.music.tr(it) } ?: name
}

data class SpatialInfo(
    val dolbyDetected: Boolean,
    val dolbyEffects: List<String>,
    val hasEffectPanel: Boolean,
    val spatializerAvailable: Boolean,
    val spatializerEnabled: Boolean,
    val spatializerLevel: String,
    val headTracking: Boolean,
    val output: String,
)

/**
 * The order audio-effect work runs in, on one thread of its own ([executor]). Every effect call is a round trip to
 * Android's audio server, which can stall for seconds (a stuck audioserver after a reboot, a Bluetooth route change,
 * an OEM effect engine): on the main thread that froze Vinz ("isn't responding", seen on the emulator in
 * `DynamicsProcessing.setNumberArray`). Here the caller never waits.
 *
 * [run] keeps strict order. [applyLatest] is for "make the effects match the settings": while one is waiting its turn,
 * more requests don't queue more work, since the waiting one reads the newest settings when it runs (a dragged EQ
 * slider is one apply, not hundreds behind a stalled server). [close] runs its last task after everything already
 * queued, then stops taking work.
 */
internal class EffectQueue(private val executor: ExecutorService) {
    private val applyQueued = AtomicBoolean(false)

    fun run(task: () -> Unit) {
        try {
            executor.execute {
                try { task() } catch (e: Throwable) { android.util.Log.w("VinzEffects", "effect work failed", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed: the service is gone, so there's nothing left to change.
        }
    }

    fun applyLatest(task: () -> Unit) {
        if (applyQueued.compareAndSet(false, true)) run { applyQueued.set(false); task() }
    }

    fun close(last: () -> Unit) {
        run(last)
        executor.shutdown()
    }

    companion object {
        fun thread(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VinzEffects").apply { isDaemon = true }
        }
    }
}

/**
 * Owns the audio effect chain attached to the player's audio session:
 * a 10-band EQ (DynamicsProcessing on API 28+, platform Equalizer otherwise),
 * bass boost, loudness enhancer and loudness normalization.
 * It also announces the session so system / OEM engines such as Dolby Atmos
 * can attach their own processing.
 *
 * Callers (the playback service, on the main thread) only hand work over: every effect is created, changed and
 * released on [EffectQueue]'s own thread, in the order asked, and the effect objects and session are only touched
 * there. What callers set ([wanted], [wantedNormalizationDb]) is read by that thread when it gets to it.
 *
 * #519 ("the equalizer, bass boost, loudness and preamp change nothing"): the effects are made only once the session has
 * a track on it ([trackReady]) and only when something is asked of them (the equalizer on, or a normalization gain).
 * They used to be made as the service started, on a session with nothing playing yet. Android keeps such effects in a
 * register of its own (the audio policy's, 512 KB for every app on the phone), and when the app's process ends before
 * anything plays (Android closing a backgrounded Vinz, an update, a swipe), bass boost, virtualizer and loudness
 * enhancer stay in that register for good, until the phone restarts (seen on the A16, Android 16: 32 of them left over
 * 15 days filled it). From then on every effect anyone asks for is refused ("memory limit exceeded"), Vinz's included,
 * silently: the sliders moved and nothing changed. Made after a track, they're let go of with the process (measured).
 */
class AudioEffects(
    private val context: Context,
    executor: ExecutorService = EffectQueue.thread(),
    /** Tells other effect engines (Dolby and the like) about the session; not for Crossfade's short-lived tail session. */
    private val announce: Boolean = true,
) {
    private val queue = EffectQueue(executor)

    // Touched only on the effects thread.
    private var sessionId = 0
    /** A track has been made on [sessionId]: effects can be made on it now (see the class note). */
    private var live = false
    /** A track was made before the session was known: the session that comes next is the one it plays on. */
    private var trackBeforeSession = false
    /** Other effect engines were told about [sessionId] (and so are told when it closes). */
    private var announced = false
    /** Android refused an effect on [sessionId] (logged once for it). */
    private var refusedLogged = false
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    /** Tests only: told of each effect asked of Android, and of each announcement ("make bass 42", "open 42"). */
    @Volatile internal var trace: ((String) -> Unit)? = null
    /** Tests only: the effect kinds ("dynamics", "equalizer", "bass", "loudness") Android is to refuse, as a full register does. */
    @Volatile internal var refuseForTest: ((String) -> Boolean)? = null

    /** Android refused the effects the settings ask for (shown in stats for nerds). */
    @Volatile private var unavailable = false
    private val _refused = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** Android refuses the effects the settings ask for, now (the Equalizer screen says so). */
    val refused: kotlinx.coroutines.flow.StateFlow<Boolean> = _refused

    // Set by callers, read by the effects thread: the newest settings always win.
    @Volatile private var wanted = EqSettings()
    @Volatile private var wantedNormalizationDb = 0f
    /** Whether the 10-band DynamicsProcessing EQ is the one in use (for [activeSummary]). */
    @Volatile private var tenBand = false

    val activeSummary: String
        get() {
            val s = wanted
            val norm = wantedNormalizationDb
            return buildList {
                if (s.enabled && unavailable) add("EQ refused by Android")
                else if (s.enabled) add(if (tenBand) "EQ 10-band" else "EQ")
                if (s.enabled && s.bassBoost > 0 && !unavailable) add("Bass")
                if (norm != 0f) add("Normalize %.1f dB".format(norm))
            }.joinToString(" · ").ifEmpty { "None" }
        }

    /** Moves the effects to the player's audio session [newSession] (0 is ignored). */
    fun attach(newSession: Int) {
        if (newSession == 0) return
        queue.run { attachNow(newSession) }
    }

    /**
     * The player made its audio track (Media3's onAudioTrackInitialized): the session now plays on an output, and the
     * effects are made on it if the settings ask for them.
     */
    fun trackReady() {
        queue.run { trackReadyNow() }
    }

    fun setNormalization(gainDb: Float) {
        wantedNormalizationDb = gainDb
        queue.applyLatest { applyNow() }
    }

    fun apply(s: EqSettings) {
        wanted = s
        queue.applyLatest { applyNow() }
    }

    /** Lets go of the effects and tells other effect engines the session is closed. */
    fun release(broadcast: Boolean = true) {
        queue.run { releaseNow(broadcast) }
    }

    /** The service is going: the effects are released after anything still queued, and the thread then ends. */
    fun shutdown() {
        queue.close { releaseNow(broadcast = true); sessionId = 0 }
    }

    private fun attachNow(newSession: Int) {
        if (newSession == sessionId) return
        // A track reported before the first session was known plays on this one (Media3 hands the session over late).
        val carried = sessionId == 0 && trackBeforeSession
        releaseNow(broadcast = true)
        sessionId = newSession
        live = false
        trackBeforeSession = false
        refusedLogged = false
        setUnavailable(false)
        if (carried) trackReadyNow()
    }

    private fun trackReadyNow() {
        if (sessionId == 0) { trackBeforeSession = true; return }
        if (live) return
        live = true
        if (announce) {
            trace?.invoke("open $sessionId")
            context.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
            announced = true
        }
        applyNow()
    }

    /** [make]s an effect on the session; null, and noted once for the session, when Android refuses it. */
    private fun <T : AudioEffect> make(kind: String, make: (Int) -> T): T? {
        trace?.invoke("make $kind $sessionId")
        return runCatching {
            if (refuseForTest?.invoke(kind) == true) throw RuntimeException("Cannot initialize effect engine (test)")
            make(sessionId)
        }.onFailure { e ->
            if (!refusedLogged) {
                refusedLogged = true
                android.util.Log.w("VinzEffects", "Android refused the $kind effect on session $sessionId", e)
                AudioDiagnostics.log(
                    "effects",
                    "Android refused the $kind effect on session $sessionId (${e.message}). When every effect is refused, " +
                        "Android's room for effects is full (left over by apps that ended before playing); restarting the phone frees it.",
                )
            }
        }.getOrNull()
    }

    private fun applyNow() {
        val s = wanted
        val normalizationDb = wantedNormalizationDb
        // Nothing is made on a session without a track (see the class note), and each effect only when the settings ask
        // something of it: Android's register is shared by every app on the phone, so Vinz holds as little of it as it
        // can. One already made stays for the session and is switched off when it's no longer asked for.
        if (sessionId == 0 || !live) return
        val asks = Asks.of(s, normalizationDb)
        val preGain = normalizationDb + if (s.enabled) s.preampDb else 0f
        if (!asks.equalizer && dynamics == null && equalizer == null) {
            tenBand = false
        } else if (Build.VERSION.SDK_INT >= 28) {
            val dp = dynamics ?: make("dynamics") { session ->
                DynamicsProcessing(
                    0, session,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, 10, false, 0, false, 0, true,
                    ).build()
                )
            }?.also { dynamics = it }
            val set = dp != null && runCatching {
                val eq = DynamicsProcessing.Eq(true, true, 10)
                for (i in 0 until 10) {
                    eq.setBand(i, DynamicsProcessing.EqBand(true, EqPresets.frequencies[i], if (s.enabled) s.bands.getOrElse(i) { 0f } else 0f))
                }
                dp.setPreEqAllChannelsTo(eq)
                dp.setInputGainAllChannelsTo(preGain)
                dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 1, 1f, 60f, 10f, -1f, 0f))
                dp.enabled = asks.equalizer
            }.isSuccess
            if (!set) {
                // Let go of it now rather than whenever it's collected, and fall back on the platform equalizer.
                runCatching { dynamics?.release() }
                dynamics = null
                applyLegacyEq(s, normalizationDb, asks.equalizer)
            }
        } else applyLegacyEq(s, normalizationDb, asks.equalizer)
        tenBand = dynamics != null

        val b = bass ?: if (asks.bass) make("bass") { BassBoost(0, it) }?.also { bass = it } else null
        runCatching {
            if (b != null) {
                if (b.strengthSupported) b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
                b.enabled = asks.bass
            }
        }
        // No platform virtualizer: Vinz Spatial (software) took its place, and it was only ever made to be switched off.
        val l = loudness ?: if (asks.loudness) make("loudness") { LoudnessEnhancer(it) }?.also { loudness = it } else null
        runCatching {
            if (l != null) {
                l.setTargetGain(s.loudnessGainMb.coerceAtLeast(0))
                l.enabled = asks.loudness
            }
        }
        // Refused: something was asked and Android made none of it (as when its register is full). An effect this phone
        // doesn't have at all isn't a refusal.
        val asked = listOf(
            asks.equalizer to (dynamics ?: equalizer),
            (asks.bass && hasType(AudioEffect.EFFECT_TYPE_BASS_BOOST)) to bass,
            (asks.loudness && hasType(AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER)) to loudness,
        ).filter { it.first }
        setUnavailable(asked.isNotEmpty() && asked.all { it.second == null })
    }

    /** Effect types this phone has (null: unknown, so every type counts as present). */
    private val types: Set<java.util.UUID>? by lazy {
        runCatching { AudioEffect.queryEffects()?.mapNotNull { it?.type }?.toSet() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun hasType(type: java.util.UUID?): Boolean = type == null || types?.contains(type) != false

    private fun setUnavailable(now: Boolean) {
        unavailable = now
        _refused.value = now
    }

    /** What the settings ask of the platform effects. */
    internal data class Asks(val equalizer: Boolean, val bass: Boolean, val loudness: Boolean) {
        companion object {
            /**
             * The equalizer (DynamicsProcessing or Equalizer) when it's on with a band or the preamp away from 0, or for a
             * normalization gain; bass boost and loudness only when on and above 0. The equalizer on but flat asks for
             * nothing: no platform effect made just to pass the sound through.
             */
            fun of(s: EqSettings, normalizationDb: Float) = Asks(
                equalizer = normalizationDb != 0f || (s.enabled && (s.preampDb != 0f || s.bands.any { it != 0f })),
                bass = s.enabled && s.bassBoost > 0,
                loudness = s.enabled && s.loudnessGainMb > 0,
            )
        }
    }

    private fun applyLegacyEq(s: EqSettings, normalizationDb: Float, on: Boolean) {
        val eq = equalizer ?: make("equalizer") { Equalizer(0, it) }?.also { equalizer = it } ?: return
        runCatching {
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                val nearest = EqPresets.frequencies.indices.minBy { kotlin.math.abs(EqPresets.frequencies[it] - centerHz) }
                val mb = ((s.bands.getOrElse(nearest) { 0f } + s.preampDb + normalizationDb) * 100).roundToInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
            eq.enabled = on
        }
    }

    private fun releaseNow(broadcast: Boolean) {
        if (sessionId != 0 && broadcast && announced) {
            trace?.invoke("close $sessionId")
            context.sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            })
            announced = false
        }
        listOf(dynamics, equalizer, bass, loudness).forEach { runCatching { it?.release() } }
        dynamics = null; equalizer = null; bass = null; loudness = null
        tenBand = false
    }

    companion object {
        fun spatialInfo(context: Context): SpatialInfo {
            val effects = runCatching { AudioEffect.queryEffects().toList() }.getOrDefault(emptyList())
            val dolby = effects.filter {
                it.implementor.contains("dolby", true) || it.name.contains("dolby", true) ||
                    it.name.contains("atmos", true) || it.name.contains("DAP", false)
            }.map { it.name }.distinct()
            val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .resolveActivity(context.packageManager) != null
            val am = context.getSystemService(AudioManager::class.java)
            var available = false
            var enabled = false
            var level = "Not supported"
            var head = false
            if (Build.VERSION.SDK_INT >= 32 && am != null) {
                val sp = am.spatializer
                available = sp.isAvailable
                enabled = sp.isEnabled
                level = when (sp.immersiveAudioLevel) {
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "Multichannel"
                    android.media.Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "None"
                    else -> "Other"
                }
                if (Build.VERSION.SDK_INT >= 33) head = sp.isHeadTrackerAvailable
            }
            return SpatialInfo(dolby.isNotEmpty(), dolby, panel, available, enabled, level, head, outputDevice(context))
        }

        fun outputDevice(context: Context): String {
            val am = context.getSystemService(AudioManager::class.java) ?: return "Unknown"
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val preferred = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
            val d = preferred.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } } ?: return "Unknown"
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
                else -> "Speaker"
            }
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
            return if (name != null) "$kind · $name" else kind
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
            AudioFormat.ENCODING_OPUS -> "Opus (offload)"
            AudioFormat.ENCODING_AAC_LC -> "AAC (offload)"
            else -> "Encoding $encoding"
        }
    }
}
