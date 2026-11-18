package com.vinz.music

import com.vinz.music.data.AutoplayLength
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from Prism's PlayerRequests182Test #299, plus the explicit-radio exemption. */
class AutoplayLengthTest {
    private fun song(id: String, seconds: Int) = Song(id = id, title = id, durationSec = seconds)

    @Test fun defaultLeavesAllSuggestionsAsBefore() {
        val songs = listOf(song("short", 180), song("mix", 3 * 3600), song("unknown", 0))
        assertEquals(songs, AutoplayLength.keep(songs, 0))
        assertTrue(songs.all { AutoplayLength.fitsSuggestion(it, 0, false) })
    }

    @Test fun limitIncludesBoundaryAndUnknownLengths() {
        val songs = listOf(song("short", 180), song("exact", 600), song("over", 601), song("mix", 3600), song("unknown", 0), song("negative", -1))
        assertEquals(listOf("short", "exact", "unknown", "negative"), AutoplayLength.keep(songs, 10).map { it.id })
    }

    @Test fun localPoolIsFilteredBeforeThePickCap() {
        val seed = song("seed", 180)
        val longs = (1..15).map { song("long$it", 3600).copy(artists = listOf(com.vinz.music.data.model.ArtistRef("artist$it"))) }
        val short = song("short", 180).copy(artists = listOf(com.vinz.music.data.model.ArtistRef("other")))
        fun picks(radio: Boolean): List<String> {
            val pool = com.vinz.music.data.foryou.LocalRadio.Pool(
                (longs + short).filter { AutoplayLength.fitsSuggestion(it, 10, radio) },
                emptyMap(), affinity = { if (it.id.startsWith("long")) 1.0 else 0.1 }, genreOf = { null },
            )
            return com.vinz.music.data.foryou.LocalRadio.pick(listOf(seed), pool, emptySet(), 15).map { it.song.id }
        }
        assertEquals(listOf("short"), picks(false))
        assertEquals(15, picks(true).size)
        assertFalse(picks(true).contains("short"))
    }

    @Test fun extremeMinuteValuesDoNotOverflow() {
        assertTrue(AutoplayLength.fits(song("long", Int.MAX_VALUE), Int.MAX_VALUE))
    }

    @Test fun explicitSongRadioIsNotRestricted() {
        val mix = song("mix", 3600)
        assertFalse(AutoplayLength.fitsSuggestion(mix, 10, false))
        assertTrue(AutoplayLength.fitsSuggestion(mix, 10, true))
    }
}
