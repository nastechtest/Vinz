package com.vinz.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.local.LocalLossless
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.AudioQuality
import com.vinz.music.data.prefs.NORMALIZE_TARGETS
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.playback.EqPresets
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingNeeds
import com.vinz.music.ui.screens.SoundChoices
import com.vinz.music.ui.screens.TrimSilence
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

private val page = SettingsPageId.SOUND_QUALITY

/**
 * Settings → Sound & quality (1.8.4's map, plan §2.3), built with the kit: lossless and the streaming quality, Premium
 * quality and music videos' quality; then loudness, Vinz Spatial, the Equalizer and skip silence. This file is the
 * only place for the page: its composable, its lines in Settings' search ([soundQualityIndex]), its "Reset this page"
 * keys ([soundQualityLookKeys]), its line on the root ([soundQualityValue]) and the old group names it answers to
 * ([soundQualityOldGroups]).
 *
 * The group "Sound" keeps its name: the player's Equalizer button opens the page there. Wi-Fi's and mobile data's
 * quality are one row ([StreamingQuality], plan §2.5) over the two settings Vinz already keeps, and so are Normalize's
 * switch and its level ([Loudness]) and skip silence ([SoundChoices]). Nothing here is part of a Look, so "Reset this
 * page" puts each row back through its own setter.
 */
@Composable
fun SoundQualityPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val d = remember { AppSettings() }
    val found by c.localLossless.found.collectAsState()
    val synced by c.losslessSync.songs.collectAsState()
    val syncing by c.losslessSync.working.collectAsState()
    val askFiles = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Lossless still works without file access; it just can't use files on the phone.
        prefs.setLossless(true)
        if (granted) prefs.setLosslessLocal(true) // lossless sync scans when this turns on
    }
    LaunchedEffect(s.losslessLocal) { if (s.losslessLocal && found < 0) c.losslessSync.sync() }

    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }
    val percent: (Float) -> String = { "${(it * 100).toInt()}%" }
    val height: (Int) -> String = { "${it}p" }
    val streaming = StreamingQuality.toMerged(s.audioQuality, s.cellularQuality)
    val loudness = Loudness.toMerged(s.normalize, s.sound.normalizeTarget)
    val silence = SoundChoices.trimSilence(s)
    // With lossless, mobile data's quality is only asked for by Data saver, and is never High (#40).
    val saverQuality = s.cellularQuality.takeIf { it != AudioQuality.HIGH } ?: AudioQuality.NORMAL

    // What differs from how Vinz comes, for the rows' dots and for Reset this page (top to bottom, as the page).
    val reset = ListeningResets()
    // Back on is without asking for the files again: what it may read stays as it is.
    val losslessChanged = reset.add(tr(R.string.lossless_playback), s.lossless, d.lossless, onOff) { prefs.setLossless(it) }
    val streamingChanged = reset.add(tr(R.string.sound_streaming_quality), streaming, StreamingQuality.toMerged(d.audioQuality, d.cellularQuality), { it.text }) {
        val (wifi, mobile) = StreamingQuality.toStored(it)
        prefs.setAudioQuality(wifi)
        prefs.setCellularQuality(mobile)
    }
    val premiumChanged = reset.add(tr(R.string.premium_quality), s.premiumStream, d.premiumStream, onOff) { prefs.setPremiumStream(it) }
    val videoChanged = reset.add(tr(R.string.music_video_quality), s.videoMaxHeight, d.videoMaxHeight, height) { prefs.setVideoMaxHeight(it) }
    val loudnessChanged = reset.add(tr(R.string.same_loudness_for_every_song), loudness, Loudness.toMerged(d.normalize, d.sound.normalizeTarget), { Loudness.label(it) }) {
        prefs.setNormalizeTarget(d.sound.normalizeTarget)
        prefs.setNormalize(d.normalize)
    }
    val albumsChanged = reset.add(tr(R.string.albums_as_they_are), s.sound.normalizeAlbums, d.sound.normalizeAlbums, onOff) { prefs.setNormalizeAlbums(it) }
    val spatialChanged = reset.add(tr(R.string.vinz_spatial), s.spatial, d.spatial, onOff) { prefs.setSpatial(it) }
    val intensityChanged = reset.add(tr(R.string.vinz_spatial_intensity), s.spatialAmount, d.spatialAmount, percent) { prefs.setSpatialAmount(it) }
    val silenceChanged = reset.add(tr(R.string.skip_silence), silence, SoundChoices.trimSilence(d), { it.label }) { SoundChoices.setTrimSilence(prefs, it) }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.quality)) {
            SwitchRow(
                tr(R.string.lossless_playback), s.lossless,
                // Its state in words, while it has one to tell.
                value = when {
                    // Without the files, the row under it says what to allow.
                    !s.lossless || !s.losslessLocal -> null
                    found < 0 -> tr(R.string.looking_for_lossless_files)
                    found == 0 -> tr(R.string.no_lossless_files_found_yet)
                    syncing -> tr(R.string.lossless_files_found_adding_them, found)
                    else -> tr(R.string.lossless_files_in_downloads, found, synced.size)
                },
                info = tr(R.string.always_plays_the_best_audio_there), changed = losslessChanged,
            ) { on ->
                if (!on) { prefs.setLossless(false); prefs.setLosslessLocal(false) }
                else if (c.localLossless.hasPermission()) { prefs.setLossless(true); prefs.setLosslessLocal(true) }
                else askFiles.launch(LocalLossless.permission)
            }
            if (s.lossless) {
                if (s.losslessLocal) LinkRow(tr(R.string.scan_for_lossless_files_again), value = tr(R.string.also_checked_every_few_hours), action = true) { scope.launch { c.losslessSync.sync() } }
                else LinkRow(tr(R.string.allow_access_to_lossless_files), value = tr(R.string.allow_access_to_lossless_files_value), action = true) { askFiles.launch(LocalLossless.permission) }
                // Data saver plays songs at this quality on mobile data, lossless or not (#40).
                if (s.dataSaver) ChoiceRow(
                    tr(R.string.streaming_quality_on_mobile_data), AudioQuality.entries.filter { it != AudioQuality.HIGH }, saverQuality, { it.label },
                    changed = s.cellularQuality != d.cellularQuality,
                ) { prefs.setCellularQuality(it) }
            } else {
                // §2.5: Wi-Fi's and mobile data's quality as one row; its sheet holds the two.
                StreamingQualityRow(streaming, streamingChanged, onWifi = { prefs.setAudioQuality(it) }, onMobile = { prefs.setCellularQuality(it) })
            }
            // Reddit 1.7: "am I getting the quality Premium offers?"
            if (s.isLoggedIn) {
                // #373: said plainly when YouTube isn't sending it, rather than songs just playing at the usual quality.
                val premium by c.streams.premiumState.collectAsState()
                val seen by c.innerTube.seen.collectAsState()
                SwitchRow(
                    tr(R.string.premium_quality), s.premiumStream,
                    value = when {
                        !s.premiumStream -> null
                        seen?.signedIn == false -> tr(R.string.youtube_no_longer_takes_this_sign_in)
                        premium == StreamResolver.PremiumState.MISSING && c.streams.premiumStateIsCurrent() -> tr(R.string.youtube_isnt_sending_256_kbps)
                        else -> tr(R.string.t_256_kbps_with_youtube_music_premium)
                    },
                    info = tr(R.string.vinz_normally_fetches_songs_without_your), changed = premiumChanged,
                ) { prefs.setPremiumStream(it) }
            }
            ChoiceRow(tr(R.string.music_video_quality), VIDEO_HEIGHTS, s.videoMaxHeight, height, changed = videoChanged) { prefs.setVideoMaxHeight(it) }
        }
        // The player's Equalizer button opens the page at this group: its name stays.
        group(tr(R.string.sound)) {
            // Normalize's switch and its level are one choice: Off, or how loud (#210). Each card has its level as a bar.
            PickCardsRow(
                tr(R.string.same_loudness_for_every_song), Loudness.options, loudness, { Loudness.label(it) },
                info = tr(R.string.each_song_is_measured_on_your) + " " + tr(R.string.how_loud_normalize_volume_makes_every),
                changed = loudnessChanged, exampleScale = 1f,
                onPick = { picked ->
                    val (on, target) = Loudness.toStored(picked, s.sound.normalizeTarget)
                    if (on) prefs.setNormalizeTarget(target)
                    prefs.setNormalize(on)
                },
            ) { LoudnessSample(it) }
            if (s.normalize) SwitchRow(tr(R.string.albums_as_they_are), s.sound.normalizeAlbums, info = tr(R.string.while_an_album_plays_through_every), changed = albumsChanged) { prefs.setNormalizeAlbums(it) }
            // A software spatializer, so it works without Dolby Atmos or spatial-audio hardware.
            SwitchRow(tr(R.string.vinz_spatial), s.spatial, info = tr(R.string.software_spatial_audio_a_wider_stage), changed = spatialChanged) { prefs.setSpatial(it) }
            if (s.spatial) SliderRow(tr(R.string.intensity), s.spatialAmount, 0.1f..1f, percent, changed = intensityChanged) { prefs.setSpatialAmount(it) }
            LinkRow(
                tr(R.string.equalizer),
                value = if (s.eq.enabled) tr(R.string.on, EqPresets.label(s.eq.preset)) else tr(R.string.off),
                changed = s.eq.enabled,
            ) { nav.go(Routes.EQ) }
            // #325: plainer names, and "Songs only".
            ChoiceRow(
                tr(R.string.skip_silence), TrimSilence.entries, silence, { it.label },
                info = tr(R.string.skip_silence_choice_info), changed = silenceChanged,
            ) { SoundChoices.setTrimSilence(prefs, it) }
        }
    }
}

/** Music videos are asked for at up to this many lines. */
private val VIDEO_HEIGHTS = listOf(480, 720, 1080, 1440)

/**
 * Plan §2.5: "Streaming quality on Wi-Fi" and "… on mobile data" as one row. One face over the two settings Vinz keeps
 * ([AppSettings.audioQuality], [AppSettings.cellularQuality]): every pair of the two stays possible, and nothing
 * stored changes (SoundQualityMergeTest).
 */
data class StreamingQuality(val wifi: AudioQuality, val mobile: AudioQuality) {
    /** The row's value: "Wi-Fi High · Mobile data Normal". */
    val text: String get() = tr(R.string.sound_streaming_quality_value, wifi.label, mobile.label)

    companion object {
        fun toMerged(audioQuality: AudioQuality, cellularQuality: AudioQuality) = StreamingQuality(audioQuality, cellularQuality)

        /** audioQuality (Wi-Fi), cellularQuality (mobile data). */
        fun toStored(merged: StreamingQuality): Pair<AudioQuality, AudioQuality> = merged.wifi to merged.mobile
    }
}

/**
 * "Same loudness for every song" (1.8.0, #210): Normalize's switch and its level as one choice, [OFF] or a level in
 * LUFS. Off keeps the level that was stored, for when it's turned back on (SoundQualityMergeTest).
 */
object Loudness {
    /** The choice "Off" (no level is this low). */
    const val OFF = Int.MIN_VALUE

    /** Off, then the levels from loud to quiet. */
    val options: List<Int> get() = listOf(OFF) + NORMALIZE_TARGETS.map { it.first }

    fun toMerged(normalize: Boolean, normalizeTarget: Int): Int = if (normalize) normalizeTarget else OFF

    /** normalize, normalizeTarget ([targetBefore]: the level stored until now, which Off leaves alone). */
    fun toStored(merged: Int, targetBefore: Int): Pair<Boolean, Int> = if (merged == OFF) false to targetBefore else true to merged

    /** "Off", "Standard"; a level that isn't one of Vinz's own (from a backup) by its number. */
    fun label(merged: Int): String =
        if (merged == OFF) tr(R.string.off) else NORMALIZE_TARGETS.firstOrNull { it.first == merged }?.second ?: lufs(merged)

    /** "As each song was mastered", "−14 LUFS, like Spotify and YouTube". */
    fun detail(merged: Int): String = if (merged == OFF) tr(R.string.as_each_song_was_mastered) else lufs(merged) + when (merged) {
        -14 -> tr(R.string.like_spotify_and_youtube)
        -16 -> tr(R.string.like_apple_music)
        -23 -> tr(R.string.like_tv_and_radio)
        else -> ""
    }

    private fun lufs(level: Int) = "$level LUFS"
}

/** A loudness choice's card: its level as a bar (1.7.8, A2), and what it is like. */
@Composable
private fun LoudnessSample(level: Int) {
    val ink = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val f = if (level == Loudness.OFF) 0f else ((level + 30f) / 25f).coerceIn(0.05f, 1f)
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            if (f > 0f) drawRoundRect(ink, size = size.copy(width = size.width * f), cornerRadius = CornerRadius(size.height / 2))
        }
        Spacer(Modifier.height(6.dp))
        Text(Loudness.detail(level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The one row for the two streaming qualities: it says both, and a tap opens a sheet with a Choice row for each. A pick
 * in the sheet is stored at once; the sheet stays for the other one and goes with a swipe down, Back or a tap outside
 * (the kit's [RowsSheet]).
 */
@Composable
private fun StreamingQualityRow(quality: StreamingQuality, changed: Boolean, onWifi: (AudioQuality) -> Unit, onMobile: (AudioQuality) -> Unit) {
    val title = tr(R.string.sound_streaming_quality)
    var open by rememberSaveable(title, "streaming-quality") { mutableStateOf(false) }
    PickRow(title, quality.text, changed = changed) { open = true }
    if (open) RowsSheet(title, { open = false }) {
        ChoiceRow(tr(R.string.sound_wifi), AudioQuality.entries, quality.wifi, { it.label }, onSelect = onWifi)
        ChoiceRow(tr(R.string.mobile_data), AudioQuality.entries, quality.mobile, { it.label }, onSelect = onMobile)
    }
}

/** The root's line for Sound & quality: the quality, then what's on of Normalize, Spatial and the equalizer. */
fun soundQualityValue(s: AppSettings, dark: Boolean): String = listOfNotNull(
    if (s.lossless) tr(R.string.lossless) else tr(R.string.audio_quality_x, s.audioQuality.label),
    if (s.normalize) tr(R.string.normalized) else null,
    if (s.spatial) tr(R.string.vinz_spatial) else null,
    if (s.eq.enabled) "EQ ${EqPresets.label(s.eq.preset)}" else null,
).joinToString(" · ")

/** The look settings "Reset this page" puts back: none (sound isn't part of a look; the page resets its rows itself). */
val soundQualityLookKeys: List<String> = emptyList()

/** Old group titles → this page's groups: none to translate ("Quality" and "Sound" are still called that). */
val soundQualityOldGroups: Map<String, String> = emptyMap()

/** The page's lines in Settings' search (each names the group it is drawn in). */
val soundQualityIndex: List<SettingEntry> by lazy {
    val quality = tr(R.string.quality)
    val sound = tr(R.string.sound)
    with(SettingNeeds) {
        listOf(
            SettingEntry(page, quality, tr(R.string.lossless_playback), tr(R.string.flac_hi_res)),
            SettingEntry(page, quality, tr(R.string.allow_access_to_lossless_files), tr(R.string.flac_permission), needs = lossless),
            SettingEntry(page, quality, tr(R.string.scan_for_lossless_files_again), "flac", needs = lossless),
            // 1.8.4 (§2.5): one row for both; found by what its two halves were called.
            SettingEntry(
                page, quality, tr(R.string.sound_streaming_quality),
                listOf(R.string.streaming_quality_on_wi_fi, R.string.streaming_quality_on_mobile_data, R.string.bitrate_audio_quality, R.string.bitrate_audio_quality_cellular, R.string.bitrate_cellular).joinToString(" ") { tr(it) },
                needs = notLossless,
            ),
            // Its two halves, as its sheet names them. With lossless, only mobile data's quality is asked for, and only by
            // Data saver (a row of its own then, "Streaming quality on mobile data").
            SettingEntry(page, quality, tr(R.string.sound_wifi), tr(R.string.bitrate_audio_quality), shown = tr(R.string.streaming_quality_on_wi_fi), needs = notLossless),
            SettingEntry(
                page, quality, tr(R.string.mobile_data), tr(R.string.bitrate_audio_quality_cellular) + " " + tr(R.string.bitrate_cellular),
                shown = tr(R.string.streaming_quality_on_mobile_data),
                needs = Needs(tr(R.string.turn_on_first, tr(R.string.data_saver))) { !it.lossless || it.dataSaver },
            ),
            SettingEntry(page, quality, tr(R.string.premium_quality), tr(R.string.youtube_music_premium_256_kbps_aac),
                needs = Needs(tr(R.string.sign_in_to_youtube_music_first)) { it.isLoggedIn }),
            SettingEntry(page, quality, tr(R.string.music_video_quality), tr(R.string.resolution_1080p_720p)),
            SettingEntry(page, sound, tr(R.string.same_loudness_for_every_song), tr(R.string.loudness_lufs_level) + " " + tr(R.string.normalize_volume) + " " + tr(R.string.normalize_loudness_lufs_loud_quiet_broadcast)),
            SettingEntry(page, sound, tr(R.string.albums_as_they_are), tr(R.string.album_mode_normalize_album_gain), needs = turnOn(tr(R.string.normalize_volume)) { it.normalize }),
            SettingEntry(page, sound, tr(R.string.vinz_spatial), tr(R.string.spatial_audio_3d_surround_atmos)),
            SettingEntry(page, sound, tr(R.string.intensity), tr(R.string.spatial_audio_width), shown = tr(R.string.vinz_spatial_intensity), needs = Needs(tr(R.string.turn_on_vinz_spatial_first)) { it.spatial }),
            SettingEntry(page, sound, tr(R.string.equalizer), tr(R.string.eq_bass_boost_treble_loudness_headphone)),
            SettingEntry(page, sound, tr(R.string.skip_silence), tr(R.string.trim_silence_songs_gaps) + " " + tr(R.string.trim_silence) + " " + tr(R.string.skip_silence_in_podcasts) + " " + tr(R.string.trim_silence_episodes_pauses_talk)),
        )
    }
}
