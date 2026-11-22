package com.vinz.music

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.data.local.LocalLossless
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.playback.NerdStats
import com.vinz.music.ui.player.undecodedFileNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.8.4.1 (Reddit): an ALAC file this phone has no decoder for was set aside and the song's stream played without a word,
 * which read as "ALAC is still unsupported". The song is now noted when its file is set aside, and Stats for nerds says on
 * it that this phone can't decode the file, so the stream plays.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class UndecodedFileNoteTest {
    private val alac = LocalLossless.Track(Uri.parse("content://media/external/audio/media/7"), "Hotel California", "Eagles", 391_000L, "audio/alac", 45_000_000L)
    private val files = LocalLossless(ApplicationProvider.getApplicationContext()).apply { use(listOf(alac)) }
    private fun song(id: String = "eagles1", title: String = "Hotel California") = Song(id, title, listOf(ArtistRef("Eagles")), durationSec = 391)

    @Test fun aFileThatWontDecodeIsSetAsideAndTheSongNoted() {
        assertEquals(alac, files.match(song()))
        assertTrue(files.setAside("eagles1"))
        assertEquals("ALAC", files.streamInstead["eagles1"])
        assertTrue(alac.uri in files.unplayable)
        // The stream plays from now on: the file isn't matched again, nor counted as what's playing.
        assertNull(files.match(song()))
        assertNull(files.matched["eagles1"])
    }

    @Test fun onlyOncePerFileAndNeverForASongWithoutOne() {
        files.match(song())
        assertTrue(files.setAside("eagles1"))
        assertFalse("already set aside: the error is the song's own", files.setAside("eagles1"))
        assertFalse("no file matched", files.setAside("other"))
        assertNull(files.streamInstead["other"])
    }

    @Test fun statsForNerdsSaysSoOnTheSong() {
        assertEquals("ALAC file: this phone can't decode it, so the stream plays instead", undecodedFileNote(NerdStats(source = "Streaming", codec = "opus", undecodedFile = "ALAC")))
        assertNull("its file played, or it has none", undecodedFileNote(NerdStats(source = "Lossless · FLAC on this phone", codec = "flac")))
        assertNull(undecodedFileNote(NerdStats(source = "Streaming", codec = "opus")))
    }

    @Test fun everyLanguageHasTheNote() {
        val res = File("src/main/res")
        val all = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }.map { File(it, "strings.xml") }.filter { it.isFile }
        assertEquals("English and the 31 translations", 32, all.size)
        val english = Regex("""<string name="file_can_t_be_decoded_stream_plays">([^<]+)</string>""").find(File(res, "values/strings.xml").readText())!!.groupValues[1]
        all.forEach { f ->
            val text = Regex("""<string name="file_can_t_be_decoded_stream_plays">([^<]+)</string>""").find(f.readText(Charsets.UTF_8))?.groupValues?.get(1)
            assertTrue("${f.parentFile.name} has the note", text != null)
            assertTrue("${f.parentFile.name} names the format", text!!.contains("%1\$s"))
            if (f.parentFile.name != "values") assertTrue("${f.parentFile.name} is translated", text != english)
        }
    }
}
