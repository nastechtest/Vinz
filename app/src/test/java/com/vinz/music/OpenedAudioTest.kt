package com.vinz.music

import android.content.Intent
import android.net.Uri
import com.vinz.music.data.local.OpenedAudio
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Open with Vinz" (Reddit, 1.8.5): an audio file opened from a file manager plays in Vinz as a one-song queue, from
 * the file itself (no copy), named by its tags when it has them, else by its file name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenedAudioTest {
    private val content = Uri.parse("content://com.example.files/document/primary%3AMusic%2Fsong.flac")

    @Test fun takesAudioFilesOpenedWithVinz() {
        assertTrue(OpenedAudio.accepts(Intent.ACTION_VIEW, content, "audio/flac"))
        assertTrue(OpenedAudio.accepts(Intent.ACTION_VIEW, content, "audio/mpeg"))
        assertTrue(OpenedAudio.accepts(Intent.ACTION_VIEW, content, "application/ogg"))
        assertTrue(OpenedAudio.accepts(Intent.ACTION_VIEW, content, "application/x-flac"))
        assertTrue(OpenedAudio.accepts(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Music/a.mp3"), "audio/mpeg"))
    }

    @Test fun leavesOtherIntentsAlone() {
        // A Look (a zip), a YouTube Music link, a vinz:// link, a share: not audio files.
        assertFalse(OpenedAudio.accepts(Intent.ACTION_VIEW, content, "application/zip"))
        assertFalse(OpenedAudio.accepts(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=abc"), null))
        assertFalse(OpenedAudio.accepts(Intent.ACTION_VIEW, Uri.parse("vinz://player"), null))
        assertFalse(OpenedAudio.accepts(Intent.ACTION_SEND, content, "audio/mpeg"))
        assertFalse(OpenedAudio.accepts(Intent.ACTION_VIEW, null, "audio/mpeg"))
        assertFalse(OpenedAudio.accepts(Intent.ACTION_VIEW, Uri.parse("https://example.com/a.mp3"), "audio/mpeg"))
    }

    @Test fun songFromTags() {
        val s = OpenedAudio.song(content, displayName = "song.flac", title = "Real Title", artist = "A; B", album = "The Album", durationMs = 185_400)
        assertTrue(s.isLocal)
        assertEquals("Real Title", s.title)
        assertEquals(listOf("A", "B"), s.artists.map { it.name })
        assertEquals("The Album", s.album?.title)
        assertEquals(185, s.durationSec)
        // It plays from the file it came from.
        assertEquals(content, OpenedAudio.uriOf(s.id))
    }

    @Test fun untaggedFileIsNamedByItsFileName() {
        val s = OpenedAudio.song(content, displayName = "My Demo.mp3", title = "  ", artist = null, album = null, durationMs = null)
        assertEquals("My Demo", s.title)
        assertTrue(s.artists.isEmpty())
        assertNull(s.album)
        assertEquals(0, s.durationSec)
        // No name at all: the path's last part.
        val f = Uri.parse("file:///sdcard/Music/track01.ogg")
        assertEquals("track01", OpenedAudio.song(f, null, null, null, null, null).title)
    }

    // Review (1.8.5): another app can name Vinz's own files or providers to it; those are never opened.

    private val pkg = "com.vinz.music"
    private val dirs = listOf("/data/user/0/$pkg", "/storage/emulated/0/Android/data/$pkg")

    /** The phone's paths as they really are: ".." resolved, and /data/data and one link in Music leading elsewhere. */
    private val canonical: (String) -> String? = { p ->
        val parts = ArrayList<String>()
        p.split('/').filter { it.isNotEmpty() && it != "." }.forEach { if (it == "..") parts.removeLastOrNull() else parts += it }
        "/" + parts.joinToString("/")
            .replace(Regex("^data/data/"), "data/user/0/")
            .replace(Regex("^sdcard/Music/innocent\\.mp3$"), "data/user/0/$pkg/files/x.mp3")
    }

    private fun own(uri: String, authorities: Set<String> = emptySet()) =
        OpenedAudio.vinzsOwn(Uri.parse(uri), pkg, dirs, authorities, canonical)

    @Test fun vinzsOwnFilesAreNeverOpened() {
        assertTrue(own("file:///data/data/$pkg/databases/vinz.db"))
        assertTrue(own("file:///data/user/0/$pkg/shared_prefs/settings.xml"))
        assertTrue("found by going up", own("file:///sdcard/Music/../../data/data/$pkg/files/cookies"))
        assertTrue("found through a link", own("file:///sdcard/Music/innocent.mp3"))
        assertTrue("its folder on shared storage", own("file:///storage/emulated/0/Android/data/$pkg/files/Downloads/a.opus"))
        assertTrue("device-protected storage (named by the folder)", own("file:///data/user_de/0/$pkg/files/a.mp3"))
        // Others' files play.
        assertFalse(own("file:///sdcard/Music/a.mp3"))
        assertFalse(own("file:///storage/emulated/0/Android/data/com.other.app/files/a.mp3"))
        assertFalse("another app whose name only starts like Vinz's", own("file:///data/user/0/$pkg.dev2/files/a.mp3"))
        // A path that can't be resolved isn't trusted.
        assertTrue(OpenedAudio.vinzsOwn(Uri.parse("file:///sdcard/a.mp3"), pkg, dirs, emptySet()) { null })
    }

    @Test fun vinzsOwnProvidersAreNeverOpened() {
        assertTrue(own("content://$pkg.share/cache/replay.png"))
        assertTrue(own("content://$pkg.artwork/abc"))
        assertTrue("for another user of the phone", own("content://10@$pkg.share/x.mp3"))
        assertTrue("a provider listed for the app", own("content://androidx.car.app.connection/x", setOf("androidx.car.app.connection")))
        assertFalse(own(content.toString()))
        assertFalse(own("content://media/external/audio/media/12"))
        assertFalse("not Vinz's, though it starts the same", own("content://$pkg" + "x.files/a.mp3"))
    }

    /** As the app asks it: its own package, folders and providers. */
    @Test fun vinzsOwnFromTheApp() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = app.packageName
        assertTrue(OpenedAudio.vinzsOwn(app, Uri.parse("file:///sdcard/../data/data/$p/shared_prefs/x.xml")))
        assertTrue(OpenedAudio.vinzsOwn(app, Uri.parse("content://$p.share/x.mp3")))
        assertFalse(OpenedAudio.vinzsOwn(app, content))
        assertFalse(OpenedAudio.vinzsOwn(app, Uri.parse("file:///sdcard/Music/a.mp3")))
    }

    @Test fun mediaStoreIdsAreNotOpenedFiles() {
        assertNull(OpenedAudio.uriOf(Song.LOCAL_PREFIX + "1234"))
        assertNull(OpenedAudio.uriOf("dQw4w9WgXcQ"))
    }
}
