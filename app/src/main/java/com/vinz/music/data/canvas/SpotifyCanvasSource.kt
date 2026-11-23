package com.vinz.music.data.canvas

import com.vinz.music.data.model.Song
import com.vinz.music.data.spotify.SpotifyCanvas
import com.vinz.music.data.spotify.SpotifyCanvasAnswer
import com.vinz.music.data.spotify.SpotifyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * "Spotify's canvas first" (1.8.7.6, plan-spotify-canvas.md §3): Spotify's own canvas for a song, asked before Vinz's
 * animated covers while the switch is on and Vinz is signed in to Spotify with the web-player session ([enabled]).
 *
 * - With [enabled] false nothing is asked and nothing remembered is given: Vinz Canvas shows as it always did.
 * - Answers are remembered per song id: a loop for [LOOP_MS] (a day: its link is asked for again after that), "none"
 *   and "no sure match" for [NONE_MS] (a week). Kept in a small file beside the animated covers' own index.
 * - A 429 quiets every canvas ask until its Retry-After has passed ([quietUntil]: the reader's own, which the library
 *   reads keep too). Any other failure (refused, no network, signed out, codes changed) gives nothing, is not remembered,
 *   and pauses the asks for [ERROR_PAUSE_MS] so a broken Spotify isn't asked about every song.
 * - The player waits at most [waitMs] for an answer, then Vinz Canvas carries on; a lookup still running finishes in
 *   the background and its answer is remembered for the next time.
 *
 * Nothing about the account, the token or the session is kept or logged here: song ids, loop addresses on Spotify's
 * file host and times only; a failure is logged by its kind ("RateLimited"), never its message.
 */
class SpotifyCanvasSource(
    private val scope: CoroutineScope,
    /** Spotify may be asked now ([spotifyCanvasOn]): the switch on and signed in with the session; Performance mode pauses it. */
    val enabled: () -> Boolean,
    /** What asks Spotify; called only once something is to be asked (it builds the Spotify connection). */
    private val finder: () -> SpotifyCanvas,
    /** Until when Spotify asked for quiet (a 429's Retry-After). */
    private val quietUntil: () -> Long = { 0L },
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
    private val log: (String) -> Unit = {},
) {
    @Serializable
    private data class Kept(val url: String? = null, val at: Long = 0, val sure: Boolean = true)

    private val kept = object : LinkedHashMap<String, Kept>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>) = size > MAX_ENTRIES
    }
    private val inFlight = HashMap<String, Deferred<CanvasArtwork?>>()
    @Volatile private var pausedUntil = 0L
    private var writeJob: Job? = null

    init {
        file?.let { f ->
            scope.launch {
                val stored = runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull() ?: return@launch
                val t = now()
                synchronized(kept) { stored.forEach { (id, k) -> if (id !in kept && fresh(k, t)) kept[id] = k } }
            }
        }
    }

    private fun fresh(k: Kept, t: Long) = t - k.at in 0 until (if (k.url != null) LOOP_MS else NONE_MS)

    private fun Kept.art(): CanvasArtwork? = url?.let { CanvasArtwork(it, spotify = true) }

    /** The song's loop as remembered (no asking), while [enabled]. */
    fun known(song: Song): CanvasArtwork? {
        if (!enabled()) return null
        val t = now()
        return synchronized(kept) { kept[song.id]?.takeIf { fresh(it, t) }?.art() }
    }

    /**
     * The song's Spotify loop, or null (Spotify has none, the match isn't sure, Spotify is slow, quiet or failing, or
     * [enabled] is false: then nothing is asked at all). Waits [wait] at most; a second call for the same song shares
     * the ask still running ([CanvasRepository.spotifyLate] waits longer for it).
     */
    suspend fun lookup(song: Song, wait: Long = waitMs): CanvasArtwork? {
        if (!enabled() || !ClipGate.isSong(song) || song.title.isBlank()) return null
        val t = now()
        synchronized(kept) { kept[song.id]?.takeIf { fresh(it, t) } }?.let { return it.art() }
        if (t < quietUntil() || t < pausedUntil) return null
        val job = synchronized(inFlight) {
            inFlight[song.id] ?: scope.async { try { ask(song) } finally { synchronized(inFlight) { inFlight.remove(song.id) } } }
                .also { inFlight[song.id] = it }
        }
        return withTimeoutOrNull(wait) { job.await() }
    }

    private suspend fun ask(song: Song): CanvasArtwork? {
        val started = now()
        val answer = try {
            finder().find(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyError.RateLimited) {
            // The reader keeps the quiet ([quietUntil]); nothing is remembered.
            log("canvas: rate limited")
            return null
        } catch (e: Exception) {
            pausedUntil = now() + ERROR_PAUSE_MS
            log("canvas: ${e.javaClass.simpleName}")
            return null
        }
        val k = when (answer) {
            is SpotifyCanvasAnswer.Loop -> Kept(answer.url, now())
            SpotifyCanvasAnswer.None -> Kept(null, now())
            SpotifyCanvasAnswer.NoSureMatch -> Kept(null, now(), sure = false)
        }
        synchronized(kept) { kept[song.id] = k }
        // How long Spotify took, and what it said (no link, no title: the song id is all).
        log("canvas: ${song.id} " + when (answer) { is SpotifyCanvasAnswer.Loop -> "loop"; SpotifyCanvasAnswer.None -> "none"; SpotifyCanvasAnswer.NoSureMatch -> "no sure match" } + " in ${now() - started} ms")
        scheduleWrite()
        return k.art()
    }

    /** Everything remembered goes (Disconnect). */
    fun forget() {
        synchronized(kept) { kept.clear() }
        writeJob?.cancel()
        file?.let { f -> scope.launch { runCatching { f.delete() } } }
    }

    private fun scheduleWrite() {
        val f = file ?: return
        writeJob?.cancel()
        writeJob = scope.launch {
            delay(1500)
            val snapshot = synchronized(kept) { HashMap(kept) }
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json.encodeToString(serializer, snapshot))
                tmp.renameTo(f)
            }
        }
    }

    /** For tests: how many songs have an answer remembered. */
    internal val rememberedCount: Int get() = synchronized(kept) { kept.size }

    companion object {
        /** About 4 s for the whole lookup (search and canvas: about 1.1 s on the A16), then Vinz Canvas. */
        const val WAIT_MS = 4_000L
        /** How long the player still waits for a late answer, after Vinz Canvas carried on ([CanvasRepository.spotifyLate]). */
        const val LATE_MS = 30_000L
        const val LOOP_MS = 86_400_000L
        const val NONE_MS = 7 * 86_400_000L
        const val ERROR_PAUSE_MS = 60_000L
        private const val MAX_ENTRIES = 3000
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Kept.serializer())
    }
}
