package com.vinz.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Plan 5.1 (#409, #448: "a small stutter about 15 s before the end"): Mix's second player ([MixEngine]'s tail) only ever
 * plays a song's ending (a video, a live stream or an episode never blends), so it's made with the audio renderers alone.
 * The stock factory also built a video renderer, subtitles, metadata, camera and picture renderers for it, on the main
 * thread, at the moment it's made, and never used them.
 */
@OptIn(UnstableApi::class)
open class AudioOnlyRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) = Unit

    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit

    override fun buildMetadataRenderers(context: Context, output: MetadataOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit

    override fun buildCameraMotionRenderers(context: Context, extensionRendererMode: Int, out: ArrayList<Renderer>) = Unit

    override fun buildImageRenderers(context: Context, out: ArrayList<Renderer>) = Unit
}

/**
 * Plan 5.1: the second player plays on the output picked in Vinz's device sheet, as the main player does
 * ([PlayerConnection.setOutput]). It was never told, so during a blend the ending song came out wherever the system
 * sends music (the phone's speaker while the music played on a USB DAC, say), its audio track, on the main player's
 * audio session, opened on another output than the main one's. None picked: both follow the system.
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.playOnPicked(output: AudioDeviceInfo?) {
    if (output != null) setPreferredAudioDevice(output)
}
