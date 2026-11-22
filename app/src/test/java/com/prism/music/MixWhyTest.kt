package com.vinz.music

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.Song
import com.vinz.music.playback.MixEngine
import com.vinz.music.playback.MixRefusal
import com.vinz.music.playback.MixState
import com.vinz.music.playback.MixWhy
import com.vinz.music.playback.mixWhyHeld
import com.vinz.music.playback.mixWhyNot
import com.vinz.music.playback.toMediaItem
import com.vinz.music.ui.player.QUEUE_DJ_WHY
import com.vinz.music.ui.player.QueueHeader
import com.vinz.music.ui.player.queueDjWhy
import com.vinz.music.ui.screens.settings.crossfadeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * GitHub #493 "DJ is not working": about fifteen things quietly gave a plain change instead of a blend, and only
 * diagnostics said which. Now the last one is kept ([MixState.why]) and said in plain words behind Crossfade's ⓘ and the
 * queue's DJ chip's, until a blend happens again.
 */
@RunWith(RobolectricTestRunner::class)
class MixWhyTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<VinzApp>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val state = MixState()
    private val prefs = context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ Every refusal has its reason

    private val source: String by lazy {
        listOf("src/main/java/com/vinz/music/playback/Mix.kt", "app/src/main/java/com/vinz/music/playback/Mix.kt")
            .map(::File).first { it.exists() }.readText()
    }

    /** Each call of [name] in the engine, from its "(" to its matching ")". */
    private fun calls(name: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            i = source.indexOf("$name(", i).takeIf { it >= 0 } ?: break
            val before = source.substring(maxOf(0, i - 12), i)
            val start = i + name.length
            var depth = 0
            var j = start
            while (j < source.length) {
                when (source[j]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
                j++
            }
            if (!before.endsWith("fun ") && source[i - 1].let { !it.isLetterOrDigit() && it != '.' }) out += source.substring(start, j + 1)
            i = j
        }
        return out
    }

    @Test fun everyRefusalInTheEngineSaysWhichReason() {
        val aborts = calls("abort")
        val noBlends = calls("noBlend")
        assertTrue("the engine's stops were found (${aborts.size})", aborts.size >= 15)
        assertTrue("its refusals were found (${noBlends.size})", noBlends.size >= 7)
        // A stop either names its reason or says (null) that it's no refusal: a new one without either doesn't compile,
        // and this says so in words.
        for (a in aborts) assertTrue("abort$a names no reason (a MixWhy, or null for a stop that's no refusal)", Regex("""^\("[^"]*",\s*(MixWhy\.\w+|null|followingWhy)""").containsMatchIn(a))
        for (n in noBlends) assertTrue("noBlend$n names no reason", Regex("""^\(\s*item,\s*(MixWhy\.\w+|why|if \(.*\) MixWhy\.\w+ else MixWhy\.\w+)""").containsMatchIn(n.replace("\n", " ")))
        // Every reason is one the engine (or its reading of the songs, mixWhyNot) gives, and only those exist.
        val used = Regex("""MixWhy\.(\w+)""").findAll(source.substringBefore("enum class MixWhy") + source.substringAfter("fun mixWhyNot")).map { it.groupValues[1] }.toSet()
        assertEquals("every reason is given somewhere, and none given is missing", MixWhy.entries.map { it.name }.toSet(), used)
    }

    @Test fun everyReasonHasItsPlainSentence() {
        val res = context.resources
        val sentences = HashSet<String>()
        for (w in MixWhy.entries) {
            assertTrue("${w.name}'s string is a mix_why_ one", res.getResourceEntryName(w.text).startsWith("mix_why_"))
            val s = tr(w.text)
            assertTrue("${w.name}: \"$s\" is a short sentence", s.isNotBlank() && s.length <= 70 && s.endsWith("."))
            assertTrue("${w.name}: its sentence is its own", sentences.add(s))
        }
        assertEquals("Last time: One of the two is a music video.", com.vinz.music.ui.screens.settings.mixWhyLine(MixWhy.VIDEO))
        assertEquals("Playing on another device.", tr(MixWhy.CASTING.text))
        assertEquals("This song is shorter than 30 seconds.", tr(MixWhy.SHORT.text))
    }

    @Test fun whatTheSongsThemselvesSayIsReadInMayBlendsOrder() {
        fun song(id: String, album: String? = "A", live: Boolean = false, episode: Boolean = false) =
            Song(id, id, album = album?.let { AlbumRef(it, it) }, isLive = live, isEpisode = episode)
        val a = song("a").toMediaItem()
        assertEquals(MixWhy.VIDEO, mixWhyNot(a, song("b", album = "B").toMediaItem(videoId = "v")))
        assertEquals(MixWhy.LIVE, mixWhyNot(a, song("b", live = true).toMediaItem()))
        assertEquals(MixWhy.PODCAST, mixWhyNot(song("e", album = null, episode = true).toMediaItem(), a))
        assertEquals(MixWhy.ALBUM, mixWhyNot(a, song("b").toMediaItem()))
        // What the songs can't say, the player says (#493, 1.8.7.6): Sing and Listen together are two reasons now.
        val other = song("b", album = "B").toMediaItem()
        assertEquals(MixWhy.SING, mixWhyNot(a, other, mixWhyHeld(sing = true, casting = false, together = false)))
        assertEquals(MixWhy.TOGETHER, mixWhyNot(a, other, mixWhyHeld(sing = false, casting = false, together = true)))
        assertEquals(MixWhy.CASTING, mixWhyNot(a, other, mixWhyHeld(sing = false, casting = true, together = true)))
        // In mayBlend's order: Sing before casting before Listen together.
        assertEquals(MixWhy.SING, mixWhyHeld(sing = true, casting = true, together = true))
        assertNull(mixWhyHeld(sing = false, casting = false, together = false))
        // What the songs say still comes first.
        assertEquals(MixWhy.VIDEO, mixWhyNot(a, song("b", album = "B").toMediaItem(videoId = "v"), MixWhy.SING))
        // With no player to say, it can't tell.
        assertEquals(MixWhy.GLITCH, mixWhyNot(a, other))
        // Each says which, in its own words.
        assertEquals("Sing is on, so each song plays whole.", tr(MixWhy.SING.text))
        assertEquals("Listen together is on, so songs follow the host.", tr(MixWhy.TOGETHER.text))
    }

    @Test fun theServiceTellsTheEngineWhichOfSingOrTogetherItWas() {
        val service = listOf("src/main/java/com/vinz/music/playback/PlaybackService.kt", "app/src/main/java/com/vinz/music/playback/PlaybackService.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue("the engine is given the player's reason", "whyNot = { from, to -> mixWhyNot(from, to, mixWhyHeld(c.sing.onNow, c.cast.casting, togetherNow())) }" in service)
        // The same test of "together" refuses the blend and names it.
        assertTrue("if (togetherNow()) return false" in service)
    }

    // ------------------------------------------------------------------ The engine keeps it, and lets it go after a blend

    private val songMs = 200_000L
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.invalid/$id")
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(songMs).build()).build()
    private val song = item("ending")
    private val next = item("next")

    private var positionMs = 0L
    private var hasNext = true
    private var repeatOne = false
    private var handedOver = false
    /** From [rollFrom] on, the songs play on with the clock (else they stay where [positionMs] puts them). */
    private var rollFrom = -1L
    private fun position() = if (rollFrom < 0) positionMs else positionMs + (android.os.SystemClock.elapsedRealtime() - rollFrom)

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
            "getCurrentMediaItem" -> if (handedOver) next else song
            "getDuration" -> songMs
            "getCurrentPosition" -> position()
            "getNextMediaItemIndex" -> if (hasNext && !handedOver) 1 else C.INDEX_UNSET
            "getMediaItemAt" -> if (args!![0] == 1) next else song
            "getRepeatMode" -> if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            "getVolume" -> 1f
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            // The hand-over: the main player moves to the next song.
            "seekTo" -> { if (args?.size == 2 && args[0] == 1) handedOver = true; Unit }
            else -> null
        }
    }

    /** The ending song's second player: loaded, playing, exactly where the main one is (in step). */
    private val tail = player { name, _ ->
        when (name) {
            "isPlaying", "getPlayWhenReady" -> true
            "getPlaybackState" -> Player.STATE_READY
            "getCurrentPosition" -> position()
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            else -> null
        }
    }

    private var engine: MixEngine? = null

    private fun engine(allowed: () -> Boolean = { true }) = MixEngine(
        main = main, scope = scope, tempo = context.container.mixTempo, mixMs = { 6_000L }, auto = { false },
        beatMatch = { false }, allowed = { _, _ -> allowed() }, scanFromPhone = {}, buildTail = { tail }, state = state,
    ).also { engine = it; it.start() }

    @After fun stop() {
        engine?.release()
        scope.cancel()
    }

    private fun at(ms: Long) {
        positionMs = ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_600))
    }

    @Test fun aRefusalIsKeptWithItsTwoSongsAndGoesOnceABlendHappens() {
        prefs.edit().clear().commit()
        repeatOne = true
        engine()
        at(100_000)
        assertNull("nothing refused yet", state.why.value)
        at(186_000)
        assertEquals(MixRefusal(MixWhy.REPEAT_ONE, "ending", "next"), state.why.value)
        // Repeat one goes off and the song is taken back: the blend is planned, but the reason stays until it happens.
        repeatOne = false
        at(100_000)
        at(186_000)
        assertEquals(MixWhy.REPEAT_ONE, state.why.value?.why)
        // From here the song plays on: the second player plays along in step, and at 194 s the songs blend.
        positionMs = 186_000
        rollFrom = android.os.SystemClock.elapsedRealtime()
        val looper = shadowOf(Looper.getMainLooper())
        var waited = 0
        while (!handedOver && waited < 15_000) { looper.idleFor(Duration.ofMillis(50)); waited += 50 }
        assertTrue("the blend happened", handedOver)
        assertNotNull(state.into.value)
        assertNull("a blend happened: nothing to say any more", state.why.value)
    }

    @Test fun nothingNextIsSaidWithNoSongToBlendInto() {
        prefs.edit().clear().commit()
        hasNext = false
        engine()
        at(100_000)
        at(186_000)
        assertEquals(MixRefusal(MixWhy.NOTHING_NEXT, "ending", null), state.why.value)
    }

    @Test fun castingIsSaidEvenWithNoBlendUnderWay() {
        prefs.edit().clear().commit()
        val e = engine()
        at(100_000)
        e.stopBlend()
        assertEquals(MixRefusal(MixWhy.CASTING, "ending", "next"), state.why.value)
    }

    @Test fun aStopThatIsNoRefusalSaysNothing() {
        prefs.edit().clear().commit()
        engine()
        at(185_000)
        // Taken back mid-preparation: planned again, no reason.
        at(100_000)
        assertNull(state.why.value)
    }

    // ------------------------------------------------------------------ Where it shows

    @Test fun crossfadesInfoGainsALastLineOnlyWithAReason() {
        val base = tr(R.string.crossfade_choice_info)
        assertEquals(base, crossfadeInfo(null))
        val with = crossfadeInfo(MixRefusal(MixWhy.SHORT, "a", "b"))
        assertTrue(with, with.startsWith(base) && with.endsWith("\n\nLast time: This song is shorter than 30 seconds."))
    }

    @Test fun theDjChipsInfoShowsWhyOnATap() {
        assertNull("no reason: no ⓘ", queueDjWhy(null, mixing = true))
        assertNull("Mix and DJ off: no ⓘ", queueDjWhy(MixWhy.VIDEO, mixing = false))
        val line = queueDjWhy(MixWhy.SLOW, mixing = true)!!
        compose.setContent { QueueHeader("Queue", dj = true, onDj = {}, djWhy = line, onSave = null, onClear = null, locked = false, onLock = {}) }
        compose.waitForIdle()
        assertEquals("behind the ⓘ, not on the row", 0, compose.onAllNodes(hasText(line)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(QUEUE_DJ_WHY).performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasText("Last time: Getting the blend ready took too long.")).fetchSemanticsNodes().size)
    }

    @Test fun withNoReasonTheDjChipHasNoInfo() {
        compose.setContent { QueueHeader("Queue", dj = true, onDj = {}, djWhy = null, onSave = null, onClear = null, locked = false, onLock = {}) }
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(androidx.compose.ui.test.hasTestTag(QUEUE_DJ_WHY)).fetchSemanticsNodes().isNotEmpty())
    }
}
