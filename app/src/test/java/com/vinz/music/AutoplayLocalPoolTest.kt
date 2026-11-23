package com.vinz.music

import com.vinz.music.data.AutoplayLength
import com.vinz.music.data.foryou.LocalRadio
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local pool must be restricted before LocalRadio's capped ranking, not after it. */
class AutoplayLocalPoolTest {
    private fun song(id: String, duration: Int) =
        Song(id, id, listOf(ArtistRef("artist-$id")), durationSec = duration)

    private val seed = song("seed", 180)
    private val longSongs = (1..65).map { song("long$it", 3600) }
    private val eligible = listOf(song("short", 180), song("boundary", 600), song("unknown", 0))

    private fun picks(limit: Int, radio: Boolean, cap: Int): List<Song> {
        val pool = LocalRadio.Pool(
            longSongs + eligible, emptyMap(),
            affinity = { if (it.id.startsWith("long")) 1.0 else 0.1 },
            genreOf = { null },
            allowed = { radio || AutoplayLength.fits(it, limit) },
        )
        return LocalRadio.pick(listOf(seed), pool, emptySet(), cap).map { it.song }
    }

    @Test fun eligibleSongsAreNotCrowdedOutByLongerHigherScoringSongs() {
        // The normal 15-pick draw and no-repeats' wider 60-pick draw must both filter first.
        for (cap in listOf(15, 60)) {
            assertEquals(eligible.map { it.id }.toSet(), picks(10, false, cap).map { it.id }.toSet())
        }
    }

    @Test fun explicitlyStartedRadioIsExempt() {
        val songs = picks(10, true, 15)
        assertEquals(15, songs.size)
        assertTrue(songs.all { it.id.startsWith("long") })
    }

    @Test fun disabledLimitRetainsTheOriginalRanking() {
        assertEquals(picks(10, true, 15), picks(0, false, 15))
    }

    @Test fun durationBoundaryAndUnknownAreAllowed() {
        assertTrue(AutoplayLength.fits(song("exact", 600), 10))
        assertFalse(AutoplayLength.fits(song("over", 601), 10))
        assertTrue(AutoplayLength.fits(song("unknown", 0), 10))
    }
}
