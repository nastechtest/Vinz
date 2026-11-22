package com.vinz.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vinz.music.data.looks.LookLabels
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.data.prefs.CarLyricsLines
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.data.prefs.ShuffleButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.4's new stored settings (#406, #404, #416, D57, PR #400, D53, D54, D55): their defaults keep 1.8.3's Vinz, each
 * setter's value comes back from the store, what 1.8.3 stored for the playlist picture reads as it did, the lyrics
 * timing stays within its range, the two look settings travel in a Look and all of them in a backup.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class Settings184Test {
    private fun repo() = SettingsRepository(org.robolectric.RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The store is one per process: each test starts from an empty one and leaves it empty. */
    private fun withStore(block: suspend (SettingsRepository) -> Unit): Unit = runBlocking {
        val r = repo()
        r.editNow { it.clear() }
        try { block(r) } finally { r.editNow { it.clear() } }
    }

    private suspend fun SettingsRepository.storedWhen(until: (AppSettings) -> Boolean): AppSettings = withTimeout(5_000) { stored.first(until) }

    @Test
    fun defaultsAreVinzAsItWas() {
        val d = AppSettings()
        assertTrue("rows still swipe to the queue", d.behavior.rowSwipes)
        assertEquals(0, d.behavior.lyricsOffsetMs)
        assertFalse(d.behavior.keepShuffle)
        assertEquals(ShuffleButton.SHUFFLE_AND_SMART, d.behavior.shuffleButton)
        assertEquals(CarLyricsLines.ONE_LINE, d.behavior.carLyricsLines)
        assertFalse(d.behavior.locatePlaying)
        assertFalse(d.view.lockScreenPlayerLook)
        assertFalse(d.view.albumHeroCover)
        // Playlists had their picture across the width, albums didn't.
        assertTrue(d.playlistHeroCover)
        assertEquals(FullWidthPictures.PLAYLISTS, d.fullWidthPictures)
    }

    @Test
    fun anEmptyStoreReadsAsTheDefaults() = withStore { r ->
        val s = r.storedWhen { true }
        assertEquals(AppSettings().behavior, s.behavior)
        assertEquals(AppSettings().view, s.view)
        assertEquals(FullWidthPictures.PLAYLISTS, s.fullWidthPictures)
    }

    @Test
    fun eachSetterComesBackFromTheStore() = withStore { r ->
        r.setRowSwipes(false)
        r.setLyricsOffsetMs(-120)
        r.setKeepShuffle(true)
        r.setShuffleButton(ShuffleButton.SHUFFLE_ONLY)
        r.setCarLyricsLines(CarLyricsLines.WRAP_TWO)
        r.setLocatePlaying(true)
        r.setLockScreenPlayerLook(true)
        r.setFullWidthPictures(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        val s = r.storedWhen { it.fullWidthPictures == FullWidthPictures.ALBUMS_AND_PLAYLISTS }
        assertFalse(s.behavior.rowSwipes)
        assertEquals(-120, s.behavior.lyricsOffsetMs)
        assertTrue(s.behavior.keepShuffle)
        assertEquals(ShuffleButton.SHUFFLE_ONLY, s.behavior.shuffleButton)
        assertEquals(CarLyricsLines.WRAP_TWO, s.behavior.carLyricsLines)
        assertTrue(s.behavior.locatePlaying)
        assertTrue(s.view.lockScreenPlayerLook)
        assertTrue(s.view.albumHeroCover)
        // A second repository on the same store (the app, started again) reads the same.
        val again = repo().storedWhen { it.fullWidthPictures == FullWidthPictures.ALBUMS_AND_PLAYLISTS }
        assertEquals(s.behavior, again.behavior)
        assertEquals(s.view, again.view)
        // And back.
        r.setRowSwipes(true)
        r.setShuffleButton(ShuffleButton.SHUFFLE_AND_SMART)
        r.setCarLyricsLines(CarLyricsLines.ONE_LINE)
        r.setFullWidthPictures(FullWidthPictures.PLAYLISTS)
        val back = r.storedWhen { it.fullWidthPictures == FullWidthPictures.PLAYLISTS }
        assertTrue(back.behavior.rowSwipes)
        assertEquals(ShuffleButton.SHUFFLE_AND_SMART, back.behavior.shuffleButton)
        assertEquals(CarLyricsLines.ONE_LINE, back.behavior.carLyricsLines)
        assertTrue(back.playlistHeroCover)
        assertFalse(back.view.albumHeroCover)
    }

    /** D55: 1.8.3 stored one switch, "playlist_hero_cover". On reads as Playlists, off as Off, with nothing rewritten. */
    @Test
    fun theOldPlaylistPictureSwitchReadsAsItDid() = withStore { r ->
        val old = booleanPreferencesKey("playlist_hero_cover")
        r.editNow { it[old] = false }
        val off = r.storedWhen { !it.playlistHeroCover }
        assertEquals(FullWidthPictures.OFF, off.fullWidthPictures)
        r.editNow { it[old] = true }
        assertEquals(FullWidthPictures.PLAYLISTS, r.storedWhen { it.playlistHeroCover }.fullWidthPictures)
        // Reading changed nothing in the store: the old switch is all there is.
        assertEquals(mapOf("playlist_hero_cover" to "b:true"), r.forBackup(settings = true, accounts = false))
        // Each choice is the old switch plus the new one, so 1.8.3's pages (and an older Vinz given this Look) still read theirs.
        r.setFullWidthPictures(FullWidthPictures.OFF)
        val none = r.storedWhen { !it.playlistHeroCover }
        assertEquals(FullWidthPictures.OFF, none.fullWidthPictures)
        assertFalse(none.view.albumHeroCover)
        r.setFullWidthPictures(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        val both = r.storedWhen { it.view.albumHeroCover }
        assertTrue(both.playlistHeroCover)
        assertEquals(FullWidthPictures.ALBUMS_AND_PLAYLISTS, both.fullWidthPictures)
        // The old switch on its own still works; there's no "albums only".
        r.setPlaylistHeroCover(false)
        assertEquals(FullWidthPictures.OFF, r.storedWhen { !it.playlistHeroCover }.fullWidthPictures)
        assertEquals(FullWidthPictures.OFF, FullWidthPictures.of(playlists = false, albums = true))
        assertEquals(FullWidthPictures.PLAYLISTS, FullWidthPictures.of(playlists = true, albums = false))
    }

    /** #404: within ±500 ms, whether it comes from the slider or from a file someone made. */
    @Test
    fun theLyricsTimingStaysWithinItsRange() = withStore { r ->
        assertEquals(-500..500, BehaviorPrefs.LYRICS_OFFSET_MS)
        r.setLyricsOffsetMs(9_999)
        assertEquals(500, r.storedWhen { it.behavior.lyricsOffsetMs != 0 }.behavior.lyricsOffsetMs)
        assertEquals("the store holds the clamped value", "i:500", r.forBackup(settings = true, accounts = false)["lyrics_offset_ms"])
        r.setLyricsOffsetMs(-9_999)
        assertEquals(-500, r.storedWhen { it.behavior.lyricsOffsetMs < 0 }.behavior.lyricsOffsetMs)
        r.setLyricsOffsetMs(500)
        assertEquals(500, r.storedWhen { it.behavior.lyricsOffsetMs > 0 }.behavior.lyricsOffsetMs)
        // A restored backup with a number out of range.
        r.editNow { it[intPreferencesKey("lyrics_offset_ms")] = -70_000 }
        assertEquals(-500, r.storedWhen { it.behavior.lyricsOffsetMs < 0 }.behavior.lyricsOffsetMs)
        r.setLyricsOffsetMs(0)
        assertEquals(0, r.storedWhen { it.behavior.lyricsOffsetMs == 0 }.behavior.lyricsOffsetMs)
    }

    /** A choice this Vinz doesn't know (a backup from a later one) is the default, not a crash. */
    @Test
    fun anUnknownChoiceIsTheDefault() = withStore { r ->
        r.editNow {
            it[stringPreferencesKey("shuffle_button")] = "SOMETHING_NEW"
            it[stringPreferencesKey("car_lyrics_lines")] = "WRAP_THREE"
            it[booleanPreferencesKey("keep_shuffle")] = true
        }
        val s = r.storedWhen { it.behavior.keepShuffle }
        assertEquals(ShuffleButton.SHUFFLE_AND_SMART, s.behavior.shuffleButton)
        assertEquals(CarLyricsLines.ONE_LINE, s.behavior.carLyricsLines)
    }

    /** D53 and D55 are part of a Look, with words for the undo bar; the rest is how Vinz behaves and stays out. */
    @Test
    fun onlyTheTwoLookSettingsTravelInALook() = withStore { r ->
        val look = setOf("lock_screen_player_look", "album_hero_cover")
        val not = setOf("row_swipes", "lyrics_offset_ms", "keep_shuffle", "shuffle_button", "car_lyrics_lines", "locate_playing")
        assertTrue(r.allKeyNames.containsAll(look + not))
        look.forEach { assertEquals(it, 'b', r.lookKinds[it]) }
        assertTrue(r.resetNames.containsAll(look))
        assertTrue(r.notLookNames.containsAll(not))
        assertTrue((r.lookKinds.keys intersect not).isEmpty())
        assertEquals("Lock screen with the player's look → On", LookLabels.change(emptyMap(), mapOf("lock_screen_player_look" to "b:1"), emptyList()))
        assertEquals("Album picture → On", LookLabels.change(emptyMap(), mapOf("album_hero_cover" to "b:1"), emptyList()))

        r.setLockScreenPlayerLook(true)
        r.setFullWidthPictures(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        r.setRowSwipes(false)
        r.storedWhen { it.view.albumHeroCover && !it.behavior.rowSwipes }
        val values = withTimeout(5_000) { r.lookValues.first { "album_hero_cover" in it } }
        assertEquals(mapOf("lock_screen_player_look" to "b:1", "album_hero_cover" to "b:1", "playlist_hero_cover" to "b:1"), values)
        // Another Look takes the two back to their defaults and leaves the behaviour alone.
        r.applyLook(emptyMap())
        val after = r.storedWhen { !it.view.albumHeroCover }
        assertFalse(after.view.lockScreenPlayerLook)
        assertEquals(FullWidthPictures.PLAYLISTS, after.fullWidthPictures)
        assertFalse(after.behavior.rowSwipes)
    }

    /** Every new setting is in a backup's settings and comes back from it; a 1.8.3.1 backup restores as it was. */
    @Test
    fun theyTravelInABackup() = withStore { r ->
        r.setRowSwipes(false)
        r.setLyricsOffsetMs(80)
        r.setKeepShuffle(true)
        r.setShuffleButton(ShuffleButton.SHUFFLE_ONLY)
        r.setCarLyricsLines(CarLyricsLines.WRAP_TWO)
        r.setLocatePlaying(true)
        r.setLockScreenPlayerLook(true)
        r.setFullWidthPictures(FullWidthPictures.ALBUMS_AND_PLAYLISTS)
        val before = r.storedWhen { it.view.albumHeroCover }
        val backup = r.forBackup(settings = true, accounts = false)
        assertEquals(
            mapOf(
                "row_swipes" to "b:false", "lyrics_offset_ms" to "i:80", "keep_shuffle" to "b:true", "shuffle_button" to "s:SHUFFLE_ONLY",
                "car_lyrics_lines" to "s:WRAP_TWO", "locate_playing" to "b:true", "lock_screen_player_look" to "b:true",
                "playlist_hero_cover" to "b:true", "album_hero_cover" to "b:true",
            ),
            backup,
        )
        r.editNow { it.clear() }
        r.storedWhen { !it.view.albumHeroCover }
        r.fromBackup(backup, settings = true, accounts = false)
        val restored = r.storedWhen { it.view.albumHeroCover }
        assertEquals(before.behavior, restored.behavior)
        assertEquals(before.view, restored.view)
        assertEquals(FullWidthPictures.ALBUMS_AND_PLAYLISTS, restored.fullWidthPictures)
        // 1.8.3.1's backup knows none of them: what's here goes back to the defaults, its playlist switch is kept.
        r.fromBackup(mapOf("playlist_hero_cover" to "b:false", "pure_black" to "b:true"), settings = true, accounts = false)
        val old = r.storedWhen { it.pureBlack }
        assertEquals(FullWidthPictures.OFF, old.fullWidthPictures)
        assertEquals(AppSettings().behavior, old.behavior)
        assertEquals(AppSettings().view, old.view)
    }
}
