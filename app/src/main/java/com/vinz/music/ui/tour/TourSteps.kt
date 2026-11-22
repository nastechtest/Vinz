package com.vinz.music.ui.tour

import com.vinz.music.R
import com.vinz.music.tr
import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import com.vinz.music.BuildConfig
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.theme.VinzIcons

/**
 * One page of Vinz's tour.
 *
 * [since] is the versionCode of the release the feature (or its current look) came in. A new install sees every step
 * (the full tour); someone updating sees only the steps newer than the last tour they saw ([Tour.seenVersion]). To
 * introduce a later release's features: add steps with that release's versionCode, and keep the older steps true to
 * the app (new installs still see all of them).
 */
data class TourStep(
    val since: Int,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val points: List<String> = emptyList(),
    /** Small icons beside the big one, for what the step covers. */
    val extras: List<ImageVector> = emptyList(),
    /** "Show me": where it opens (the tour closes; it can be taken again from Settings → About). */
    val showMe: String? = null,
    val kind: Kind = Kind.PLAIN,
    /**
     * 1.8.7.6 (plan-1.8.7.6 §4.1 S6, D5): the card ends with one line asking to support Vinz, shown once per release and
     * never with "I've supported Vinz" on ([com.vinz.music.ui.support.SupportWhatsNewLine]). Only on the last card of
     * a release's cards (`TourTest`).
     */
    val support: Boolean = false,
) {
    enum class Kind { PLAIN, NOTIFICATIONS }
}

object Tour {
    /** Vinz 1.7 (build 39): the redesign and its features. Everyone, new or updating, sees all of these once. */
    private const val V1_7 = 39
    /** Vinz 1.7.2 (build 41): color, and the smaller things that came with it. */
    private const val V1_7_2 = 41
    private const val V1_7_3 = 42
    /** Vinz 1.7.4 (build 43): the player, your way, and a faster Vinz. */
    private const val V1_7_4 = 43
    /** Vinz 1.7.5 (build 44): lyrics, your way. */
    private const val V1_7_5 = 44
    /** Vinz 1.7.6 (build 45): Home, Library and Search, your way. */
    private const val V1_7_6 = 45
    /** Vinz 1.7.7 (build 46): shapes, icons, type and widgets, and what you asked for. */
    private const val V1_7_7 = 46
    /** Vinz 1.7.8 (build 48): sound, your way; Sing simpler; Classic Vinz; your requests. */
    private const val V1_7_8 = 48
    /** Vinz 1.7.9 (build 49): Vinz in 31 languages; your requests. */
    private const val V1_7_9 = 49
/** Vinz 1.7.9.2 (build 51): your requests, each an option. */
private const val V1_7_9_2 = 51
/** Vinz 1.8.0 (build 53): Vinz TV, Settings made clearer, the player and lyrics in parts. */
private const val V1_8_0 = 53
/** Vinz 1.8.1 (build 55): your history brought in, Spotify with your own key, History & Replay, your requests. */
private const val V1_8_1 = 55
/** Vinz 1.8.2 (build 57): the lock screen player. */
private const val V1_8_2 = 57
/** Vinz 1.8.3 (build 59): your library, smarter. */
private const val V1_8_3 = 59
/** Vinz 1.8.4 (build 61): Settings, made simple, and your requests. */
private const val V1_8_4 = 61
/** Vinz 1.8.5 (build 63): Replay & Rewind 2026. */
private const val V1_8_5 = 63
private const val V1_8_6 = 64
private const val V1_8_7 = 65
/** Vinz 1.8.7.5 (build 66): Your Spotify, and fixes. */
private const val V1_8_7_5 = 66
/** Vinz 1.8.7.6 (build 67): the small things, and Spotify's canvas. */
private const val V1_8_7_6 = 67

    /** Every step, in the order they're shown. */
    val steps: List<TourStep> = listOf(
        TourStep(
            V1_7, VinzIcons.Home, tr(R.string.home),
            tr(R.string.your_starting_point_made_from_what),
            listOf(
                tr(R.string.the_replay_card_at_the_top),
                tr(R.string.quick_picks_songs_around_the_artists),
                tr(R.string.made_for_you_daily_mixes_your),
                tr(R.string.the_grid_button_at_the_top),
                tr(R.string.continue_picks_up_the_album_you),
            ),
            listOf(VinzIcons.StarFilled, VinzIcons.Customize),
        ),
        TourStep(
            V1_7, VinzIcons.PlayCircle, tr(R.string.the_player),
            tr(R.string.tap_the_mini_player_or_swipe),
            listOf(
                tr(R.string.best_part_jumps_to_where_listeners),
                tr(R.string.double_tap_the_cover_s_sides),
                tr(R.string.hold_previous_or_next_to_seek),
                tr(R.string.has_the_sleep_timer_stats_downloads),
            ),
            listOf(VinzIcons.Flame, VinzIcons.Moon),
        ),
        TourStep(
            V1_7, VinzIcons.Lyrics, tr(R.string.lyrics),
            tr(R.string.open_them_from_the_button_under),
            listOf(
                tr(R.string.tap_a_line_to_jump_there),
                tr(R.string.their_source_is_at_the_bottom),
                tr(R.string.translate_them_or_read_other_scripts),
            ),
            listOf(VinzIcons.Translate, VinzIcons.Romanize),
        ),
        TourStep(
            // Simpler in 1.7.8: one button.
            V1_7_8, VinzIcons.Mic, tr(R.string.sing),
            tr(R.string.karaoke_with_the_song_s_own),
            listOf(
                tr(R.string.with_the_lyrics_open_tap_the),
                tr(R.string.hold_it_and_slide_for_how),
                tr(R.string.move_the_key_up_or_down),
            ),
        ),
        TourStep(
            V1_7, VinzIcons.Equalizer, tr(R.string.dj_mode_and_crossfade),
            tr(R.string.let_vinz_mix_your_music_songs),
            listOf(
                tr(R.string.in_the_queue_tap_dj_then),
                tr(R.string.long_press_an_album_or_playlist),
                tr(R.string.crossfade_settings_playback_sound_blends_any),
            ),
            listOf(VinzIcons.Queue, VinzIcons.Shuffle),
        ),
        TourStep(
            V1_7, VinzIcons.StarFilled, tr(R.string.made_for_you),
            tr(R.string.mixes_made_on_your_phone_from),
            listOf(
                tr(R.string.daily_mixes_group_the_artists_you),
                tr(R.string.release_radar_finds_new_music_from),
                tr(R.string.made_for_you_also_has_mixes),
                tr(R.string.when_the_queue_ends_vinz_s),
                tr(R.string.tap_shuffle_twice_on_a_playlist),
                tr(R.string.not_for_me_and_leave_out),
            ),
            listOf(VinzIcons.SmartShuffle, VinzIcons.Block),
        ),
        TourStep(
            V1_7, VinzIcons.Search, tr(R.string.search_and_recognize),
            tr(R.string.find_anything_in_vinz_music_or),
            listOf(
                tr(R.string.filters_for_songs_videos_albums_artists),
                tr(R.string.on_this_phone_searches_your_own),
                tr(R.string.the_waveform_button_in_the_search),
                tr(R.string.add_the_recognize_widget_or_quick),
                tr(R.string.podcasts_the_button_at_the_top),
            ),
            listOf(VinzIcons.Recognize),
        ),
        TourStep(
            V1_7, VinzIcons.LibraryFilled, tr(R.string.your_library),
            tr(R.string.everything_you_keep_with_or_without),
            listOf(
                tr(R.string.liked_songs_downloads_and_the_music),
                tr(R.string.make_playlists_and_save_albums_without),
                tr(R.string.a_vinz_account_settings_at_the),
                tr(R.string.blend_makes_a_playlist_of_two),
                tr(R.string.listen_together_plays_in_step_with),
            ),
            listOf(VinzIcons.Blend, VinzIcons.Groups),
        ),
        TourStep(
            V1_7, VinzIcons.Stats, tr(R.string.replay_and_rewind),
            tr(R.string.your_listening_recapped_on_your_phone),
            listOf(
                tr(R.string.replay_your_top_songs_artists_and),
                tr(R.string.rewind_your_year_in_music_as),
                tr(R.string.share_any_slide_as_a_picture),
            ),
            listOf(VinzIcons.History),
        ),
        TourStep(
            V1_7, VinzIcons.Cast, tr(R.string.vinz_connect),
            tr(R.string.music_beyond_your_phone),
            listOf(
                tr(R.string.play_on_speakers_tvs_and_chromecasts),
                tr(R.string.control_vinz_from_a_browser_on),
                tr(R.string.hand_what_s_playing_to_another),
            ),
            listOf(VinzIcons.Tv, VinzIcons.Handoff),
            showMe = Routes.settings("connect"),
        ),
        TourStep(
            V1_7, VinzIcons.Palette, tr(R.string.make_it_yours),
            tr(R.string.vinz_takes_its_color_from_what),
            listOf(
                tr(R.string.appearance_the_spectrum_s_colors_or),
                tr(R.string.layout_the_tab_bar_your_tabs),
                tr(R.string.player_its_background_the_cover_and),
                tr(R.string.search_settings_from_the_box_at),
            ),
            listOf(VinzIcons.Customize, VinzIcons.Settings),
            showMe = Routes.settings("appearance"),
        ),
        TourStep(
            V1_7_2, VinzIcons.Palette, tr(R.string.color_your_way),
            tr(R.string.settings_customize_vinz_color_every_choice),
            listOf(
                tr(R.string.the_light_12_presets_white_black),
                tr(R.string.your_accent_any_color_from_a),
                tr(R.string.the_page_background_plain_tinted_with),
                tr(R.string.each_group_says_everything_it_changes),
            ),
            listOf(VinzIcons.Customize),
            showMe = Routes.settings("color"),
        ),
        TourStep(
            V1_7_2, VinzIcons.LibraryFilled, tr(R.string.also_new),
            tr(R.string.smaller_things_that_make_vinz_yours),
            listOf(
                tr(R.string.hold_vinz_s_icon_for_search),
                tr(R.string.library_pick_its_tiles_or_put),
                tr(R.string.pin_vinz_playlists_to_home_customize),
                tr(R.string.song_credits_in_any_song_s),
                tr(R.string.see_which_downloads_failed_and_why),
            ),
            listOf(VinzIcons.Edit),
        ),
        TourStep(
            V1_7_3, VinzIcons.Looks, tr(R.string.looks),
            tr(R.string.settings_customize_vinz_looks_your_whole),
            listOf(
                tr(R.string.try_vinz_s_own_paper_midnight),
                tr(R.string.save_yours_update_it_when_you),
                tr(R.string.browse_looks_people_shared_under_from),
                tr(R.string.one_look_by_day_and_another),
                tr(R.string.every_look_page_shows_home_the),
                tr(R.string.customize_by_tapping_hold_the_settings),
            ),
            listOf(VinzIcons.Sun, VinzIcons.Moon, VinzIcons.Undo),
            showMe = Routes.settings("looks"),
        ),
        TourStep(
            V1_7_4, VinzIcons.PlayCircle, tr(R.string.the_player_your_way),
            tr(R.string.settings_customize_vinz_player_or_player),
            listOf(
                tr(R.string.layouts_standard_a_big_cover_lyrics),
                tr(R.string.a_visualizer_if_you_d_like),
                tr(R.string.pick_the_buttons_under_the_controls),
                tr(R.string.put_the_lyric_line_progress_bar),
                tr(R.string.a_waveform_progress_bar_its_color),
                tr(R.string.choose_what_swipes_a_double_tap),
            ),
            listOf(VinzIcons.Turntable, VinzIcons.Customize),
            showMe = Routes.settings("player"),
        ),
        TourStep(
            V1_7_4, VinzIcons.Speed, tr(R.string.faster),
            tr(R.string.vinz_scrolls_searches_and_opens_the),
            listOf(
                tr(R.string.vinz_tells_android_which_of_its),
                tr(R.string.less_work_while_lists_scroll_while),
                tr(R.string.if_something_still_stutters_settings_backup),
            ),
        ),
        TourStep(
            V1_7_5, VinzIcons.Lyrics, tr(R.string.lyrics_your_way),
            tr(R.string.settings_customize_vinz_lyrics_how_your),
            listOf(
                tr(R.string.six_styles_vinz_classic_karaoke_bold),
                tr(R.string.the_sung_line_and_the_others),
                tr(R.string.weight_line_spacing_how_dim_the),
                tr(R.string.the_sung_line_near_the_top),
                tr(R.string.translations_over_or_under_each_line),
                tr(R.string.a_background_of_their_own_plain),
            ),
            listOf(VinzIcons.Lyrics, VinzIcons.Palette),
            showMe = Routes.settings("lyrics_look"),
        ),
        TourStep(
            V1_7_5, VinzIcons.History, tr(R.string.also_new),
            tr(R.string.a_few_things_you_asked_for),
            listOf(
                tr(R.string.library_history_what_you_played_by),
                tr(R.string.liked_a_song_and_its_video),
                tr(R.string.vinz_s_own_volume_apart_from),
                tr(R.string.hide_the_status_bar_customize_vinz),
                tr(R.string.settings_pages_are_tidier_tap_beside),
                tr(R.string.library_s_tiles_the_edit_button),
            ),
            listOf(VinzIcons.History, VinzIcons.HeartFilled),
        ),
        TourStep(
            V1_7_6, VinzIcons.Home, tr(R.string.home_your_way),
            tr(R.string.customize_home_the_button_at_the),
            listOf(
                tr(R.string.hold_any_album_playlist_artist_or),
                tr(R.string.sections_from_your_own_listening_liked),
                tr(R.string.rename_any_section_leave_youtube_music),
                tr(R.string.the_top_of_home_the_greeting),
                tr(R.string.home_shows_at_once_when_vinz),
            ),
            listOf(VinzIcons.Home, VinzIcons.Pin),
            showMe = Routes.CATALOGUE,
        ),
        TourStep(
            V1_7_6, VinzIcons.Library, tr(R.string.library_and_tabs_your_way),
            tr(R.string.folders_pins_and_tabs_of_your),
            listOf(
                tr(R.string.hold_a_playlist_move_to_a),
                tr(R.string.playlists_albums_and_artists_each_a),
                tr(R.string.make_a_cover_for_a_playlist),
                tr(R.string.keep_youtube_playlists_and_copy_them),
                tr(R.string.tabs_downloads_liked_songs_or_podcasts),
            ),
            listOf(VinzIcons.Folder, VinzIcons.Library),
            showMe = Routes.settings("interface"),
        ),
        TourStep(
            V1_7_6, VinzIcons.Search, tr(R.string.search_redesigned),
            tr(R.string.search_starts_from_your_music),
            listOf(
                tr(R.string.type_a_line_of_a_song),
                tr(R.string.what_you_opened_lately_your_artists),
                tr(R.string.artists_and_songs_with_their_pictures),
                tr(R.string.results_already_in_your_library_right),
            ),
            listOf(VinzIcons.Search, VinzIcons.Recognize),
            showMe = Routes.SEARCH,
        ),
        TourStep(
            V1_7_6, VinzIcons.Settings, tr(R.string.settings_easier_to_find),
            tr(R.string.settings_are_sorted_by_what_they),
            listOf(
                tr(R.string.everything_about_how_vinz_looks_is),
                tr(R.string.gestures_and_buttons_in_controls_gestures),
                tr(R.string.every_mobile_data_choice_on_one),
                tr(R.string.android_auto_speakers_discord_and_last),
                tr(R.string.performance_mode_at_the_top_for),
            ),
            listOf(VinzIcons.Settings, VinzIcons.Speed),
            showMe = Routes.SETTINGS,
        ),
        TourStep(
            V1_7_7, VinzIcons.Category, tr(R.string.shapes_icons_and_type),
            tr(R.string.make_the_pieces_of_vinz_yours),
            listOf(
                tr(R.string.covers_as_circles_squircles_flowers_arches),
                tr(R.string.corners_on_a_slider_and_pill),
                tr(R.string.icons_in_outline_duotone_or_filled),
                tr(R.string.bolder_or_lighter_text_wider_spacing),
                tr(R.string.each_home_screen_widget_with_its),
            ),
            listOf(VinzIcons.Category, VinzIcons.Signature),
            showMe = Routes.settings("icons"),
        ),
        TourStep(
            V1_7_7, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.rearrange_your_playlists_songs_rearrange_songs),
                tr(R.string.swipe_a_song_out_of_the),
                tr(R.string.the_light_from_the_playing_cover),
                tr(R.string.ribbons_a_progress_bar_with_strands),
                tr(R.string.a_bold_title_on_the_player),
            ),
            listOf(VinzIcons.SwapVertical, VinzIcons.Palette),
        ),
        TourStep(
            V1_7_8, VinzIcons.Equalizer, tr(R.string.sound_your_way),
            tr(R.string.settings_playback_sound),
            listOf(
                tr(R.string.speed_pitch_slowed_sped_up_or),
                tr(R.string.how_songs_cross_in_a_crossfade),
                tr(R.string.normalize_volume_s_level_and_albums),
                tr(R.string.your_own_equalizer_presets_shared_as),
                tr(R.string.fades_for_the_sleep_timer_and),
            ),
            listOf(VinzIcons.Speed, VinzIcons.Equalizer),
            showMe = Routes.settings("sound"),
        ),
        TourStep(
            V1_7_8, VinzIcons.Looks, tr(R.string.classic_vinz),
            tr(R.string.liked_how_vinz_1_6_looked),
            listOf(
                tr(R.string.the_classic_look_customize_vinz_looks),
                tr(R.string.or_only_its_icons_its_bold),
                tr(R.string.the_player_s_buttons_apart_instead),
            ),
            listOf(VinzIcons.Looks, VinzIcons.Category),
            showMe = Routes.settings("looks"),
        ),
        TourStep(
            V1_7_8, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.last_fm_scrobbling_devices_connections_and),
                tr(R.string.pick_several_songs_in_the_queue),
                tr(R.string.choose_what_goes_in_a_backup),
                tr(R.string.swipe_up_on_the_player_for),
                tr(R.string.lyrics_into_your_m4a_files_and),
            ),
            listOf(VinzIcons.Queue, VinzIcons.Calendar),
        ),
        TourStep(
            V1_7_9, VinzIcons.Globe, tr(R.string.tour_179_language),
            tr(R.string.tour_179_language_body),
            listOf(tr(R.string.tour_179_language_youtube)),
            showMe = Routes.settings("language"),
        ),
        TourStep(
            V1_7_9, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_179_versions),
                tr(R.string.tour_179_playlists),
                tr(R.string.search_in_local_files),
                tr(R.string.tour_179_lyrics_button, tr(R.string.sing)),
                tr(R.string.tour_179_widget),
            ),
            listOf(VinzIcons.Lyrics, VinzIcons.Palette),
        ),
        TourStep(
            V1_7_9_2, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_1792_search_chips),
                tr(R.string.tour_1792_small_player),
                tr(R.string.tour_1792_slide),
                tr(R.string.tour_1792_gestures),
                tr(R.string.tour_1792_colors),
            ),
            listOf(VinzIcons.Search, VinzIcons.Palette),
        ),
        TourStep(
            V1_8_0, VinzIcons.Tv, tr(R.string.tour_180_tv),
            tr(R.string.tour_180_tv_body),
            listOf(
                tr(R.string.tour_180_tv_phone),
                tr(R.string.tour_180_tv_get, com.vinz.music.connect.TvInstall.DOWNLOADER_CODE),
                tr(R.string.tour_180_tv_big),
            ),
            listOf(VinzIcons.Tv, VinzIcons.Handoff),
            showMe = Routes.settings("connect"),
        ),
        TourStep(
            V1_8_0, VinzIcons.Settings, tr(R.string.tour_180_settings),
            tr(R.string.tour_180_settings_body),
            listOf(
                tr(R.string.tour_180_settings_merged),
                tr(R.string.tour_180_settings_search),
                tr(R.string.tour_180_settings_tablet),
            ),
            listOf(VinzIcons.Settings, VinzIcons.Search),
            showMe = Routes.settings("sound"),
        ),
        TourStep(
            V1_8_0, VinzIcons.PlayCircle, tr(R.string.tour_180_player),
            tr(R.string.tour_180_player_body),
            listOf(tr(R.string.tour_180_player_parts), tr(R.string.tour_180_player_menu)),
            listOf(VinzIcons.PlayCircle, VinzIcons.Edit),
            showMe = Routes.settings("player"),
        ),
        TourStep(
            V1_8_0, VinzIcons.Lyrics, tr(R.string.tour_180_lyrics),
            tr(R.string.tour_180_lyrics_body),
            listOf(tr(R.string.tour_180_lyrics_start), tr(R.string.tour_180_lyrics_smooth)),
            listOf(VinzIcons.Lyrics, VinzIcons.Palette),
            showMe = Routes.settings("lyrics_look"),
        ),
        TourStep(
            V1_8_0, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_180_desk),
                tr(R.string.tour_180_crossfade),
                tr(R.string.tour_180_silence),
                tr(R.string.tour_180_normalize),
                tr(R.string.tour_180_size),
            ),
            listOf(VinzIcons.Moon, VinzIcons.Equalizer),
        ),
        TourStep(
            V1_8_1, VinzIcons.History, tr(R.string.tour_181_history),
            tr(R.string.tour_181_history_body),
            listOf(
                tr(R.string.tour_181_history_spotify),
                tr(R.string.tour_181_history_others),
                tr(R.string.tour_181_history_replay),
            ),
            listOf(VinzIcons.Download, VinzIcons.Stats),
            showMe = Routes.BRING_HISTORY,
        ),
        TourStep(
            V1_8_1, VinzIcons.Library, tr(R.string.tour_181_spotify),
            tr(R.string.tour_181_spotify_body),
            listOf(
                tr(R.string.tour_181_spotify_lists),
                tr(R.string.tour_181_spotify_plays),
                tr(R.string.tour_181_spotify_likes),
            ),
            listOf(VinzIcons.Sync, VinzIcons.HeartFilled),
            showMe = Routes.settings("spotify"),
        ),
        TourStep(
            // Opens Recommendations & Replay at its History group (pause, what counts as a play).
            V1_8_1, VinzIcons.Stats, tr(R.string.tour_181_replay),
            tr(R.string.tour_181_replay_body),
            listOf(
                tr(R.string.tour_181_replay_pause),
                tr(R.string.tour_181_replay_counts),
                tr(R.string.tour_181_replay_graphs),
            ),
            listOf(VinzIcons.Pause, VinzIcons.History),
            showMe = Routes.settings("library", find = tr(R.string.hist_title)),
        ),
        TourStep(
            V1_8_1, VinzIcons.Palette, tr(R.string.tour_181_yours),
            tr(R.string.tour_181_yours_body),
            listOf(
                tr(R.string.tour_181_yours_cover),
                tr(R.string.tour_181_yours_explicit),
                tr(R.string.tour_181_yours_white),
                tr(R.string.tour_181_yours_fonts),
                tr(R.string.tour_181_yours_artists),
            ),
            listOf(VinzIcons.Photo, VinzIcons.Signature),
            showMe = Routes.settings("appearance"),
        ),
        TourStep(
            V1_8_1, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_181_files),
                tr(R.string.tour_181_crossfade),
                tr(R.string.tour_181_auto),
                tr(R.string.tour_181_tv),
            ),
            listOf(VinzIcons.Car, VinzIcons.Tv),
        ),
        TourStep(
            V1_8_2, VinzIcons.Lock, tr(R.string.tour_182_lock_title),
            tr(R.string.tour_182_lock_body),
            listOf(tr(R.string.tour_182_lock_point_1), tr(R.string.tour_182_lock_point_2), tr(R.string.tour_182_lock_point_3)),
            showMe = Routes.settings("player", find = tr(R.string.lock_screen_title)),
        ),
        TourStep(
            V1_8_2, VinzIcons.Radio, tr(R.string.tour_182_discover_title),
            tr(R.string.tour_182_discover_body),
            listOf(
                tr(R.string.tour_182_discover_new),
                tr(R.string.tour_182_discover_deep),
                tr(R.string.tour_182_discover_day),
                tr(R.string.tour_182_discover_tune),
            ),
            showMe = Routes.settings("library", find = tr(R.string.tune_radios)),
        ),
        TourStep(
            V1_8_2, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_182_also_shows),
                tr(R.string.tour_182_also_offline),
                tr(R.string.tour_182_also_pages),
                tr(R.string.tour_182_also_files),
                tr(R.string.tour_182_also_crossfade),
            ),
        ),
        TourStep(
            V1_8_3, VinzIcons.SmartShuffle, tr(R.string.tour_183_smart_title),
            tr(R.string.tour_183_smart_body),
            listOf(tr(R.string.tour_183_smart_point_1), tr(R.string.tour_183_smart_point_2), tr(R.string.tour_183_smart_point_3)),
            showMe = Routes.LIBRARY,
        ),
        TourStep(
            V1_8_3, VinzIcons.Tag, tr(R.string.tour_183_tags_title),
            tr(R.string.tour_183_tags_body),
            listOf(tr(R.string.tour_183_tags_point_1), tr(R.string.tour_183_tags_point_2), tr(R.string.tour_183_tags_point_3)),
        ),
        TourStep(
            V1_8_3, VinzIcons.CheckCircle, tr(R.string.tour_183_select_title),
            tr(R.string.tour_183_select_body),
            listOf(tr(R.string.tour_183_select_point_1), tr(R.string.tour_183_select_point_2)),
        ),
        TourStep(
            V1_8_3, VinzIcons.Search, tr(R.string.tour_183_search_title),
            tr(R.string.tour_183_search_body),
            listOf(tr(R.string.tour_183_search_point_1), tr(R.string.tour_183_search_point_2), tr(R.string.tour_183_search_point_3)),
            showMe = Routes.librarySearch(),
        ),
        TourStep(
            V1_8_3, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_183_also_file),
                tr(R.string.tour_183_also_duplicates),
                tr(R.string.tour_183_also_songs_from),
                tr(R.string.tour_183_also_lock),
                tr(R.string.tour_183_also_uploads),
            ),
        ),
        TourStep(
            // Opens Settings' new first page (plan §5).
            V1_8_4, VinzIcons.Settings, tr(R.string.tour_184_settings_title),
            tr(R.string.tour_184_settings_body),
            listOf(
                tr(R.string.tour_184_settings_point_1),
                tr(R.string.tour_184_settings_point_2),
                tr(R.string.tour_184_settings_point_3),
                tr(R.string.tour_184_settings_point_4),
                tr(R.string.tour_184_settings_point_5),
            ),
            listOf(VinzIcons.Search, VinzIcons.Speed),
            showMe = Routes.SETTINGS,
        ),
        TourStep(
            // #404, with #392 and the car's Long lines. Opens Lyrics & Sing at the group with "Lyrics earlier / later".
            V1_8_4, VinzIcons.Lyrics, tr(R.string.tour_184_lyrics_title),
            tr(R.string.tour_184_lyrics_body),
            listOf(tr(R.string.tour_184_lyrics_point_1), tr(R.string.tour_184_lyrics_point_2), tr(R.string.tour_184_lyrics_point_3)),
            listOf(VinzIcons.Timer, VinzIcons.Car),
            showMe = Routes.settings(SettingsPageId.LYRICS_SING.key, find = tr(R.string.ls_sources_and_timing)),
        ),
        TourStep(
            // D53. Opens Player at Modes, where the lock screen and its options are.
            V1_8_4, VinzIcons.Lock, tr(R.string.tour_184_lock_title),
            tr(R.string.tour_184_lock_body),
            listOf(
                tr(R.string.tour_184_lock_point_1, tr(R.string.settings_player_same_look), tr(R.string.settings_player_lock_options)),
                tr(R.string.tour_184_lock_point_2),
                tr(R.string.tour_184_lock_point_3),
            ),
            listOf(VinzIcons.PlayCircle, VinzIcons.HeartFilled),
            showMe = Routes.settings(SettingsPageId.PLAYER.key, find = tr(R.string.settings_player_modes)),
        ),
        TourStep(
            // D54, D55, D56 and #406. Opens Home & Library at Lists, with "Button to find the playing song".
            V1_8_4, VinzIcons.QueueMusic, tr(R.string.tour_184_lists_title),
            tr(R.string.tour_184_lists_body),
            listOf(
                tr(R.string.tour_184_lists_point_1, tr(R.string.go_to_the_playing_song)),
                tr(R.string.tour_184_lists_point_2, tr(R.string.hl_locate_button)),
                tr(R.string.tour_184_lists_point_3),
                tr(R.string.tour_184_lists_point_4),
                tr(R.string.tour_184_lists_point_5),
            ),
            listOf(VinzIcons.Album, VinzIcons.Library),
            showMe = Routes.settings(SettingsPageId.HOME_LIBRARY.key, find = tr(R.string.hl_lists)),
        ),
        TourStep(
            // D57 + #416, D58/D59, #413, and the fixes #432 and #429.
            V1_8_4, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_184_also_shuffle),
                tr(R.string.tour_184_also_replay),
                tr(R.string.tour_184_also_color),
                tr(R.string.tour_184_also_crossfade),
                tr(R.string.tour_184_also_glass),
            ),
            listOf(VinzIcons.Shuffle, VinzIcons.Stats),
        ),
        TourStep(
            // The listening clock, the calendar, milestones and the monthly recap. Opens Replay.
            V1_8_5, VinzIcons.Stats, tr(R.string.tour_185_replay_title),
            tr(R.string.tour_185_replay_body),
            listOf(
                tr(R.string.tour_185_replay_point_1),
                tr(R.string.tour_185_replay_point_2),
                tr(R.string.tour_185_replay_point_3),
                tr(R.string.tour_185_replay_point_4),
            ),
            listOf(VinzIcons.Timer, VinzIcons.StarFilled),
            showMe = Routes.REPLAY,
        ),
        TourStep(
            V1_8_5, VinzIcons.ReplayFilled, tr(R.string.tour_185_rewind_title),
            tr(R.string.tour_185_rewind_body),
            listOf(tr(R.string.tour_185_rewind_point_1), tr(R.string.tour_185_rewind_point_2), tr(R.string.tour_185_rewind_point_3)),
        ),
        TourStep(
            // The widgets, the two notices and Finish the lyric. Opens Recommendations & history at Replay, where the switches are.
            V1_8_5, VinzIcons.Update, tr(R.string.tour_185_more_title),
            tr(R.string.tour_185_more_body),
            listOf(
                tr(R.string.tour_185_more_point_1),
                tr(R.string.tour_185_more_point_2, tr(R.string.replay_notices)),
                tr(R.string.tour_185_more_point_3, tr(R.string.finish_lyric_title)),
            ),
            listOf(VinzIcons.Lyrics, VinzIcons.Home),
            showMe = Routes.settings(SettingsPageId.RECOMMENDATIONS_HISTORY.key, find = tr(R.string.replay_2)),
        ),
        TourStep(
            // #469, #477, face-down pause, #374, #481 and Open with Vinz.
            V1_8_5, VinzIcons.QueueMusic, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_185_also_history),
                tr(R.string.tour_185_also_search),
                tr(R.string.tour_185_also_face_down, tr(R.string.playback_face_down_pause)),
                tr(R.string.tour_185_also_dj),
                tr(R.string.tour_185_also_open_with),
            ),
            listOf(VinzIcons.Search, VinzIcons.Shuffle),
        ),
        TourStep(
            // 1.8.6 Canvas. Opens the Player page at Cover, the group the Canvas row is in.
            V1_8_6, VinzIcons.AnimatedCover, tr(R.string.tour_186_canvas_title),
            tr(R.string.tour_186_canvas_about),
            listOf(
                tr(R.string.tour_186_canvas_point_1),
                // Owner, 2026-11-13: Canvas (the loops from music videos) and Animated covers are two settings.
                tr(R.string.tour_186_canvas_two, tr(R.string.canvas), tr(R.string.animated_covers)),
                tr(R.string.tour_186_canvas_point_3, tr(R.string.canvas_pick_video), tr(R.string.canvas_pick_part)),
                tr(R.string.tour_186_canvas_point_4),
            ),
            listOf(VinzIcons.Video, VinzIcons.Fullscreen),
            showMe = Routes.settings(SettingsPageId.PLAYER.key, find = tr(R.string.cover)),
        ),
        TourStep(
            // The hours on Replay's "Hours by month" bars (the owner's ask, 2026-11-13).
            V1_8_6, VinzIcons.Stats, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(tr(R.string.tour_186_also_hours, tr(R.string.hours_by_month))),
            showMe = Routes.REPLAY,
        ),
        TourStep(
            // 1.8.7 It just plays. Opens Backup & about at Diagnostics, where "Is playback working?" is.
            V1_8_7, VinzIcons.Play, tr(R.string.tour_187_plays_title),
            tr(R.string.tour_187_plays_about),
            listOf(
                tr(R.string.tour_187_plays_point_1),
                tr(R.string.tour_187_plays_point_2),
                tr(R.string.tour_187_plays_point_3),
                tr(R.string.tour_187_plays_point_4, tr(R.string.selftest_title), tr(R.string.diagnostics)),
            ),
            listOf(VinzIcons.Check, VinzIcons.Info),
            showMe = Routes.settings(SettingsPageId.BACKUP_ABOUT.key, find = tr(R.string.diagnostics)),
        ),
        TourStep(
            // Opens the Player page at Cover: "Keep lyrics showing" is in the Canvas row's sheet there.
            V1_8_7, VinzIcons.Lyrics, tr(R.string.also_new),
            tr(R.string.tour_187_also_about),
            listOf(
                tr(R.string.tour_187_also_lyrics_line, tr(R.string.canvas_lyrics)),
                tr(R.string.tour_187_also_lyrics_right),
                tr(R.string.tour_187_also_blend, tr(R.string.crossfade)),
                tr(R.string.tour_187_also_lighter),
            ),
            listOf(VinzIcons.Shuffle, VinzIcons.Speed),
            showMe = Routes.settings(SettingsPageId.PLAYER.key, find = tr(R.string.cover)),
        ),
        TourStep(
            // 1.8.7.5 Your Spotify. Opens Accounts & sharing at Spotify, where the Connect sheet's two cards are.
            V1_8_7_5, VinzIcons.Link, tr(R.string.tour_1875_spotify_title),
            tr(R.string.tour_1875_spotify_about),
            listOf(
                tr(R.string.tour_1875_spotify_point_1),
                tr(R.string.tour_1875_spotify_point_2),
                tr(R.string.tour_1875_spotify_point_3),
                tr(R.string.tour_1875_spotify_point_4),
            ),
            listOf(VinzIcons.Heart, VinzIcons.QueueMusic),
            showMe = Routes.settings(SettingsPageId.ACCOUNTS_SHARING.key, find = tr(R.string.spotify)),
        ),
        TourStep(
            V1_8_7_5, VinzIcons.Check, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_1875_also_queue),
                tr(R.string.tour_1875_also_home),
                tr(R.string.tour_1875_also_busy),
                tr(R.string.tour_1875_also_heard),
            ),
        ),
        TourStep(
            V1_8_7_6, VinzIcons.PlayCircle, tr(R.string.tour_1876_canvas_title),
            tr(R.string.tour_1876_canvas_about),
            listOf(
                tr(R.string.tour_1876_canvas_point_1),
                tr(R.string.tour_1876_canvas_point_2),
                tr(R.string.tour_1876_canvas_point_3),
            ),
            // The Canvas switch and "Spotify's canvas first" under it are in the Player page's Cover group.
            showMe = Routes.settings(SettingsPageId.PLAYER.key, find = tr(R.string.cover)),
        ),
        TourStep(
            V1_8_7_6, VinzIcons.Check, tr(R.string.also_new),
            tr(R.string.from_your_requests),
            listOf(
                tr(R.string.tour_1876_also_loop),
                tr(R.string.tour_1876_also_eq),
                tr(R.string.tour_1876_also_kids),
                tr(R.string.tour_1876_also_cast),
                tr(R.string.tour_1876_also_history),
            ),
            // The release's last card carries the one line about supporting Vinz (plan-1.8.7.6 §4.1 S6).
            support = true,
        ),
        TourStep(
            V1_7, VinzIcons.Update, tr(R.string.stay_in_the_loop),
            tr(R.string.let_vinz_tell_you_when_something),
            listOf(
                tr(R.string.download_progress),
                tr(R.string.new_releases_from_artists_you_star),
                tr(R.string.your_rewind_ready_on_december_1),
            ),
            kind = TourStep.Kind.NOTIFICATIONS,
        ),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("tour", Context.MODE_PRIVATE)

    /** The versionCode of the last tour finished or skipped; 0 for none (a new install, or one from before tours). */
    fun seenVersion(context: Context): Int = prefs(context).getInt("seen_version", 0)

    /** The tour is done for this version: later only newer steps are shown. */
    fun markSeen(context: Context) = prefs(context).edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply()

    /** The steps someone who last saw the tour at [seen] hasn't seen: all of them for 0. */
    fun stepsAfter(seen: Int): List<TourStep> = steps.filter { it.since > seen }

    /** Whether it's the whole tour (never seen one) or only what's new since the last one. */
    fun isFullTour(seen: Int) = seen == 0
}
