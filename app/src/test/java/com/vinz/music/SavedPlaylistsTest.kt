package com.vinz.music

import com.vinz.music.data.VinzLibrary
import com.vinz.music.data.SavedPlaylist
import com.vinz.music.ui.screens.playlistIdIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** H7: YouTube playlists kept on the phone without an account, and links pasted into Library. */
class SavedPlaylistsTest {
    @Test
    fun savedPlaylistsAreKeptAndReadBack() {
        val dir = Files.createTempDirectory("vinzlib").toFile()
        val lib = VinzLibrary(dir)
        lib.savePlaylist(SavedPlaylist("VLPLabc", "Road trip", "Playlist • Someone", null, 1))
        // The same playlist by its plain id: one entry, the newest.
        lib.savePlaylist(SavedPlaylist("PLabc", "Road trip!", "Playlist • Someone", null, 2))
        assertEquals(1, lib.playlists.value.size)
        assertTrue(lib.isPlaylistSaved("VLPLabc"))
        // Read back from the file (what a restart, or a restored backup, sees).
        val again = VinzLibrary(dir)
        assertEquals("Road trip!", again.playlists.value.single().title)
        again.unsavePlaylist("VLPLabc")
        assertFalse(again.isPlaylistSaved("PLabc"))
        dir.deleteRecursively()
    }

    /** 1.7.6 folders: a playlist is in one folder at most; taking a folder apart leaves its playlists alone. */
    @Test
    fun foldersHoldPlaylists() {
        val dir = Files.createTempDirectory("vinzlib").toFile()
        val lib = VinzLibrary(dir)
        val gym = lib.createFolder("Gym", "VLPLa")
        val chill = lib.createFolder("Chill")
        lib.moveToFolder("PLb", chill)
        lib.moveToFolder("PLa", chill)
        assertEquals(emptyList<String>(), lib.folders.value.first { it.id == gym }.playlists)
        assertEquals(listOf("PLb", "PLa"), lib.folders.value.first { it.id == chill }.playlists)
        assertEquals("Chill", lib.folderOf("VLPLb")?.name)
        lib.moveToFolder("PLb", null)
        assertNull(lib.folderOf("PLb"))
        lib.deleteFolder(chill)
        assertEquals(listOf("Gym"), VinzLibrary(dir).folders.value.map { it.name })
        dir.deleteRecursively()
    }

    @Test
    fun mixesAreNotKept() {
        assertTrue(VinzLibrary.isMix("RDTMAK5uy_abc"))
        assertTrue(VinzLibrary.isMix("VLRDAMVMxyz"))
        assertFalse(VinzLibrary.isMix("PLabc"))
        // YouTube Music's own featured playlists stay as they are: they can be kept.
        assertFalse(VinzLibrary.isMix("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
    }

    @Test
    fun playlistLinksAreRead() {
        assertEquals("PLx-y_z", playlistIdIn("https://www.youtube.com/playlist?list=PLx-y_z"))
        assertEquals("PLabc", playlistIdIn("Look: https://music.youtube.com/playlist?list=PLabc&si=123 nice"))
        assertEquals("OLAK5uy_q", playlistIdIn("https://youtube.com/watch?v=abc&list=OLAK5uy_q"))
        assertNull(playlistIdIn("https://youtu.be/abc"))
        assertNull(playlistIdIn("https://example.com/?list=PLabc"))
    }
}
