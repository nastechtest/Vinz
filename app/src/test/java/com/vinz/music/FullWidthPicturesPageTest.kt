package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.ui.screens.CollectionType
import com.vinz.music.ui.screens.heroCover
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D55 (1.8.4), "Full-width pictures: Off / Playlists / Albums and playlists": which pages have their picture across the
 * whole width, for every kind of page and every choice. Until 1.8.4 an album never had.
 */
class FullWidthPicturesPageTest {
    @Test fun everyKindOfPageForEveryChoice() {
        for (type in CollectionType.entries) {
            assertEquals("$type, Off", false, heroCover(type, FullWidthPictures.OFF, sideBySide = false))
            assertEquals("$type, Playlists", type != CollectionType.ALBUM, heroCover(type, FullWidthPictures.PLAYLISTS, sideBySide = false))
            assertEquals("$type, Albums and playlists", true, heroCover(type, FullWidthPictures.ALBUMS_AND_PLAYLISTS, sideBySide = false))
        }
    }

    @Test fun neverBesideTheSongs() {
        // A tablet on its side keeps the cover in its own column, whatever is chosen.
        for (type in CollectionType.entries) for (pictures in FullWidthPictures.entries) {
            assertEquals("$type, $pictures", false, heroCover(type, pictures, sideBySide = true))
        }
    }

    @Test fun asVinzShipsPlaylistsHaveItAndAlbumsDont() {
        val pictures = AppSettings().fullWidthPictures
        assertEquals(true, heroCover(CollectionType.PLAYLIST, pictures, sideBySide = false))
        assertEquals(true, heroCover(CollectionType.LIKED, pictures, sideBySide = false))
        assertEquals(false, heroCover(CollectionType.ALBUM, pictures, sideBySide = false))
    }
}
