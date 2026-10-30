package com.vinz.music

import com.vinz.music.data.lyrics.Lrc
import com.vinz.music.data.model.parseDuration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The podcast and lyric parsing that the timing fixes rely on. Offline only: the tests that call
 * YouTube Music live in PodcastLiveTest, because they fail on network hiccups and must not gate CI.
 */
class PodcastTest {
    @Test
    fun episodeLengthsInWords() {
        assertEquals(2 * 3600 + 54 * 60, parseDuration("2 hr 54 min"))
        assertEquals(47 * 60, parseDuration("47 min"))
        assertEquals(3600, parseDuration("1 hr"))
        assertEquals(3 * 60 + 5, parseDuration("3:05"))
    }

    @Test
    fun lrcOffsetMovesEveryLine() {
        // [offset:+500]: the lyrics come half a second sooner than their stamps.
        val lines = Lrc.parse("[offset:+500]\n[00:10.00]first\n[00:12.50]second").filter { !it.isGap }
        assertEquals(9_500L, lines[0].timeMs)
        assertEquals(12_000L, lines[1].timeMs)
        val later = Lrc.parse("[offset:-250]\n[00:10.00]first").filter { !it.isGap }
        assertEquals(10_250L, later[0].timeMs)
    }
}
