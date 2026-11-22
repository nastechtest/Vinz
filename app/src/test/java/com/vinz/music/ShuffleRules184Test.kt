package com.vinz.music

import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.ShuffleButton
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.playback.ShuffleMode
import com.vinz.music.playback.StartKind
import com.vinz.music.playback.carryShuffle
import com.vinz.music.playback.lyricShiftOf
import com.vinz.music.playback.nextShuffle
import com.vinz.music.playback.startKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.4's rules in [com.vinz.music.playback.PlayerConnection], as plain functions: the Shuffle button's next state
 * (D57), what a new queue does with the shuffle the last one had (#416), and the lyric shift (#404).
 */
class ShuffleRules184Test {
    private val both = ShuffleButton.SHUFFLE_AND_SMART
    private val only = ShuffleButton.SHUFFLE_ONLY

    // ------------------------------------------------------------------ D57

    @Test fun theButtonAsItWas() {
        assertEquals(ShuffleMode.SHUFFLE, nextShuffle(ShuffleMode.OFF, both, smartAllowed = true))
        assertEquals(ShuffleMode.SMART, nextShuffle(ShuffleMode.SHUFFLE, both, smartAllowed = true))
        assertEquals(ShuffleMode.OFF, nextShuffle(ShuffleMode.SMART, both, smartAllowed = true))
        // A radio has no Smart Shuffle: on → off.
        assertEquals(ShuffleMode.OFF, nextShuffle(ShuffleMode.SHUFFLE, both, smartAllowed = false))
    }

    @Test fun shuffleOnlyIsOffOnOff() {
        assertEquals(ShuffleMode.SHUFFLE, nextShuffle(ShuffleMode.OFF, only, smartAllowed = true))
        assertEquals(ShuffleMode.OFF, nextShuffle(ShuffleMode.SHUFFLE, only, smartAllowed = true))
        assertEquals("from Smart Shuffle the next tap is off", ShuffleMode.OFF, nextShuffle(ShuffleMode.SMART, only, smartAllowed = true))
        assertEquals(ShuffleMode.OFF, nextShuffle(ShuffleMode.SHUFFLE, only, smartAllowed = false))
    }

    // ------------------------------------------------------------------ #416

    private fun carry(was: ShuffleMode, keep: Boolean, kind: StartKind, asked: Boolean = false, button: ShuffleButton = both, smartAllowed: Boolean = true) =
        carryShuffle(was, keep, kind, asked, button, smartAllowed)

    @Test fun withTheSettingOffEveryNewQueueIsAsBefore() {
        for (kind in StartKind.entries) for (was in ShuffleMode.entries) {
            assertEquals("$kind after $was", ShuffleMode.OFF, carry(was, keep = false, kind))
            assertEquals("$kind after $was, Shuffle pressed", ShuffleMode.SHUFFLE, carry(was, keep = false, kind, asked = true))
        }
    }

    @Test fun aListKeepsTheShuffleTheLastQueueHad() {
        assertEquals(ShuffleMode.OFF, carry(ShuffleMode.OFF, keep = true, StartKind.LIST))
        assertEquals(ShuffleMode.SHUFFLE, carry(ShuffleMode.SHUFFLE, keep = true, StartKind.LIST))
        assertEquals(ShuffleMode.SMART, carry(ShuffleMode.SMART, keep = true, StartKind.LIST))
        // The list's own Shuffle button: on whatever was; Smart Shuffle stays Smart Shuffle.
        assertEquals(ShuffleMode.SHUFFLE, carry(ShuffleMode.OFF, keep = true, StartKind.LIST, asked = true))
        assertEquals(ShuffleMode.SHUFFLE, carry(ShuffleMode.SHUFFLE, keep = true, StartKind.LIST, asked = true))
        assertEquals(ShuffleMode.SMART, carry(ShuffleMode.SMART, keep = true, StartKind.LIST, asked = true))
    }

    @Test fun smartShuffleCarriesOnlyWhereItMay() {
        assertEquals("a queue Smart Shuffle can't add to", ShuffleMode.SHUFFLE, carry(ShuffleMode.SMART, keep = true, StartKind.LIST, smartAllowed = false))
        assertEquals("the button set to Shuffle only", ShuffleMode.SHUFFLE, carry(ShuffleMode.SMART, keep = true, StartKind.LIST, button = only))
    }

    @Test fun everythingButAListIsLeftAlone() {
        for (kind in StartKind.entries.filter { it != StartKind.LIST }) for (was in ShuffleMode.entries) {
            assertEquals("$kind after $was", ShuffleMode.OFF, carry(was, keep = true, kind))
            assertEquals("$kind after $was, Shuffle pressed", ShuffleMode.SHUFFLE, carry(was, keep = true, kind, asked = true))
        }
    }

    @Test fun whatANewQueueIs() {
        val list = (0 until 5).map { Song("s$it", "Song $it") }
        fun kind(source: QueueSource, songs: List<Song> = list, start: Int = 0, video: Boolean = false, dj: Boolean = false) = startKind(source, songs, start, video, dj)
        for (k in listOf(QueueKind.PLAYLIST, QueueKind.ALBUM, QueueKind.LIBRARY, QueueKind.REPLAY, QueueKind.OTHER)) {
            assertEquals("$k", StartKind.LIST, kind(QueueSource(k, "x"), start = 3))
        }
        assertEquals(StartKind.RADIO, kind(QueueSource(QueueKind.RADIO, "x")))
        assertEquals(StartKind.SINGLE, kind(QueueSource(QueueKind.SINGLE, "x"), songs = list.take(1)))
        assertEquals("one song isn't a list", StartKind.SINGLE, kind(QueueSource(QueueKind.PLAYLIST, "x"), songs = list.take(1)))
        assertEquals(StartKind.DJ_SET, kind(QueueSource(QueueKind.ALBUM, "x"), dj = true))
        assertEquals(StartKind.VIDEO, kind(QueueSource(QueueKind.PLAYLIST, "x"), video = true))
        val spoken = list.toMutableList().also { it[2] = it[2].copy(isEpisode = true) }
        assertEquals(StartKind.SPOKEN, kind(QueueSource(QueueKind.OTHER, "x"), songs = spoken, start = 2))
        val live = list.toMutableList().also { it[1] = it[1].copy(isLive = true) }
        assertEquals(StartKind.SPOKEN, kind(QueueSource(QueueKind.OTHER, "x"), songs = live, start = 1))
        // A radio is a radio whatever else it is.
        assertEquals(StartKind.RADIO, kind(QueueSource(QueueKind.RADIO, "x"), songs = list.take(1)))
    }

    // ------------------------------------------------------------------ #404

    @Test fun theShiftIsTheSongsOffsetPlusSettingsLessTheVideosLag() {
        assertEquals(0L, lyricShiftOf(songOffsetMs = 0, settingMs = 0, videoLagMs = 0))
        assertEquals("Settings alone", 120L, lyricShiftOf(0, 120, 0))
        assertEquals(-500L, lyricShiftOf(0, -500, 0))
        assertEquals("the song's own nudge adds to it", 420L, lyricShiftOf(300, 120, 0))
        assertEquals("less a music video's lag", -1_580L, lyricShiftOf(300, 120, 2_000))
        assertEquals("as 1.8.3 with Settings at 0", -1_700L, lyricShiftOf(300, 0, 2_000))
    }
}
