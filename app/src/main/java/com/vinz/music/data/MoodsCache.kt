package com.vinz.music.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * D40 (1.8.3): YouTube Music's "Moods & genres" page, kept in memory and on the phone for [MAX_AGE_MS]. It was asked for
 * again on every visit (Home, Search and the Moods page each), though it hardly ever changes. Kept per page language
 * ([key]); when YouTube can't be reached, a kept page older than that still shows.
 */
class MoodsCache(dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, "home/moods.json")
    private val lock = Mutex()
    @Volatile private var memory: Kept? = null
    /** [memory]'s page as it was read last (1.8.7): each visit parsed the whole kept page again. One reader is assumed. */
    private var read: Pair<Kept, List<*>>? = null

    @Serializable
    private data class Kept(val key: String, val at: Long, val page: String)

    /** The page for [key]: kept while fresh, else [fetch]ed (and kept when it has anything). [parse] reads a page. */
    suspend fun <T> get(key: String, fetch: suspend () -> JsonObject, parse: (JsonObject) -> List<T>): List<T> = lock.withLock {
        val kept = memory?.takeIf { it.key == key } ?: readFile()?.takeIf { it.key == key }?.also { memory = it }
        if (kept != null && now() - kept.at in 0 until MAX_AGE_MS) parsed(kept, parse).let { if (it.isNotEmpty()) return@withLock it }
        val fresh = try { fetch() } catch (e: Exception) {
            // Offline: an old page beats an error.
            if (kept != null) return@withLock parsed(kept, parse)
            throw e
        }
        val shelves = parse(fresh)
        if (shelves.isNotEmpty()) Kept(key, now(), fresh.toString()).also { memory = it; read = it to shelves; writeFile(it) }
        shelves
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> parsed(kept: Kept, parse: (JsonObject) -> List<T>): List<T> =
        read?.takeIf { it.first === kept }?.let { it.second as List<T> }
            ?: parse(Json.parseToJsonElement(kept.page) as JsonObject).also { read = kept to it }

    fun clear() { memory = null; read = null; runCatching { file.delete() } }

    private suspend fun readFile(): Kept? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { Json.decodeFromString(Kept.serializer(), file.readText()) }.onFailure { Log.w(TAG, "couldn't read the kept moods", it) }.getOrNull()
    }

    private suspend fun writeFile(k: Kept) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, "moods.json.new")
            next.writeText(Json.encodeToString(Kept.serializer(), k))
            if (!next.renameTo(file)) { file.delete(); next.renameTo(file) }
        }.onFailure { Log.w(TAG, "couldn't keep the moods", it) }
    }

    companion object {
        /** A day: the page changes a few times a year. */
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        private const val TAG = "VinzMoods"
    }
}
