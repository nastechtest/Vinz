package com.vinz.music

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.playback.AudioOnlyRenderers
import com.vinz.music.playback.playOnPicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.AudioDeviceInfoBuilder
import java.lang.reflect.Proxy

/**
 * Plan 5.1 (#409, #448: a small stutter as the crossfade's second player starts, ~15 s before a song's end): that player
 * is made with the audio renderers alone (the stock factory also made video, subtitle, metadata, camera and picture
 * renderers for it, on the main thread, never used), and it plays on the output picked in the device sheet, as the main
 * player does (it was never told, and played the ending song wherever the system sent music).
 */
@RunWith(RobolectricTestRunner::class)
class MixTailPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<VinzApp>()

    private fun trackTypes(factory: DefaultRenderersFactory): List<Int> = factory.createRenderers(
        Handler(Looper.getMainLooper()),
        object : VideoRendererEventListener {},
        object : AudioRendererEventListener {},
        object : TextOutput { override fun onCues(cueGroup: CueGroup) = Unit },
        MetadataOutput { },
    ).map { it.trackType }

    @Test fun theSecondPlayerIsMadeWithTheAudioRenderersOnly() {
        // The stock factory, as the tail had it: a video renderer and the rest besides the audio one.
        val stock = trackTypes(DefaultRenderersFactory(context))
        assertTrue("the stock factory makes a video renderer ($stock)", C.TRACK_TYPE_VIDEO in stock)
        val tail = trackTypes(AudioOnlyRenderers(context))
        assertTrue("an audio renderer ($tail)", tail.isNotEmpty())
        assertTrue("audio renderers only ($tail)", tail.all { it == C.TRACK_TYPE_AUDIO })
        assertEquals("as many audio renderers as the stock factory's", stock.count { it == C.TRACK_TYPE_AUDIO }, tail.size)
    }

    /** A player that only notes the outputs it's pointed at. */
    private fun player(pointedAt: MutableList<AudioDeviceInfo?>): ExoPlayer =
        Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { self, method, args ->
            when (method.name) {
                "setPreferredAudioDevice" -> { pointedAt += args!![0] as AudioDeviceInfo?; null }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "tail"
                else -> null
            }
        } as ExoPlayer

    @Test fun theSecondPlayerPlaysOnTheOutputPickedForTheMainOne() {
        val usb = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_HEADSET).build()
        val pointedAt = ArrayList<AudioDeviceInfo?>()
        player(pointedAt).playOnPicked(usb)
        assertEquals(listOf<AudioDeviceInfo?>(usb), pointedAt)
        // None picked: it follows the system, as the main player does (nothing to point it at).
        val none = ArrayList<AudioDeviceInfo?>()
        player(none).playOnPicked(null)
        assertTrue(none.isEmpty())
    }
}
