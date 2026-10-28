package com.prism.music

import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {
    private val ep = AlbumItem("MPREb_ep", "A Day", "EP • Polar Colors • 2021", null)
    private val adtr = ArtistItem("UCadtr", "A Day to Remember", "Artist • 2.1M monthly audience", null)
    private val song = SongItem(Song("vid1", "A Day", listOf(ArtistRef("Someone Else"))))
    private val results = listOf(
        SearchFilter.SONGS to listOf(song),
        SearchFilter.ALBUMS to listOf(ep),
        SearchFilter.ARTISTS to listOf(adtr),
    )

    private val fan = SearchRank.Taste(listOf(SearchRank.KnownArtist("UCadtr", "A Day to Remember", null, 0.6)), emptyMap())

    @Test fun exactMatchWinsWithoutTaste() {
        assertEquals("MPREb_ep", SearchRank.rank("A Day", results).top?.id)
    }

    @Test fun abbreviationsFindTheArtist() {
        // "adtr": a playlist and a single literally called ADTR used to beat A Day to Remember.
        val r = SearchRank.rank("adtr", listOf(
            SearchFilter.PLAYLISTS to listOf(com.prism.music.data.model.PlaylistItem("PLx", "ADTR", "Playlist • someone", null)),
            SearchFilter.ALBUMS to listOf(AlbumItem("MPREb_x", "ADTR", "Single • Nobody • 2024", null)),
            SearchFilter.ARTISTS to listOf(adtr),
        ))
        assertEquals("UCadtr", r.top?.id)
    }

    @Test fun youTubesTopCardBeatsALiteralNearMiss() {
        // "rhcp": YouTube's card is the band; an unknown artist's single titled "RHCP" mustn't take the top.
        val band = ArtistItem("UCrhcp", "Red Hot Chili Peppers", "Artist • 40.8M monthly audience", null)
        val single = AlbumItem("MPREb_s", "RHCP", "Single • 100% Studio • 2024", null)
        assertEquals("UCrhcp", SearchRank.rank("rhcp", listOf(SearchFilter.ALBUMS to listOf(single)), card = band).top?.id)
        // A typo the per-kind searches miss entirely.
        val swift = ArtistItem("UCts", "Taylor Swift", "Artist • 440M monthly audience", null)
        assertEquals("UCts", SearchRank.rank("taylor swfit", listOf(SearchFilter.SONGS to listOf(song)), card = swift).top?.id)
    }

    @Test fun aKnockOffCardDoesntWin() {
        // Signed out, YouTube's card for "blinding lights" can be a reggae flip; the real song is in the Songs results.
        val flip = SongItem(Song("flip", "Blinding Lights (Reggae Flip)", listOf(ArtistRef("FlipVersion"))))
        val real = SongItem(Song("real", "Blinding Lights", listOf(ArtistRef("The Weeknd"))))
        val r = SearchRank.rank("blinding lights", listOf(SearchFilter.SONGS to listOf(flip, real)), card = flip)
        assertEquals("real", r.top?.id)
    }

    @Test fun aVideoCardGivesWayToItsSong() {
        val video = SongItem(Song("vid", "HUMBLE.", listOf(ArtistRef("Kendrick Lamar")), isVideo = true))
        val track = SongItem(Song("trk", "HUMBLE.", listOf(ArtistRef("Kendrick Lamar"))))
        val r = SearchRank.rank("humble kendrick", listOf(SearchFilter.SONGS to listOf(track), SearchFilter.VIDEOS to listOf(video)), card = video)
        assertEquals("trk", r.top?.id)
    }

    @Test fun anArtistTheListenerPlaysComesFirst() {
        assertEquals("UCadtr", SearchRank.rank("A Day", results, fan).top?.id)
    }

    @Test fun aKnownArtistMissingFromResultsIsAdded() {
        val r = SearchRank.rank("a day", results.filter { it.first != SearchFilter.ARTISTS }, fan)
        assertEquals("UCadtr", r.top?.id)
    }

    @Test fun partialWordsDontBorrowTaste() {
        // "halo" is a prefix of Halocene, not a word of it: the exact song still wins.
        val halo = SongItem(Song("halo", "Halo", listOf(ArtistRef("Beyoncé"))))
        val halocene = ArtistItem("UChalocene", "Halocene", "Artist • 300K monthly audience", null)
        val taste = SearchRank.Taste(listOf(SearchRank.KnownArtist("UChalocene", "Halocene", null, 0.6)), emptyMap())
        val r = SearchRank.rank("halo", listOf(SearchFilter.SONGS to listOf(halo), SearchFilter.ARTISTS to listOf(halocene)), taste)
        assertEquals("halo", r.top?.id)
        assertTrue(SearchRank.knownArtists("halo", taste).isEmpty())
    }

    @Test fun liveStreamsAlwaysGetTheLastRowButNotTheTop() {
        val stream = SongItem(Song("lofi", "lofi hip hop radio", listOf(ArtistRef("Lofi Girl")), isVideo = true, isLive = true))
        val r = SearchRank.rank("A Day", results + (SearchFilter.LIVE to listOf(stream)))
        assertEquals("MPREb_ep", r.top?.id)
        assertEquals(SearchFilter.LIVE, r.sections.last().first)
        assertTrue(r.sections.size > 1)
        // With nothing else found, a stream can lead.
        assertEquals("lofi", SearchRank.rank("lofi", listOf(SearchFilter.LIVE to listOf(stream))).top?.id)
    }

    @Test fun aTitleTrackGivesWayToItsAlbum() {
        val track = Song("ah", "After Hours", listOf(ArtistRef("The Weeknd")), album = com.prism.music.data.model.AlbumRef("After Hours", "MPREb_ah"))
        assertTrue(SearchRank.namesItsAlbum("after hours", track))
        assertTrue(!SearchRank.namesItsAlbum("blinding lights", track.copy(title = "Blinding Lights")))
        val r = SearchRank.rank("after hours", listOf(SearchFilter.SONGS to listOf(SongItem(track))))
        val album = AlbumItem("MPREb_ah", "After Hours", "Album • The Weeknd • 2020", null)
        val moved = SearchRank.withAlbumOnTop(r, album)
        assertEquals("MPREb_ah", moved.top?.id)
        assertEquals("ah", moved.sections.first { it.first == SearchFilter.SONGS }.second.first().id)
    }
}
