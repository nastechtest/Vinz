package com.vinz.music

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.playback.BlendMark
import com.vinz.music.playback.MixEngine
import com.vinz.music.playback.MixState
import com.vinz.music.playback.Tempo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * #432 / #431: "when I slide the progress bar near the crossfade mark, the mark disappears and then it doesn't make the
 * transition", and "sometimes it won't work on some songs". The crossfade engine itself, run against a player that only
 * says where it is: what a seek (and a queue that fills late) does to the blend that was coming and to its mark.
 */
@RunWith(RobolectricTestRunner::class)
class MixSeekTest {
    private val context = ApplicationProvider.getApplicationContext<VinzApp>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = MixState()
    private val prefs = context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)

    private val songMs = 200_000L
    private val song = item("ending")
    private val next = item("next")

    // What the player says.
    private var positionMs = 0L
    private var hasNext = true
    private var automatic = false
    private var tails = 0

    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(songMs).build()).build()

    /** A player that answers [answers] by method name, and nothing (false, 0, null) to anything else. */
    private fun player(answers: (String, Array<out Any?>?) -> Any?): ExoPlayer =
        Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "player"
                else -> answers(method.name, args) ?: when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as ExoPlayer

    private val main = player { name, args ->
        when (name) {
            "isPlaying", "getPlayWhenReady" -> true
            "getCurrentMediaItem" -> song
            "getDuration" -> songMs
            "getCurrentPosition" -> positionMs
            "getNextMediaItemIndex" -> if (hasNext) 1 else C.INDEX_UNSET
            "getMediaItemAt" -> if (args!![0] == 1) next else song
            "getVolume" -> 1f
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            else -> null
        }
    }

    private lateinit var engine: MixEngine

    @Before
    fun start() {
        prefs.edit().clear().commit()
        engine = MixEngine(
            main = main,
            scope = scope,
            tempo = context.container.mixTempo,
            mixMs = { 6_000L },
            auto = { automatic },
            beatMatch = { false },
            allowed = { _, _ -> true },
            scanFromPhone = {},
            buildTail = { tails++; player { _, _ -> null } },
            state = state,
        )
        engine.start()
    }

    @After
    fun stop() {
        engine.release()
        scope.cancel()
    }

    /** The song plays on to [ms] (or is taken there), and the engine looks a few times. */
    private fun at(ms: Long) {
        positionMs = ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_600))
    }

    @Test
    fun aSixSecondBlendIsMarkedAndGotReadyFor() {
        at(100_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        assertEquals(0, tails)
        at(185_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        assertEquals(1, tails)
    }

    /** "…when I slide the progress bar to skip a part, the crossfade mark disappears and then the crossfade won't work." */
    @Test
    fun takenBackAfterTheBlendWasGotReadyItIsPlannedAgain() {
        at(185_000)
        assertEquals(1, tails)
        // Back to the middle of the song: the blend that was coming is let go…
        at(100_000)
        // …and comes again: its mark is back, and so is the blend as the end comes up.
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        at(185_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        assertEquals(2, tails)
    }

    /** "…if you slide the progress bar near to the crossfade mark, the mark disappeared, and then it doesn't make the transition." */
    @Test
    fun takenToJustBeforeTheMarkItStillBlendsStartingALittleLater() {
        at(100_000)
        // Four seconds before the mark: too little to get the ending song's second player in step by 194 s.
        at(190_000)
        assertNotNull(state.mark.value)
        val mark = state.mark.value!!
        // The blend starts later and is that much shorter: it still ends where the song does.
        assertEquals(200_000, mark.startMs + mark.lengthMs)
        assertEquals(true, mark.startMs > 194_000 && mark.lengthMs >= 1_500)
        assertEquals(1, tails)
    }

    @Test
    fun takenPastTheMarkWithNoRoomLeftThereIsNoBlend() {
        at(185_000)
        at(194_700)
        // Past the mark and the second player was let go: no room left for a blend before the song ends (5.3 s to go).
        assertNull(state.mark.value)
        assertEquals(1, tails)
    }

    @Test
    fun takenToTheVeryEndThereIsNoBlendAndTakenBackThereIsOneAgain() {
        at(100_000)
        at(198_500)
        assertNull(state.mark.value)
        assertEquals(0, tails)
        // (Still none as it stays there: nothing is tried over and over.)
        at(198_600)
        assertNull(state.mark.value)
        assertEquals(0, tails)
        at(100_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        at(185_000)
        assertEquals(1, tails)
    }

    /** "sometimes it won't work on some songs": the next song joined the queue (Autoplay, a radio's next page) late. */
    @Test
    fun aNextSongThatComesLateIsStillBlendedInto() {
        hasNext = false
        at(100_000)
        at(179_000)
        assertNull(state.mark.value)
        hasNext = true
        at(180_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        at(185_000)
        assertEquals(1, tails)
    }

    /**
     * The automatic length reads the songs' scans from their own prefs, as before 1.8.3's song_traits table: a scanned
     * song's fade-out is the blend's length, an unscanned one's is six seconds.
     */
    @Test
    fun theAutomaticLengthIsTheScannedFadeOutAndSixSecondsWithoutAScan() {
        automatic = true
        at(100_000)
        assertEquals(194_000L, state.mark.value?.startMs)
        at(178_000)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        // Its scan arrives (music to 199 s, a ten-second fade-out): the blend is over the fade.
        prefs.edit().putString("ending", Tempo(120.0, 5.0, 0, 199_000, 0, 198_000, fadeOutMs = 10_000, quietIntroMs = 0).encode()).commit()
        at(178_100)
        assertEquals(BlendMark("ending", 189_000, 10_000), state.mark.value)
    }

    @Test
    fun aLateBlendStartsALeadInAndAMomentOnAndKeepsAtLeastASecondAndAHalf() {
        // Time enough as it is: where it was planned.
        assertEquals(194_000L, MixEngine.lateStartMs(194_000, 6_000, 187_500, 0.0))
        // Four seconds before: 6.5 s on from here, the blend that much shorter.
        assertEquals(196_500L, MixEngine.lateStartMs(194_000, 6_000, 190_000, 0.0))
        // With the beats matched (500 ms apart), on a beat: 2.5 s later is five beats, 2.6 s is six.
        assertEquals(196_500L, MixEngine.lateStartMs(194_000, 6_000, 190_000, 500.0))
        assertEquals(197_000L, MixEngine.lateStartMs(194_000, 6_000, 190_100, 500.0))
        // 1.5 s of blend left is the least; less, and there's none.
        assertEquals(198_500L, MixEngine.lateStartMs(194_000, 6_000, 192_000, 0.0))
        assertNull(MixEngine.lateStartMs(194_000, 6_000, 192_001, 0.0))
    }

    @Test
    fun aRefusalHoldsUntilTheSongIsTakenBackOrAnotherSongIsNext() {
        assertEquals(true, MixEngine.stillRefused(190_000, "next", 195_000, "next"))
        // (A reading a moment behind isn't the song taken back.)
        assertEquals(true, MixEngine.stillRefused(190_000, "next", 189_500, "next"))
        assertEquals(false, MixEngine.stillRefused(190_000, "next", 120_000, "next"))
        assertEquals(false, MixEngine.stillRefused(190_000, "next", 195_000, "another"))
        assertEquals(false, MixEngine.stillRefused(190_000, null, 195_000, "next"))
    }

    /** A scan that lands late can move the blend to earlier than there's time to get ready for: shorter then, not dropped. */
    @Test
    fun aScanThatMovesTheBlendTooCloseShortensItInsteadOfDroppingIt() {
        automatic = true
        at(100_000)
        at(179_500)
        assertEquals(BlendMark("ending", 194_000, 6_000), state.mark.value)
        assertEquals(0, tails)
        // Music ends at 195 s with a twelve-second fade: the blend would have started at 183 s, 3.5 s from now.
        prefs.edit().putString("ending", Tempo(120.0, 5.0, 0, 195_000, 0, 194_000, fadeOutMs = 12_000, quietIntroMs = 0).encode()).commit()
        at(179_600)
        val mark = state.mark.value!!
        assertEquals(195_000, mark.startMs + mark.lengthMs)
        assertEquals(true, mark.lengthMs in 8_000..11_000)
        assertEquals(1, tails)
    }
}
