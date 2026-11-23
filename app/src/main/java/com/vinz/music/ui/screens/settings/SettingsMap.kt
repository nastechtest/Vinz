package com.vinz.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.tr
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.theme.VinzIcons

/** The five groups of the Settings root, top to bottom (1.8.4, plan §2.3). */
enum class SettingsRootGroup(val title: String) {
    MAKE_IT_YOURS(tr(R.string.make_it_yours)),
    // Its own string: `listening` is Replay's listening time ("再生時間" in Japanese), not this heading.
    LISTENING(tr(R.string.root_listening)),
    YOUR_MUSIC(tr(R.string.your_music)),
    CONNECTIONS(tr(R.string.connections)),
    APP(tr(R.string.root_app)),
}

/**
 * The map of Settings (1.8.4, plan §2.3, approved): 17 pages in 5 groups, each one tap from the root. It is fixed: a
 * new setting joins an existing page and group; a new page or root entry needs the owner's word.
 *
 * A page's [title] is its root entry's name. Everything else about a page lives in its own file,
 * `<Page>Page.kt` in this folder, and nowhere else: its composable, its search lines (`…Index`), its "Reset this page"
 * keys (`…LookKeys`), its root value line (`…Value`) and the old group names it still answers to (`…OldGroups`). The
 * `when`s at the foot of this file tie the two together, so rebuilding a page never touches this file.
 *
 * - [key]: the page's name in a route (`settings/<key>`), see [SettingsMap.resolve] for the old keys.
 * - [oldNames]: the names of the pages before 1.8.4 whose settings it took over; search finds the page, and everything
 *   on it, by them. [words]: what else the page itself is looked for by.
 */
enum class SettingsPageId(
    val key: String,
    val group: SettingsRootGroup,
    val title: String,
    val icon: ImageVector,
    val oldNames: String,
    val words: String = "",
) {
    LOOKS("looks", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.looks), VinzIcons.Looks, tr(R.string.customize_vinz), tr(R.string.theme_preset_save_switch_share_style)),
    THEME_COLORS(
        "theme_colors", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.root_theme_colors), VinzIcons.Palette,
        listOf(R.string.customize_vinz, R.string.color_2, R.string.glass_backgrounds).joinToString(" ") { tr(it) },
        tr(R.string.color_colors_colour_accent_split_light) + " " + tr(R.string.blur_backgrounds_motion),
    ),
    TEXT_ICONS(
        "text_icons", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.root_text_icons), VinzIcons.Signature,
        listOf(R.string.customize_vinz, R.string.text, R.string.icons_shapes).joinToString(" ") { tr(it) },
        tr(R.string.font_typeface_size) + " " + tr(R.string.icon_pack_app_icon_corners),
    ),
    PLAYER("player", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.player), VinzIcons.PlayCircle, tr(R.string.customize_vinz), tr(R.string.now_playing_look_layout_cover)),
    LYRICS_SING(
        "lyrics_sing", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.lyrics_sing), VinzIcons.Lyrics,
        tr(R.string.customize_vinz) + " " + tr(R.string.lyrics), tr(R.string.lyrics_look_style_colors),
    ),
    HOME_LIBRARY("home_library", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.home_library), VinzIcons.Home, tr(R.string.customize_vinz), tr(R.string.home_screen_library_look_views)),
    TABS_MINI_PLAYER(
        "tabs_mini_player", SettingsRootGroup.MAKE_IT_YOURS, tr(R.string.tabs_mini_player), VinzIcons.Customize,
        tr(R.string.customize_vinz), tr(R.string.layout_navigation_tab_bar_bottom_bar),
    ),
    PLAYBACK("playback", SettingsRootGroup.LISTENING, tr(R.string.playback), VinzIcons.PlaylistPlay, tr(R.string.playback_sound)),
    SOUND_QUALITY("sound_quality", SettingsRootGroup.LISTENING, tr(R.string.root_sound_quality), VinzIcons.Equalizer, tr(R.string.playback_sound)),
    GESTURES_BUTTONS("gestures_buttons", SettingsRootGroup.LISTENING, tr(R.string.root_gestures_buttons), VinzIcons.Touch, tr(R.string.controls_gestures)),
    DOWNLOADS_DATA("downloads_data", SettingsRootGroup.YOUR_MUSIC, tr(R.string.root_downloads_data), VinzIcons.DownloadCircle, tr(R.string.downloads_storage_data)),
    RECOMMENDATIONS_HISTORY(
        "recommendations_history", SettingsRootGroup.YOUR_MUSIC, tr(R.string.root_recommendations_history), VinzIcons.History,
        tr(R.string.recommendations_replay),
    ),
    CAR("car", SettingsRootGroup.CONNECTIONS, tr(R.string.root_car), VinzIcons.Car, tr(R.string.devices_connections), tr(R.string.android_auto)),
    SPEAKERS_TVS_BROWSER(
        "speakers_tvs_browser", SettingsRootGroup.CONNECTIONS, tr(R.string.speakers_tvs_browser), VinzIcons.Cast,
        // And what people call casting ("chromecast", "dlna", "sonos"…).
        tr(R.string.devices_connections), tr(R.string.vinz_connect_cast_remote_handoff) + " " + tr(R.string.cast_casting_google_cast_google_tv),
    ),
    ACCOUNTS_SHARING(
        "accounts_sharing", SettingsRootGroup.CONNECTIONS, tr(R.string.root_accounts_sharing), VinzIcons.Groups,
        listOf(tr(R.string.devices_connections), tr(R.string.discord), LAST_FM, tr(R.string.spotify)).joinToString(" "),
        tr(R.string.rich_presence_status) + " " + tr(R.string.lastfm_last_fm_scrobble_scrobbling),
    ),
    LANGUAGE_REGION("language_region", SettingsRootGroup.APP, tr(R.string.language_and_region), VinzIcons.Globe, tr(R.string.language)),
    BACKUP_ABOUT("backup_about", SettingsRootGroup.APP, tr(R.string.backup_about), VinzIcons.Info, "");

    companion object {
        fun of(key: String?): SettingsPageId? = entries.firstOrNull { it.key == key }
    }
}

/** The service's own name, the same in every language. */
internal const val LAST_FM = "Last.fm"

/** Where a settings route leads: a [page], the group to open it at ([find]), or the licenses (a screen of Backup & about). */
data class SettingsTarget(val page: SettingsPageId, val find: String? = null, val licenses: Boolean = false)

/**
 * The root's order, and the old keys.
 *
 * **Old keys are aliases.** Before 1.8.4 a settings page was named by a `SettingsSection` key ("sound", "lyrics_look",
 * "connect"…), and links written with those keys are everywhere: `Routes.settings(...)` call sites, `vinz://` links,
 * the tour's "Show me" steps, "Customize this", widgets. They all still work: [resolve] sends an old key (and the old
 * group, where a link names one) to the page of the map that holds it now.
 */
object SettingsMap {
    /** The root, top to bottom: each group with its pages. */
    val groups: List<Pair<SettingsRootGroup, List<SettingsPageId>>> =
        SettingsRootGroup.entries.map { g -> g to SettingsPageId.entries.filter { it.group == g } }

    /** An old key's new home: the [page], and the group it opens at when the link names none (what the old page began with). */
    class Alias(val page: SettingsPageId, val find: String? = null, val licenses: Boolean = false)

    /** Every key of before 1.8.4 (`SettingsSection`'s), and where it leads now. */
    val aliases: Map<String, Alias> by lazy {
        mapOf(
            // Customize Vinz's own page held the theme and Motion (its list of look pages is the root's first group now).
            "appearance" to Alias(SettingsPageId.THEME_COLORS),
            "looks" to Alias(SettingsPageId.LOOKS),
            "color" to Alias(SettingsPageId.THEME_COLORS, tr(R.string.spectrum)),
            "glass" to Alias(SettingsPageId.THEME_COLORS, tr(R.string.backgrounds)),
            "text" to Alias(SettingsPageId.TEXT_ICONS),
            "icons" to Alias(SettingsPageId.TEXT_ICONS, tr(R.string.icons)),
            "interface" to Alias(SettingsPageId.TABS_MINI_PLAYER),
            "home" to Alias(SettingsPageId.HOME_LIBRARY),
            "player" to Alias(SettingsPageId.PLAYER),
            "lyrics_look" to Alias(SettingsPageId.LYRICS_SING),
            "lyrics" to Alias(SettingsPageId.LYRICS_SING, tr(R.string.sources)),
            // Playback & sound: most of it is Playback; its Quality and Sound groups are in [movedGroups].
            "sound" to Alias(SettingsPageId.PLAYBACK),
            "controls" to Alias(SettingsPageId.GESTURES_BUTTONS),
            "library" to Alias(SettingsPageId.RECOMMENDATIONS_HISTORY),
            "downloads" to Alias(SettingsPageId.DOWNLOADS_DATA),
            // Devices & connections: Android Auto, then links to the pages that are root entries now.
            "devices" to Alias(SettingsPageId.CAR),
            "connect" to Alias(SettingsPageId.SPEAKERS_TVS_BROWSER),
            "discord" to Alias(SettingsPageId.ACCOUNTS_SHARING, tr(R.string.discord)),
            "lastfm" to Alias(SettingsPageId.ACCOUNTS_SHARING, LAST_FM),
            "spotify" to Alias(SettingsPageId.ACCOUNTS_SHARING, tr(R.string.spotify)),
            "language" to Alias(SettingsPageId.LANGUAGE_REGION),
            "about" to Alias(SettingsPageId.BACKUP_ABOUT),
            // Not a page of the map: the licenses' own screen, opened from Backup & about.
            "licenses" to Alias(SettingsPageId.BACKUP_ABOUT, licenses = true),
        )
    }

    /**
     * Old (key, group) pairs whose group went to another page than the rest of its old page did. Only what is drawn on
     * the other page today is listed: a group still drawn by a whole old composable stays where that is shown, and
     * moves here when its new page is built.
     */
    val movedGroups: Map<Pair<String, String>, SettingsPageId> by lazy {
        mapOf(
            ("sound" to tr(R.string.quality)) to SettingsPageId.SOUND_QUALITY,
            ("sound" to tr(R.string.sound)) to SettingsPageId.SOUND_QUALITY,
            ("devices" to tr(R.string.sharing_and_links)) to SettingsPageId.ACCOUNTS_SHARING,
            ("icons" to tr(R.string.home_screen_widgets)) to SettingsPageId.THEME_COLORS,
            ("library" to tr(R.string.liked_songs)) to SettingsPageId.HOME_LIBRARY,
            ("library" to tr(R.string.imp_title)) to SettingsPageId.ACCOUNTS_SHARING,
            ("appearance" to tr(R.string.customize_this)) to SettingsPageId.LOOKS,
        )
    }

    /**
     * Where `settings/<key>?find=<find>` leads: a page of the map by its own key, or by a key of before 1.8.4 (with its
     * old group, where the link names one). Null: no such page (the route then shows the root).
     */
    fun resolve(key: String?, find: String? = null): SettingsTarget? {
        if (key == null) return null
        val group = find?.takeIf { it.isNotBlank() }
        val alias = aliases[key]
        // A page's own key first ("looks" and "player" are both a page's key and an old key, of the same page).
        val own = SettingsPageId.of(key)
        val page: SettingsPageId
        val at: String?
        when {
            own != null -> { page = own; at = group }
            alias != null -> {
                val moved = group?.let { movedGroups[key to it] }
                page = moved ?: alias.page
                at = group ?: alias.find
                if (alias.licenses) return SettingsTarget(page, licenses = true)
            }
            else -> return null
        }
        // The page may have renamed the group since: it says which of its groups the old name means.
        return SettingsTarget(page, at?.let { page.oldGroups[it] ?: it })
    }
}

// ---------------------------------------------------------------------------- Each page's own parts (its file)

/** The page's lines for Settings' search. */
val SettingsPageId.index: List<SettingEntry>
    get() = when (this) {
        SettingsPageId.LOOKS -> looksIndex
        SettingsPageId.THEME_COLORS -> themeColorsIndex
        SettingsPageId.TEXT_ICONS -> textIconsIndex
        SettingsPageId.PLAYER -> playerIndex
        SettingsPageId.LYRICS_SING -> lyricsSingIndex
        SettingsPageId.HOME_LIBRARY -> homeLibraryIndex
        SettingsPageId.TABS_MINI_PLAYER -> tabsMiniPlayerIndex
        SettingsPageId.PLAYBACK -> playbackIndex
        SettingsPageId.SOUND_QUALITY -> soundQualityIndex
        SettingsPageId.GESTURES_BUTTONS -> gesturesButtonsIndex
        SettingsPageId.DOWNLOADS_DATA -> downloadsDataIndex
        SettingsPageId.RECOMMENDATIONS_HISTORY -> recommendationsHistoryIndex
        SettingsPageId.CAR -> carIndex
        SettingsPageId.SPEAKERS_TVS_BROWSER -> speakersTvsBrowserIndex
        SettingsPageId.ACCOUNTS_SHARING -> accountsSharingIndex
        SettingsPageId.LANGUAGE_REGION -> languageRegionIndex
        SettingsPageId.BACKUP_ABOUT -> backupAboutIndex
    }

/** The look settings "Reset this page" puts back (empty: the page has none of its own to reset). */
val SettingsPageId.lookKeys: List<String>
    get() = when (this) {
        SettingsPageId.LOOKS -> looksLookKeys
        SettingsPageId.THEME_COLORS -> themeColorsLookKeys
        SettingsPageId.TEXT_ICONS -> textIconsLookKeys
        SettingsPageId.PLAYER -> playerLookKeys
        SettingsPageId.LYRICS_SING -> lyricsSingLookKeys
        SettingsPageId.HOME_LIBRARY -> homeLibraryLookKeys
        SettingsPageId.TABS_MINI_PLAYER -> tabsMiniPlayerLookKeys
        SettingsPageId.PLAYBACK -> playbackLookKeys
        SettingsPageId.SOUND_QUALITY -> soundQualityLookKeys
        SettingsPageId.GESTURES_BUTTONS -> gesturesButtonsLookKeys
        SettingsPageId.DOWNLOADS_DATA -> downloadsDataLookKeys
        SettingsPageId.RECOMMENDATIONS_HISTORY -> recommendationsHistoryLookKeys
        SettingsPageId.CAR -> carLookKeys
        SettingsPageId.SPEAKERS_TVS_BROWSER -> speakersTvsBrowserLookKeys
        SettingsPageId.ACCOUNTS_SHARING -> accountsSharingLookKeys
        SettingsPageId.LANGUAGE_REGION -> languageRegionLookKeys
        SettingsPageId.BACKUP_ABOUT -> backupAboutLookKeys
    }

/**
 * Old group names the page still answers to: a group's title before 1.8.4 → the key of the page's group that holds it
 * now. Links written before the rework ("settings/library?find=Tune radios") land on the right group through it.
 */
val SettingsPageId.oldGroups: Map<String, String>
    get() = when (this) {
        SettingsPageId.LOOKS -> looksOldGroups
        SettingsPageId.THEME_COLORS -> themeColorsOldGroups
        SettingsPageId.TEXT_ICONS -> textIconsOldGroups
        SettingsPageId.PLAYER -> playerOldGroups
        SettingsPageId.LYRICS_SING -> lyricsSingOldGroups
        SettingsPageId.HOME_LIBRARY -> homeLibraryOldGroups
        SettingsPageId.TABS_MINI_PLAYER -> tabsMiniPlayerOldGroups
        SettingsPageId.PLAYBACK -> playbackOldGroups
        SettingsPageId.SOUND_QUALITY -> soundQualityOldGroups
        SettingsPageId.GESTURES_BUTTONS -> gesturesButtonsOldGroups
        SettingsPageId.DOWNLOADS_DATA -> downloadsDataOldGroups
        SettingsPageId.RECOMMENDATIONS_HISTORY -> recommendationsHistoryOldGroups
        SettingsPageId.CAR -> carOldGroups
        SettingsPageId.SPEAKERS_TVS_BROWSER -> speakersTvsBrowserOldGroups
        SettingsPageId.ACCOUNTS_SHARING -> accountsSharingOldGroups
        SettingsPageId.LANGUAGE_REGION -> languageRegionOldGroups
        SettingsPageId.BACKUP_ABOUT -> backupAboutOldGroups
    }

/** The page's line on the root: its state in a few words, from the settings ([dark]: Vinz is in dark mode now). */
fun SettingsPageId.value(s: AppSettings, dark: Boolean = true): String = when (this) {
    SettingsPageId.LOOKS -> looksValue(s, dark)
    SettingsPageId.THEME_COLORS -> themeColorsValue(s, dark)
    SettingsPageId.TEXT_ICONS -> textIconsValue(s, dark)
    SettingsPageId.PLAYER -> playerValue(s, dark)
    SettingsPageId.LYRICS_SING -> lyricsSingValue(s, dark)
    SettingsPageId.HOME_LIBRARY -> homeLibraryValue(s, dark)
    SettingsPageId.TABS_MINI_PLAYER -> tabsMiniPlayerValue(s, dark)
    SettingsPageId.PLAYBACK -> playbackValue(s, dark)
    SettingsPageId.SOUND_QUALITY -> soundQualityValue(s, dark)
    SettingsPageId.GESTURES_BUTTONS -> gesturesButtonsValue(s, dark)
    SettingsPageId.DOWNLOADS_DATA -> downloadsDataValue(s, dark)
    SettingsPageId.RECOMMENDATIONS_HISTORY -> recommendationsHistoryValue(s, dark)
    SettingsPageId.CAR -> carValue(s, dark)
    SettingsPageId.SPEAKERS_TVS_BROWSER -> speakersTvsBrowserValue(s, dark)
    SettingsPageId.ACCOUNTS_SHARING -> accountsSharingValue(s, dark)
    SettingsPageId.LANGUAGE_REGION -> languageRegionValue(s, dark)
    SettingsPageId.BACKUP_ABOUT -> backupAboutValue(s, dark)
}

/**
 * The page itself. [find]: the group to open it at (a search result, a link). [showBack] false: beside the root on a
 * wide window. [onSearch]: opens Settings search (the bar's search button). Call it inside `StoredSettings`.
 */
@Composable
fun SettingsPageContent(page: SettingsPageId, bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    when (page) {
        SettingsPageId.LOOKS -> LooksPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.THEME_COLORS -> ThemeColorsPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.TEXT_ICONS -> TextIconsPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.PLAYER -> PlayerPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.LYRICS_SING -> LyricsSingPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.HOME_LIBRARY -> HomeLibraryPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.TABS_MINI_PLAYER -> TabsMiniPlayerPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.PLAYBACK -> PlaybackPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.SOUND_QUALITY -> SoundQualityPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.GESTURES_BUTTONS -> GesturesButtonsPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.DOWNLOADS_DATA -> DownloadsDataPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.RECOMMENDATIONS_HISTORY -> RecommendationsHistoryPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.CAR -> CarPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.SPEAKERS_TVS_BROWSER -> SpeakersTvsBrowserPage(bottomPadding, find, showBack, onSearch)
        SettingsPageId.ACCOUNTS_SHARING -> AccountsSharingPage(bottomPadding, find, showBack, onSearch)
        // One untitled card: there is no group to open it at.
        SettingsPageId.LANGUAGE_REGION -> LanguageRegionPage(bottomPadding, showBack, onSearch)
        SettingsPageId.BACKUP_ABOUT -> BackupAboutPage(bottomPadding, find, showBack, onSearch)
    }
}
