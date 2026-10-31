package com.vinz.music.ui.intro

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.vinz.music.R
import kotlinx.coroutines.delay

/**
 * The entrance's three sounds: the room it opens in, what it says, and the sting it closes on.
 *
 * All of them are optional in the strict sense: a phone in silent mode (or with media muted) gets silence, and any
 * failure to make a player means the entrance is quiet, never that it fails. The bed is ducked out rather than cut when
 * the ending starts or the entrance is skipped, so nothing lands on top of a pad.
 *
 * The words are a file, not a synthesiser: `scripts/brand/make_intro_voice.py` renders them and places each line on the
 * beat it belongs to, so the voice already carries its own timing and this only has to start it at the same moment as
 * the bed. That is also why skipping needs no cue sheet — one player to duck, one file to stop.
 */
internal class IntroSound(private val context: Context) {

    /** A playing file and the descriptor it was opened from: they are closed together, always. */
    private class Track(var player: MediaPlayer? = null, var source: AssetFileDescriptor? = null) {
        val playing: Boolean get() = player != null
    }

    private val bed = Track()
    private val voice = Track()
    private val stingTrack = Track()

    /** 0 while the entrance is muted from its own corner button, 1 otherwise. New players start at it. */
    private var level = 1f

    @Suppress("DEPRECATION")
    private val silent: Boolean
        get() = runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching false
            // A phone on silent, or one whose media stream is muted (a focus mode, "no sound" in a Do Not Disturb
            // rule): either way the entrance is a picture and nothing more.
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT || audio.isStreamMute(AudioManager.STREAM_MUSIC)
        }.getOrDefault(false)

    /** The 16 s bed: a sub sweep, air, the chord the name lands on, and the bell that says where the ring is. */
    fun bed() {
        if (bed.playing || silent) return
        start(bed, R.raw.vinz_intro_space, volume = 1f)
    }

    /** What the entrance says. It is mixed under the bed, which is why the bed is left alone here. */
    fun voice() {
        if (voice.playing || silent) return
        start(voice, R.raw.vinz_intro_voice, volume = 1f)
    }

    /** The sting: it starts the ending and is over before the app has finished drawing its first page. */
    fun sting() {
        stop(bed)
        stop(voice)
        if (stingTrack.playing || silent) return
        start(stingTrack, R.raw.vinz_intro_welcome, volume = 1f)
    }

    /** Takes the sound away underneath a beat of silence, for when the entrance is tapped through or skipped. */
    suspend fun quiet() {
        val players = listOfNotNull(bed.player, voice.player)
        if (players.isEmpty()) return
        runCatching {
            for (step in 1..8) {
                // A player that is still preparing throws on setVolume; it is on its way out anyway.
                players.forEach { p -> runCatching { p.setVolume(1f - step / 8f, 1f - step / 8f) } }
                delay(32)
            }
        }
        stop(bed)
        stop(voice)
    }

    /** The entrance's own sound switch: it silences what is playing and whatever starts next. */
    fun mute(muted: Boolean) {
        level = if (muted) 0f else 1f
        runCatching {
            bed.player?.setVolume(level, level)
            voice.player?.setVolume(level, level)
            stingTrack.player?.setVolume(level, level)
        }
    }

    fun stop() {
        stop(bed)
        stop(voice)
        stop(stingTrack)
    }

    /**
     * Opens [id] and starts it as soon as it is ready. On any failure the player is released here rather than left to
     * the caller: this used to hand back a player that had been built and then thrown away, and a `MediaPlayer` that
     * never got a `release()` holds a codec until the process dies.
     */
    private fun start(track: Track, id: Int, volume: Float) {
        val afd = runCatching { context.resources.openRawResourceFd(id) }.getOrNull() ?: return
        val player = MediaPlayer()
        val made = runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            player.isLooping = false
            player.setVolume(volume * level, volume * level)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { p -> runCatching { p.release() } }
            player.prepareAsync()
            player
        }.getOrNull()
        if (made == null) {
            runCatching { player.release() }
            runCatching { afd.close() }
            return
        }
        track.player = made
        track.source = afd
    }

    private fun stop(track: Track) {
        runCatching { track.player?.stop() }
        runCatching { track.player?.release() }
        runCatching { track.source?.close() }
        track.player = null
        track.source = null
    }
}
