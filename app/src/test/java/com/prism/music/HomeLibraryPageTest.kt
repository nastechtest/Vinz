package com.vinz.music

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.data.prefs.LibraryColumns
import com.vinz.music.data.prefs.LibraryTile
import com.vinz.music.data.prefs.LibraryView
import com.vinz.music.data.prefs.RecentShows
import com.vinz.music.data.prefs.ReplayGraph
import com.vinz.music.data.prefs.SearchSection
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.ReplayCardStyle
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.TopButtons
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.lookKeys
import com.vinz.music.ui.screens.settings.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Home & Library rebuilt with the kit (plan §2.3, the ledger's Home & Library table): six groups of
 * the five rows within the budgets, a control for every stored setting the ledger gives the page (worked here, and the
 * store follows), the rows that are new on it (Arrange Search, View, Full-width pictures with its three choices, the
 * button to find the playing song, "A song and its video as one"), Reset this page, and the page drawn in light, dark,
 * Performance mode and at large text. The window is tall so that the page's lazy list holds every group at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class HomeLibraryPageTest : HomePagesHarness() {
    private fun t(id: Int) = tr(id)

    private val customizeHome = t(R.string.customize_home)
    private val continueLine = t(R.string.continue_line)
    private val recentShows = t(R.string.recently_played_shows)
    private val topButtons = t(R.string.buttons_at_the_top_of_home)
    private val keyboard = t(R.string.open_the_keyboard)
    private val arrangeSearch = t(R.string.arrange_search)
    private val look = t(R.string.hl_card_style)
    private val graph = t(R.string.replay_card_graph)
    private val tiles = t(R.string.library_tiles)
    private val view = t(R.string.hl_view)
    private val columns = t(R.string.covers_across)
    private val details = t(R.string.details_under_each_one)
    private val opensOn = t(R.string.open_library_on)
    private val songAndVideo = t(R.string.a_song_and_its_video_as)
    private val show = t(R.string.hl_show_in_lists)
    private val artistShow = t(R.string.hl_show_on_artist_pages)
    private val fullWidth = t(R.string.hl_full_width_pictures)
    private val locate = t(R.string.hl_locate_button)

    private val genres = t(R.string.hl_genres)
    private val albumNames = t(R.string.hl_album_names)
    private val artistYou = t(R.string.artist_you_title)
    private val artistEssentials = t(R.string.artist_essentials_title)
    private val artistBio = t(R.string.artist_bio_title)

    private val groups = listOf(R.string.home, R.string.search, R.string.replay_card, R.string.library, R.string.hl_lists, R.string.artist_pages).map { t(it) }

    @Test fun sixGroupsWithinTheBudgets() {
        showAt(PAGE)
        assertBudgets("defaults")
        // The ledger's layout with a fresh install's settings.
        assertEquals(groups, groupTitles())
        assertEquals(listOf(4, 2, 2, 6, 3, 1), groupSizes())
        assertEquals("nothing differs from how Vinz comes", 0, changedRows())
        // No group is named as the page; the jump chips are there (four or more groups).
        assertTrue(groupTitles().none { it == SettingsPageId.HOME_LIBRARY.title })
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Each row is the type the ledger and the brief give it, and says its state.
        assertEquals(SettingsTags.ROW_LINK, typeOf(customizeHome))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(continueLine))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(recentShows))
        assertEquals(RecentShows.SONGS.label, valueOf(recentShows))
        assertEquals(SettingsTags.ROW_PICK, typeOf(topButtons))
        assertEquals(TopButtons.BOTH.label, valueOf(topButtons))
        assertEquals(SettingsTags.ROW_LINK, typeOf(arrangeSearch))
        assertEquals(SettingsTags.ROW_PICK, typeOf(look))
        assertEquals(ReplayCardStyle.WASH.label, valueOf(look))
        assertEquals(SettingsTags.ROW_LINK, typeOf(tiles))
        assertEquals(SettingsTags.ROW_PICK, typeOf(view))
        assertEquals(LibraryView.LIST.label, valueOf(view))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(fullWidth))
        assertEquals(FullWidthPictures.PLAYLISTS.label, valueOf(fullWidth))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(locate))
        // The two rows of show-chips: the lists', then the artist pages'.
        // (What artist pages show is off as Vinz comes.)
        assertEquals(listOf("$genres · $albumNames"), valuesOf(show))
        assertEquals(listOf(t(R.string.none)), valuesOf(artistShow))
    }

    /** Principle 8: every stored setting the ledger gives Home & Library has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        showAt(PAGE)
        val d = AppSettings()

        // home_continue
        tap(continueLine)
        until("home_continue") { it.home.continueLine != d.home.continueLine }
        // recent_shows
        choose(recentShows, RecentShows.COLLECTIONS.label)
        until("recent_shows") { it.behavior.recentShows == RecentShows.COLLECTIONS }
        // home_podcasts_button, home_customize_top_button: one pick of four.
        pick(topButtons, TopButtons.PODCASTS.label)
        until("home_customize_top_button") { it.home.podcastsButton && !it.home.customizeTopButton }
        pick(topButtons, TopButtons.NONE.label)
        until("home_podcasts_button") { !it.home.podcastsButton && !it.home.customizeTopButton }
        assertEquals(TopButtons.NONE.label, valueOf(topButtons))

        // search_keyboard
        tap(keyboard)
        until("search_keyboard") { it.searchKeyboard != d.searchKeyboard }
        // search_sections, search_chips: Search's own "Arrange Search" sheet, opened from here.
        tap(arrangeSearch)
        compose.onNode(hasContentDescription(SearchSection.MOODS.label) and isToggleable()).performClick()
        until("search_sections") { SearchSection.MOODS !in it.searchSections && it.searchSections.size == SearchSection.entries.size - 1 }
        // Its chips are in the same sheet, each with its grip (their order is dragged, which isn't done here).
        assertTrue(compose.onAllNodes(hasTestTag("chip:PHONE")).fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasContentDescription(SearchSection.MOODS.label) and isToggleable()).performClick()
        until("search_sections back") { it.searchSections.toSet() == SearchSection.entries.toSet() }
    }

    @Test fun replayCardAndLibraryStoreWhatIsPicked() {
        showAt(PAGE)
        val d = AppSettings()
        // replay_card: its looks in a sheet, each drawn as the card.
        tap(look)
        assertEquals(ReplayCardStyle.entries.size, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        assertEquals("a sheet swiped away changes nothing", d.replayCard, stored.replayCard)
        pick(look, ReplayCardStyle.RING.label)
        until("replay_card") { it.replayCard == ReplayCardStyle.RING.name }
        assertEquals(ReplayCardStyle.RING.label, valueOf(look))
        // replay_card_graph
        choose(graph, ReplayGraph.DAYS.label)
        until("replay_card_graph") { it.view.replayGraph == ReplayGraph.DAYS }

        // library_views: one sheet with the three tabs (library_view stays what a tab without its own falls back on).
        tap(view)
        assertEquals(1, sheets())
        choose(t(R.string.albums_as), LibraryView.GRID.label)
        until("library_views") { it.library.views["ALBUMS"] == LibraryView.GRID }
        choose(t(R.string.artists_as), LibraryView.GRID.label)
        choose(t(R.string.playlists_as), LibraryView.GRID.label)
        until("library_views") { v -> listOf("PLAYLISTS", "ALBUMS", "ARTISTS").all { v.library.views[it] == LibraryView.GRID } }
        choose(t(R.string.playlists_as), LibraryView.LIST.label)
        until("library_views") { it.library.views["PLAYLISTS"] == LibraryView.LIST }
        assertEquals("the sheet stays for the other tabs", 1, sheets())
        swipeSheetAway()
        assertEquals(d.library.view, stored.library.view)
        val mixed = valueOf(view)
        assertTrue(mixed, LibraryView.GRID.label in mixed && LibraryView.LIST.label in mixed && t(R.string.playlists) in mixed)
        // library_columns, library_subtitles, library_start_tab, merge_liked_videos
        choose(columns, LibraryColumns.THREE.label)
        until("library_columns") { it.library.columns == LibraryColumns.THREE }
        tap(details)
        until("library_subtitles") { it.library.subtitles != d.library.subtitles }
        pick(opensOn, t(R.string.albums))
        until("library_start_tab") { it.library.startTab == "ALBUMS" }
        assertEquals(t(R.string.albums), valueOf(opensOn))
        tap(songAndVideo)
        until("merge_liked_videos") { it.mergeLikedVideos != d.mergeLikedVideos }
        // library_tiles, library_playlists_first: the sheet Library's Edit opens.
        tap(tiles)
        compose.onNode(hasContentDescription(LibraryTile.HISTORY.label) and isToggleable()).performClick()
        until("library_tiles") { LibraryTile.HISTORY in it.library.tiles }
        compose.onNode(hasText(t(R.string.playlists_first))).performClick()
        until("library_playlists_first") { it.library.playlistsFirst }
    }

    @Test fun listsAndArtistPagesStoreWhatIsPicked() {
        showAt(PAGE)
        val d = AppSettings()
        // playlist_genres, downloads_albums: two chips of one row, each its own stored switch.
        chip(genres)
        until("playlist_genres") { !it.behavior.playlistGenres && it.view.downloadsAlbums }
        chip(albumNames)
        until("downloads_albums") { !it.behavior.playlistGenres && !it.view.downloadsAlbums }
        // With nothing ticked a row of chips still says so.
        assertEquals(t(R.string.none), valuesOf(show).first())
        chip(genres)
        until("playlist_genres") { it.behavior.playlistGenres && !it.view.downloadsAlbums }
        assertEquals(genres, valuesOf(show).first())
        // playlist_hero_cover, album_hero_cover: Off / Playlists / Albums and playlists (D55).
        choose(fullWidth, FullWidthPictures.ALBUMS_AND_PLAYLISTS.label)
        until("album_hero_cover") { it.playlistHeroCover && it.view.albumHeroCover && it.fullWidthPictures == FullWidthPictures.ALBUMS_AND_PLAYLISTS }
        choose(fullWidth, FullWidthPictures.OFF.label)
        until("playlist_hero_cover") { !it.playlistHeroCover && !it.view.albumHeroCover }
        // locate_playing (new in 1.8.4, D54): off until switched on.
        assertFalse(d.behavior.locatePlaying)
        tap(locate)
        until("locate_playing") { it.behavior.locatePlaying }
        // artist_you, artist_essentials, artist_bio: three chips of one row.
        chip(artistYou)
        until("artist_you") { it.behavior.artistYou != d.behavior.artistYou && it.behavior.artistEssentials == d.behavior.artistEssentials && it.behavior.artistBio == d.behavior.artistBio }
        chip(artistBio)
        until("artist_bio") { it.behavior.artistBio != d.behavior.artistBio && it.behavior.artistEssentials == d.behavior.artistEssentials }
        chip(artistEssentials)
        until("artist_essentials") { it.behavior.artistEssentials != d.behavior.artistEssentials }
        assertEquals("$artistYou · $artistEssentials · $artistBio", valuesOf(artistShow).last())
        assertBudgets("chips off")
    }

    /** Every row changed: each says so, the root's line follows, and Reset this page lists them and puts them back. */
    @Test fun resetThisPagePutsEveryRowBack() {
        val p = container.settings
        val d = AppSettings()
        p.setHomeContinue(!d.home.continueLine); p.setRecentShows(RecentShows.COLLECTIONS); p.setHomePodcastsButton(false)
        p.setSearchKeyboard(!d.searchKeyboard); p.setSearchSections(d.searchSections - SearchSection.MOODS)
        p.setReplayCard(ReplayCardStyle.RING.name); p.setReplayGraph(ReplayGraph.DAYS)
        p.setLibraryTiles(d.library.tiles + LibraryTile.HISTORY); p.setLibraryTabView("ALBUMS", LibraryView.GRID); p.setLibraryColumns(LibraryColumns.THREE)
        p.setLibrarySubtitles(!d.library.subtitles); p.setLibraryStartTab("ALBUMS"); p.setMergeLikedVideos(!d.mergeLikedVideos)
        p.setPlaylistGenres(!d.behavior.playlistGenres); p.setFullWidthPictures(FullWidthPictures.ALBUMS_AND_PLAYLISTS); p.setLocatePlaying(true)
        p.setArtistBio(!d.behavior.artistBio)
        until("everything changed") { it.behavior.artistBio != d.behavior.artistBio && it.home.continueLine != d.home.continueLine }
        showAt(PAGE)
        assertBudgets("everything changed")
        assertEquals(listOf(4, 2, 2, 6, 3, 1), groupSizes())
        // Every row but the link to Customize Home (a screen of its own) differs, and says so.
        assertEquals(17, changedRows())
        // The root's line is live and short.
        val line = SettingsPageId.HOME_LIBRARY.value(stored)
        assertTrue(line, t(R.string.hl_view_mixed) in line && line.length <= 30)
        assertTrue(SettingsPageId.HOME_LIBRARY.value(d), LibraryView.LIST.label in SettingsPageId.HOME_LIBRARY.value(d))

        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 17, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("$fullWidth: ${FullWidthPictures.ALBUMS_AND_PLAYLISTS.label} → ${FullWidthPictures.PLAYLISTS.label}") })
        until("the reset") { it.behavior == d.behavior && it.view == d.view }
        until("the reset") {
            it.home.continueLine == d.home.continueLine && it.home.podcastsButton == d.home.podcastsButton && it.searchKeyboard == d.searchKeyboard &&
                it.searchSections == d.searchSections && it.replayCard == d.replayCard && it.library.tiles == d.library.tiles &&
                it.library.views == d.library.views && it.library.columns == d.library.columns && it.library.subtitles == d.library.subtitles &&
                it.library.startTab == d.library.startTab && it.mergeLikedVideos == d.mergeLikedVideos && it.playlistHeroCover == d.playlistHeroCover
        }
        assertEquals(0, changedRows())
    }

    /** The first row of Home opens Customize Home, a screen of its own (plan §2.6). */
    @Test fun customizeHomeIsALinkOnHome() {
        showAt(PAGE)
        tap(customizeHome)
        compose.waitUntil(5_000) { route() == Routes.CATALOGUE }
        compose.waitForIdle()
        assertTrue("Customize Home is showing", compose.onAllNodes(hasText(t(R.string.top_of_home))).fetchSemanticsNodes().isNotEmpty())
    }

    /** Search finds every row in the group it is drawn in; old links land; the look keys are the page's look settings. */
    @Test fun searchLinesAndOldLinks() {
        showAt(PAGE)
        val drawn = groupTitles().toSet()
        val mine = SettingsIndex.entries.filter { it.page == SettingsPageId.HOME_LIBRARY && it.route == null }
        // ("Hide this section" is a row of Home's own "Customize this" sheet only, and no longer a line of this page.)
        val lost = mine.filter { it.group.isNotBlank() && it.group !in drawn }
        assertTrue(mine.none { it.title == t(R.string.hide_this_section) })
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        fun at(title: String) = mine.single { it.title == title }.group
        assertEquals(t(R.string.search), at(arrangeSearch))
        assertEquals(t(R.string.library), at(view))
        assertEquals(t(R.string.library), at(songAndVideo))
        assertEquals(t(R.string.hl_lists), at(fullWidth))
        assertEquals(t(R.string.hl_lists), at(locate))
        assertEquals(t(R.string.hl_lists), at(t(R.string.genres_on_playlists)))
        assertEquals(t(R.string.artist_pages), at(artistBio))
        // The old names of what was merged or renamed still find the row.
        assertTrue(SettingsIndex.search("full-width playlist pictures").any { it.title == fullWidth })
        assertTrue(SettingsIndex.search("albums as").any { it.title == view })
        assertTrue(SettingsIndex.search("go to the playing song").any { it.title == locate })
        // The three rows that live on Customize Home only (plan §2.6) are found there, not here.
        for (id in listOf(R.string.quick_picks_from, R.string.add_new_sections_automatically, R.string.customize_button_on_home)) {
            val e = SettingsIndex.entries.single { it.title == t(id) }
            assertEquals(t(id), Routes.CATALOGUE, e.route?.substringBefore('?'))
            assertEquals(t(R.string.options), e.group)
            assertFalse("$id is drawn on Home & Library", hasRow(t(id)))
        }
        // Old links: the page's old key, with and without a group.
        assertEquals(SettingsTarget(SettingsPageId.HOME_LIBRARY), SettingsMap.resolve("home"))
        for (g in listOf(R.string.home, R.string.search, R.string.replay_card, R.string.library, R.string.artist_pages)) {
            assertEquals(SettingsTarget(SettingsPageId.HOME_LIBRARY, t(g)), SettingsMap.resolve("home", t(g)))
        }
        // D55: both switches behind Full-width pictures are the page's to reset.
        val keys = SettingsPageId.HOME_LIBRARY.lookKeys
        assertTrue(keys.toString(), "playlist_hero_cover" in keys && "album_hero_cover" in keys && "library_views" in keys && "replay_card" in keys)
        // The ⓘ's words, as the owner gave them.
        assertEquals(
            "In a long list, a small button above the mini player takes you to the song that's playing. The list's menu always has 'Go to the playing song'.",
            t(R.string.hl_locate_button_info),
        )
    }

    @Test fun atLargeTextNothingIsCutOff() {
        showAt(PAGE, fontScale = 1.5f)
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        showAt(PAGE, glass = glass, over = over)
        val file = save("home-library", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnLight() = drawn("home-library-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnDark() = drawn("home-library-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun drawnPerformance() = drawn("home-library-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** As a phone shows it: the top of the page, with the jump chips. */
    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") fun drawnAsAPhoneShowsIt() {
        showAt(PAGE, glass = true) { it.copy(themeMode = ThemeMode.DARK) }
        assertTrue(save("home-library", "home-library-phone").length() > 10_000)
    }
}
