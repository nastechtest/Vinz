package com.vinz.music.data.canvas

import android.app.Application
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.7: the second look's decoder is told to hurry (work to get through, not playback to keep pace with), and a phone
 * whose decoder won't have it is read again as before: the hint can never leave a loop unchecked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipDecodeHurryTest {
    @Test fun theDecoderIsToldToHurryAndNothingElseChanges() {
        val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7f420888) }
        ClipDecode.hurried(format)
        assertEquals("not real time", 1, format.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(Short.MAX_VALUE.toInt(), format.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
        assertEquals(1920, format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(0x7f420888, format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
    }

    @Test fun aDecoderThatWontHurryIsReadAsBefore() {
        val tries = mutableListOf<Boolean>()
        // Hurried, it gives enough frames: read once.
        assertEquals(220, ClipDecode.hurriedFirst<Int>({ it >= 30 }) { hurry -> tries += hurry; 220 })
        assertEquals(listOf(true), tries)
        // It refuses the hint (configure throws): read again without it.
        tries.clear()
        assertEquals(221, ClipDecode.hurriedFirst<Int>({ it >= 30 }) { hurry -> tries += hurry; if (hurry) throw IllegalArgumentException("operating rate") else 221 })
        assertEquals(listOf(true, false), tries)
        // Too few frames with it: read again without it.
        tries.clear()
        assertEquals(219, ClipDecode.hurriedFirst<Int>({ it >= 30 }) { hurry -> tries += hurry; if (hurry) 3 else 219 })
        assertEquals(listOf(true, false), tries)
        // Failing both ways fails as it did before (the loop's look then counts as not read).
        val thrown = runCatching { ClipDecode.hurriedFirst<Int>({ true }) { error("decoder") } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }
}
