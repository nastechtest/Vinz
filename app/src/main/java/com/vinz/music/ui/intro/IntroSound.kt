package com.vinz.music.ui.intro

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.vinz.music.R
import kotlinx.coroutines.delay

/**
 * The intro's two sounds: the room it opens in, and the sting it closes on.
 *
 * Both are generated rather than sampled (see `docs/branding/intro-sound.md`), and both are optional in the strict
 * sense: silent mode means silence, and any failure to make a player means the entrance is quiet, never that it fails.
 * The bed is ducked out rather than cut when the ending starts or the cinematic is skipped, so the sting lands on
 * silence instead of on the top of a pad.
 */
internal class IntroSound(private val context: Context) {

    private var bedPlayer: MediaPlayer? = null
    private var bedSource: AssetFileDescriptor? = null
    private var stingPlayer: MediaPlayer? = null
    private var stingSource: AssetFileDescriptor? = null

    private val silent: Boolean
        get() = runCatching {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode == AudioManager.RINGER_MODE_SILENT
        }.getOrDefault(false)

    /** The 15 s bed: a sub sweep, air, and a chord that lands when the planet does. */
    fun bed() {
        if (bedPlayer != null || silent) return
        bedPlayer = play(R.raw.vinz_intro_space, loop = false) { bedSource = it }
    }

    /** The sting: it starts the ending and is over before the app has finished drawing its first page. */
    fun sting() {
        stop(bedPlayer, bedSource).also { bedPlayer = null; bedSource = null }
        if (stingPlayer != null || silent) return
        stingPlayer = play(R.raw.vinz_intro_welcome, loop = false) { stingSource = it }
    }

    /** Takes the bed away underneath a beat of silence, for when the cinematic is tapped through. */
    suspend fun quiet() {
        val player = bedPlayer ?: return
        bedPlayer = null
        runCatching {
            for (step in 1..8) {
                player.setVolume(1f - step / 8f, 1f - step / 8f)
                delay(32)
            }
        }
        stop(player, bedSource)
        bedSource = null
    }

    fun stop() {
        stop(bedPlayer, bedSource)
        stop(stingPlayer, stingSource)
        bedPlayer = null
        bedSource = null
        stingPlayer = null
        stingSource = null
    }

    private fun play(id: Int, loop: Boolean, keep: (AssetFileDescriptor) -> Unit): MediaPlayer? = runCatching {
        val afd = context.resources.openRawResourceFd(id)
        keep(afd)
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        player.isLooping = loop
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { p -> runCatching { p.release() } }
        player.prepareAsync()
        player
    }.getOrElse { null }

    private fun stop(player: MediaPlayer?, source: AssetFileDescriptor?) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        runCatching { source?.close() }
    }
}
