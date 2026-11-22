package com.vinz.music

import com.vinz.music.playback.DjFlow
import com.vinz.music.playback.DjSequencer
import com.vinz.music.playback.DjTrack
import com.vinz.music.playback.MusicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * #374 (1.8.5): Shuffle in DJ mode deals a new DJ set. [DjSequencer.order] can be told the song to start from (its
 * default call is exactly as before), and [DjSequencer.newSet] picks a different, still well-blending first song at
 * random and orders the rest on from it.
 */
class DjShuffleTest {
    private fun t(id: String, tonic: Int, minor: Boolean = false, bpm: Double = 120.0, loud: Float = -9f, artist: String = id) =
        DjTrack(id, MusicKey(tonic, minor, 0.2f), bpm, loud, artist)

    /** A queue's worth of songs: keys round the wheel, tempos 100-130, a few artists more than once. */
    private fun queue(n: Int = 16, seed: Long = 7): List<DjTrack> {
        val r = Random(seed)
        return (0 until n).map { t("s$it", r.nextInt(12), r.nextBoolean(), 100.0 + r.nextInt(30), -14f + r.nextFloat() * 6, "a${it % 6}") }
    }

    private val start = t("start", 0, artist = "first")

    private fun artistsRunning(seq: List<DjTrack>, before: DjTrack) = (listOf(before) + seq).zipWithNext().count { (a, b) -> a.artist == b.artist }

    @Test fun theDefaultCallIsUnchanged() {
        val tracks = queue()
        assertEquals(DjSequencer.order(start, tracks, DjFlow.STEADY), DjSequencer.order(start, tracks, DjFlow.STEADY, first = null))
        assertEquals(DjSequencer.order(start, tracks, DjFlow.BUILD), DjSequencer.order(start, tracks, DjFlow.BUILD, first = null))
    }

    @Test fun aChosenFirstSongLeadsAndTheRestFollowsFromIt() {
        val tracks = queue()
        val usual = DjSequencer.order(start, tracks, DjFlow.STEADY)
        val other = tracks.first { it != usual.first() }
        val set = DjSequencer.order(start, tracks, DjFlow.STEADY, first = other)
        assertEquals(other, set.first())
        assertEquals("the same songs, each once", tracks.toSet(), set.toSet())
        assertEquals(tracks.size, set.size)
        assertEquals("the rest is the set that follows on from it", DjSequencer.order(other, tracks - other, DjFlow.STEADY), set.drop(1))
        assertNotEquals(usual, set)
    }

    @Test fun aNewSetIsAValidSetThatDiffersFromTheDefault() {
        val window: List<DjTrack?> = queue()
        val usual = DjSequencer.order(start, window.filterNotNull(), DjFlow.STEADY)
        // The queue as DJ mode left it: in its order.
        val current = usual.map { window.indexOf(it) }
        val asLaid = current.map { window[it] }
        val idx = DjSequencer.newSet(start, asLaid, DjFlow.STEADY, Random(1))!!
        assertEquals("every song once", asLaid.indices.toList(), idx.sorted())
        val set = idx.map { asLaid[it]!! }
        assertNotEquals("another song comes next", asLaid.first(), set.first())
        assertNotEquals(asLaid, set)
        // Still a DJ set: after the first, the order DJ mode would give from it; no artist twice running where avoidable.
        assertEquals(DjSequencer.order(set.first(), asLaid.map { it!! } - set.first(), DjFlow.STEADY), set.drop(1))
        assertTrue("artists twice running: ${(listOf(start) + set).map { it.artist }}", artistsRunning(set, start) <= artistsRunning(usual, start))
        // The first song blends: among the better half of what could follow the playing song.
        val e = DjSequencer.energies(listOf(start) + asLaid.map { it!! })
        fun cost(i: Int) = DjSequencer.step(start, asLaid[i]!!, e[0], e[i + 1], 1, asLaid.size, DjFlow.STEADY)
        val costs = (1 until asLaid.size).map(::cost).sorted()
        val firstCost = cost(idx.first())
        assertTrue("first step $firstCost, the better half's worst ${costs[(costs.size + 1) / 2 - 1]}", firstCost <= costs[(costs.size + 1) / 2 - 1] + 1e-9)
    }

    @Test fun eachTapGivesAnotherOrder() {
        var laid: List<DjTrack?> = queue()
        val seen = HashSet<List<String>>()
        val r = Random(3)
        repeat(6) {
            val idx = DjSequencer.newSet(start, laid, DjFlow.STEADY, r)!!
            val next = idx.map { laid[it] }
            assertNotEquals("tap $it: another song first", laid.first(), next.first())
            laid = next
            seen += next.map { it!!.id }
        }
        assertTrue("several different sets (${seen.size})", seen.size >= 3)
    }

    @Test fun songsNotScannedStayAfterInTheirOrder() {
        val known = queue(6)
        val window = listOf(known[0], null, known[1], known[2], null, known[3], known[4], known[5])
        val idx = DjSequencer.newSet(start, window, DjFlow.STEADY, Random(2))!!
        assertEquals(listOf(1, 4), idx.takeLast(2))
        assertNotEquals(0, idx.first())
        assertEquals(window.indices.toList(), idx.sorted())
    }

    @Test fun withNothingScannedAnotherSongStillComesFirst() {
        val window = List<DjTrack?>(5) { null }
        val idx = DjSequencer.newSet(start, window, DjFlow.STEADY, Random(5))!!
        assertNotEquals(0, idx.first())
        assertEquals("the rest keep their order", (0 until 5).filter { it != idx.first() }, idx.drop(1))
    }

    @Test fun nothingToReorderInAOneSongWindow() {
        assertNull(DjSequencer.newSet(start, listOf(t("only", 0)), DjFlow.STEADY, Random(1)))
        assertNull(DjSequencer.newSet(start, emptyList(), DjFlow.STEADY, Random(1)))
    }

    @Test fun anUnknownPlayingSongIsFine() {
        val idx = DjSequencer.newSet(null, queue(5), DjFlow.WIND_DOWN, Random(9))!!
        assertEquals((0 until 5).toList(), idx.sorted())
        assertNotEquals(0, idx.first())
    }
}
