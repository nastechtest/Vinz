package com.vinz.music.playback

import androidx.media3.common.Player
import com.vinz.music.data.innertube.InnerTube
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.7: queue.json is put together off the main thread, from each lined-up item's stored song as text. The file is the
 * one the old way wrote (the songs read on the main thread, then written again), so a queue saved by either restores the same.
 */
class SavedQueueTextTest {
    private val songs = List(40) { i -> Song("id$i", "Title $i \"quoted\" é") }
    private val asStored = songs.map { InnerTube.json.encodeToString(Song.serializer(), it) }
    private fun read(text: String) = InnerTube.json.decodeFromString(SavedQueue.serializer(), text)

    @Test fun theFileIsWhatTheOldWayWrote() {
        val order = songs.indices.reversed().toList()
        val old = InnerTube.json.encodeToString(
            SavedQueue.serializer(),
            SavedQueue(songs, 7, 61_500, "My playlist", shuffle = true, shuffleOrder = order, repeat = Player.REPEAT_MODE_ALL),
        )
        assertEquals(old, savedQueueText(asStored, 7, 61_500, "My playlist", shuffle = true, order = order, repeat = Player.REPEAT_MODE_ALL))
        val q = read(old)
        assertEquals(songs, q.songs)
        assertEquals(7, q.index); assertEquals(61_500, q.positionMs); assertEquals("My playlist", q.title)
        assertEquals(order, q.shuffleOrder); assertEquals(Player.REPEAT_MODE_ALL, q.repeat)
    }

    @Test fun notShuffledKeepsNoOrder() {
        val q = read(savedQueueText(asStored, 0, 0, "", shuffle = false, order = emptyList(), repeat = Player.REPEAT_MODE_OFF))
        assertEquals(emptyList<Int>(), q.shuffleOrder)
        assertEquals(false, q.shuffle)
    }

    /** As before: an item without a readable song is left out, and the play order (by index) is then not kept. */
    @Test fun anItemThatCantBeReadIsLeftOutAndTheOrderWithIt() {
        val holed = asStored.toMutableList<String?>().also { it[3] = null; it[9] = "{not a song" }
        val q = read(savedQueueText(holed, 2, 0, "t", shuffle = true, order = songs.indices.toList(), repeat = Player.REPEAT_MODE_OFF))
        assertEquals(songs.size - 2, q.songs.size)
        assertEquals(true, q.shuffle)
        assertEquals(emptyList<Int>(), q.shuffleOrder)
    }

    @Test fun nothingLinedUpKeepsShuffleAndRepeat() {
        val q = read(savedQueueText(emptyList(), 0, 0, "", shuffle = true, order = emptyList(), repeat = Player.REPEAT_MODE_ONE))
        assertEquals(emptyList<Song>(), q.songs)
        assertEquals(true, q.shuffle); assertEquals(Player.REPEAT_MODE_ONE, q.repeat)
    }
}
