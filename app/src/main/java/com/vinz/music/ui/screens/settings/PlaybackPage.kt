package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.AutoplayLength
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.CrossfadeCurve
import com.vinz.music.data.prefs.ExplicitContent
import com.vinz.music.data.prefs.MAX_CROSSFADE_SECONDS
import com.vinz.music.data.prefs.ShuffleButton
import com.vinz.music.playback.MixRefusal
import com.vinz.music.playback.MixWhy
import com.vinz.music.tr
import com.vinz.music.ui.screens.Crossfade
import com.vinz.music.ui.screens.CurveSample
import com.vinz.music.ui.screens.QueueEnd
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingNeeds
import com.vinz.music.ui.screens.SoundChoices
import com.vinz.music.ui.screens.VideoPolicy
import com.vinz.music.ui.screens.pitchText
import com.vinz.music.ui.screens.speedText
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

private val page = SettingsPageId.PLAYBACK

/**
 * Settings → Playback (1.8.4's map, plan §2.3), built with the kit: what plays next, the Shuffle button, crossfade
 * with its curve, speed and pitch when Vinz starts, stopping and pausing with the two fades. This file is the only
 * place for the page: its composable, its lines in Settings' search ([playbackIndex]), its "Reset this page" keys
 * ([playbackLookKeys]), its line on the root ([playbackValue]) and the old group names it answers to
 * ([playbackOldGroups]).
 *
 * The choices that are one face over two stored settings (when the queue ends, music videos, crossfade) read and write
 * through [SoundChoices], as the page before 1.8.4 did: nothing stored changes. Nothing here is part of a Look, so
 * "Reset this page" puts each row back through its own setter.
 */
@Composable
fun PlaybackPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    // #493: why the last two songs didn't blend, when they didn't (until one does): a last line in Crossfade's ⓘ.
    val lastWhy = c.mixState.why.collectAsState().value

    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val seconds: (Int) -> String = { ListeningResets.seconds(it) }
    val queueEnd = SoundChoices.queueEnd(s)
    val videos = SoundChoices.videoPolicy(s)
    val crossfade = SoundChoices.crossfade(s)
    val minutes: (Int) -> String = { if (it <= 0) tr(R.string.autoplay_no_length_limit) else tr(R.string.min, it) }
    val fade: (Int) -> String = { if (it < 1) tr(R.string.off) else seconds(it) }

    // What differs from how Vinz comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val queueEndChanged = reset.add(tr(R.string.when_the_queue_ends), queueEnd, SoundChoices.queueEnd(d), { it.label }) { SoundChoices.setQueueEnd(prefs, it) }
    val noRepeatsChanged = reset.add(tr(R.string.no_repeats_title), s.behavior.autoplayNoRepeats, d.behavior.autoplayNoRepeats, onOff) { prefs.setAutoplayNoRepeats(it) }
    val longestChanged = reset.add(tr(R.string.longest_song_for_autoplay), s.behavior.autoplayMaxMinutes, d.behavior.autoplayMaxMinutes, minutes) { prefs.setAutoplayMaxMinutes(it) }
    val videosChanged = reset.add(tr(R.string.music_videos_label), videos, SoundChoices.videoPolicy(d), { it.label }) { SoundChoices.setVideoPolicy(prefs, it) }
    val keepVideosChanged = reset.add(tr(R.string.keep_playing_videos), s.videoContinues, d.videoContinues, onOff) { prefs.setVideoContinues(it) }
    val explicitChanged = reset.add(tr(R.string.explicit_songs), s.behavior.explicitContent, d.behavior.explicitContent, { it.label }) { prefs.setExplicitContent(it) }
    val shuffleButtonChanged = reset.add(tr(R.string.playback_shuffle_button), s.behavior.shuffleButton, d.behavior.shuffleButton, { it.label }) { prefs.setShuffleButton(it) }
    val keepShuffleChanged = reset.add(tr(R.string.playback_keep_shuffle), s.behavior.keepShuffle, d.behavior.keepShuffle, onOff) { prefs.setKeepShuffle(it) }
    val crossfadeChanged = reset.add(tr(R.string.crossfade), crossfade, SoundChoices.crossfade(d), { it.label }) {
        // Off keeps whether the length was automatic: put that back too.
        prefs.setMixAuto(d.mix.auto)
        prefs.setMix(d.mix.on)
    }
    val lengthChanged = reset.add(tr(R.string.length), s.mix.seconds, d.mix.seconds, seconds) { prefs.setMixSeconds(it) }
    val beatMatchChanged = reset.add(tr(R.string.match_tempo_and_beats_dj_style), s.mix.beatMatch, d.mix.beatMatch, onOff) { prefs.setMixBeatMatch(it) }
    val keepAlbumsChanged = reset.add(tr(R.string.keep_an_album_s_songs_whole), s.mix.keepAlbums, d.mix.keepAlbums, onOff) { prefs.setMixKeepAlbums(it) }
    val curveChanged = reset.add(tr(R.string.how_they_cross), s.sound.crossfadeCurve, d.sound.crossfadeCurve, { it.label }) { prefs.setCrossfadeCurve(it) }
    // The two are stored together: either one's reset writes the pair as it will be once both are back.
    val speedAfter = d.sound.musicSpeed
    val pitchAfter = d.sound.musicPitch
    val speedChanged = reset.add(tr(R.string.speed_when_vinz_starts), s.sound.musicSpeed, speedAfter, ::speedText) {
        prefs.setMusicDefaults(speedAfter, pitchAfter); c.player.setMusicSpeed(speedAfter)
    }
    val pitchChanged = reset.add(tr(R.string.pitch_when_vinz_starts), s.sound.musicPitch, pitchAfter, ::pitchText) {
        prefs.setMusicDefaults(speedAfter, pitchAfter); c.player.setMusicPitch(pitchAfter)
    }
    val sleepFadeChanged = reset.add(tr(R.string.sleep_timer_fades_out), s.sound.sleepFadeSeconds, d.sound.sleepFadeSeconds, fade) { prefs.setSleepFade(it) }
    val pauseFadeChanged = reset.add(tr(R.string.fade_on_pause_and_play), s.sound.pauseFade, d.sound.pauseFade, onOff) { prefs.setPauseFade(it) }
    val stopOnSwipeChanged = reset.add(tr(R.string.stop_when_swiped_away), s.stopOnSwipeAway, d.stopOnSwipeAway, onOff) { prefs.setStopOnSwipeAway(it) }
    val pauseOnMuteChanged = reset.add(tr(R.string.pause_when_muted), s.behavior.pauseOnMute, d.behavior.pauseOnMute, onOff) { prefs.setPauseOnMute(it) }
    val faceDownChanged = reset.add(tr(R.string.playback_face_down_pause), s.behavior.faceDownPause, d.behavior.faceDownPause, onOff) { prefs.setFaceDownPause(it) }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.what_plays_next)) {
            // A list in a sheet, not chips: its four names don't fit one capsule on a 360 dp phone ("Your music" broke in two).
            PickListRow(
                tr(R.string.when_the_queue_ends), QueueEnd.entries, queueEnd, { it.label },
                info = tr(R.string.when_the_queue_ends_info), changed = queueEndChanged,
            ) { SoundChoices.setQueueEnd(prefs, it) }
            // #159: off, what's left out is what's in the queue and what was heard in the last hours.
            SwitchRow(tr(R.string.no_repeats_title), s.behavior.autoplayNoRepeats, info = tr(R.string.no_repeats_info), changed = noRepeatsChanged) { prefs.setAutoplayNoRepeats(it) }
            // #299: long mixes and compilations stay out of Autoplay.
            if (s.autoplay) SliderRow(
                tr(R.string.longest_song_for_autoplay), s.behavior.autoplayMaxMinutes.toFloat(), 0f..AutoplayLength.MAX_MINUTES.toFloat(),
                { minutes(it.roundToInt()) }, steps = AutoplayLength.MAX_MINUTES / AutoplayLength.STEP_MINUTES - 1,
                info = tr(R.string.longest_song_for_autoplay_info), changed = longestChanged,
            ) { prefs.setAutoplayMaxMinutes(it.roundToInt()) }
            ChoiceRow(
                tr(R.string.music_videos_label), VideoPolicy.entries, videos, { it.label },
                info = tr(R.string.music_videos_label_info), changed = videosChanged,
            ) { SoundChoices.setVideoPolicy(prefs, it) }
            SwitchRow(tr(R.string.keep_playing_videos), s.videoContinues, info = tr(R.string.after_a_video_the_next_song), changed = keepVideosChanged) { prefs.setVideoContinues(it) }
            // #216 (1.8.1): explicit songs as before, never played, or not shown either.
            ChoiceRow(
                tr(R.string.explicit_songs), ExplicitContent.entries, s.behavior.explicitContent, { it.label },
                info = tr(R.string.explicit_songs_info), changed = explicitChanged,
            ) { prefs.setExplicitContent(it) }
        }
        // D57, #416 (1.8.4).
        group(tr(R.string.shuffle)) {
            ChoiceRow(
                tr(R.string.playback_shuffle_button), ShuffleButton.entries, s.behavior.shuffleButton, { it.label },
                info = tr(R.string.playback_shuffle_button_info), changed = shuffleButtonChanged,
            ) { prefs.setShuffleButton(it) }
            SwitchRow(tr(R.string.playback_keep_shuffle), s.behavior.keepShuffle, info = tr(R.string.playback_keep_shuffle_info), changed = keepShuffleChanged) { prefs.setKeepShuffle(it) }
        }
        // Not "Crossfade": that is its first row's name (§2.7).
        group(tr(R.string.playback_song_to_song)) {
            ChoiceRow(
                tr(R.string.crossfade), Crossfade.entries, crossfade, { it.label },
                info = crossfadeInfo(lastWhy), changed = crossfadeChanged,
            ) { SoundChoices.setCrossfade(prefs, it) }
            // #351: up to 30 s (a song's blend is still at most a third of it: MixEngine.makePlan).
            if (s.mix.on && !s.mix.auto) SliderRow(
                tr(R.string.length), s.mix.seconds.toFloat(), 1f..MAX_CROSSFADE_SECONDS.toFloat(), { seconds(it.roundToInt()) },
                steps = MAX_CROSSFADE_SECONDS - 2, changed = lengthChanged,
            ) { prefs.setMixSeconds(it.roundToInt()) }
            if (s.mix.on) {
                SwitchRow(tr(R.string.match_tempo_and_beats_dj_style), s.mix.beatMatch, info = tr(R.string.the_next_song_plays_at_the), changed = beatMatchChanged) { prefs.setMixBeatMatch(it) }
                // #247: an album played as an album never blended; this keeps its songs whole anywhere they meet.
                SwitchRow(tr(R.string.keep_an_album_s_songs_whole), s.mix.keepAlbums, info = tr(R.string.an_album_playing_through_never_blends), changed = keepAlbumsChanged) { prefs.setMixKeepAlbums(it) }
            }
            // Each card draws the two volumes over the blend (1.7.8, A1), with what the curve does under it.
            PickCardsRow(
                tr(R.string.how_they_cross), CrossfadeCurve.entries, s.sound.crossfadeCurve, { it.label },
                info = tr(R.string.how_the_ending_song_goes_down), changed = curveChanged, exampleScale = 1f,
                onPick = { prefs.setCrossfadeCurve(it) },
            ) { curve ->
                Column {
                    CurveSample(curve)
                    Spacer(Modifier.height(4.dp))
                    Text(curve.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        group(tr(R.string.playback_speed_and_pitch)) {
            SliderRow(
                tr(R.string.speed_when_vinz_starts), s.sound.musicSpeed, 0.5f..2f, { speedText(it) }, steps = 29,
                info = tr(R.string.songs_start_at_this_speed_each), changed = speedChanged,
            ) { prefs.setMusicDefaults(it, s.sound.musicPitch); c.player.setMusicSpeed(it) }
            SliderRow(
                tr(R.string.pitch_when_vinz_starts), s.sound.musicPitch.toFloat(), -12f..12f, { pitchText(it.roundToInt()) }, steps = 23,
                info = tr(R.string.moves_every_song_up_or_down), changed = pitchChanged,
            ) { prefs.setMusicDefaults(s.sound.musicSpeed, it.roundToInt()); c.player.setMusicPitch(it.roundToInt()) }
        }
        group(tr(R.string.stopping_and_pausing)) {
            SliderRow(
                tr(R.string.sleep_timer_fades_out), s.sound.sleepFadeSeconds.toFloat(), 0f..60f, { fade(it.roundToInt()) }, steps = 11,
                info = tr(R.string.the_music_fades_out_over_the), changed = sleepFadeChanged,
            ) { prefs.setSleepFade(it.roundToInt()) }
            SwitchRow(tr(R.string.fade_on_pause_and_play), s.sound.pauseFade, info = tr(R.string.pausing_fades_the_sound_out_over), changed = pauseFadeChanged) { prefs.setPauseFade(it) }
            SwitchRow(tr(R.string.stop_when_swiped_away), s.stopOnSwipeAway, info = tr(R.string.swiping_vinz_out_of_recent_apps), changed = stopOnSwipeChanged) { prefs.setStopOnSwipeAway(it) }
            SwitchRow(tr(R.string.pause_when_muted), s.behavior.pauseOnMute, info = tr(R.string.only_the_phone_s_media_volume), changed = pauseOnMuteChanged) { prefs.setPauseOnMute(it) }
            // 1.8.5: the accelerometer, only while this is on and music plays (playback/FaceDown.kt).
            SwitchRow(tr(R.string.playback_face_down_pause), s.behavior.faceDownPause, info = tr(R.string.playback_face_down_pause_info), changed = faceDownChanged) { prefs.setFaceDownPause(it) }
        }
    }
}

/** The Crossfade row's ⓘ: what the three choices do, and (#493) a last line "Last time: …" when the last blend didn't happen. */
fun crossfadeInfo(last: MixRefusal?): String {
    val base = tr(R.string.crossfade_choice_info)
    return if (last == null) base else base + "\n\n" + mixWhyLine(last.why)
}

/** "Last time: One of the two is a music video." (Crossfade's ⓘ and the DJ chip's in the queue.) */
fun mixWhyLine(why: MixWhy): String = tr(R.string.mix_why_last, tr(why.text))

/**
 * What "Reset this page" lists and puts back on Playback and on Sound & quality. Their settings aren't part of a Look
 * (so not in the pages' look keys): each row adds itself with [add], which also says whether it differs from how Vinz
 * comes (the row's dot).
 */
internal class ListeningResets {
    private val lines = ArrayList<String>()
    private val undo = ArrayList<() -> Unit>()

    /**
     * A row of the page: its [title], the value [now] and Vinz's [default] ([words] says either). Returns whether they
     * differ; if so, the reset lists it (unless [listed] is false: a second stored value behind a row already listed)
     * and [put] is called with the default.
     */
    fun <T> add(title: String, now: T, default: T, words: (T) -> String, put: (T) -> Unit): Boolean {
        if (now == default) return false
        lines += resetLine(title, words(now), words(default))
        undo += { put(default) }
        return true
    }

    fun pageReset() = PageReset(lines.toList()) { undo.toList().forEach { it() } }

    companion object {
        /** "On" / "Off", for a switch's line. */
        fun onOff(on: Boolean): String = if (on) tr(R.string.on_2) else tr(R.string.off)

        /** "6 s". */
        fun seconds(n: Int): String = "$n s"
    }
}

/** The root's line for Playback: the crossfade, and "No repeats" when it's on ("Crossfade 6 s · No repeats this session"). */
fun playbackValue(s: AppSettings, dark: Boolean): String = listOfNotNull(
    when {
        !s.mix.on -> tr(R.string.root_crossfade_off)
        s.mix.auto -> tr(R.string.crossfade)
        else -> tr(R.string.root_crossfade_seconds, s.mix.seconds)
    },
    if (s.behavior.autoplayNoRepeats) tr(R.string.no_repeats_title) else null,
).joinToString(" · ")

/** The look settings "Reset this page" puts back: none (what plays and how isn't part of a look; the page resets its rows itself). */
val playbackLookKeys: List<String> = emptyList()

/**
 * Old group titles → this page's groups: "Crossfade" and "How they cross" (a fold of its own before 1.8.4) are the
 * group around the Crossfade row, and "Speed, pitch and fades" is where the two sliders are (the fades went to
 * Stopping and pausing; search opens them there).
 */
val playbackOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.crossfade) to tr(R.string.playback_song_to_song),
        tr(R.string.how_they_cross) to tr(R.string.playback_song_to_song),
        tr(R.string.speed_pitch_and_fades) to tr(R.string.playback_speed_and_pitch),
    )
}

/** The page's lines in Settings' search (each names the group it is drawn in). */
val playbackIndex: List<SettingEntry> by lazy {
    val next = tr(R.string.what_plays_next)
    val shuffle = tr(R.string.shuffle)
    val between = tr(R.string.playback_song_to_song)
    val speed = tr(R.string.playback_speed_and_pitch)
    val stopping = tr(R.string.stopping_and_pausing)
    with(SettingNeeds) {
        listOf(
            // 1.8.0 (#210): merged choices; the old titles stay as search words.
            SettingEntry(page, next, next),
            SettingEntry(page, next, tr(R.string.when_the_queue_ends), tr(R.string.radio_continue_similar) + " " + tr(R.string.autoplay) + " " + tr(R.string.autoplay_from) + " " + tr(R.string.radio_your_music_vinz_radio_youtube)),
            SettingEntry(page, next, tr(R.string.no_repeats_title), tr(R.string.no_repeats_detail)),
            SettingEntry(page, next, tr(R.string.longest_song_for_autoplay), tr(R.string.longest_song_for_autoplay_keywords), needs = turnOn(tr(R.string.autoplay)) { it.autoplay }),
            SettingEntry(page, next, tr(R.string.music_videos_label), tr(R.string.music_videos_audio) + " " + tr(R.string.songs_not_videos) + " " + tr(R.string.songs_not_videos_everywhere)),
            SettingEntry(page, next, tr(R.string.keep_playing_videos), tr(R.string.music_video_2)),
            SettingEntry(page, next, tr(R.string.explicit_songs), tr(R.string.explicit_songs_keywords)),
            // 1.8.4: D57, #416.
            SettingEntry(page, shuffle, tr(R.string.playback_shuffle_button), tr(R.string.playback_shuffle_keywords) + " " + tr(R.string.shuffle_button_both) + " " + tr(R.string.shuffle_button_only)),
            SettingEntry(page, shuffle, tr(R.string.playback_keep_shuffle), tr(R.string.playback_shuffle_keywords)),
            SettingEntry(page, between, tr(R.string.crossfade), tr(R.string.mix_blend_transition_fade) + " " + tr(R.string.crossfade_songs) + " " + tr(R.string.automatic_length)),
            SettingEntry(page, between, tr(R.string.length), needs = manualLength),
            SettingEntry(page, between, tr(R.string.match_tempo_and_beats_dj_style), tr(R.string.bpm_beatmatch), needs = crossfade),
            SettingEntry(page, between, tr(R.string.keep_an_album_s_songs_whole), tr(R.string.keep_albums_keywords), needs = crossfade),
            SettingEntry(page, between, tr(R.string.how_they_cross), tr(R.string.curve_equal_power_linear_s_curve)),
            SettingEntry(page, speed, tr(R.string.speed_when_vinz_starts), tr(R.string.playback_speed_default_tempo_faster_slower)),
            SettingEntry(page, speed, tr(R.string.pitch_when_vinz_starts), tr(R.string.key_semitones_transpose_higher_lower_slowed)),
            SettingEntry(page, stopping, stopping),
            SettingEntry(page, stopping, tr(R.string.sleep_timer_fades_out), tr(R.string.sleep_fade_out_bedtime)),
            SettingEntry(page, stopping, tr(R.string.fade_on_pause_and_play), tr(R.string.pause_fade_gentle_soft)),
            SettingEntry(page, stopping, tr(R.string.stop_when_swiped_away), tr(R.string.recents_background)),
            SettingEntry(page, stopping, tr(R.string.pause_when_muted), tr(R.string.volume_zero_mute_pause_resume)),
            SettingEntry(page, stopping, tr(R.string.playback_face_down_pause), tr(R.string.playback_face_down_keywords)),
        )
    }
}
