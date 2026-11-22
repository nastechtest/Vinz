package com.vinz.music

import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.PlaylistItem
import com.vinz.music.data.model.Shelf
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.model.quickPicks
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.QuickPicksSource
import com.vinz.music.ui.screens.quickPicksToShow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.8.2's Home round: #349. */
class IssueFixes182Test {
    private fun songs(vararg ids: String) = ids.map { SongItem(Song(it, "Song $it", listOf(ArtistRef("A")))) }

    /**
     * #349: YouTube's Quick picks are known by what they are (song rows that each start the song's radio, 1.8.7; by
     * their shape before), however the account's language spells them.
     */
    @Test fun quickPicksAreFoundInAnySpelling() {
        val cards = Shelf("Listas", items = listOf(PlaylistItem("p", "P", "", null)))
        for (title in listOf("Selecciones rápidas", "Selección rápida", "Selecciones Rapidas", "Schnellauswahl")) {
            val picks = Shelf(title, items = songs("a", "b"), rows = true, startsRadios = true)
            assertEquals(title, listOf(cards, picks).quickPicks()?.title)
        }
        // Your own recent plays, in a carousel of rows as well, aren't them.
        val mine = Shelf("Vuelve a escucharlo", strapline = "Sam", items = songs("c"), rows = true, startsRadios = true)
        val picks = Shelf("Selección rápida", items = songs("a"), rows = true, startsRadios = true)
        assertEquals(picks.title, listOf(mine, picks).quickPicks("Sam")?.title)
    }

    /** #349: "Quick picks from: YouTube Music": no set of Vinz's in the way, and they come in once. */
    @Test fun youTubesPicksShowWhenChosen() {
        val yt = listOf(Song("y1", "Y1"), Song("y2", "Y2"))
        val fresh = listOf(Song("f1", "F1"))
        // Nothing of Vinz's is passed in that choice: nothing shows until the fresh feed is in, then YouTube's.
        assertEquals(emptyList<Song>(), quickPicksToShow(null, emptyList(), yt, ready = false))
        assertEquals(yt, quickPicksToShow(null, emptyList(), yt, ready = true))
        assertEquals(yt, quickPicksToShow(yt, emptyList(), fresh, ready = true))
    }

    /** #349: the choice starts as Vinz's own, as it's always been. */
    @Test fun vinzsPicksStayTheDefault() {
        assertEquals(QuickPicksSource.VINZ, AppSettings().behavior.quickPicksSource)
    }
}
