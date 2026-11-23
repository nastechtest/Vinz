package com.vinz.music

import com.vinz.music.data.local.LocalMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #520: a local album's cover looked pixelated on the player and the album page. Android's album art is a small
 * thumbnail; a large view asks for the album's own embedded picture instead, lists keep the small one.
 */
class LocalFullArtTest {
    private val art = "content://media/external/audio/albumart/42"

    @Test fun aLargeViewGetsTheFullPicture() {
        assertEquals(42L, LocalMusic.fullArtAlbum(art, 1080, 1080))
        // A tall slot counts by its larger side.
        assertEquals(42L, LocalMusic.fullArtAlbum(art, 400, 900))
    }

    @Test fun listsKeepTheThumbnail() {
        assertNull(LocalMusic.fullArtAlbum(art, 150, 150))
        assertNull(LocalMusic.fullArtAlbum(art, 544, 544))
        // A size Coil doesn't know stays light.
        assertNull(LocalMusic.fullArtAlbum(art, null, null))
    }

    @Test fun onlyAndroidsAlbumArt() {
        assertNull(LocalMusic.fullArtAlbum("file:///data/user/0/com.vinz.music/files/artwork/local/a42.jpg", 1080, 1080))
        assertNull(LocalMusic.fullArtAlbum("https://lh3.googleusercontent.com/abc=w544-h544", 1080, 1080))
        assertNull(LocalMusic.fullArtAlbum("content://media/external/audio/albumart/0", 1080, 1080))
        assertNull(LocalMusic.fullArtAlbum("content://media/external/audio/albumart/x", 1080, 1080))
        assertNull(LocalMusic.fullArtAlbum(null, 1080, 1080))
    }
}
