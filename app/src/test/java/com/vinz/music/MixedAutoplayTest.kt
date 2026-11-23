package com.vinz.music

import com.vinz.music.data.foryou.LocalRadio
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #461: "When the queue ends" set to both (your music and Vinz's catalog). An amapiano song played from search brought
 * the listener's own phonk, R&B and pop in after it. Your own half has to stay close to the song the queue is for.
 */
class MixedAutoplayTest {
    private fun song(id: String, artist: String) = Song(id, "Song $id", listOf(ArtistRef(artist)), durationSec = 200)

    private val dance = "Dance & electronic"

    /**
     * The seed is amapiano ("Dance & electronic"); the songs the listener happened to play just before searching it are
     * R&B, pop and hip-hop: one sitting's neighbours, not songs that belong with it.
     */
    @Test
    fun ownMusicOfOtherGenresStaysOut() {
        val seed = song("askije", "Kabza")
        val rnb = song("rnb", "Singer")          // played right before the seed, once
        val pop = song("pop", "Popstar")         // two before
        val phonk = song("phonk", "Phonker")     // three before (filed under hip-hop)
        val noGenre = song("nogenre", "Unknown") // no genre known, played next to the seed once
        val amapiano = song("amapiano", "DJ Maphorisa")
        val house = song("house", "Black Coffee")
        val sameArtist = song("kabza2", "Kabza")
        val genres = mapOf(
            "askije" to dance, "rnb" to "R&B & soul", "pop" to "Pop", "phonk" to "Hip-hop",
            "amapiano" to dance, "house" to dance,
        )
        val co = mapOf("askije" to mapOf("rnb" to 1.0, "pop" to 0.5, "phonk" to 1.0 / 3, "nogenre" to 1.0))
        val pool = LocalRadio.Pool(
            listOf(rnb, pop, phonk, noGenre, amapiano, house, sameArtist), co,
            // The favourites are the unrelated ones.
            affinity = { if (it.id in setOf("rnb", "pop", "phonk")) 1.0 else 0.3 },
            genreOf = { genres[it] },
        )
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 15)
        val mine = LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song.id }.toSet()
        assertEquals(setOf("amapiano", "house", "kabza2"), mine)
    }

    /** A song new to the listener (searched, no genre known yet): only its artist, or songs played with it again and again. */
    @Test
    fun seedWithNoGenreTakesOnlyWhatReallyGoesWithIt() {
        val seed = song("new", "Kabza")
        val rnb = song("rnb", "Singer")
        val pop = song("pop", "Popstar")
        val often = song("often", "Friend")      // played around the seed in several sittings
        val sameArtist = song("kabza2", "Kabza")
        val genres = mapOf("rnb" to "R&B & soul", "pop" to "Pop", "kabza2" to dance)
        val co = mapOf("new" to mapOf("rnb" to 1.0, "pop" to 0.5, "often" to 3.0))
        val pool = LocalRadio.Pool(listOf(rnb, pop, often, sameArtist), co, affinity = { 0.5 }, genreOf = { genres[it] })
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 15)
        assertEquals(setOf("often", "kabza2"), LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song.id }.toSet())
    }

    /** With no genre to go by, an artist YouTube's own radio for the seed plays is close enough; a known other genre isn't. */
    @Test
    fun artistsOnYouTubesRadioCountWhenNothingElseIsKnown() {
        val seed = song("new", "Kabza")
        val onRadio = song("mapho", "DJ Maphorisa")       // no genre known, artist on YouTube's radio
        val onRadioButPop = song("mapho-pop", "Tyla")      // artist on the radio, but this song is known to be... pop
        val stranger = song("stranger", "Somebody")        // no genre, nothing to do with it
        val genres = mapOf("new" to dance, "mapho-pop" to "Pop")
        val pool = LocalRadio.Pool(listOf(onRadio, onRadioButPop, stranger), emptyMap(), affinity = { 0.5 }, genreOf = { genres[it] })
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 15)
        val radio = listOf(song("yt1", "DJ Maphorisa"), song("yt2", "Tyla"), song("yt3", "Uncle Waffles"))
        assertEquals(listOf("mapho"), LocalRadio.besideYouTube(picks, onlyYours = false, radio = radio).map { it.song.id })
        assertEquals("without the radio, nothing is known of them", emptyList<String>(), LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song.id })
    }

    /**
     * Phonk and amapiano are both "Dance & electronic" to Vinz; when both tempos are measured, a far one tells them apart
     * (a tempo read at half or double still counts as near).
     */
    @Test
    fun sameGenreAtAFarTempoIsNotClose() {
        val seed = song("askije", "Kabza")
        val phonk = song("phonk", "Phonker")
        val house = song("house", "Black Coffee")
        val halfRead = song("half", "Uncle Waffles")
        val unmeasured = song("unmeasured", "DJ Maphorisa")
        val bpm = mapOf("askije" to 113.0, "phonk" to 145.0, "house" to 122.0, "half" to 56.5)
        val pool = LocalRadio.Pool(
            listOf(phonk, house, halfRead, unmeasured), emptyMap(), affinity = { 0.5 }, genreOf = { dance }, bpmOf = { bpm[it] },
        )
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 15)
        assertEquals(setOf("house", "half", "unmeasured"), LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song.id }.toSet())
        assertEquals(LocalRadio.Fit.APART, picks.first { it.song.id == "phonk" }.fit)
    }

    /**
     * What doesn't change: "Your music" (and offline) still takes everything; how picks are scored and ordered (related or
     * not) is as before; and YouTube's half of a mixed batch keeps all its songs in its own order, an artist never twice
     * running and at most [com.vinz.music.data.foryou.RadioTopUp.PER_ARTIST] times ([com.vinz.music.data.foryou.Variety.arrange]).
     */
    @Test
    fun yourMusicOnlyAndTheCatalogHalfAreUnchanged() {
        val seed = song("askije", "Kabza")
        val rnb = song("rnb", "Singer")
        val amapiano = song("amapiano", "DJ Maphorisa")
        val genres = mapOf("askije" to dance, "rnb" to "R&B & soul", "amapiano" to dance)
        val pool = LocalRadio.Pool(listOf(rnb, amapiano), mapOf("askije" to mapOf("rnb" to 1.0)), affinity = { 0.5 }, genreOf = { genres[it] })
        val picks = LocalRadio.pick(listOf(seed), pool, emptySet(), 15)
        assertEquals("Your music: everything", picks, LocalRadio.besideYouTube(picks, onlyYours = true, radio = listOf(song("yt", "X"))))
        // Scoring as before: played next to the seed still ranks it first, and still counts as related for mixes.
        assertEquals(listOf("rnb", "amapiano"), picks.map { it.song.id })
        assertTrue(picks.all { it.related })

        val youtube = (1..20).map { song("yt$it", "Artist ${it % 6}") }
        val mine = LocalRadio.besideYouTube(picks, onlyYours = false).map { it.song }
        val rules = com.vinz.music.data.foryou.RadioTopUp.Rules(emptySet(), emptySet(), { false }, { true })
        val batch = com.vinz.music.data.foryou.RadioTopUp.batch(mine, youtube, songRadio = false, rules = rules)
        val ytIds = youtube.map { it.id }.toSet()
        val theirs = batch.filter { it.id in ytIds }.map { it.id }
        assertEquals("YouTube's songs in its own order", youtube.map { it.id }.filter { it in theirs }, theirs)
        assertEquals(listOf("amapiano"), batch.filter { it.id !in ytIds }.map { it.id })
        for (i in 1 until batch.size) assertTrue("same artist twice running at $i", batch[i].primaryArtist != batch[i - 1].primaryArtist)
        assertTrue(batch.groupingBy { it.primaryArtist }.eachCount().values.all { it <= com.vinz.music.data.foryou.RadioTopUp.PER_ARTIST })
    }
}
