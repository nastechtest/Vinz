package com.vinz.music

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.Song
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #374 (1.8.5), on the real [com.vinz.music.playback.PlayerConnection] with a real player: with DJ mode on, Shuffle
 * keeps DJ mode on and asks for a new DJ set ([com.vinz.music.playback.DjMode.newSets], which the playback service
 * deals); Media3's own shuffle stays off, so the DJ order is the order. With DJ mode off Shuffle is as before.
 */
@RunWith(RobolectricTestRunner::class)
class DjShuffleConnTest {
    private val app get() = ApplicationProvider.getApplicationContext<VinzApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    private fun songs(n: Int) = (0 until n).map { Song("s$it", "Song $it") }
    private val playlist = QueueSource(QueueKind.PLAYLIST, "A playlist", originId = "PLtest")

    @Before fun setUp() {
        player = ExoPlayer.Builder(app).build()
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
        c.queue.smartShuffle.value = false
    }

    @After fun tearDown() {
        c.dj.setOn(false)
        c.player.testPlayer = null
        player.release()
        c.queue.smartShuffle.value = false
    }

    @Test fun shuffleInDjModeKeepsDjModeAndDealsANewSet() {
        c.player.playQueue(songs(12), 0, playlist)
        c.player.setDjMode(true)
        assertTrue(c.dj.onNow)
        val before = c.dj.newSets.value

        c.player.toggleShuffle()
        assertTrue("DJ mode stays on", c.dj.onNow && c.dj.on.value)
        assertFalse("Media3's shuffle stays off: the DJ order is the order", player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)
        assertEquals("a new set is asked for", before + 1, c.dj.newSets.value)

        // Each tap asks again.
        c.player.toggleShuffle()
        assertEquals(before + 2, c.dj.newSets.value)
        assertFalse(player.shuffleModeEnabled)
        assertTrue(c.dj.onNow)
    }

    @Test fun withDjModeOffShuffleIsAsBefore() {
        c.player.playQueue(songs(12), 0, playlist)
        val before = c.dj.newSets.value
        c.player.toggleShuffle()
        assertTrue(player.shuffleModeEnabled)
        assertFalse(c.dj.onNow)
        assertEquals("no DJ set asked for", before, c.dj.newSets.value)
    }
}
