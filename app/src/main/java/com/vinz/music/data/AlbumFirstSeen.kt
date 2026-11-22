package com.vinz.music.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * When Vinz first saw each of the account's library albums (1.8.4, D56), for Library → Albums' "Recently added".
 * YouTube's library list comes without dates and in its own order (Vinz asks for it without a sort), so the phone
 * keeps its own: every album that's new in a fetched list gets the time of that fetch. The albums of one fetch share
 * one time, so they keep YouTube's order among themselves, as do all the albums that were already there the first
 * time this ran ("seen at upgrade").
 *
 * Times are only dropped when an album is taken out from Vinz ([forget]), never because a fetched list lacks it: a
 * list cut short by a failed page would otherwise make old albums new again.
 */
class AlbumFirstSeen(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    /** Album id → when it was first seen in the library (ms). */
    val times = MutableStateFlow(decode(prefs.getString(KEY, null)))

    /** The library's albums as just loaded: the ones not seen before are first seen now. */
    @Synchronized
    fun note(ids: List<String>) {
        val next = noted(times.value, ids, clock())
        if (next != times.value) save(next)
    }

    /** An album taken out of the library here: saved again later, it's new again. */
    @Synchronized
    fun forget(id: String) {
        if (id in times.value) save(times.value - id)
    }

    private fun save(next: Map<String, Long>) {
        times.value = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    companion object {
        /** In "library_order" (which backups carry), beside the sorts. */
        const val KEY = "albums:first_seen"

        /** [known] with [now] for each of [ids] it doesn't have yet. */
        fun noted(known: Map<String, Long>, ids: List<String>, now: Long): Map<String, Long> {
            val fresh = ids.filter { it.isNotEmpty() && it !in known }
            return if (fresh.isEmpty()) known else known + fresh.associateWith { now }
        }

        /**
         * [albums] with the most recently added first. Ones added at the same time, and ones with no time (they go
         * last), stay in the order they came in.
         */
        fun <T> newestFirst(albums: List<T>, addedAt: (T) -> Long?): List<T> = albums.sortedByDescending { addedAt(it) ?: 0L }

        fun encode(times: Map<String, Long>): String = times.entries.joinToString("\n") { "${it.key}\t${it.value}" }

        fun decode(text: String?): Map<String, Long> = text.orEmpty().lineSequence().mapNotNull { line ->
            val at = line.substringAfterLast('\t', "").toLongOrNull() ?: return@mapNotNull null
            line.substringBeforeLast('\t').takeIf { it.isNotEmpty() }?.let { it to at }
        }.toMap()
    }
}
