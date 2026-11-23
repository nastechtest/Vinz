package com.vinz.music.ui.screens.settings

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FullWidthPictures
import com.vinz.music.data.prefs.HomeHeader
import com.vinz.music.data.prefs.LibraryColumns
import com.vinz.music.data.prefs.LibraryTile
import com.vinz.music.data.prefs.LibraryView
import com.vinz.music.data.prefs.RecentShows
import com.vinz.music.data.prefs.ReplayGraph
import com.vinz.music.data.prefs.SearchSection
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.ArrangeSearchSheet
import com.vinz.music.ui.screens.HomeChoices
import com.vinz.music.ui.screens.LibraryTilesSheet
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.ReplayCardPreview
import com.vinz.music.ui.screens.ReplayCardStyle
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.screens.TopButtons
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons

private val page = SettingsPageId.HOME_LIBRARY

/** The page's six groups, by the names they're drawn and found under. */
internal object HlGroups {
    val home get() = tr(R.string.home)
    val search get() = tr(R.string.search)
    val replayCard get() = tr(R.string.replay_card)
    val library get() = tr(R.string.library)
    val lists get() = tr(R.string.hl_lists)
    val artistPages get() = tr(R.string.artist_pages)
}

/** Library's tabs, by the name each is stored under ("Open Library on"). */
private val libraryTabs: List<Pair<String, String>>
    get() = listOf(
        "PLAYLISTS" to tr(R.string.playlists), "SONGS" to tr(R.string.songs), "ALBUMS" to tr(R.string.albums), "ARTISTS" to tr(R.string.artists),
        "HISTORY" to tr(R.string.history), "DOWNLOADS" to tr(R.string.downloads),
    )

/**
 * Settings → Home & Library (1.8.4's map, plan §2.3), built with the kit: Home's own rows under a link to Customize
 * Home (a screen of its own, plan §2.6), Search, the Replay card, Library, the song lists, and what artist pages show.
 * This file is the only place for the page: its composable, its lines in Settings' search ([homeLibraryIndex], which
 * also holds Customize Home's), its "Reset this page" keys ([homeLibraryLookKeys]), its line on the root
 * ([homeLibraryValue]) and the old group names it answers to ([homeLibraryOldGroups]).
 *
 * Several rows are one face over stored settings that stay as they are (plan §2.5): the buttons at the top of Home
 * ([HomeChoices]), View ([libraryViewsToMerged]), the two rows of show-chips ([listsShowToMerged],
 * [artistShowToMerged]) and Full-width pictures ([FullWidthPictures]). "Reset this page" puts each row back through
 * its own setter, as Playback does: not all of them are part of a Look.
 */
@Composable
fun HomeLibraryPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }

    // What differs from how Vinz comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val continueChanged = reset.add(tr(R.string.continue_line), s.home.continueLine, d.home.continueLine, onOff) { prefs.setHomeContinue(it) }
    val recentChanged = reset.add(tr(R.string.recently_played_shows), s.behavior.recentShows, d.behavior.recentShows, { it.label }) { prefs.setRecentShows(it) }
    val topButtons = HomeChoices.topButtons(s)
    val topButtonsChanged = reset.add(tr(R.string.buttons_at_the_top_of_home), topButtons, HomeChoices.topButtons(d), { it.label }) { HomeChoices.setTopButtons(prefs, it) }
    val keyboardChanged = reset.add(tr(R.string.open_the_keyboard), s.searchKeyboard, d.searchKeyboard, onOff) { prefs.setSearchKeyboard(it) }
    val arrangeDefault = d.searchSections to d.searchChips
    val arrangeChanged = reset.add(tr(R.string.arrange_search), s.searchSections to s.searchChips, arrangeDefault, asVinzComes(arrangeDefault)) { (sections, chips) ->
        prefs.setSearchSections(sections); prefs.setSearchChips(chips)
    }
    val look = replayCardStyle(s)
    val lookChanged = reset.add(tr(R.string.hl_card_style), look, replayCardStyle(d), { it.label }) { prefs.setReplayCard(it.name) }
    val graphChanged = reset.add(tr(R.string.replay_card_graph), s.view.replayGraph, d.view.replayGraph, { it.label }) { prefs.setReplayGraph(it) }
    val tilesDefault = d.library.tiles to d.library.playlistsFirst
    val tilesChanged = reset.add(tr(R.string.library_tiles), s.library.tiles to s.library.playlistsFirst, tilesDefault, asVinzComes(tilesDefault)) { (tiles, first) ->
        prefs.setLibraryTiles(tiles); prefs.setLibraryPlaylistsFirst(first)
    }
    val views = libraryViewsToMerged(s.library.views, s.library.view)
    // Back to one view for every tab: Vinz's (the tabs' own go, and what they fall back on is put back).
    val viewChanged = reset.add(tr(R.string.hl_view), views, libraryViewsToMerged(d.library.views, d.library.view), ::libraryViewsText) { prefs.setLibraryView(d.library.view) }
    val columnsChanged = reset.add(tr(R.string.covers_across), s.library.columns, d.library.columns, { it.label }) { prefs.setLibraryColumns(it) }
    val detailsChanged = reset.add(tr(R.string.details_under_each_one), s.library.subtitles, d.library.subtitles, onOff) { prefs.setLibrarySubtitles(it) }
    val tabs = libraryTabs
    // "LOCAL" (On this phone) was a tab before Local files got its own page: Playlists stands in for it.
    val startTab = s.library.startTab.takeIf { k -> tabs.any { it.first == k } } ?: "PLAYLISTS"
    val tabName: (String) -> String = { k -> tabs.firstOrNull { it.first == k }?.second ?: k }
    val startTabChanged = reset.add(tr(R.string.open_library_on), startTab, d.library.startTab, tabName) { prefs.setLibraryStartTab(it) }
    val songAndVideoChanged = reset.add(tr(R.string.a_song_and_its_video_as), s.mergeLikedVideos, d.mergeLikedVideos, onOff) { prefs.setMergeLikedVideos(it) }
    // 1.8.7.5: the two rows of show-chips no longer share the name "Show".
    val listsShowTitle = tr(R.string.hl_show_in_lists)
    val artistShowTitle = tr(R.string.hl_show_on_artist_pages)
    val listsShow = listsShowToMerged(s.behavior.playlistGenres, s.view.downloadsAlbums)
    val listsShowChanged = reset.add("$listsShowTitle (${HlGroups.lists})", listsShow, listsShowToMerged(d.behavior.playlistGenres, d.view.downloadsAlbums), { shownWords(it.map(::listsShowLabel)) }) {
        listsShowToStored(it).let { (genres, albumNames) -> prefs.setPlaylistGenres(genres); prefs.setDownloadsAlbums(albumNames) }
    }
    val fullWidthChanged = reset.add(tr(R.string.hl_full_width_pictures), s.fullWidthPictures, d.fullWidthPictures, { it.label }) { prefs.setFullWidthPictures(it) }
    val locateChanged = reset.add(tr(R.string.hl_locate_button), s.behavior.locatePlaying, d.behavior.locatePlaying, onOff) { prefs.setLocatePlaying(it) }
    val artistShow = artistShowToMerged(s.behavior.artistYou, s.behavior.artistEssentials, s.behavior.artistBio)
    val artistDefault = artistShowToMerged(d.behavior.artistYou, d.behavior.artistEssentials, d.behavior.artistBio)
    val artistShowChanged = reset.add("$artistShowTitle (${HlGroups.artistPages})", artistShow, artistDefault, { shownWords(it.map(::artistShowLabel)) }) {
        artistShowToStored(it).let { (you, essentials, bio) -> prefs.setArtistYou(you); prefs.setArtistEssentials(essentials); prefs.setArtistBio(bio) }
    }

    val offered = LibraryTile.offered(c.uploads.has.collectAsState().value)
    var arranging by rememberSaveable { mutableStateOf(false) }
    var editingTiles by rememberSaveable { mutableStateOf(false) }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(HlGroups.home) {
            // Sections, the top of Home, the shortcut tiles and Home's options: a screen of its own (plan §2.6).
            LinkRow(tr(R.string.customize_home), value = tr(R.string.sections_the_top_of_home_shortcuts), icon = VinzIcons.Customize) { nav.go(Routes.CATALOGUE) }
            SwitchRow(tr(R.string.continue_line), s.home.continueLine, info = tr(R.string.the_album_you_were_last_playing), changed = continueChanged) { prefs.setHomeContinue(it) }
            ChoiceRow(
                tr(R.string.recently_played_shows), RecentShows.entries, s.behavior.recentShows, { it.label },
                info = tr(R.string.every_song_you_played_or_the), changed = recentChanged,
            ) { prefs.setRecentShows(it) }
            // 1.8.0: the two top-bar switches as one choice. A list in a sheet: its four names don't fit one capsule.
            PickListRow(
                tr(R.string.buttons_at_the_top_of_home), TopButtons.entries, topButtons, { it.label },
                info = tr(R.string.off_podcasts_opens_from_library_playlists), changed = topButtonsChanged,
            ) { HomeChoices.setTopButtons(prefs, it) }
        }
        group(HlGroups.search) {
            SwitchRow(tr(R.string.open_the_keyboard), s.searchKeyboard, info = tr(R.string.ready_to_type_when_search_opens), changed = keyboardChanged) { prefs.setSearchKeyboard(it) }
            // Search's own sheet (its sections and chips), which only Search's page opened before (plan §2.11).
            LinkRow(
                tr(R.string.arrange_search), value = tr(R.string.hl_shown_of, s.searchSections.size, SearchSection.entries.size), changed = arrangeChanged,
            ) { arranging = true }
        }
        // Not "Replay card" for the row too: a group and a row never share a name.
        group(HlGroups.replayCard) {
            PickCardsRow(
                tr(R.string.hl_card_style), ReplayCardStyle.entries, look, { it.label },
                info = tr(R.string.how_replay_looks_on_home_from, look.label, look.note), changed = lookChanged,
                onPick = { prefs.setReplayCard(it.name) },
            ) { ReplayCardPreview(it) }
            // #286: what the bars are, on the looks that draw them.
            ChoiceRow(
                tr(R.string.replay_card_graph), ReplayGraph.entries, s.view.replayGraph, { it.label },
                info = tr(R.string.replay_card_graph_info), changed = graphChanged,
            ) { prefs.setReplayGraph(it) }
        }
        group(HlGroups.library) {
            // The sheet Library's own Edit button opens: which tiles show, their order, and where the playlists go.
            LinkRow(tr(R.string.library_tiles), value = tr(R.string.hl_shown_of, s.library.tiles.size, offered.size), changed = tilesChanged) { editingTiles = true }
            LibraryViewRow(views, viewChanged)
            ChoiceRow(
                tr(R.string.covers_across), LibraryColumns.entries, s.library.columns, { it.label },
                info = tr(R.string.in_the_grid_auto_fits_as), changed = columnsChanged,
            ) { prefs.setLibraryColumns(it) }
            SwitchRow(tr(R.string.details_under_each_one), s.library.subtitles, info = tr(R.string.song_counts_owners_and_years), changed = detailsChanged) { prefs.setLibrarySubtitles(it) }
            PickListRow(tr(R.string.open_library_on), tabs.map { it.first }, startTab, tabName, changed = startTabChanged, searchable = false) { prefs.setLibraryStartTab(it) }
            // From Recommendations (plan §2.11): it is about what Liked songs lists.
            SwitchRow(
                tr(R.string.a_song_and_its_video_as), s.mergeLikedVideos,
                info = tr(R.string.liked_both_they_show_once_as) + ". " + tr(R.string.when_you_ve_liked_a_song), changed = songAndVideoChanged,
            ) { prefs.setMergeLikedVideos(it) }
        }
        group(HlGroups.lists) {
            // Two switches as chips (plan §2.6); each is also in its list's sort menu.
            ShowChipsRow(
                listsShowTitle,
                listOf(
                    ShowOption(listsShowLabel(ListsShow.GENRES), ListsShow.GENRES in listsShow) { prefs.setPlaylistGenres(it) },
                    ShowOption(listsShowLabel(ListsShow.ALBUM_NAMES), ListsShow.ALBUM_NAMES in listsShow) { prefs.setDownloadsAlbums(it) },
                ),
                info = tr(R.string.hl_lists_show_info), changed = listsShowChanged,
            )
            // D55 (1.8.4): albums can have it too; the old switch's "on" is Playlists.
            ChoiceRow(
                tr(R.string.hl_full_width_pictures), FullWidthPictures.entries, s.fullWidthPictures, { it.label },
                info = tr(R.string.hl_full_width_pictures_info), changed = fullWidthChanged,
            ) { prefs.setFullWidthPictures(it) }
            // D54 (1.8.4): off as Vinz comes.
            SwitchRow(tr(R.string.hl_locate_button), s.behavior.locatePlaying, info = tr(R.string.hl_locate_button_info), changed = locateChanged) { prefs.setLocatePlaying(it) }
        }
        // What artist pages show (their colors are Theme & colors' "Page colors").
        group(HlGroups.artistPages) {
            ShowChipsRow(
                artistShowTitle,
                listOf(
                    ShowOption(artistShowLabel(ArtistShow.YOU), ArtistShow.YOU in artistShow) { prefs.setArtistYou(it) },
                    ShowOption(artistShowLabel(ArtistShow.ESSENTIALS), ArtistShow.ESSENTIALS in artistShow) { prefs.setArtistEssentials(it) },
                    ShowOption(artistShowLabel(ArtistShow.BIO), ArtistShow.BIO in artistShow) { prefs.setArtistBio(it) },
                ),
                info = tr(R.string.hl_artist_show_info), changed = artistShowChanged,
            )
        }
    }
    if (arranging) ArrangeSearchSheet { arranging = false }
    if (editingTiles) LibraryTilesSheet { editingTiles = false }
}

/**
 * The one row for the three Library tabs' "show as": it says them, and a tap opens a sheet with a Choice row for each.
 * A pick in the sheet is stored at once; the sheet stays for the other tabs and goes with a swipe down, Back or a tap
 * outside (the kit's [RowsSheet]).
 */
@Composable
private fun LibraryViewRow(views: LibraryViews, changed: Boolean) {
    val prefs = LocalContainer.current.settings
    val title = tr(R.string.hl_view)
    var open by rememberSaveable(title, "library-view") { mutableStateOf(false) }
    PickRow(title, libraryViewsText(views), info = tr(R.string.hl_view_info), changed = changed) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        // Each tab its own way (1.7.6); the names are the ones the three rows had.
        libraryViewTabs.forEach { (tab, name, now) ->
            ChoiceRow(tr(name), LibraryView.entries, now(views), { it.label }) { prefs.setLibraryTabView(tab, it) }
        }
    }
}

/** A tab's stored name, its row's name in the sheet, and its view. */
private val libraryViewTabs: List<Triple<String, Int, (LibraryViews) -> LibraryView>> = listOf(
    Triple(LibraryViews.PLAYLISTS, R.string.playlists_as) { it.playlists },
    Triple(LibraryViews.ALBUMS, R.string.albums_as) { it.albums },
    Triple(LibraryViews.ARTISTS, R.string.artists_as) { it.artists },
)

/** What is ticked, in words, for a reset line. */
private fun shownWords(labels: List<String>): String = labels.joinToString(" · ").ifEmpty { tr(R.string.none) }

/** A reset line's words for a list kept in a sheet: "Changed", and "As Vinz comes" for [default]. */
internal fun <T> asVinzComes(default: T): (T) -> String = { if (it == default) tr(R.string.hl_as_vinz_comes) else tr(R.string.changed) }

private fun replayCardStyle(s: AppSettings): ReplayCardStyle = ReplayCardStyle.entries.firstOrNull { it.name == s.replayCard } ?: ReplayCardStyle.WASH

// ---------------------------------------------------------------------------- One control instead of several (plan §2.5)
// Each keeps its stored values as they are: `…ToMerged` reads them into the control, `…ToStored` is what it writes.

/** Library's three tabs that can be a grid or a list, each as it shows now. */
data class LibraryViews(val playlists: LibraryView, val albums: LibraryView, val artists: LibraryView) {
    companion object {
        const val PLAYLISTS = "PLAYLISTS"
        const val ALBUMS = "ALBUMS"
        const val ARTISTS = "ARTISTS"
    }
}

/** The stored views by tab ([AppSettings.libraryViews]); a tab without its own shows as [fallback] ([AppSettings.libraryView]). */
fun libraryViewsToMerged(stored: Map<String, LibraryView>, fallback: LibraryView) = LibraryViews(
    stored[LibraryViews.PLAYLISTS] ?: fallback, stored[LibraryViews.ALBUMS] ?: fallback, stored[LibraryViews.ARTISTS] ?: fallback,
)

/** What the three tabs are stored as once each was picked (the sheet writes one tab at a time, the same way). */
fun libraryViewsToStored(views: LibraryViews): Map<String, LibraryView> =
    mapOf(LibraryViews.PLAYLISTS to views.playlists, LibraryViews.ALBUMS to views.albums, LibraryViews.ARTISTS to views.artists)

/** "List", or "Playlists: Grid · Albums: List · Artists: List" when the tabs differ. */
fun libraryViewsText(v: LibraryViews): String =
    if (v.playlists == v.albums && v.albums == v.artists) v.playlists.label
    else listOf(R.string.playlists to v.playlists, R.string.albums to v.albums, R.string.artists to v.artists).joinToString(" · ") { (name, view) -> tr(R.string.hl_view_tab, tr(name), view.label) }

/** Lists → Show: genres on playlists and album names in Downloads, two switches as one row of chips. */
enum class ListsShow { GENRES, ALBUM_NAMES }

fun listsShowToMerged(genres: Boolean, albumNames: Boolean): Set<ListsShow> = setOfNotNull(ListsShow.GENRES.takeIf { genres }, ListsShow.ALBUM_NAMES.takeIf { albumNames })

/** Genres on playlists, album names in Downloads. */
fun listsShowToStored(show: Set<ListsShow>): Pair<Boolean, Boolean> = (ListsShow.GENRES in show) to (ListsShow.ALBUM_NAMES in show)

fun listsShowLabel(s: ListsShow): String = when (s) {
    ListsShow.GENRES -> tr(R.string.hl_genres)
    ListsShow.ALBUM_NAMES -> tr(R.string.hl_album_names)
}

/** Artist pages → Show: three switches as one row of chips. */
enum class ArtistShow { YOU, ESSENTIALS, BIO }

fun artistShowToMerged(you: Boolean, essentials: Boolean, bio: Boolean): Set<ArtistShow> =
    setOfNotNull(ArtistShow.YOU.takeIf { you }, ArtistShow.ESSENTIALS.takeIf { essentials }, ArtistShow.BIO.takeIf { bio })

/** You and this artist, Essentials for you, Bio from Wikipedia. */
fun artistShowToStored(show: Set<ArtistShow>): Triple<Boolean, Boolean, Boolean> = Triple(ArtistShow.YOU in show, ArtistShow.ESSENTIALS in show, ArtistShow.BIO in show)

fun artistShowLabel(s: ArtistShow): String = when (s) {
    ArtistShow.YOU -> tr(R.string.artist_you_title)
    ArtistShow.ESSENTIALS -> tr(R.string.artist_essentials_title)
    ArtistShow.BIO -> tr(R.string.artist_bio_title)
}

/**
 * Full-width pictures: what [FullWidthPictures] is stored as (`playlist_hero_cover`, `album_hero_cover`), as
 * `SettingsRepository.setFullWidthPictures` writes it. Reading is [FullWidthPictures.of].
 */
fun fullWidthToStored(v: FullWidthPictures): Pair<Boolean, Boolean> = (v != FullWidthPictures.OFF) to (v == FullWidthPictures.ALBUMS_AND_PLAYLISTS)

// ---------------------------------------------------------------------------- The root's line, reset keys, old links, search

/** The root's line for Home & Library: how Library shows ("Library: List", "Library: Grid and list"). */
fun homeLibraryValue(s: AppSettings, dark: Boolean): String {
    val v = libraryViewsToMerged(s.library.views, s.library.view)
    return tr(R.string.hl_root_library, if (v.playlists == v.albums && v.albums == v.artists) v.playlists.label else tr(R.string.hl_view_mixed))
}

/**
 * The look settings "Reset this page" puts back. The page's reset goes through each row's own setter (its other rows
 * aren't part of a Look); these are the ones among them that a Look holds.
 */
val homeLibraryLookKeys: List<String> = listOf(
    "replay_card", "replay_card_graph", "library_view", "library_views", "library_columns", "library_subtitles",
    "playlist_hero_cover", "album_hero_cover", "downloads_albums",
)

/** Old group titles → this page's groups: "Liked songs" (on Recommendations before 1.8.4) held what is in Library now. */
val homeLibraryOldGroups: Map<String, String> by lazy { mapOf(tr(R.string.liked_songs) to HlGroups.library) }

/** Customize Home's route, opened at one of its groups (the screen reads `find` from the route it was opened with). */
internal fun customizeHomeRoute(group: String? = null): String = Routes.CATALOGUE + (group?.let { "?find=" + Uri.encode(it) } ?: "")

/**
 * The page's lines in Settings' search, each naming the group it is drawn in, then Customize Home's: that screen isn't
 * one of Settings' own pages, so its lines carry its route and name, and a result opens it at the line's group.
 */
val homeLibraryIndex: List<SettingEntry> by lazy {
    val g = HlGroups
    fun words(vararg ids: Int) = ids.joinToString(" ") { tr(it) }
    val customizeHome = tr(R.string.customize_home)
    val top = tr(R.string.top_of_home)
    val sections = tr(R.string.sections)
    val tiles = tr(R.string.section_shortcut_tiles)
    val options = tr(R.string.options)
    fun onHome(group: String, title: String, keywords: String = "", needs: Needs? = null) =
        SettingEntry(page, group, title, keywords, needs = needs, route = customizeHomeRoute(group), routeTitle = customizeHome)
    val withGreeting = Needs(tr(R.string.turn_off) + " · " + HomeHeader.NONE.label) { it.home.header != HomeHeader.NONE }
    val withPicture = Needs(top + " · " + HomeHeader.PICTURE.label) { it.home.header == HomeHeader.PICTURE || it.home.header == HomeHeader.NOW_PLAYING }
    val onlyPicture = Needs(top + " · " + HomeHeader.PICTURE.label) { it.home.header == HomeHeader.PICTURE }
    val notYouTubeOnly = Needs(tr(R.string.turn_off) + " · " + tr(R.string.speed_dial_youtube_music_s_only)) { !it.speedDialYouTubeOnly }
    listOf(
        // Home
        SettingEntry(page, g.home, customizeHome, tr(R.string.sections_order_shortcuts_quick_picks_speed)),
        SettingEntry(page, g.home, tr(R.string.continue_line), tr(R.string.continue_album_resume_greeting_hide_context)),
        SettingEntry(page, g.home, tr(R.string.recently_played_shows), tr(R.string.recently_played_albums_playlists_songs_home)),
        SettingEntry(page, g.home, tr(R.string.buttons_at_the_top_of_home), words(R.string.podcasts_button, R.string.customize_button_at_the_top)),
        // Search
        SettingEntry(page, g.search, tr(R.string.search)),
        SettingEntry(page, g.search, tr(R.string.open_the_keyboard), tr(R.string.search_typing)),
        SettingEntry(page, g.search, tr(R.string.arrange_search), words(R.string.hl_arrange_search_keywords, R.string.search_chips)),
        // Replay card
        SettingEntry(page, g.replayCard, tr(R.string.hl_card_style),tr(R.string.replay_teaser_look_style_design_quiet), shown = g.replayCard),
        SettingEntry(page, g.replayCard, tr(R.string.replay_card_graph), tr(R.string.replay_card_graph_keywords)),
        // Library
        SettingEntry(page, g.library, tr(R.string.library_tiles), words(R.string.liked_downloads_local_files_replay_podcasts, R.string.where_your_playlists_go, R.string.playlists_first, R.string.tiles_first)),
        SettingEntry(page, g.library, tr(R.string.hl_view), words(R.string.playlists_as, R.string.albums_as, R.string.artists_as, R.string.grid_list_view, R.string.hl_view_keywords)),
        SettingEntry(page, g.library, tr(R.string.covers_across), tr(R.string.columns_grid_two_three_four)),
        SettingEntry(page, g.library, tr(R.string.details_under_each_one), tr(R.string.subtitles_counts_song_count_owner_year)),
        SettingEntry(page, g.library, tr(R.string.open_library_on), tr(R.string.start_tab)),
        SettingEntry(page, g.library, tr(R.string.a_song_and_its_video_as), words(R.string.merge_liked_music_video_duplicate_same, R.string.liked_songs)),
        // Lists: the two chips of Show by their own names.
        SettingEntry(page, g.lists, tr(R.string.genres_on_playlists), tr(R.string.genres_on_playlists_keywords)),
        SettingEntry(page, g.lists, tr(R.string.album_names_in_downloads), tr(R.string.album_names_in_downloads_keywords)),
        SettingEntry(page, g.lists, tr(R.string.hl_full_width_pictures), words(R.string.full_width_playlist_pictures, R.string.full_width_albums_and_playlists, R.string.hl_full_width_keywords)),
        SettingEntry(page, g.lists, tr(R.string.hl_locate_button), words(R.string.go_to_the_playing_song, R.string.hl_locate_keywords)),
        // Artist pages: the three chips of Show by their own names.
        SettingEntry(page, g.artistPages, tr(R.string.artist_you_title), tr(R.string.artist_you_detail)),
        SettingEntry(page, g.artistPages, tr(R.string.artist_essentials_title), tr(R.string.artist_essentials_detail)),
        SettingEntry(page, g.artistPages, tr(R.string.artist_bio_title), tr(R.string.artist_bio_keywords)),

        // Customize Home → Top of Home
        onHome(top, tr(R.string.style), words(R.string.ch_top_keywords, R.string.greeting, R.string.just_the_buttons, R.string.a_picture)),
        onHome(top, tr(R.string.headline), tr(R.string.good_morning_afternoon_evening), needs = withGreeting),
        onHome(top, tr(R.string.show_your_name), needs = withGreeting),
        onHome(top, tr(R.string.height), needs = withPicture),
        onHome(top, tr(R.string.picture), tr(R.string.your_photo), needs = onlyPicture),
        // Customize Home → Sections
        onHome(sections, sections, words(R.string.sections_order_shortcuts_quick_picks_speed, R.string.add_a_section)),
        // Customize Home → Shortcut tiles
        onHome(tiles, tiles, tr(R.string.ch_tiles_keywords)),
        onHome(tiles, tr(R.string.pinned_playlists)),
        onHome(tiles, tr(R.string.recently_played_albums)),
        // Customize Home → Options (the three rows that were on Home & Library too are here only, plan §2.6).
        onHome(options, tr(R.string.add_new_sections_automatically), "shelves"),
        onHome(options, tr(R.string.ch_hide_from_home), words(R.string.leave_off_music_video_sections, R.string.leave_off_podcast_sections, R.string.ch_hide_keywords)),
        onHome(options, tr(R.string.quick_picks_from), tr(R.string.quick_picks_from_keywords)),
        onHome(options, tr(R.string.quick_picks_start_a_radio)),
        onHome(options, tr(R.string.speed_dial_youtube_music_s_only), tr(R.string.without_the_albums_and_songs_vinz)),
        // #372. Only there, so its result opens that screen (1.8.4's bug round).
        SettingEntry(page, options, tr(R.string.speed_dial_your_plays_first), tr(R.string.speed_dial_your_plays_first_sub), needs = notYouTubeOnly, route = customizeHomeRoute(options), routeTitle = customizeHome),
        onHome(options, tr(R.string.customize_button_on_home), tr(R.string.customize_button_at_the_bottom)),
    )
}
