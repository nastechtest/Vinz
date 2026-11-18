package com.vinz.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Local song labels, adapted from Prism. */
@Entity(tableName = "song_tags", primaryKeys = ["tag", "songId"], indices = [Index("songId")])
data class SongTagEntity(val tag: String, val songId: String, val addedAt: Long)

data class TagSummary(val tag: String, val songs: Int, val lastAt: Long)

@Dao
interface TagDao {
    @Query("SELECT tag, COUNT(*) AS songs, MAX(addedAt) AS lastAt FROM song_tags GROUP BY tag ORDER BY lastAt DESC, tag")
    fun summaries(): Flow<List<TagSummary>>

    @Query("SELECT tag FROM song_tags WHERE songId = :songId ORDER BY tag COLLATE NOCASE")
    fun tagsOf(songId: String): Flow<List<String>>

    @Query("SELECT s.* FROM song_tags t JOIN songs s ON s.id = t.songId WHERE t.tag = :tag ORDER BY t.addedAt DESC, s.id")
    fun songs(tag: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT tag FROM song_tags")
    suspend fun names(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<SongTagEntity>): List<Long>

    @Query("DELETE FROM song_tags WHERE tag = :tag AND songId = :songId")
    suspend fun remove(tag: String, songId: String)

    @Query("DELETE FROM song_tags WHERE tag = :tag")
    suspend fun delete(tag: String)

    @Query("UPDATE OR IGNORE song_tags SET tag = :to WHERE tag = :from")
    suspend fun renameRows(from: String, to: String)
}
