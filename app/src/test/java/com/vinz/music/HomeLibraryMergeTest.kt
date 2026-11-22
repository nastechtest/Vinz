package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.data.prefs.LibraryView
import com.vinz.music.ui.screens.HomeChoices
import com.vinz.music.ui.screens.TopButtons
import com.vinz.music.ui.screens.settings.ArtistShow
import com.vinz.music.ui.screens.settings.LibraryViews
import com.vinz.music.ui.screens.settings.ListsShow
import com.vinz.music.ui.screens.settings.artistShowToMerged
import com.vinz.music.ui.screens.settings.artistShowToStored
import com.vinz.music.ui.screens.settings.fullWidthToStored
import com.vinz.music.ui.screens.settings.libraryViewsToMerged
import com.vinz.music.ui.screens.settings.libraryViewsToStored
import com.vinz.music.ui.screens.settings.listsShowToMerged
import com.vinz.music.ui.screens.settings.listsShowToStored
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.8.4, plan §2.5: Home & Library's rows that are one control over several stored settings keep every combination
 * that could be stored before. Each stored combination is read into the control and written back unchanged.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class HomeLibraryMergeTest {
    private val both = listOf(false, true)

    /** Lists → Show: genres on playlists × album names in Downloads, 4 combinations. */
    @Test fun listsShowKeepsBothSwitches() {
        var n = 0
        for (genres in both) for (albumNames in both) {
            val merged = listsShowToMerged(genres, albumNames)
            assertEquals("$genres, $albumNames", genres to albumNames, listsShowToStored(merged))
            assertEquals(genres, ListsShow.GENRES in merged)
            assertEquals(albumNames, ListsShow.ALBUM_NAMES in merged)
            n++
        }
        assertEquals(4, n)
        // And every pick of the chips is a stored combination of its own.
        assertEquals(4, both.flatMap { g -> both.map { a -> listsShowToMerged(g, a) } }.toSet().size)
    }

    /** Artist pages → Show: three switches, 8 combinations. */
    @Test fun artistShowKeepsAllThreeSwitches() {
        val seen = HashSet<Set<ArtistShow>>()
        for (you in both) for (essentials in both) for (bio in both) {
            val merged = artistShowToMerged(you, essentials, bio)
            assertEquals("$you, $essentials, $bio", Triple(you, essentials, bio), artistShowToStored(merged))
            seen += merged
        }
        assertEquals(8, seen.size)
    }

    /**
     * View: each of the three tabs stored as a grid, a list or not at all (27 maps), over either fallback (54). A tab
     * reads as its own view, or the fallback's; writing what was read back reads the same; and a pick for one tab (the
     * sheet writes one tab) leaves the other two as they read.
     */
    @Test fun viewKeepsEachTabsOwn() {
        val each = listOf(null, LibraryView.GRID, LibraryView.LIST)
        var n = 0
        for (fallback in LibraryView.entries) for (p in each) for (a in each) for (r in each) {
            val stored = listOfNotNull(p?.let { LibraryViews.PLAYLISTS to it }, a?.let { LibraryViews.ALBUMS to it }, r?.let { LibraryViews.ARTISTS to it }).toMap()
            val merged = libraryViewsToMerged(stored, fallback)
            assertEquals(LibraryViews(p ?: fallback, a ?: fallback, r ?: fallback), merged)
            for (other in LibraryView.entries) assertEquals("$stored over $fallback", merged, libraryViewsToMerged(libraryViewsToStored(merged), other))
            for (pick in LibraryView.entries) {
                assertEquals(merged.copy(albums = pick), libraryViewsToMerged(stored + (LibraryViews.ALBUMS to pick), fallback))
            }
            n++
        }
        assertEquals(54, n)
        // As Vinz comes: no tab has its own, all three are the fallback.
        val d = AppSettings()
        assertEquals(LibraryViews(d.library.view, d.library.view, d.library.view), libraryViewsToMerged(d.library.views, d.library.view))
    }

    /**
     * Full-width pictures (D55): the switch of before 1.8.4 (on or off, albums never) reads as Playlists or Off and is
     * written back unchanged; each of the three choices is stored and read back as itself. The fourth stored pair
     * (playlists off, albums on) can't be picked and reads as Off: an album never has it without playlists.
     */
    @Test fun fullWidthPicturesKeepsTheOldSwitch() {
        for (old in both) {
            val merged = FullWidthPictures.of(playlists = old, albums = false)
            assertEquals(if (old) FullWidthPictures.PLAYLISTS else FullWidthPictures.OFF, merged)
            assertEquals(old to false, fullWidthToStored(merged))
        }
        for (v in FullWidthPictures.entries) {
            val (playlists, albums) = fullWidthToStored(v)
            assertEquals(v, FullWidthPictures.of(playlists, albums))
        }
        assertEquals(FullWidthPictures.OFF, FullWidthPictures.of(playlists = false, albums = true))
        assertEquals(true to true, fullWidthToStored(FullWidthPictures.ALBUMS_AND_PLAYLISTS))
        // The model reads the two stored switches the same way.
        val d = AppSettings()
        assertEquals(FullWidthPictures.of(d.playlistHeroCover, d.view.albumHeroCover), d.fullWidthPictures)
    }

    /** Buttons at the top of Home (one choice since 1.8.0, a pick sheet now): 4 combinations. */
    @Test fun topButtonsKeepBothSwitches() {
        val d = AppSettings()
        for (podcasts in both) for (customize in both) {
            val merged = HomeChoices.topButtons(d.copy(home = d.home.copy(podcastsButton = podcasts, customizeTopButton = customize)))
            assertEquals(podcasts to customize, HomeChoices.topButtonsValues(merged))
        }
        assertEquals(4, TopButtons.entries.size)
    }
}
