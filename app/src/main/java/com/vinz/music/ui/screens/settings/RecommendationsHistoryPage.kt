package com.vinz.music.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.vinz.music.R
import com.vinz.music.data.HistoryPause
import com.vinz.music.data.PlayCount
import com.vinz.music.data.imports.CountSources
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.spotify.SpotifyStore
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingsSection
import com.vinz.music.ui.screens.StoryTypeSample
import com.vinz.music.ui.screens.pausedUntilText
import com.vinz.music.ui.screens.worded
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch
import kotlin.math.abs

private val page = SettingsPageId.RECOMMENDATIONS_HISTORY

/** A radio slider's place in words: "As it comes" in the middle, else the end it leans to and how far ("New · 50%"). */
private fun tuneText(v: Float, low: String, high: String): String = when {
    abs(v) < 0.05f -> tr(R.string.tune_as_it_comes)
    v < 0 -> worded(low, -v)
    else -> worded(high, v)
}

/** Close to the middle is the middle: "as it comes" has to be easy to get back to. */
private fun tuneSnap(v: Float) = if (abs(v) < 0.05f) 0f else v

/** "Off", or "Paused until 3:05 PM": what the Pause history row says. */
private fun pauseText(until: Long): String = if (HistoryPause.isPaused(until)) tr(R.string.hist_paused_line, pausedUntilText(until)) else tr(R.string.off)

/** What Replay counts, in words: Vinz's own plays always, then each history that is switched on ("Plays in Vinz · Spotify"). */
private fun countedText(sources: Set<ImportSource>): String =
    (listOf(tr(R.string.imp_vinz_plays)) + ImportSource.entries.filter { it in sources }.map { it.label }).joinToString(" · ")

/**
 * Settings → Recommendations & history (1.8.4's map, plan §2.3), built with the kit: what shapes your mixes, the three
 * radio sliders, history with its pause and what counts as a play, bringing a history in, and Replay. This file is the
 * only place for the page: its composable, its lines in Settings' search ([recommendationsHistoryIndex]), its "Reset
 * this page" keys ([recommendationsHistoryLookKeys]), its line on the root ([recommendationsHistoryValue]) and the old
 * group names it answers to ([recommendationsHistoryOldGroups]).
 *
 * Your artists, Exclusions and Bring your history are their own screens, opened from Link rows. "A song and its video
 * as one" is Home & Library's since 1.8.4. Nothing here is part of a Look, so "Reset this page" puts each row back
 * through its own setter ([ListeningResets]).
 */
@Composable
fun RecommendationsHistoryPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val b = s.behavior
    val tune = b.radioTune
    val tuneAsItComes = d.behavior.radioTune
    val storyType: (Boolean) -> String = { if (it) tr(R.string.replay_story_font_own) else tr(R.string.replay_story_font_poster) }
    val countAfter = b.playCountSeconds.takeIf { it in PlayCount.CHOICES } ?: PlayCount.DEFAULT_SEC
    val pausedUntil = b.historyPausedUntil.takeIf { HistoryPause.isPaused(it) } ?: 0L

    // 1.8.1: histories from Spotify, YouTube Music (Takeout) and Last.fm, and the live ones.
    val imported by c.imports.summaries.collectAsState(emptyList())
    val teach by c.imports.teach.collectAsState()
    val counted by c.imports.counted.collectAsState()
    val ytLive by c.imports.youtubeLive.on.collectAsState()
    // Spotify's connection is only built when it's connected (most never connect; see Spotify.pageOpened).
    val spotifyConnected = remember { SpotifyStore.connectedIn(context.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)) }
    val spotifyLive = if (spotifyConnected) c.spotify.options.collectAsState().value.recent else false
    val sources = CountSources.rows(
        imported,
        buildSet {
            if (ytLive) add(ImportSource.YOUTUBE_LIVE)
            if (spotifyLive) add(ImportSource.SPOTIFY_LIVE)
        },
        counted,
    )
    val countedNow = sources.filter { it.counted }.map { it.source }.toSet()

    // What differs from how Vinz comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val releasesChanged = reset.add(tr(R.string.new_release_notifications), s.releaseNotifications, d.releaseNotifications, onOff) { prefs.setReleaseNotifications(it) }
    // The three sliders are stored together: any one's reset writes all three as they will be once all are back.
    val newnessChanged = reset.add(tr(R.string.tune_familiar_new), tune.newness, tuneAsItComes.newness, { tuneText(it, tr(R.string.tune_familiar), tr(R.string.tune_new)) }) { prefs.setRadioTune(tuneAsItComes) }
    val depthChanged = reset.add(tr(R.string.tune_hits_deep), tune.depth, tuneAsItComes.depth, { tuneText(it, tr(R.string.tune_hits), tr(R.string.tune_deep)) }) { prefs.setRadioTune(tuneAsItComes) }
    val energyChanged = reset.add(tr(R.string.tune_calm_energetic), tune.energy, tuneAsItComes.energy, { tuneText(it, tr(R.string.tune_calm), tr(R.string.tune_energetic)) }) { prefs.setRadioTune(tuneAsItComes) }
    val pauseChanged = reset.add(tr(R.string.hist_pause), pausedUntil, 0L, ::pauseText) { prefs.setHistoryPausedUntil(it) }
    val countAfterChanged = reset.add(tr(R.string.hist_count_after), countAfter, d.behavior.playCountSeconds, PlayCount::label) { prefs.setPlayCountSeconds(it) }
    val accountChanged = reset.add(tr(R.string.hist_count_account), b.playCountOnAccount, d.behavior.playCountOnAccount, onOff) { prefs.setPlayCountOnAccount(it) }
    val hoursChanged = reset.add(tr(R.string.show_time_in_hours), s.replayHours, d.replayHours, onOff) { prefs.setReplayHours(it) }
    val themeColorsChanged = reset.add(tr(R.string.use_theme_colors), s.replayThemeColors, d.replayThemeColors, onOff) { prefs.setReplayThemeColors(it) }
    val storyTypeChanged = reset.add(tr(R.string.replay_story_font), s.view.replayStoryFont, d.view.replayStoryFont, storyType) { prefs.setReplayStoryFont(it) }
    val rewindChanged = reset.add(tr(R.string.rewind_notification), s.rewindNotification, d.rewindNotification, onOff) { prefs.setRewindNotification(it) }
    // 1.8.5: the monthly recap's notice, the milestone notice and "Finish the lyric", all off as Vinz comes.
    val recapChanged = reset.add(tr(R.string.recap_notification), b.recapNotify, d.behavior.recapNotify, onOff) { prefs.setRecapNotify(it) }
    val milestoneChanged = reset.add(tr(R.string.milestone_notification), b.milestoneNotify, d.behavior.milestoneNotify, onOff) { prefs.setMilestoneNotify(it) }
    val finishLyricChanged = reset.add(tr(R.string.finish_lyric_title), b.finishLyric, d.behavior.finishLyric, onOff) { prefs.setFinishLyric(it) }
    // Replay as it comes counts Vinz's own plays only.
    val countedChanged = reset.add(tr(R.string.imp_count_history_from), countedNow, emptySet(), ::countedText) { countedNow.forEach { source -> c.imports.setCounted(source, false) } }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.made_for_you)) {
            LinkRow(tr(R.string.your_artists), info = tr(R.string.they_shape_your_mixes_and_release)) { nav.go(Routes.PICK_ARTISTS) }
            SwitchRow(
                tr(R.string.new_release_notifications), s.releaseNotifications,
                info = tr(R.string.when_a_starred_artist_puts_out), changed = releasesChanged,
            ) { prefs.setReleaseNotifications(it) }
            LinkRow(tr(R.string.make_my_mixes_again), value = tr(R.string.fresh_daily_mixes_now), action = true) {
                c.scope.launch { c.forYou.refresh(force = true) }
                Toast.makeText(context, tr(R.string.making_your_mixes), Toast.LENGTH_SHORT).show()
            }
            LinkRow(tr(R.string.exclusions), info = tr(R.string.artists_not_to_play_and_playlists)) { nav.go(Routes.EXCLUSIONS) }
            // Suggestions skipped at once stay out of mixes and radio for a while (1.7.9): this lets them all back.
            LinkRow(tr(R.string.forget_skipped_suggestions), value = tr(R.string.let_every_song_be_suggested_again), info = tr(R.string.forget_skipped_suggestions_info), action = true) {
                c.recFeedback.clear()
                Toast.makeText(context, tr(R.string.suggestions_start_fresh), Toast.LENGTH_SHORT).show()
            }
        }
        // 1.8.2: three sliders over every radio and autoplay, each with both its ends named; each goes by what the
        // phone really knows, and says so behind its ⓘ. Halfway to an end is a real step (RadioTuner).
        group(tr(R.string.tune_radios), info = tr(R.string.tune_radios_info)) {
            SliderRow(
                tr(R.string.tune_familiar_new), tune.newness, -1f..1f, { tuneText(it, tr(R.string.tune_familiar), tr(R.string.tune_new)) }, steps = 7,
                info = tr(R.string.tune_familiar_new_info), changed = newnessChanged,
            ) { prefs.setRadioTune(tune.copy(newness = tuneSnap(it))) }
            SliderRow(
                tr(R.string.tune_hits_deep), tune.depth, -1f..1f, { tuneText(it, tr(R.string.tune_hits), tr(R.string.tune_deep)) }, steps = 7,
                info = tr(R.string.tune_hits_deep_info), changed = depthChanged,
            ) { prefs.setRadioTune(tune.copy(depth = tuneSnap(it))) }
            // How much the last one has to go by is a figure: it closes the slider's ⓘ while the slider is in use.
            val measured = remember { c.mixTempo.measuredCount() }
            SliderRow(
                tr(R.string.tune_calm_energetic), tune.energy, -1f..1f, { tuneText(it, tr(R.string.tune_calm), tr(R.string.tune_energetic)) }, steps = 7,
                info = tr(R.string.tune_calm_energetic_info) + if (tune.energy != 0f) "\n" + tr(R.string.tune_measured_songs, measured) else "",
                changed = energyChanged,
            ) { prefs.setRadioTune(tune.copy(energy = tuneSnap(it))) }
        }
        // 1.8.1: pause history (#296), when a song counts as played (#263), and histories from elsewhere.
        group(tr(R.string.hist_title), info = tr(R.string.hist_info)) {
            // A list in a sheet: "Until I turn it back on" doesn't fit a chip beside three more, and the row says until when.
            // A timed pause picked again starts again from now (with 20 minutes left, "1 hour" is the current pick).
            val pauseChoice = HistoryPause.choiceOf(b.historyPausedUntil)
            PickListRow(
                tr(R.string.hist_pause), HistoryPause.Choice.entries, pauseChoice, { it.label },
                value = pauseText(b.historyPausedUntil), info = tr(R.string.hist_pause_info), changed = pauseChanged,
                onRepick = { prefs.setHistoryPausedUntil(HistoryPause.until(pauseChoice, System.currentTimeMillis())) },
            ) { prefs.setHistoryPausedUntil(HistoryPause.until(it, System.currentTimeMillis())) }
            ChoiceRow(
                tr(R.string.hist_count_after), PlayCount.CHOICES, countAfter, PlayCount::label,
                info = tr(R.string.hist_count_after_info), changed = countAfterChanged,
            ) { prefs.setPlayCountSeconds(it) }
            // Only the account's history waits for it, and only when there's an account.
            if (s.isLoggedIn) SwitchRow(
                tr(R.string.hist_count_account), b.playCountOnAccount,
                info = tr(R.string.hist_count_account_info), changed = accountChanged,
            ) { prefs.setPlayCountOnAccount(it) }
            if (imported.isNotEmpty()) SwitchRow(tr(R.string.imp_shape_mixes), teach, info = tr(R.string.imp_shape_mixes_info)) { c.imports.setTeach(it) }
            // 1.8.7.5: what Replay counts is a history choice, so it sits with the histories.
            RhCountHistoryFrom(sources, countedText(countedNow), countedChanged)
        }
        group(tr(R.string.replay_2)) {
            SwitchRow(
                tr(R.string.show_time_in_hours), s.replayHours,
                value = if (s.replayHours) tr(R.string.like_20h_4m) else tr(R.string.like_1_204_min), changed = hoursChanged,
            ) { prefs.setReplayHours(it) }
            SwitchRow(tr(R.string.use_theme_colors), s.replayThemeColors, info = tr(R.string.your_accent_instead_of_vinz_s), changed = themeColorsChanged) { prefs.setReplayThemeColors(it) }
            // #287: the stories' type, each shown as a slide would set it.
            PickCardsRow(
                tr(R.string.replay_story_font), listOf(false, true), s.view.replayStoryFont, storyType,
                info = tr(R.string.replay_story_font_info), changed = storyTypeChanged, onPick = { prefs.setReplayStoryFont(it) },
            ) { StoryTypeSample(it) }
            // 1.8.5: Replay's three notices are one row (the group stays within its seven); each has its switch in the sheet.
            RhReplayNotices(rewindChanged, recapChanged, milestoneChanged)
            SwitchRow(tr(R.string.finish_lyric_title), b.finishLyric, info = tr(R.string.finish_lyric_info), changed = finishLyricChanged) { prefs.setFinishLyric(it) }
        }
    }
}

/**
 * "Count history from" (1.8.1) as one row: it says what Replay counts (Vinz's plays always, then each history that is
 * switched on), and a tap opens every source in a sheet. One with plays here (or a live one that's on) has its switch;
 * the others lead to where they are brought in (Bring your history) or turned on (Spotify's page), and never count.
 * The sheet is the kit's [RowsSheet].
 */
@Composable
private fun RhCountHistoryFrom(sources: List<CountSources.Row>, value: String, changed: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val title = tr(R.string.imp_count_history_from)
    val count = remember { java.text.NumberFormat.getIntegerInstance() }
    var open by rememberSaveable(title, "count-history") { mutableStateOf(false) }
    PickRow(title, value, info = tr(R.string.imp_count_history_info), changed = changed) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        sources.forEach { row ->
            val source = row.source
            if (row.state == CountSources.State.COUNTABLE) SwitchRow(
                source.label, row.counted,
                value = if (row.plays > 0) tr(R.string.imp_n_plays, count.format(row.plays)) else tr(R.string.imp_live_no_plays),
            ) { c.imports.setCounted(source, it) }
            // Leaves for another screen: the sheet goes with it.
            else LinkRow(source.label, value = if (source.isLive) tr(R.string.off) else tr(R.string.imp_not_brought_in)) {
                open = false
                if (source == ImportSource.SPOTIFY_LIVE) nav.go(Routes.settings(SettingsSection.SPOTIFY.key)) else nav.go(Routes.BRING_HISTORY)
            }
        }
    }
}

/**
 * "Notifications" (1.8.5) as one row: Rewind's (1 December), the monthly recap's and the milestones', each a switch in
 * the row's sheet. The row names the ones on (1.8.7.6; it said "1 of 3 on" before).
 */
@Composable
private fun RhReplayNotices(rewindChanged: Boolean, recapChanged: Boolean, milestoneChanged: Boolean) {
    val c = LocalContainer.current
    val prefs = c.settings
    val s = LocalAppSettings.current
    val b = s.behavior
    val title = tr(R.string.replay_notices)
    // The ones on, by name ("Rewind · Monthly recap"), or Off.
    val on = listOfNotNull(
        if (s.rewindNotification) tr(R.string.rewind_3) else null,
        if (b.recapNotify) tr(R.string.replay_notice_recap_short) else null,
        if (b.milestoneNotify) tr(R.string.milestones) else null,
    )
    var open by rememberSaveable(title, "replay-notices") { mutableStateOf(false) }
    // A notice turned on asks for notifications (Android 13+) if Vinz may not post them yet: else nothing would come.
    fun asked(turnedOn: Boolean) { if (turnedOn) c.askNotifications() }
    PickRow(title, if (on.isEmpty()) tr(R.string.off) else on.joinToString(" · "), changed = rewindChanged || recapChanged || milestoneChanged) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        SwitchRow(tr(R.string.rewind_notification), s.rewindNotification, info = tr(R.string.on_december_1_when_your_year), changed = rewindChanged) { prefs.setRewindNotification(it); asked(it) }
        SwitchRow(tr(R.string.recap_notification), b.recapNotify, info = tr(R.string.recap_notification_info), changed = recapChanged) { prefs.setRecapNotify(it); asked(it) }
        SwitchRow(tr(R.string.milestone_notification), b.milestoneNotify, info = tr(R.string.milestone_notification_info), changed = milestoneChanged) { prefs.setMilestoneNotify(it); asked(it) }
    }
}

/** The root's line for Recommendations & history: only what's on the page itself, and "History paused" while it is. */
fun recommendationsHistoryValue(s: AppSettings, dark: Boolean): String = SettingsSection.LIBRARY.summary(s, dark)

/** The look settings "Reset this page" puts back: none (recommendations and history aren't part of a look; the page resets its rows itself). */
val recommendationsHistoryLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: none ("Made for you", "Tune your radios", "History" and "Replay" are still
 * called that). (The old "Liked songs" group is Home & Library's; "Bring your history" is Accounts & sharing's row since
 * 1.8.7.6, see [SettingsMap.movedGroups].)
 */
val recommendationsHistoryOldGroups: Map<String, String> = emptyMap()

/** The page's lines in Settings' search (each names the group it is drawn in). */
val recommendationsHistoryIndex: List<SettingEntry> by lazy {
    val made = tr(R.string.made_for_you)
    val radios = tr(R.string.tune_radios)
    val history = tr(R.string.hist_title)
    val replay = tr(R.string.replay_2)
    val radioWords = radios + " " + tr(R.string.autoplay)
    val signedIn = Needs(tr(R.string.hist_needs_account)) { it.isLoggedIn }
    listOf(
        // GitHub requests in 1.7.5 (#111 #115 #116).
        SettingEntry(page, made, tr(R.string.your_artists), tr(R.string.onboarding_pick_artists_favourites_starred_recom)),
        SettingEntry(page, made, tr(R.string.new_release_notifications), tr(R.string.release_radar_new_music_alerts)),
        SettingEntry(page, made, tr(R.string.make_my_mixes_again), tr(R.string.daily_mix_refresh_recommendations)),
        SettingEntry(page, made, tr(R.string.exclusions), tr(R.string.taste_profile_exclude_artist_playlist_don)),
        SettingEntry(page, made, tr(R.string.forget_skipped_suggestions), tr(R.string.skipped_suggestions_keywords) + " " + tr(R.string.let_every_song_be_suggested_again)),
        SettingEntry(page, radios, tr(R.string.tune_familiar_new), radioWords),
        SettingEntry(page, radios, tr(R.string.tune_hits_deep), radioWords),
        SettingEntry(page, radios, tr(R.string.tune_calm_energetic), radioWords),
        // 1.8.1: History (#296, #263).
        SettingEntry(page, history, tr(R.string.hist_pause), tr(R.string.hist_pause_keywords)),
        SettingEntry(page, history, tr(R.string.hist_count_after), tr(R.string.hist_count_keywords)),
        SettingEntry(page, history, tr(R.string.hist_count_account), tr(R.string.hist_count_keywords), needs = signedIn),
        // 1.8.1: Bring your history (its row is on Accounts & sharing since 1.8.7.6).
        SettingEntry(page, history, tr(R.string.imp_shape_mixes), tr(R.string.imp_shape_mixes_keywords)),
        SettingEntry(page, history, tr(R.string.imp_count_history_from), tr(R.string.imp_count_keywords) + " " + tr(R.string.imp_vinz_plays)),
        // On the Bring your history screen (opened from this group): the fresh-download reminder.
        SettingEntry(page, history, tr(R.string.imp_remind), tr(R.string.imp_remind_keywords)),
        // And YouTube Music (live): the account's History page every few hours (signed in).
        SettingEntry(page, history, tr(R.string.ytlive_title), tr(R.string.ytlive_keywords), needs = Needs(tr(R.string.ytlive_sign_in)) { it.isLoggedIn }),
        SettingEntry(page, replay, tr(R.string.show_time_in_hours), tr(R.string.stats_minutes)),
        SettingEntry(page, replay, tr(R.string.use_theme_colors), tr(R.string.colors_stats)),
        SettingEntry(page, replay, tr(R.string.replay_story_font), tr(R.string.replay_story_font_keywords)),
        // One row, three notices in its sheet: each name finds it.
        SettingEntry(
            page, replay, tr(R.string.replay_notices),
            listOf(R.string.rewind_notification, R.string.rewind_wrapped_year_in_review_december, R.string.recap_notification, R.string.milestone_notification, R.string.milestones).joinToString(" ") { tr(it) },
        ),
        // Each of the three, by its own name (drawn in that row's sheet).
        SettingEntry(page, replay, tr(R.string.rewind_notification), tr(R.string.rewind_wrapped_year_in_review_december)),
        SettingEntry(page, replay, tr(R.string.recap_notification), tr(R.string.recap_ready_text)),
        SettingEntry(page, replay, tr(R.string.milestone_notification), tr(R.string.milestones)),
        SettingEntry(page, replay, tr(R.string.finish_lyric_title), tr(R.string.finish_lyric_info)),
    )
}
