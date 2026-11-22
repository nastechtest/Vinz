package com.vinz.music.data

import com.prism.music.data.db.HistoryRow
import com.prism.music.data.db.PlayEventDao
import com.prism.music.data.db.PlayedSongNames

/**
 * Searching Library → History (#469): the whole of this phone's history, not only the plays shown so far. Each played
 * song's names (title, artists, album) are read once into an [Index] (a few thousand short rows even for years of
 * listening), matched in Kotlin as the Library tabs match (every word, without minding capitals or accents), and the
 * matching songs' plays are read from the database, newest first.
 */
object HistorySearch {
    /** Ids per `IN (…)`: well under SQLite's 999 variables on older Androids. */
    private const val CHUNK = 500

    /** The played songs with their names lowered and without accents, ready to match as one types. */
    class Index(songs: List<PlayedSongNames>) {
        internal val ids = Array(songs.size) { songs[it].id }
        internal val hays = Array(songs.size) { i ->
            val s = songs[i]
            listOfNotNull(s.title, s.artistName, s.albumTitle).joinToString(" ") { LibrarySearch.norm(it) }
        }
        internal val raw = Array(songs.size) { i -> songs[i].let { listOfNotNull(it.title, it.artistName, it.albumTitle).joinToString(" ") } }
        val size get() = ids.size
    }

    /** The ids of the songs [query] finds: every word anywhere in the names (a search of only punctuation as typed). */
    fun match(index: Index, query: String): List<String> {
        val words = LibrarySearch.norm(query).split(' ').filter { it.isNotBlank() }
        val plain = query.trim()
        if (words.isEmpty() && plain.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (i in index.ids.indices) {
            val hit = if (words.isEmpty()) index.raw[i].contains(plain, ignoreCase = true) else words.all { it in index.hays[i] }
            if (hit) out += index.ids[i]
        }
        return out
    }

    /** The latest [limit] plays of [songIds], newest first, in chunks the database takes. */
    suspend fun rows(dao: PlayEventDao, songIds: List<String>, limit: Int, minMs: Long = 0): List<HistoryRow> {
        if (songIds.isEmpty()) return emptyList()
        val all = songIds.chunked(CHUNK).flatMap { dao.historyOf(it, limit, minMs) }
        return if (songIds.size <= CHUNK) all else all.sortedByDescending { it.playedAt }.take(limit)
    }

    /**
     * One search box's worth: keeps the [Index] until the history changes ([version], the unfiltered list's latest
     * emission: a new play or a removal), so typing only matches.
     */
    class Searcher(private val dao: PlayEventDao, private val minMs: Long = 0) {
        private var index: Index? = null
        private var at: Any? = null

        suspend fun find(query: String, limit: Int, version: Any?): List<HistoryRow> {
            val idx = index?.takeIf { at === version } ?: Index(dao.playedSongNames(minMs)).also { index = it; at = version }
            return rows(dao, match(idx, query), limit, minMs)
        }
    }
}
