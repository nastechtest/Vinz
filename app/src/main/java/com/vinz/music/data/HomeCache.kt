package com.vinz.music.data

import android.util.Log
import com.vinz.music.data.innertube.Parser
import com.vinz.music.data.model.Shelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * Home as YouTube last sent it (#126): its pages kept on the phone, so the next start shows Home at once while the
 * new one loads behind it, instead of a spinner. Only the account's own Home is kept, and it goes when the account
 * does ([clear], from signing out and switching accounts).
 */
class HomeCache(dir: File) {
    private val file = File(dir, "home/feed.json")

    /** The shelves of the last Home, or null when none is kept. Parsed off the main thread. */
    suspend fun read(): List<Shelf>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { Json.parseToJsonElement(file.readText()).jsonArray.flatMap { Parser.shelves(it) }.distinctBy { it.title } }
            .onFailure { Log.w(TAG, "couldn't read the kept Home", it) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    suspend fun write(pages: List<JsonObject>) = withContext(Dispatchers.IO) { writeNow(pages) }

    /** Writes on the calling thread; [write] runs this on the IO dispatcher. */
    internal fun writeNow(pages: List<JsonObject>) {
        if (pages.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            // Written beside and moved over, so a start never reads half a file.
            val next = File(file.parentFile, "feed.json.new")
            // Avoid copying deeply nested Home pages into intermediate strings at each level.
            next.bufferedWriter().use { it.appendJson(JsonArray(pages)) }
            if (!next.renameTo(file)) { file.delete(); next.renameTo(file) }
        }.onFailure { Log.w(TAG, "couldn't keep Home", it) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val TAG = "VinzHome"
    }
}

/** Writes the same JSON as [JsonElement.toString] without building strings for nested containers. */
internal fun Appendable.appendJson(e: JsonElement) {
    when (e) {
        is JsonObject -> {
            append('{')
            var first = true
            for ((k, v) in e) {
                if (!first) append(',')
                first = false
                append(JsonPrimitive(k).toString()).append(':')
                appendJson(v)
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            e.forEachIndexed { i, v -> if (i > 0) append(','); appendJson(v) }
            append(']')
        }
        else -> append(e.toString())
    }
}
