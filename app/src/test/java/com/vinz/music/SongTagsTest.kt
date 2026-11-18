package com.vinz.music

import androidx.room.Room
import com.vinz.music.data.TagStore
import com.vinz.music.data.db.VinzDatabase
import com.vinz.music.data.db.toEntity
import com.vinz.music.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongTagsTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VinzDatabase::class.java).allowMainThreadQueries().build()
    private val tags = TagStore(db)
    private val one = Song("one", "One")
    private val two = Song("two", "Two")
    @After fun close() = db.close()

    @Test fun names() {
        assertNull(TagStore.clean("  \n "))
        assertEquals("rainy day", TagStore.clean("  rainy   day  "))
        assertEquals(40, TagStore.clean("x".repeat(80))!!.length)
    }

    @Test fun addRemoveAndMergePreserveSongs() = runBlocking {
        db.songs().upsert(one.toEntity().copy(liked = true, lastPlayed = 42))
        assertEquals(2, tags.add(" Gym ", listOf(one, two, two)))
        assertEquals(0, tags.add("gym", listOf(one)))
        assertEquals(listOf("Gym"), tags.tagsOf(one.id).first())
        assertTrue(db.songs().get(one.id)!!.liked)
        assertEquals(42L, db.songs().get(one.id)!!.lastPlayed)
        tags.add("Relax", listOf(one))
        assertEquals("Relax", tags.rename("Gym", "RELAX"))
        assertEquals(setOf("one", "two"), tags.songs("Relax").first().map { it.id }.toSet())
        assertEquals(listOf("Relax"), tags.tagsOf(one.id).first())
        tags.remove("Relax", one.id)
        assertEquals(listOf("two"), tags.songs("Relax").first().map { it.id })
        tags.delete("Relax")
        assertTrue(tags.summaries.first().isEmpty())
        assertNotNull(db.songs().get(one.id))
    }

    @Test fun batchOrderAcrossChunkBoundary() = runBlocking {
        val input = (0..501).map { Song("s$it", "Song $it") }
        assertEquals(input.size, tags.add("Batch", input))
        assertEquals(input.map { it.id }, tags.songs("Batch").first().map { it.id })
    }

    @Test fun renameOnlyChangesCase() = runBlocking {
        tags.add("Gym", listOf(one))
        assertEquals("GYM", tags.rename("Gym", "GYM"))
        assertEquals(listOf("GYM"), tags.tagsOf(one.id).first())
    }

    @Test fun episodesNotTagged() = runBlocking {
        assertEquals(0, tags.add("x", listOf(Song("episode", "Episode", isEpisode = true))))
        assertTrue(tags.summaries.first().isEmpty())
    }
}
