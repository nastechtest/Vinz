package com.vinz.music

import com.vinz.music.data.prefs.AudioQuality
import com.vinz.music.data.stream.AudioPick
import com.vinz.music.data.stream.CachedFormat
import com.vinz.music.playback.RetryKeys
import com.vinz.music.ui.player.streamQualityNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.7 plan 4.2 ("a song stopping seconds in: malformed WebM") and 4.3 (Premium stuck near 140-150 kbps, #373 #179
 * #148): saved bytes are only joined by the same file, the once-only retries come back at each song change, Premium's
 * best plays on Wi-Fi whatever the quality setting, and Stats for nerds says when something held it back.
 */
class SavedAudioFormatTest {
    private val url = "https://rr1.googlevideo.com/videoplayback?expire=1&itag=251&clen=4012345&lmt=1700000000123456&c=WEB_REMIX"

    @Test fun theSameItagAsAnotherFileDropsTheCachedBytes() {
        val cached = CachedFormat.of(251, 4_012_345, url)
        assertEquals("1700000000123456", cached.lmt)
        // The same itag, another length (the volume-levelled variant): what's cached goes, never joined on.
        val other = CachedFormat.of(251, 4_100_002, url.replace("4012345", "4100002"))
        assertFalse(cached.sameFile(other))
        assertFalse(CachedFormat.mayAdd(before = cached, now = other, saved = true))
        // The same length but YouTube made the file again (another lmt): not the same file either.
        assertFalse(cached.sameFile(CachedFormat(251, 4_012_345, "1700000000999999")))
        // The very same file: added to.
        assertTrue(CachedFormat.mayAdd(before = cached, now = CachedFormat.of(251, 4_012_345, url), saved = true))
        // A link without lmt: the itag and length decide.
        assertTrue(cached.sameFile(CachedFormat(251, 4_012_345)))
        // Nothing saved yet: anything may start it.
        assertTrue(CachedFormat.mayAdd(before = cached, now = other, saved = false))
    }

    @Test fun whatWasRecordedReadsBackAndAnItagAloneIsntTakenOnTrust() {
        val f = CachedFormat(774, 7_654_321, "1700000000123456")
        assertEquals(f, CachedFormat.decode(f.encode()))
        assertEquals(CachedFormat(140, 3_000_000), CachedFormat.decode(CachedFormat(140, 3_000_000).encode()))
        // Before 1.8.7 only the itag was recorded: its length isn't known, so cached bytes under it go once.
        val legacy = CachedFormat.decode(251)
        assertEquals(251, legacy?.itag)
        assertFalse(CachedFormat.mayAdd(before = legacy, now = CachedFormat(251, 4_012_345), saved = true))
        assertNull(CachedFormat.decode(0))
        assertNull(CachedFormat.decode(null))
    }

    @Test fun aSecondUnreadableSongStillGetsItsRetry() {
        val retries = HashMap<String, Int>()
        // As PlaybackService does it: one go per song for an unreadable cached part.
        fun retry(id: String): Boolean = (retries["cache:$id"] == null).also { if (it) retries["cache:$id"] = 1 }
        assertTrue(retry("a"))
        assertFalse("only once for the same song", retry("a"))
        RetryKeys.onSongChange(retries)
        assertTrue("the next song gets its own", retry("b"))
        RetryKeys.onSongChange(retries)
        // Played again later and broken again: it gets its retry again (before 1.8.7 it never did that service's lifetime).
        assertTrue(retry("a"))
        retries["file:a"] = 1; retries["codec:a"] = 2; retries["stuck:a"] = 1; retries["a"] = 3
        RetryKeys.onSongChange(retries)
        assertTrue(retries.isEmpty())
    }

    private val formats = listOf(
        AudioPick.Choice("audio/webm", "opus", 141), // 251
        AudioPick.Choice("audio/mp4", "mp4a.40.2", 130), // 140
        AudioPick.Choice("audio/webm", "opus", 256), // 774 (Premium)
        AudioPick.Choice("audio/mp4", "mp4a.40.2", 256), // 141 (Premium)
    )

    @Test fun premiumOnWifiPicksTheBestWhateverTheQualitySetting() {
        val q = AudioPick.quality(AudioQuality.NORMAL, AudioQuality.NORMAL, lossless = false, dataSaver = false, metered = false, premiumInHand = true)
        assertEquals(AudioQuality.HIGH, q.quality)
        assertNull(q.heldBack)
        assertEquals(2, AudioPick.pick(formats, q.quality))
        // Before 1.8.7 Normal stayed Normal: 251 at ~141 kbps, Premium's formats unused and nothing said.
        val without = AudioPick.quality(AudioQuality.NORMAL, AudioQuality.NORMAL, lossless = false, dataSaver = false, metered = false, premiumInHand = false)
        assertEquals(AudioQuality.NORMAL, without.quality)
        assertEquals(0, AudioPick.pick(formats, without.quality))
    }

    @Test fun onMobileDataTheMobileQualityHoldsAndSaysSo() {
        val q = AudioPick.quality(AudioQuality.HIGH, AudioQuality.NORMAL, lossless = false, dataSaver = false, metered = true, premiumInHand = true)
        assertEquals(AudioQuality.NORMAL, q.quality)
        assertEquals(AudioQuality.NORMAL, q.heldBack)
        assertEquals(0, AudioPick.pick(formats, q.quality))
        // Data saver: at most Normal, and said too.
        val saver = AudioPick.quality(AudioQuality.HIGH, AudioQuality.HIGH, lossless = true, dataSaver = true, metered = true, premiumInHand = true)
        assertEquals(AudioQuality.NORMAL, saver.heldBack)
        // High on mobile data too, or no Premium formats: nothing held back.
        assertNull(AudioPick.quality(AudioQuality.HIGH, AudioQuality.HIGH, false, false, metered = true, premiumInHand = true).heldBack)
        assertNull(AudioPick.quality(AudioQuality.HIGH, AudioQuality.NORMAL, false, false, metered = true, premiumInHand = false).heldBack)
        val note = streamQualityNote("Streaming", q.heldBack, null)
        assertEquals("Premium's best is held back by your mobile data quality (Normal); on Wi-Fi it plays", note)
    }

    @Test fun statsForNerdsSaysASavedCopyPlaysInItsOwnFormat() {
        val copy = CachedFormat(251, 3_670_016)
        val cached = streamQualityNote("Cached", AudioQuality.NORMAL, copy).orEmpty()
        assertTrue(cached, cached.startsWith("The copy saved on this phone plays, in the format it was saved in: itag 251 (Opus) · 3"))
        assertTrue(cached, cached.endsWith("50 MB"))
        assertEquals(
            "The copy saved on this phone plays, in the format it was saved in: itag 140 (AAC)",
            streamQualityNote("Downloaded", null, CachedFormat(140, -1)),
        )
        // Streaming with nothing held back, or a copy whose format was never recorded: nothing to say.
        assertNull(streamQualityNote("Streaming", null, null))
        assertNull(streamQualityNote("Cached", null, null))
    }
}
