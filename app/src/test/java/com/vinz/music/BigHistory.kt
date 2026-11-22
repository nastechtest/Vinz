package com.vinz.music

import com.prism.music.data.db.ImportedPlayEntity
import com.prism.music.data.db.PrismDatabase
import com.prism.music.data.db.SongEntity
import com.vinz.music.data.imports.ImportSource
import java.time.Instant
import kotlin.random.Random

/**
 * A big listening history for tests that need one (1.8.5): three years of Vinz plays (60,000 by default) across ~4,000
 * songs by ~300 artists, plus ~10,000 imported Spotify plays (most matched to those songs, some not, some too short to
 * count). Deterministic: the same [seed] gives the same history. Songs are stored, so joins with `songs` work.
 *
 * Plays lean towards a few favourite songs (as real ones do) and towards the day and evening, so per-hour and per-song
 * numbers aren't flat. Filling takes about 6 s with `@SQLiteMode(NATIVE)` on the test class, about 30 s without.
 */
object BigHistory {
    /** The history's span: three years up to the day before 1.8.5 was built. */
    val START: Long = Instant.parse("2023-11-12T00:00:00Z").toEpochMilli()
    val END: Long = Instant.parse("2026-11-11T23:59:59Z").toEpochMilli()

    class Filled(val songs: Int, val artists: Int, val vinzPlays: Int, val importedPlays: Int)

    fun songId(i: Int) = "big%05d".format(i)

    /** Fills [db] (expected empty). Vinz plays go in one transaction straight through SQLite: Room's one-row inserts would take minutes. */
    suspend fun fill(
        db: PrismDatabase,
        seed: Int = 1885,
        plays: Int = 60_000,
        songs: Int = 4_000,
        artists: Int = 300,
        imported: Int = 10_000,
    ): Filled {
        val r = Random(seed)
        val songRows = (0 until songs).map { i ->
            val a = (i * 7 + r.nextInt(3)) % artists
            SongEntity(
                id = songId(i), title = "Song $i", artistName = "Artist $a", artistId = "UCartist$a",
                albumTitle = "Album ${i / 12}", albumId = "MPREb$i", durationSec = 120 + r.nextInt(240),
                thumbnail = "https://i/$i.jpg", primaryArtist = "Artist $a",
            )
        }
        db.songs().upsertAll(songRows)

        val span = END - START
        // Favourites: a song's chance falls off with its index squared.
        fun pickSong() = (r.nextDouble().let { it * it } * songs).toInt().coerceIn(0, songs - 1)
        // A time of day weighted to the afternoon and evening.
        fun pickTime(): Long {
            val day = START + (r.nextLong(span) / 86_400_000L) * 86_400_000L
            val hour = if (r.nextInt(4) == 0) r.nextInt(24) else 8 + r.nextInt(15)
            return (day + hour * 3_600_000L + r.nextLong(3_600_000L)).coerceAtMost(END)
        }

        val w = db.openHelper.writableDatabase
        w.beginTransaction()
        try {
            val st = w.compileStatement("INSERT INTO play_events (songId, timestamp, playedMs, source, excluded) VALUES (?, ?, ?, ?, ?)")
            val sources = arrayOf(null, "ALBUM", "PLAYLIST", "RADIO", "smart", "mix:daily:1")
            repeat(plays) {
                val s = pickSong()
                st.clearBindings()
                st.bindString(1, songId(s))
                st.bindLong(2, pickTime())
                st.bindLong(3, (songRows[s].durationSec * 1000L * (30 + r.nextInt(71)) / 100).coerceAtLeast(10_000))
                sources[r.nextInt(sources.size)]?.let { st.bindString(4, it) } ?: st.bindNull(4)
                st.bindLong(5, if (r.nextInt(50) == 0) 1 else 0)
                st.executeInsert()
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }

        val importedRows = (0 until imported).map { i ->
            val s = pickSong()
            val matched = r.nextInt(10) != 0 // one in ten never matched
            ImportedPlayEntity(
                origin = ImportSource.SPOTIFY.key,
                // Distinct times, so the unique (origin, timestamp, songKey) index keeps every one.
                timestamp = START + i * (span / imported) + r.nextLong(span / imported / 2),
                playedMs = if (r.nextInt(20) == 0) 5_000 else 30_000 + r.nextLong(200_000), // some too short to count
                title = "Song $s", artist = songRows[s].artistName,
                songKey = "song $s|${songRows[s].artistName.lowercase()}",
                matchedId = if (matched) songId(s) else null,
            )
        }
        importedRows.chunked(2_000).forEach { db.imports().insertPlays(it) }
        return Filled(songs, artists, plays, imported)
    }
}
