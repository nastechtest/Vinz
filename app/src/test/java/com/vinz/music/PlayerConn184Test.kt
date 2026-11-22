package com.vinz.music

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ShuffleButton
import com.vinz.music.playback.HistoryShuffleOrder
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * 1.8.4, on the real [com.vinz.music.playback.PlayerConnection] with a real player (no playback service):
 * - D57: with "Shuffle only" the button goes off → on → off, and from Smart Shuffle straight to off;
 * - #416: with "Keep shuffle on for the next list", a song tapped in a list plays first and the rest is shuffled, Smart
 *   Shuffle staying Smart Shuffle; radios, single songs and the setting off are as they were;
 * - #404: the lyrics timing of Settings is part of the lyric shift, and a change applies at once.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerConn184Test {
    private val app get() = ApplicationProvider.getApplicationContext<VinzApp>()
    private val c get() = app.container
    private lateinit var player: ExoPlayer

    private fun songs(n: Int, prefix: String = "s") = (0 until n).map { Song("$prefix$it", "Song $it") }
    private val playlist = QueueSource(QueueKind.PLAYLIST, "A playlist", originId = "PLtest")

    /** The settings are stored off the main thread: waits until they read as asked. */
    private fun settled(until: (AppSettings) -> Boolean) = runBlocking { withTimeout(10_000) { c.settings.flow.first(until) } }

    @Before fun setUp() {
        runBlocking { c.settings.editNow { it.clear() } }
        settled { !it.behavior.keepShuffle && it.behavior.shuffleButton == ShuffleButton.SHUFFLE_AND_SMART && it.behavior.lyricsOffsetMs == 0 }
        player = ExoPlayer.Builder(app).build()
        // As the playback service sets it up.
        player.setShuffleOrder(HistoryShuffleOrder(IntArray(0)) { player.currentMediaItemIndex })
        c.player.testPlayer = player
        c.queue.smartShuffle.value = false
    }

    @After fun tearDown() {
        c.player.testPlayer = null
        player.release()
        c.queue.smartShuffle.value = false
        runBlocking { c.settings.editNow { it.clear() } }
    }

    /** The queue's ids in the order they'll play. */
    private fun order(): List<String> {
        val t = player.currentTimeline
        val out = ArrayList<String>()
        var i = t.getFirstWindowIndex(player.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < t.windowCount) {
            out += player.getMediaItemAt(i).mediaId
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return out
    }

    // ------------------------------------------------------------------ D57

    @Test fun shuffleOnlyGoesOffOnOff() {
        c.settings.setShuffleButton(ShuffleButton.SHUFFLE_ONLY)
        settled { it.behavior.shuffleButton == ShuffleButton.SHUFFLE_ONLY }
        c.player.playQueue(songs(8), 0, playlist)
        assertFalse(player.shuffleModeEnabled)

        c.player.toggleShuffle()
        assertTrue("off → on", player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)

        c.player.toggleShuffle()
        assertFalse("on → off, with no Smart Shuffle between", c.queue.smartShuffle.value)
        assertFalse("on → off", player.shuffleModeEnabled)
    }

    @Test fun shuffleOnlyFromSmartShuffleTurnsShuffleOff() {
        c.player.playQueue(songs(8), 0, playlist)
        c.player.toggleShuffle()
        c.player.toggleShuffle()
        assertTrue("the default button reaches Smart Shuffle on a playlist", c.queue.smartShuffle.value && player.shuffleModeEnabled)

        c.settings.setShuffleButton(ShuffleButton.SHUFFLE_ONLY)
        settled { it.behavior.shuffleButton == ShuffleButton.SHUFFLE_ONLY }
        c.player.toggleShuffle()
        assertFalse(c.queue.smartShuffle.value)
        assertFalse("nobody is left in Smart Shuffle: the next tap is off", player.shuffleModeEnabled)
        c.player.toggleShuffle()
        assertTrue(player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)
    }

    @Test fun theDefaultButtonStillCyclesThroughSmartShuffle() {
        c.player.playQueue(songs(8), 0, playlist)
        c.player.toggleShuffle()
        assertTrue(player.shuffleModeEnabled); assertFalse(c.queue.smartShuffle.value)
        c.player.toggleShuffle()
        assertTrue(player.shuffleModeEnabled); assertTrue(c.queue.smartShuffle.value)
        c.player.toggleShuffle()
        assertFalse(player.shuffleModeEnabled); assertFalse(c.queue.smartShuffle.value)
    }

    // ------------------------------------------------------------------ #416

    @Test fun keepShuffleATappedSongPlaysFirstAndTheRestIsShuffled() {
        c.settings.setKeepShuffle(true)
        settled { it.behavior.keepShuffle }
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        assertTrue(player.shuffleModeEnabled)

        val list = songs(40)
        c.player.playQueue(list, 3, QueueSource(QueueKind.ALBUM, "An album"))
        assertTrue("shuffle is still on", player.shuffleModeEnabled)
        assertFalse("plain shuffle stays plain", c.queue.smartShuffle.value)
        assertEquals("the tapped song plays", "s3", player.currentMediaItem?.mediaId)
        val order = order()
        assertEquals("it is first in the order, so everything else is still to come", "s3", order.first())
        assertEquals("every song once", list.map { it.id }.sorted(), order.sorted())
        // 37 songs in list order after it by chance: 1 in 39!.
        assertNotEquals("the rest isn't 4, 5, 6…", (4 until 40).map { "s$it" }, order.drop(1).take(36))
        assertNotEquals(list.map { it.id }, order)
    }

    @Test fun keepShuffleSmartShuffleStaysSmartShuffle() {
        c.settings.setKeepShuffle(true)
        settled { it.behavior.keepShuffle }
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.toggleShuffle()
        assertTrue(c.queue.smartShuffle.value)

        c.player.playQueue(songs(20), 5, playlist.copy(title = "Another playlist"))
        assertTrue(player.shuffleModeEnabled)
        assertTrue("Smart Shuffle carried over", c.queue.smartShuffle.value)
        assertEquals("s5", order().first())

        // A queue Smart Shuffle can't add to (Replay) carries on as plain shuffle.
        c.player.playQueue(songs(20), 2, QueueSource(QueueKind.REPLAY, "Replay"))
        assertTrue(player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)
    }

    /**
     * What the playback service hears through [com.vinz.music.playback.QueueState.smartOn] while [block] runs, collected
     * as the service collects it (a coroutine on the main thread): true asks for Smart Shuffle's picks, false drops them.
     */
    private fun serviceHears(block: () -> Unit): List<Boolean> {
        val heard = ArrayList<Boolean>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        scope.launch { c.queue.smartOn.collect { heard += it } }
        shadowOf(Looper.getMainLooper()).idle()
        block()
        shadowOf(Looper.getMainLooper()).idle()
        scope.cancel()
        return heard
    }

    @Test fun keepShuffleSmartShuffleAsksTheServiceForPicksAtOnce() {
        c.settings.setKeepShuffle(true)
        settled { it.behavior.keepShuffle }
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.toggleShuffle()
        assertTrue(c.queue.smartShuffle.value)

        // Smart Shuffle goes off and on again inside one call: the service has to hear that this queue wants its picks
        // now, not when its first song ends.
        val heard = serviceHears { c.player.playQueue(songs(20), 5, playlist.copy(title = "Another playlist")) }
        assertTrue("Smart Shuffle carried over", c.queue.smartShuffle.value)
        assertTrue("the service is asked for picks for the new queue (heard $heard)", true in heard)

        // Carried as plain shuffle (Replay can't take picks): nothing is asked, the old picks are dropped.
        val replay = serviceHears { c.player.playQueue(songs(20), 2, QueueSource(QueueKind.REPLAY, "Replay")) }
        assertTrue(player.shuffleModeEnabled)
        assertEquals(listOf(false), replay)

        // Plain shuffle carried to another list: nothing to hear.
        assertEquals(emptyList<Boolean>(), serviceHears { c.player.playQueue(songs(20), 1, playlist) })
    }

    @Test fun withTheSettingOffANewQueueAsksForNoPicks() {
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.toggleShuffle()
        assertTrue(c.queue.smartShuffle.value)
        assertEquals("Smart Shuffle ends with the queue: its picks are dropped", listOf(false), serviceHears { c.player.playQueue(songs(20), 3, playlist) })
        assertFalse(player.shuffleModeEnabled)
    }

    @Test fun keepShuffleLeavesRadiosAndSingleSongsAlone() {
        c.settings.setKeepShuffle(true)
        settled { it.behavior.keepShuffle }
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.playQueue(songs(20), 0, QueueSource(QueueKind.RADIO, "A radio"))
        assertFalse("a radio plays in its order", player.shuffleModeEnabled)

        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.playSingle(Song("one", "One song"))
        assertFalse("a single song (Search) starts unshuffled, as before", player.shuffleModeEnabled)
    }

    @Test fun keepShuffleWithShuffleOffStaysOff() {
        c.settings.setKeepShuffle(true)
        settled { it.behavior.keepShuffle }
        c.player.playQueue(songs(8, "old"), 0, playlist)
        val list = songs(20)
        c.player.playQueue(list, 3, playlist)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(list.map { it.id }, order())
        assertEquals("s3", player.currentMediaItem?.mediaId)
    }

    @Test fun withTheSettingOffANewQueueTurnsShuffleOffAsBefore() {
        c.player.playQueue(songs(8, "old"), 0, playlist)
        c.player.toggleShuffle()
        c.player.toggleShuffle()
        assertTrue(c.queue.smartShuffle.value)

        val list = songs(20)
        c.player.playQueue(list, 3, playlist)
        assertFalse(player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)
        assertEquals("s3", player.currentMediaItem?.mediaId)
        assertEquals(list.map { it.id }, order())

        // A list's own Shuffle button: shuffled from a random song, plain shuffle.
        c.player.playQueue(list, 0, playlist, shuffle = true)
        assertTrue(player.shuffleModeEnabled)
        assertFalse(c.queue.smartShuffle.value)
        assertEquals(player.currentMediaItem?.mediaId, order().first())
    }

    // ------------------------------------------------------------------ #404

    @Test fun theLyricsTimingOfSettingsShiftsTheLyricsAtOnce() {
        assertEquals(0L, c.player.lyricShift.value)
        c.settings.setLyricsOffsetMs(120)
        settled { it.behavior.lyricsOffsetMs == 120 }
        awaitShift(120)
        assertEquals("the playhead in the lyrics' time", c.player.position + 120, c.player.lyricPosition)

        // Changed again while the same song plays: applied without a song change.
        c.settings.setLyricsOffsetMs(-300)
        settled { it.behavior.lyricsOffsetMs == -300 }
        awaitShift(-300)
    }

    /** PlayerConnection follows the settings on the main thread: runs it until the shift is [ms]. */
    private fun awaitShift(ms: Long) {
        val until = System.currentTimeMillis() + 5_000
        while (c.player.lyricShift.value != ms && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("the lyric shift follows Settings → lyrics timing", ms, c.player.lyricShift.value)
    }
}
