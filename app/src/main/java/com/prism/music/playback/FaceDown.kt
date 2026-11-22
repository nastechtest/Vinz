package com.vinz.music.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Settings → Playback → "Pause when face down" (1.8.5, off by default): laying the phone screen-down pauses the music;
 * picking it up (screen up again) within five minutes plays on. Vinz's first sensor: the accelerometer is listened to
 * only while the setting is on and music is playing (or a face-down pause is under five minutes old, to resume it), and
 * never otherwise, so the switch off costs nothing.
 *
 * The decisions are [FaceDownDetector]'s (pure, unit-tested); this class wires it to the sensor ([SensorTap]) and the
 * player. A pause or play the listener made themselves, or a new queue, ends a face-down pause's claim: it isn't resumed.
 * It does nothing while [Controls.blocked] (casting, a call, Android Auto).
 */
class FaceDown(
    private val tap: SensorTap,
    private val settingOn: () -> Boolean,
    private val controls: Controls,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Runs a check later (the five minutes running out while paused). */
    private val later: Later = mainLater(),
) : Player.Listener {

    /** A check run later, on the main thread. */
    fun interface Later {
        /** Runs [block] in [ms]; replaces any check still waiting. */
        fun run(ms: Long, block: () -> Unit)
        /** Drops the check still waiting, if any. */
        fun cancel() {}
    }

    /** What [FaceDown] needs of the player and the service. */
    interface Controls {
        /** Music is playing (or about to: asked to play and loading). */
        val playing: Boolean
        /** Not now: casting to another device, a call, the car. */
        val blocked: Boolean
        fun pause()
        fun play()
    }

    /** The accelerometer, or a fake one in tests. */
    interface SensorTap {
        /** Starts sending samples (m/s², the phone's x, y, z axes); false when the phone has no accelerometer. */
        fun start(onSample: (x: Float, y: Float, z: Float) -> Unit): Boolean
        fun stop()
    }

    private val detector = FaceDownDetector()
    var listening = false
        private set
    /** The playWhenReady our own pause / play is about to report (so it isn't taken for the listener's). */
    private var expect: Boolean? = null
    /**
     * Let go ([release]): the setting was switched off (switched on again, the service makes a new one). Nothing here runs
     * again, so a check still waiting can't take the sensor back for a FaceDown nobody tells of the player any more.
     */
    private var released = false

    /** Starts or stops listening to match the setting and playback. Call on the main thread. */
    fun update() {
        if (released) return
        val now = clock()
        val on = settingOn()
        if (!on) detector.forget()
        val want = on && (controls.playing || detector.waiting(now))
        if (want != listening) {
            listening = want
            if (want) {
                detector.restart()
                if (!tap.start(::onSample)) listening = false
            } else tap.stop()
        }
        if (listening && !controls.playing) detector.pausedAt()?.let { at ->
            later.run(at + FaceDownDetector.RESUME_WITHIN_MS - now + 1_000) { update() }
        }
    }

    fun release() {
        released = true
        later.cancel()
        if (listening) tap.stop()
        listening = false
        detector.forget()
    }

    private fun onSample(x: Float, y: Float, z: Float) {
        if (released) return
        if (controls.blocked) { detector.restart(); return }
        when (detector.sample(x, y, z, clock(), controls.playing)) {
            FaceDownDetector.Action.PAUSE -> {
                expect = false
                controls.pause()
                AudioDiagnostics.log("state", "paused: the phone was laid face down")
            }
            FaceDownDetector.Action.RESUME -> {
                expect = true
                controls.play()
                AudioDiagnostics.log("state", "playing on: the phone was picked up")
            }
            FaceDownDetector.Action.NONE -> return
        }
        update()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (expect == playWhenReady) expect = null
        else { expect = null; detector.listenerActed() }
        update()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = update()

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Another queue started (or the song playing was taken away): a face-down pause isn't for it.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) { detector.listenerActed(); update() }
    }

    /** The real accelerometer, at the normal rate (about 5 a second: plenty for "lying still for a second"). */
    class Accelerometer(context: Context) : SensorTap {
        private val manager = context.getSystemService(SensorManager::class.java)
        private var listener: SensorEventListener? = null

        override fun start(onSample: (Float, Float, Float) -> Unit): Boolean {
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) = onSample(e.values[0], e.values[1], e.values[2])
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            listener = l
            return runCatching { manager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL) }.getOrDefault(false)
        }

        override fun stop() {
            listener?.let { runCatching { manager?.unregisterListener(it) } }
            listener = null
        }
    }

    companion object {
        private fun mainLater(): Later = object : Later {
            private val handler by lazy { Handler(Looper.getMainLooper()) }
            private val token = Any()
            override fun run(ms: Long, block: () -> Unit) {
                handler.removeCallbacksAndMessages(token)
                handler.postAtTime({ block() }, token, SystemClock.uptimeMillis() + ms.coerceAtLeast(0))
            }
            override fun cancel() = handler.removeCallbacksAndMessages(token)
        }
    }
}

/**
 * When to pause and when to play on, from the accelerometer's samples (pure, so it's unit-tested).
 *
 * - **Face down**: the screen points at the floor (z below [DOWN_Z]), the phone lies flat (little pull along x and y)
 *   and still (the whole pull about gravity's), every sample for [DOWN_MS]. A phone turned over in the hand, tossed in a
 *   bag or carried while walking doesn't stay like that long. Only after the phone was seen not face down since
 *   listening started ([restart]): music started on a phone already lying face down plays.
 * - **Picked up**: screen up again (z above [UP_Z]) for [UP_MS], within [RESUME_WITHIN_MS] of the face-down pause.
 */
class FaceDownDetector {
    enum class Action { NONE, PAUSE, RESUME }

    private var downSince = -1L
    private var upSince = -1L
    private var armed = false
    private var pausedAt = -1L

    fun sample(x: Float, y: Float, z: Float, at: Long, playing: Boolean): Action {
        val down = z < DOWN_Z && hypot(x, y) < FLAT_XY && abs(sqrt(x * x + y * y + z * z) - GRAVITY) < STILL
        if (!down) { downSince = -1; armed = true } else if (downSince < 0) downSince = at

        if (pausedAt >= 0) {
            if (at - pausedAt > RESUME_WITHIN_MS) { pausedAt = -1; upSince = -1; return Action.NONE }
            if (z > UP_Z) {
                if (upSince < 0) upSince = at
                if (at - upSince >= UP_MS) {
                    pausedAt = -1; upSince = -1
                    return if (playing) Action.NONE else Action.RESUME
                }
            } else upSince = -1
            return Action.NONE
        }
        if (down && armed && playing && at - downSince >= DOWN_MS) {
            pausedAt = at; armed = false; downSince = -1; upSince = -1
            return Action.PAUSE
        }
        return Action.NONE
    }

    /** When the face-down pause waiting to be resumed was made, or null. */
    fun pausedAt(): Long? = pausedAt.takeIf { it >= 0 }

    /** A face-down pause is waiting to be resumed at [now] (under five minutes old). */
    fun waiting(now: Long): Boolean {
        if (pausedAt >= 0 && now - pausedAt > RESUME_WITHIN_MS) pausedAt = -1
        return pausedAt >= 0
    }

    /** The listener paused or played themselves, or another queue started: a face-down pause isn't resumed. */
    fun listenerActed() { pausedAt = -1; upSince = -1 }

    /** Listening (re)starts: nothing seen yet, so a phone already lying face down isn't taken as just laid down. */
    fun restart() { downSince = -1; upSince = -1; armed = false }

    fun forget() { restart(); pausedAt = -1 }

    companion object {
        const val GRAVITY = 9.81f
        /** Screen down: most of gravity's 9.8 m/s² on the screen's axis, pointing away (the phone tilted under ~30°). */
        const val DOWN_Z = -8.5f
        /** Flat: what's left along the phone's length and width (a tilt of about 15° or less). */
        const val FLAT_XY = 2.5f
        /** Still: the whole pull within this of gravity's (walking, a swinging bag, a hand moving it go past it). */
        const val STILL = 1.2f
        const val DOWN_MS = 1_200L
        /** Screen up again: the phone held up to look at it, or lying screen-up. */
        const val UP_Z = 3f
        const val UP_MS = 500L
        const val RESUME_WITHIN_MS = 5 * 60_000L
    }
}
