package com.vinz.music.data

import androidx.room.withTransaction
import com.prism.music.data.db.PlayEventEntity
import com.prism.music.data.db.PrismDatabase

/**
 * "Remove from history" (#469): takes plays out of this phone's history by their own ids (one play, not every play of the
 * song), keeping them for Undo exactly as they were. Only Vinz's own plays (`play_events`); brought-in histories are
 * managed on their own page.
 *
 * What counts plays reads them from the database as it needs them (Replay, the mixes' "anything new?" check by count,
 * Room's lists), so a removal shows there on its own; Home's Replay card keeps its year for a few minutes and is told
 * by the caller. A kept Rewind of a finished year ([RewindStore]) is not written again: finished years stay as they were.
 */
object HistoryRemoval {
    /** What a removal took: the plays as stored, and the songs' "last played" before it (for Undo). */
    class Removed(val events: List<PlayEventEntity>, val lastPlayed: Map<String, Long>) {
        val count get() = events.size
    }

    private const val CHUNK = 500

    /**
     * Takes the plays [ids] out. A song whose latest play went gets its "last played" from the play before it (0 with
     * none left), so Recently played doesn't keep showing it; one started again later (a skip, kept as started but not as a
     * play) keeps its time. Null when none of them was there.
     */
    suspend fun remove(db: PrismDatabase, ids: Collection<Long>): Removed? {
        if (ids.isEmpty()) return null
        val list = ids.distinct()
        return db.withTransaction {
            val plays = db.plays()
            val events = list.chunked(CHUNK).flatMap { plays.byIds(it) }
            if (events.isEmpty()) return@withTransaction null
            if (list.size == 1) plays.deleteById(list[0]) else list.chunked(CHUNK).forEach { plays.deleteByIds(it) }
            val before = HashMap<String, Long>()
            for ((songId, gone) in events.groupBy { it.songId }) {
                val song = db.songs().get(songId) ?: continue
                before[songId] = song.lastPlayed
                // "Last played" is written as a song starts, its play as it ends: a later time is a later start.
                if (song.lastPlayed > gone.maxOf { it.timestamp }) continue
                db.songs().touch(songId, plays.lastPlayOf(songId) ?: 0L)
            }
            Removed(events, before)
        }
    }

    /** Undo: the same plays back (same ids and all), and each song's "last played" as it was unless it was played since. */
    suspend fun restore(db: PrismDatabase, removed: Removed) {
        db.withTransaction {
            db.plays().reinsert(removed.events)
            for ((songId, at) in removed.lastPlayed) {
                val now = db.songs().get(songId)?.lastPlayed ?: continue
                if (at > now) db.songs().touch(songId, at)
            }
        }
    }
}
