package com.vinz.music

import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.model.Song
import com.vinz.music.playback.QueuePlace
import com.vinz.music.playback.SavedQueue
import com.vinz.music.playback.resumptionOf
import com.vinz.music.playback.writeAtomically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * 1.8.7 (plan 4.5, #388): a headset button or the system's resume card starts Vinz after it was killed with the saved
 * queue at the kept place; the place file is written whole or not at all.
 */
@RunWith(RobolectricTestRunner::class)
class QueueResumeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val songs = listOf("a", "b", "c").map { Song(it, "Song $it", durationSec = 200) }
    private fun saved(index: Int = 0, positionMs: Long = 5_000) =
        InnerTube.json.encodeToString(SavedQueue.serializer(), SavedQueue(songs, index, positionMs, "My mix"))

    @Test fun resumingPlaysTheSavedQueueAtTheKeptPlace() {
        val r = resumptionOf(saved(index = 0, positionMs = 5_000), QueuePlace("b", 1, 93_000))!!
        assertEquals(listOf("a", "b", "c"), r.songs.map { it.id })
        assertEquals("the song it was on", 1, r.index)
        assertEquals("near the second it was at", 93_000L, r.positionMs)
        assertEquals("My mix", r.title)
    }

    @Test fun withoutAKeptPlaceTheQueuesOwnPlace() {
        val r = resumptionOf(saved(index = 2, positionMs = 12_000), null)!!
        assertEquals(2, r.index)
        assertEquals(12_000L, r.positionMs)
    }

    @Test fun aKeptPlaceForASongNoLongerQueuedIsLeftAside() {
        val r = resumptionOf(saved(index = 2, positionMs = 12_000), QueuePlace("gone", 0, 50_000))!!
        assertEquals(2, r.index)
        assertEquals(12_000L, r.positionMs)
    }

    @Test fun nothingSavedOrUnreadableResumesNothing() {
        assertNull(resumptionOf(null, null))
        assertNull(resumptionOf("{ half a file", null))
        assertNull(resumptionOf(InnerTube.json.encodeToString(SavedQueue.serializer(), SavedQueue(emptyList(), 0, 0, "")), null))
    }

    @Test fun aWriteCutShortLeavesTheOldPlaceFile() {
        val file = File(tmp.root, "queue-place.json").apply { writeText("""{"songId":"a","index":0,"positionMs":10000}""") }
        val cutShort = runCatching {
            writeAtomically(file, """{"songId":"b","index":1,"positionMs":93000}""") { f, t ->
                f.writeText(t.take(10)) // half written, then the process dies
                throw IOException("killed")
            }
        }
        assertTrue(cutShort.isFailure)
        assertEquals("the old place is still whole", """{"songId":"a","index":0,"positionMs":10000}""", file.readText())
        assertFalse("no half file left beside it", File(tmp.root, "queue-place.json.tmp").exists())
    }

    @Test fun aWholeWriteReplacesTheFile() {
        val file = File(tmp.root, "queue-place.json").apply { writeText("old") }
        writeAtomically(file, "new")
        assertEquals("new", file.readText())
        assertFalse(File(tmp.root, "queue-place.json.tmp").exists())
        val fresh = File(tmp.root, "queue.json")
        writeAtomically(fresh, "first")
        assertEquals("first", fresh.readText())
    }
}
