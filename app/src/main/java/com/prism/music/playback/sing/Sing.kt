package com.prism.music.playback.sing

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.util.Log
import com.prism.music.AppContainer
import com.prism.music.data.ModelStore
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Sing: karaoke with the song's own backing. Each song's vocals are separated on the phone (Spleeter, [Spleeter]) from
 * the very copy the player plays, kept in [store], and Sing's audio processor ([VocalStemProcessor]) takes them out of
 * the music as it plays, as much as [level] says.
 *
 * Songs are separated one at a time, the playing one first, then the next; the vocals are written as they're worked
 * out, so a song can be sung along to a few seconds after Sing starts on it.
 */
class Sing(private val c: AppContainer) : VocalStemProcessor.Source {
    override val store = StemStore(c.app.filesDir)

    private val _on = MutableStateFlow(false)
    /** Sing is on (for this session; it's switched on from the player). */
    val on: StateFlow<Boolean> = _on
    private val _level = MutableStateFlow(0f)
    /** How much of the lead vocals stays in: 0 = none (karaoke), 1 = all (the song as it is). */
    val level: StateFlow<Float> = _level
    private val _semitones = MutableStateFlow(0)
    /** The key, moved up or down (−6…+6 semitones), for singing in your own range. */
    val semitones: StateFlow<Int> = _semitones

    /** Songs being separated: song id → how far, 0..1. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress
    private val _problem = MutableStateFlow<String?>(null)
    /** Why the last song couldn't be separated, if it couldn't. */
    val problem: StateFlow<String?> = _problem

    /** Read by the audio processor on the playback thread. */
    @Volatile override var levelNow = 0f
        private set
    @Volatile override var onNow = false
        private set

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "PrismSing").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val wanted = ArrayDeque<Song>()
    private var job: Job? = null
    private var sessions: Pair<OrtSession, OrtSession>? = null
    private var env: OrtEnvironment? = null
    private var releaseJob: Job? = null

    /** The runtime and model are on the phone. */
    fun engineReady(): Boolean {
        val runtime = ModelStore.Model.singRuntime() ?: return false
        return c.models.ready(runtime) && c.models.ready(ModelStore.Model.SING_MODEL)
    }

    /** This phone's processor can run Sing at all. */
    val supported: Boolean get() = ModelStore.Model.singRuntime() != null

    /** Downloads the engine and the model (about 50 MB, once). */
    suspend fun downloadEngine(): Boolean {
        val runtime = ModelStore.Model.singRuntime() ?: return false
        // Both parts on Sing's own scope, so closing the sheet after the first doesn't leave the second undownloaded.
        return scope.async { c.models.download(runtime) && c.models.download(ModelStore.Model.SING_MODEL) }.await()
    }

    /** Settings → Lyrics → Sing → "Get songs ready while Sing is off": songs are separated ahead with Sing off too. */
    private val alwaysReady: Boolean get() = c.settings.current.singAlwaysReady

    /** Songs are being got ready: Sing is on, or set to get them ready anyway. */
    private val working: Boolean get() = onNow || alwaysReady

    fun setOn(on: Boolean) {
        _on.value = on
        onNow = on
        if (!on && !alwaysReady) {
            synchronized(wanted) { wanted.clear() }
            scheduleRelease()
        }
    }

    fun setLevel(v: Float) {
        _level.value = v.coerceIn(0f, 1f)
        levelNow = _level.value
    }

    fun setSemitones(v: Int) { _semitones.value = v.coerceIn(-6, 6) }

    /** Separates [songs] (the playing one first), skipping what's done, what can't be done, and what's already queued. */
    fun prepare(songs: List<Song>) {
        if (!working || !engineReady()) return
        val todo = songs.filter { canSing(it) && !store.complete(it.id) }
        synchronized(wanted) {
            // The playing song goes to the front; anything no longer wanted is dropped.
            wanted.removeAll { w -> todo.none { it.id == w.id } }
            todo.asReversed().forEach { s -> wanted.removeAll { it.id == s.id }; wanted.addFirst(s) }
        }
        if (job?.isActive != true) job = scope.launch { drain() }
    }

    /** Songs Sing works with: not live streams, not podcast episodes. */
    fun canSing(song: Song) = !song.isLive && !song.isEpisode

    private suspend fun drain() {
        while (true) {
            val next = synchronized(wanted) { wanted.removeFirstOrNull() } ?: return
            if (!working) return
            runCatching { withContext(worker) { separate(next) } }
                .onSuccess { _problem.value = null }
                .onFailure {
                    Log.w("PrismSing", "Couldn't separate ${next.id}: $it")
                    _problem.value = it.message ?: "Couldn't get this song ready"
                }
            _progress.update { it - next.id }
        }
    }

    private fun sessions(): Pair<OrtSession, OrtSession> {
        sessions?.let { return it }
        val runtime = ModelStore.Model.singRuntime() ?: throw IOException("Sing doesn't run on this phone's processor")
        val e = OrtLoader.environment(c.models.dir(runtime))
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val dir = c.models.dir(ModelStore.Model.SING_MODEL)
        val pair = e.createSession(File(dir, "vocals.fp16.onnx").path, opts) to e.createSession(File(dir, "accompaniment.fp16.onnx").path, opts)
        env = e
        sessions = pair
        return pair
    }

    /** The networks take ~100 MB: let go of them a few minutes after Sing is switched off. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(3 * 60_000L)
            withContext(worker) {
                if (!working) {
                    sessions?.let { (v, a) -> runCatching { v.close() }; runCatching { a.close() } }
                    sessions = null
                }
            }
        }
    }

    /** Works out [song]'s vocals and writes them to [store] as it goes. */
    private fun separate(song: Song) {
        val id = song.id
        if (store.complete(id)) return
        val started = System.nanoTime()
        _progress.update { it + (id to 0f) }
        // The whole song from the player's own copy (cached whole first if need be), so the vocals line up with it.
        if (!song.isLocal) c.warmUp(id, whole = true, cellular = c.settings.current.singOnCellular)
        val scratch = File(c.app.cacheDir, "sing_${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.part")
        val uri = c.audioOnPhone(id, scratch)
            ?: throw IOException(if (c.network.metered.value && !c.settings.current.singOnCellular) "Waiting for Wi-Fi to get this song ready" else "This song isn't on the phone yet")
        val (vocals, accompaniment) = sessions()
        com.prism.music.playback.AudioDiagnostics.log("background", "Sing separation of $id started")
        try {
            PcmDecoder(c.app, uri).use { dec ->
                val first = dec.read() ?: throw IOException("The song didn't decode")
                val rate = dec.sampleRate
                val total = dec.totalFrames.coerceAtLeast(1)
                store.pcm(id).delete()
                store.writeMeta(id, StemStore.Meta(rate, total))
                val back = Resampler(2, Spleeter.RATE, rate)
                val spleeter = Spleeter(env!!, vocals, accompaniment)
                // Decoding (the phone's decoder answers a buffer at a time) and the step to 44.1 kHz run on their own
                // thread, a few pieces ahead, while the networks work on what's already in.
                val reader = PieceReader(dec, first, Resampler(2, rate, Spleeter.RATE))
                var written = 0L
                BufferedOutputStream(FileOutputStream(store.pcm(id)), 1 shl 16).use { out ->
                    val bytes = ByteArray(16 * 1024)
                    fun write(v: FloatArray, limit: Long = Long.MAX_VALUE) {
                        var i = 0
                        val frames = minOf(v.size / 2L, limit - written).toInt()
                        while (i < frames * 2) {
                            var n = 0
                            while (n < bytes.size && i < frames * 2) {
                                val s = (v[i] * 32767f).toInt().coerceIn(-32768, 32767)
                                bytes[n] = s.toByte(); bytes[n + 1] = (s shr 8).toByte()
                                n += 2; i++
                            }
                            out.write(bytes, 0, n)
                        }
                        written += frames
                    }
                    try {
                        while (true) {
                            if (!working) throw IOException("Sing was switched off")
                            val in44 = reader.next() ?: break
                            val vocalsNow = back.push(spleeter.feed(in44))
                            if (vocalsNow.isNotEmpty()) {
                                write(vocalsNow)
                                out.flush()
                                _progress.update { it + (id to (written.toFloat() / total).coerceAtMost(0.99f)) }
                            }
                        }
                    } finally {
                        reader.stop()
                    }
                    val decoded = reader.decoded
                    write(back.push(spleeter.finish()), decoded)
                    write(back.finish(), decoded)
                    // Exactly as long as the song: the resampling round trip can be a sample short.
                    if (written < decoded) write(FloatArray(((decoded - written) * 2).toInt()), decoded)
                }
                // Marked complete only once every byte is in the file.
                store.writeMeta(id, StemStore.Meta(rate, written, complete = true))
                val secs = (System.nanoTime() - started) / 1e9
                Log.i("PrismSing", "separated $id: ${"%.1f".format(written / rate.toDouble())} s of song in ${"%.1f".format(secs)} s")
            }
        } catch (t: Throwable) {
            store.delete(id)
            throw t
        } finally {
            com.prism.music.playback.AudioDiagnostics.log("background", "Sing separation of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
            scratch.delete()
            store.trim(c.settings.current.singCacheMb * 1_048_576L, setOfNotNull(id, c.player.currentSong.value?.id))
        }
    }
}

/**
 * Decodes a song and brings it to 44.1 kHz on a thread of its own, up to [AHEAD] pieces ahead of [next]. Ends with
 * the resampler's tail; an error on that thread comes out of [next].
 */
private class PieceReader(private val dec: PcmDecoder, first: FloatArray, private val toModel: Resampler) {
    private val queue = java.util.concurrent.ArrayBlockingQueue<FloatArray>(AHEAD)
    @Volatile private var stopped = false
    @Volatile private var error: Throwable? = null
    @Volatile var decoded = 0L
        private set
    private val thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            var piece: FloatArray? = first
            while (piece != null && !stopped) {
                decoded += piece.size / 2
                put(toModel.push(piece))
                piece = dec.read()
            }
            if (!stopped) put(toModel.finish())
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            error = t
        } finally {
            if (!stopped) runCatching { put(END) }
        }
    }, "PrismSingDecode").apply { start() }

    private fun put(p: FloatArray) {
        while (!stopped) if (queue.offer(p, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return
    }

    /** The next piece at 44.1 kHz, or null once the song is over. */
    fun next(): FloatArray? {
        val p = queue.take()
        error?.let { throw it }
        return if (p === END) null else p
    }

    /** Stops the thread and waits for it (so the decoder can be closed). */
    fun stop() {
        stopped = true
        thread.interrupt()
        thread.join()
    }

    private companion object {
        const val AHEAD = 6
        val END = FloatArray(0)
    }
}
