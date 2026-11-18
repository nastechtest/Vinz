package com.vinz.music.data

import androidx.room.withTransaction
import com.vinz.music.data.db.SongTagEntity
import com.vinz.music.data.db.VinzDatabase
import com.vinz.music.data.db.toEntity
import com.vinz.music.data.model.Song
import kotlinx.coroutines.flow.map

/** Local labels. Names are normalized and matched case-insensitively, as in Prism. */
class TagStore(private val db: VinzDatabase) {
    val summaries get() = db.tags().summaries()
    fun tagsOf(id: String) = db.tags().tagsOf(id)
    fun songs(tag: String) = db.tags().songs(tag).map { rows -> rows.map { it.toSong() } }

    suspend fun add(typed: String, songs: List<Song>): Int {
        val clean = clean(typed) ?: return 0
        val eligible = songs.filter { !it.isEpisode }.distinctBy { it.id }
        if (eligible.isEmpty()) return 0
        return db.withTransaction {
            val name = db.tags().names().firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
            val now = System.currentTimeMillis()
            eligible.chunked(500).forEach { part ->
                val existing = db.songs().getAll(part.map { it.id }).associateBy { it.id }
                db.songs().upsertAll(part.map { it.toEntity(existing[it.id]) })
            }
            // Index the full list before chunking, so the order survives batches larger than 500.
            eligible.mapIndexed { index, song -> SongTagEntity(name, song.id, now - index) }
                .chunked(500).sumOf { part -> db.tags().add(part).count { it != -1L } }
        }
    }

    suspend fun remove(tag: String, songId: String) = db.tags().remove(tag, songId)
    suspend fun delete(tag: String) = db.tags().delete(tag)

    /** Rename or merge; duplicate songs retain the existing destination entry. */
    suspend fun rename(from: String, typed: String): String? {
        val clean = clean(typed) ?: return null
        return db.withTransaction {
            val target = db.tags().names().firstOrNull { it != from && it.equals(clean, ignoreCase = true) } ?: clean
            if (target != from) {
                db.tags().renameRows(from, target)
                db.tags().delete(from)
            }
            target
        }
    }

    companion object {
        const val MAX_LENGTH = 40
        fun clean(typed: String): String? = typed.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().takeIf { it.isNotEmpty() }
    }
}
