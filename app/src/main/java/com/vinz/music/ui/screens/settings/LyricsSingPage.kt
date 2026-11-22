package com.vinz.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.data.prefs.FontChoice
import com.vinz.music.data.prefs.LyricsAlign
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.LyricsColor
import com.vinz.music.data.prefs.LyricsMotion
import com.vinz.music.data.prefs.RomanizeMode
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.tr
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.PerformancePaused
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.screens.SettingNeeds
import com.vinz.music.ui.screens.worded
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlin.math.roundToInt

private val page = SettingsPageId.LYRICS_SING

/**
 * Settings → Lyrics & Sing (1.8.4's map, plan §2.3; rebuilt with the kit, plan §2.4–2.7 and §2.11). This file is the
 * only place for the page: its composable, its lines in Settings' search ([lyricsSingIndex]), its "Reset this page" keys
 * ([lyricsSingLookKeys]), its line on the root ([lyricsSingValue]), the old group names it answers to
 * ([lyricsSingOldGroups]), and the merged controls' mappings (the `…ToMerged` / `…ToStored` pairs). The rows
 * themselves are in `LyricsSingParts.kt`.
 *
 * Six groups: Style · Text · Color and effects · Language and translations · Sources and timing · Sing and music
 * files, under a pinned lyrics sample. Three of them sit at seven rows: the next setting here needs a sheet.
 */
@Composable
fun LyricsSingPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val d = remember { AppSettings() }
    // The sample's lines grow with the text size; never past what a preview may take.
    val previewHeight = (150 * LocalDensity.current.fontScale).dp.coerceAtMost(PreviewMaxHeight)
    SettingsPageScaffold(
        page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find,
        reset = PageReset(lyricsSingChanges(s, d)) { resetLyricsSing(prefs, d) },
        preview = { strip -> LsPreview(strip) }, previewHeight = previewHeight,
    ) {
        if (s.performanceMode) item("paused") { PerformancePaused() }
        group(LsGroups.style) { LsStyleRows(d) }
        group(LsGroups.text) { LsTextRows(d) }
        group(LsGroups.color) { LsColorRows(d) }
        group(LsGroups.language) { LsLanguageRows(d) }
        group(LsGroups.sources) { LsSourceRows(d) }
        group(LsGroups.sing) { LsSingRows(d) }
    }
}

/** The page's six groups, by the names they're drawn and found under. */
internal object LsGroups {
    val style get() = tr(R.string.style)
    val text get() = tr(R.string.text)
    val color get() = tr(R.string.ls_color_and_effects)
    val language get() = tr(R.string.ls_language_and_translations)
    val sources get() = tr(R.string.ls_sources_and_timing)
    val sing get() = tr(R.string.ls_sing_and_music_files)
}

/** The root's line for Lyrics & Sing: the style and where the sung line sits, and "Translated" when lyrics are. */
fun lyricsSingValue(s: AppSettings, dark: Boolean): String =
    listOfNotNull(tr(R.string.style_2, s.lyricsStyle.label, s.lyricsPosition.label), if (s.lyrics.translate) tr(R.string.translated) else null).joinToString(" · ")

/**
 * The look settings "Reset this page" puts back. The page's reset also puts back its other rows (sources, language,
 * timing, Sing, the lyrics font) through their own setters, see [resetLyricsSing]. Immersive's background is Player's
 * (plan §2.11) and no longer here (no key is in two pages' lists).
 */
val lyricsSingLookKeys: List<String> = listOf(
    "lyrics_align", "lyrics_scale", "lyrics_blur", "lyrics_progress_line", "lyrics_style", "lyrics_blur_amount", "lyrics_spacing",
    "lyrics_others_alpha", "lyrics_sung_color", "lyrics_sung_custom", "lyrics_other_color", "lyrics_other_custom", "lyrics_weight",
    "lyrics_backing_size", "lyrics_shadow", "lyrics_uppercase", "lyrics_ahead", "lyrics_position", "lyrics_extra_size", "lyrics_extra_color",
    "lyrics_extra_italic", "lyrics_extra_place", "lyrics_background", "lyrics_bg_color", "lyrics_bg_color2", "lyrics_everywhere",
    "lyrics_strip_align", "lyrics_strip_two_lines", "lyrics_gap_mark",
    // 1.8.0 lyrics look
    "lyrics_motion", "lyrics_word_by_word", "lyrics_card", "lyrics_halo", "lyrics_bold_lines",
    // The Lyrics source button is a look setting too (a Look carries it): the page's reset puts it back by its key.
    "lyrics_source_button",
    // The lyrics font came from Text & icons, which let go of it.
    "lyrics_font",
)

/** Old group titles → this page's groups (links and tour steps written before 1.8.4 name the old ones). */
val lyricsSingOldGroups: Map<String, String> by lazy {
    mapOf(
        tr(R.string.start_from) to LsGroups.style, tr(R.string.animation) to LsGroups.style, tr(R.string.background) to LsGroups.style,
        tr(R.string.on_the_player) to LsGroups.style,
        tr(R.string.effects) to LsGroups.color,
        tr(R.string.translations_romanized_lines) to LsGroups.language, tr(R.string.language) to LsGroups.language,
        tr(R.string.sources) to LsGroups.sources,
        tr(R.string.sing) to LsGroups.sing, tr(R.string.your_music_files) to LsGroups.sing,
    )
}

/** Word by word on every line shows with an animation picked; Your colors with that background. */
private val needsAnimation = Needs(tr(R.string.ls_pick_an_animation_first)) { it.lyricsLook.motionNow != LyricsMotion.NONE }
private val needsYourColors = Needs(tr(R.string.ls_pick_your_colors_first)) { it.lyricsBackground == LyricsBackground.COLOR || it.immersiveBackground == LyricsBackground.COLOR }

/** The page's lines in Settings' search: one per row, in the page's order, each naming its group. */
val lyricsSingIndex: List<SettingEntry> by lazy {
    with(SettingNeeds) {
        val g = LsGroups
        listOf(
            // Style
            SettingEntry(page, g.style, tr(R.string.start_from), tr(R.string.lyrics_style) + " " + tr(R.string.vinz_classic_karaoke_bold_card_glow) + " " + tr(R.string.lyrics_start_from_keywords)),
            SettingEntry(page, g.style, tr(R.string.animation), tr(R.string.lyrics_animation_keywords)),
            SettingEntry(page, g.style, tr(R.string.word_by_word_on_every_line), tr(R.string.lyrics_word_by_word_keywords), needs = needsAnimation),
            SettingEntry(page, g.style, tr(R.string.background), tr(R.string.behind_the_lyrics) + " " + tr(R.string.lyrics_background_dark_blurred_animated_cover) + " " + tr(R.string.bold_card)),
            SettingEntry(page, g.style, tr(R.string.your_colors), tr(R.string.top_color) + " " + tr(R.string.bottom_color), needs = needsYourColors),
            SettingEntry(page, g.style, tr(R.string.the_line_on_the_player), tr(R.string.current_lyric_strip_center_centered_left) + " " + tr(R.string.up_to_two_lines) + " " + tr(R.string.current_lyric_strip_wrap_two_lines)),
            SettingEntry(page, g.style, tr(R.string.instrumental_breaks), tr(R.string.instrumental_gap_dots_music_note_break)),
            // Text
            SettingEntry(page, g.text, tr(R.string.lyrics_font), tr(R.string.font_typeface_size)),
            SettingEntry(page, g.text, tr(R.string.lyrics_size), tr(R.string.lyrics_font_size) + " " + tr(R.string.text_size)),
            SettingEntry(page, g.text, tr(R.string.alignment), tr(R.string.left_centre_center_right)),
            SettingEntry(page, g.text, tr(R.string.weight), tr(R.string.lyrics_bold_thin_light_heavy_font)),
            SettingEntry(page, g.text, tr(R.string.line_spacing), tr(R.string.lyrics_gap_room_between_lines)),
            SettingEntry(page, g.text, tr(R.string.capitals), tr(R.string.uppercase_caps_all_capitals_lyrics)),
            SettingEntry(page, g.text, tr(R.string.where_the_sung_line_sits), tr(R.string.lyrics_position_middle_centre_center_top)),
            // Color and effects
            SettingEntry(page, g.color, tr(R.string.the_line_being_sung), tr(R.string.lyrics_color_sung_highlight_accent_spectrum)),
            SettingEntry(page, g.color, tr(R.string.the_other_lines), tr(R.string.lyrics_color_dim_inactive)),
            SettingEntry(page, g.color, tr(R.string.other_lines_brightness), tr(R.string.other_lines) + " " + tr(R.string.lyrics_dim_faint_brightness_opacity_inactive)),
            SettingEntry(page, g.color, tr(R.string.focus_blur)),
            SettingEntry(page, g.color, tr(R.string.lines_to_come), tr(R.string.lyrics_ahead_next_lines_hide_preview)),
            SettingEntry(
                page, g.color, tr(R.string.effects),
                listOf(R.string.glow, R.string.lyrics_glow_keywords, R.string.shadow, R.string.lyrics_text_shadow_readable_photo, R.string.progress_line, R.string.spectrum_line_underline_sung_lyric).joinToString(" ") { tr(it) },
            ),
            SettingEntry(page, g.color, tr(R.string.background_vocals), tr(R.string.backing_vocals_size_ad_libs)),
            // Language and translations
            SettingEntry(page, g.language, tr(R.string.translate_into), tr(R.string.translation_language) + " " + tr(R.string.translate_lyrics)),
            SettingEntry(
                page, g.language, tr(R.string.romanize_lyrics),
                listOf(R.string.romanization_romaji_pinyin_latin_letters_transli, R.string.romanize_my_own_language_too, R.string.romanization_native_script).joinToString(" ") { tr(it) },
            ),
            SettingEntry(page, g.language, tr(R.string.japanese_kanji_readings), tr(R.string.romaji_kanji_dictionary_download_kuromoji_japane)),
            SettingEntry(page, g.language, tr(R.string.translation_place), tr(R.string.translation_look) + " " + tr(R.string.translation_romanization_size_color_italic_above)),
            SettingEntry(page, g.language, tr(R.string.translation_size), tr(R.string.translation_romanization_size_color_italic_above)),
            SettingEntry(page, g.language, tr(R.string.translation_color), tr(R.string.translation_romanization_size_color_italic_above)),
            SettingEntry(page, g.language, tr(R.string.translation_in_italics), tr(R.string.italics)),
            // Sources and timing
            SettingEntry(page, g.sources, tr(R.string.prefer_karaoke_synced_lyrics), tr(R.string.word_by_word_time_synced)),
            SettingEntry(page, g.sources, tr(R.string.uncensor_lyrics), tr(R.string.explicit_swear_stars)),
            SettingEntry(page, g.sources, tr(R.string.ls_source_order), tr(R.string.order) + " " + tr(R.string.lyrics_providers_sources_priority)),
            // No row of its own: the "Your music files" source's switch in the list (plan §2.6).
            SettingEntry(page, g.sources, tr(R.string.read_lyrics_from_your_music_files), tr(R.string.flac_mp3_tags_embed)),
            SettingEntry(page, g.sources, tr(R.string.lyrics_source_button), tr(R.string.change_lyrics_source)),
            SettingEntry(page, g.sources, tr(R.string.ls_offset), tr(R.string.ls_offset_keywords)),
            // Sing and music files
            SettingEntry(page, g.sing, tr(R.string.sing_s_voice_separation), tr(R.string.karaoke_vocals_remove_instrumental_download_mode)),
            SettingEntry(page, g.sing, tr(R.string.get_songs_ready_while_sing_is), tr(R.string.karaoke_prepare_ahead_background_preload_vocals)),
            SettingEntry(page, g.sing, tr(R.string.room_for_sung_songs), tr(R.string.karaoke_storage_space)),
            SettingEntry(page, g.sing, tr(R.string.clear_sung_songs), tr(R.string.karaoke_storage_delete)),
            SettingEntry(page, g.sing, tr(R.string.save_lyrics_into_your_music_files), tr(R.string.flac_mp3_tags_embed)),
            SettingEntry(
                page, g.sing, tr(R.string.ls_when_saving),
                listOf(R.string.keep_word_timings, R.string.lyrics_file_words_keywords, R.string.include_the_translation, R.string.lyrics_file_translation_keywords).joinToString(" ") { tr(it) },
            ),
        )
    }
}

// ---------------------------------------------------------------------------- One control instead of several (plan §2.5)
// Each keeps its stored values as they are: `…ToMerged` reads them into the control, `…ToStored` is what a pick writes.

/** The line on the player: where it sits and whether a long one wraps (two stored values, one pick of four). */
data class StripLine(val align: LyricsAlign, val twoLines: Boolean)

/** Left, Centered, then each with two lines. */
val STRIP_LINES: List<StripLine> = listOf(false, true).flatMap { two -> LyricsAlign.entries.map { StripLine(it, two) } }

fun stripLineToMerged(align: LyricsAlign, twoLines: Boolean) = StripLine(align, twoLines)
fun stripLineToStored(line: StripLine): Pair<LyricsAlign, Boolean> = line.align to line.twoLines

fun stripLineLabel(line: StripLine): String {
    val where = if (line.align == LyricsAlign.CENTER) tr(R.string.centered) else tr(R.string.left)
    return if (line.twoLines) tr(R.string.ls_two_lines, where) else where
}

/** Romanize lyrics with "my own language too": one pick. Off keeps the stored "my own language too" as it is. */
data class RomanizePick(val mode: RomanizeMode, val ownLanguageToo: Boolean)

fun romanizeToMerged(mode: RomanizeMode, all: Boolean) = RomanizePick(mode, all)
fun romanizeToStored(pick: RomanizePick): Pair<RomanizeMode, Boolean> = pick.mode to pick.ownLanguageToo

/** What the pick offers: Off (with "my own language too" left as stored, [all]), then each way without and with it. */
fun romanizeOptions(all: Boolean): List<RomanizePick> =
    listOf(RomanizePick(RomanizeMode.OFF, all)) + RomanizeMode.entries.filter { it != RomanizeMode.OFF }.flatMap { listOf(RomanizePick(it, false), RomanizePick(it, true)) }

fun romanizeLabel(pick: RomanizePick): String = when {
    pick.mode == RomanizeMode.OFF -> tr(R.string.off)
    pick.ownLanguageToo -> pick.mode.label + " · " + tr(R.string.lyrics_too, java.util.Locale.getDefault().displayLanguage)
    else -> pick.mode.label
}

/** Glow, Shadow and the Progress line: three switches as one row of show-chips. */
enum class LyricsEffect { GLOW, SHADOW, PROGRESS_LINE }

fun effectsToMerged(glow: Boolean, shadow: Boolean, progressLine: Boolean): Set<LyricsEffect> =
    setOfNotNull(LyricsEffect.GLOW.takeIf { glow }, LyricsEffect.SHADOW.takeIf { shadow }, LyricsEffect.PROGRESS_LINE.takeIf { progressLine })

/** Glow, shadow, progress line. */
fun effectsToStored(effects: Set<LyricsEffect>): Triple<Boolean, Boolean, Boolean> =
    Triple(LyricsEffect.GLOW in effects, LyricsEffect.SHADOW in effects, LyricsEffect.PROGRESS_LINE in effects)

fun effectLabel(e: LyricsEffect): String = when (e) {
    LyricsEffect.GLOW -> tr(R.string.glow)
    LyricsEffect.SHADOW -> tr(R.string.shadow)
    LyricsEffect.PROGRESS_LINE -> tr(R.string.progress_line)
}

/** What goes into a music file with its lyrics: word timings and the translation, two switches as one row. */
enum class SavedWith { WORD_TIMINGS, TRANSLATION }

fun savedWithToMerged(words: Boolean, translation: Boolean): Set<SavedWith> =
    setOfNotNull(SavedWith.WORD_TIMINGS.takeIf { words }, SavedWith.TRANSLATION.takeIf { translation })

/** Word timings, translation. */
fun savedWithToStored(with: Set<SavedWith>): Pair<Boolean, Boolean> = (SavedWith.WORD_TIMINGS in with) to (SavedWith.TRANSLATION in with)

/** Behind the lyrics: the six backgrounds and Bold card (already one control over `lyrics_background` + `lyrics_card`). */
data class LsBehind(val ground: LyricsBackground, val card: Boolean)

val LS_BEHIND: List<LsBehind> = listOf(LsBehind(LyricsBackground.PLAYER, false), LsBehind(LyricsBackground.PLAYER, true)) +
    LyricsBackground.entries.filter { it != LyricsBackground.PLAYER }.map { LsBehind(it, false) }

fun behindLabel(b: LsBehind): String = if (b.card) tr(R.string.bold_card) else b.ground.label

// ---------------------------------------------------------------------------- Lyrics earlier / later (#404)

/** How far the lyrics can be moved, and by how much a step. */
internal val LS_OFFSET_RANGE = BehaviorPrefs.LYRICS_OFFSET_MS.first.toFloat()..BehaviorPrefs.LYRICS_OFFSET_MS.last.toFloat()
internal const val LS_OFFSET_STEP_MS = 10
internal val LS_OFFSET_STEPS = (BehaviorPrefs.LYRICS_OFFSET_MS.last - BehaviorPrefs.LYRICS_OFFSET_MS.first) / LS_OFFSET_STEP_MS - 1

/** The slider's place as a stored offset: whole steps of 10 ms, within the range. */
fun lyricsOffsetSnap(v: Float): Int = ((v / LS_OFFSET_STEP_MS).roundToInt() * LS_OFFSET_STEP_MS).coerceIn(BehaviorPrefs.LYRICS_OFFSET_MS)

/** "120 ms earlier", "80 ms later", "As timed" (a positive offset shows the lyrics earlier). */
fun lyricsOffsetText(ms: Int): String = when {
    ms > 0 -> tr(R.string.ls_offset_earlier, ms)
    ms < 0 -> tr(R.string.ls_offset_later, -ms)
    else -> tr(R.string.ls_offset_none)
}

// ---------------------------------------------------------------------------- Values in words (rows and the reset's list)

internal fun lsPercent(v: Float) = "${(v * 100).roundToInt()}%"
internal fun lsSpacingText(v: Float) = worded(if (v < 0.85f) tr(R.string.tight) else if (v <= 1.15f) tr(R.string.as_usual) else tr(R.string.airy), v)
internal fun lsAlphaText(v: Float) = worded(if (v < 0.35f) tr(R.string.faint) else if (v < 0.75f) tr(R.string.dimmed) else tr(R.string.bright), v)
internal fun lsBlurText(v: Float) = if (v < 0.05f) tr(R.string.off) else lsPercent(v)
internal fun lsBackingText(v: Float) = tr(R.string.of_the_line, (v * 100).roundToInt())
internal fun lsOnOff(on: Boolean) = if (on) tr(R.string.on_2) else tr(R.string.off)

/** One step past 4 GB is "Unlimited" (#238). */
internal const val LS_SING_ROOM_TOP = 4096f + 256f
internal fun lsSingRoomText(mb: Float): String =
    if (mb >= LS_SING_ROOM_TOP - 1f) tr(R.string.unlimited) else (mb / 1024f).let { gb -> if (gb >= 1) tr(R.string.t_1f_gb).format(gb) else "${mb.roundToInt()} MB" }

/** A lyrics color in words; your own with its code, so two of them read apart. */
internal fun lsColorText(color: LyricsColor, custom: Int) =
    if (color == LyricsColor.CUSTOM) color.label + " " + com.vinz.music.ui.theme.ColorMath.toHex(custom) else color.label

/** Languages lyrics can be translated into (Google Translate codes); "" is the phone's own. */
internal val LS_TRANSLATE_LANGUAGES: List<Pair<String, String>> by lazy {
    listOf(
        "" to tr(R.string.phone_s_language), "en" to tr(R.string.english), "es" to tr(R.string.spanish), "pt" to tr(R.string.portuguese), "fr" to tr(R.string.french),
        "de" to tr(R.string.german), "it" to tr(R.string.italian), "nl" to tr(R.string.dutch), "pl" to tr(R.string.polish), "tr" to tr(R.string.turkish), "ru" to tr(R.string.russian),
        "uk" to tr(R.string.ukrainian), "ar" to tr(R.string.arabic), "hi" to tr(R.string.hindi), "id" to tr(R.string.indonesian), "vi" to tr(R.string.vietnamese),
        "tl" to tr(R.string.filipino), "ja" to tr(R.string.japanese), "ko" to tr(R.string.korean), tr(R.string.zh_cn) to tr(R.string.chinese_simplified), tr(R.string.zh_tw) to tr(R.string.chinese_traditional),
    )
}

/** "Translate into" stands for two stored values (already one control): Off, or the language. */
internal const val LS_TRANSLATE_OFF = "\u0000off"

internal fun translateToMerged(on: Boolean, to: String): String =
    if (!on) LS_TRANSLATE_OFF else to.takeIf { code -> LS_TRANSLATE_LANGUAGES.any { it.first == code } } ?: ""

internal fun translateLabel(code: String): String =
    if (code == LS_TRANSLATE_OFF) tr(R.string.off) else LS_TRANSLATE_LANGUAGES.first { it.first == code }.second

/** The lyrics font in words: "App font", a built-in font's name, or the file's. */
internal fun lsFontText(font: String, fileName: String): String = when (font) {
    "" -> tr(R.string.app_font)
    FontChoice.CUSTOM.name -> fileName.ifBlank { tr(R.string.your_font_2) }
    else -> FontChoice.entries.firstOrNull { it.name == font }?.label ?: tr(R.string.app_font)
}

// ---------------------------------------------------------------------------- Reset this page

/** One line per row of the page that isn't as Vinz comes ([d]), as the reset's confirm lists them. */
internal fun lyricsSingChanges(s: AppSettings, d: AppSettings): List<String> = buildList {
    fun line(title: String, now: String, default: String) { if (now != default) add(resetLine(title, now, default)) }
    fun line(id: Int, now: String, default: String) = line(tr(id), now, default)
    val p = s.lyricsLook
    val q = d.lyricsLook
    fun effects(a: AppSettings) = effectsToMerged(a.lyricsLook.haloNow, a.lyricsLook.shadow, a.lyrics.progressLine).joinToString(" · ") { effectLabel(it) }.ifBlank { tr(R.string.none) }
    fun saved(a: AppSettings) = savedWithToMerged(a.lyrics.fileWords, a.lyrics.fileTranslation)
        .joinToString(" · ") { if (it == SavedWith.WORD_TIMINGS) tr(R.string.ls_word_timings) else tr(R.string.ls_translation) }.ifBlank { tr(R.string.none) }
    fun behind(l: com.vinz.music.data.prefs.LyricsLookPrefs) = behindLabel(if (l.onCard) LsBehind(LyricsBackground.PLAYER, true) else LsBehind(l.background, false))
    fun colors(l: com.vinz.music.data.prefs.LyricsLookPrefs) = com.vinz.music.ui.theme.ColorMath.toHex(l.bgColor) + if (l.bgColor2 == 0) "" else " · " + com.vinz.music.ui.theme.ColorMath.toHex(l.bgColor2)
    // Style
    line(R.string.start_from, p.style.label + if (p.changedFromStart) " · " + tr(R.string.with_your_changes) else "", q.style.label)
    line(R.string.animation, p.motionNow.label, q.motionNow.label)
    line(R.string.word_by_word_on_every_line, lsOnOff(p.wordByWordNow), lsOnOff(q.wordByWordNow))
    line(R.string.background, behind(p), behind(q))
    line(R.string.your_colors, colors(p), colors(q))
    line(R.string.the_line_on_the_player, stripLineLabel(stripLineToMerged(s.lyrics.stripAlign, s.lyrics.stripTwoLines)), stripLineLabel(stripLineToMerged(d.lyrics.stripAlign, d.lyrics.stripTwoLines)))
    line(R.string.instrumental_breaks, p.gapMark.label, q.gapMark.label)
    // Text
    line(R.string.lyrics_font, lsFontText(s.lyrics.font, s.lyrics.fontName), lsFontText(d.lyrics.font, d.lyrics.fontName))
    line(R.string.lyrics_size, lsPercent(s.lyrics.scale), lsPercent(d.lyrics.scale))
    line(R.string.alignment, s.lyrics.align.label, d.lyrics.align.label)
    line(R.string.weight, p.weightNow.label, q.weightNow.label)
    line(R.string.line_spacing, lsSpacingText(p.spacing), lsSpacingText(q.spacing))
    line(R.string.capitals, lsOnOff(p.uppercase), lsOnOff(q.uppercase))
    line(R.string.where_the_sung_line_sits, p.position.label, q.position.label)
    // Color and effects
    line(R.string.the_line_being_sung, lsColorText(p.sungColor, p.sungCustom), lsColorText(q.sungColor, q.sungCustom))
    line(R.string.the_other_lines, lsColorText(p.otherColor, p.otherCustom), lsColorText(q.otherColor, q.otherCustom))
    line(R.string.other_lines_brightness, lsAlphaText(p.othersAlpha), lsAlphaText(q.othersAlpha))
    line(R.string.focus_blur, lsBlurText(p.blurAmount), lsBlurText(q.blurAmount))
    line(R.string.lines_to_come, p.ahead.label, q.ahead.label)
    line(R.string.effects, effects(s), effects(d))
    line(R.string.background_vocals, lsBackingText(p.backingSize), lsBackingText(q.backingSize))
    // Language and translations
    line(R.string.translate_into, translateLabel(translateToMerged(s.lyrics.translate, s.lyrics.translateTo)), translateLabel(translateToMerged(d.lyrics.translate, d.lyrics.translateTo)))
    line(R.string.romanize_lyrics, romanizeLabel(romanizeToMerged(s.romanize, s.romanizeAll)), romanizeLabel(romanizeToMerged(d.romanize, d.romanizeAll)))
    line(R.string.translation_place, p.extraPlace.label, q.extraPlace.label)
    line(R.string.translation_size, lsPercent(p.extraSize), lsPercent(q.extraSize))
    line(R.string.translation_color, p.extraColor.label, q.extraColor.label)
    line(R.string.translation_in_italics, lsOnOff(p.extraItalic), lsOnOff(q.extraItalic))
    // Sources and timing
    line(R.string.prefer_karaoke_synced_lyrics, lsOnOff(s.preferSynced), lsOnOff(d.preferSynced))
    line(R.string.uncensor_lyrics, lsOnOff(s.skipCensored), lsOnOff(d.skipCensored))
    fun order(a: AppSettings) = a.lyrics.order.filter(a::lyricsSourceOn).joinToString(", ") { it.label }
    line(R.string.ls_source_order, order(s), order(d))
    line(R.string.lyrics_source_button, lsOnOff(s.lyrics.sourceButton), lsOnOff(d.lyrics.sourceButton))
    line(R.string.ls_offset, lyricsOffsetText(s.behavior.lyricsOffsetMs), lyricsOffsetText(d.behavior.lyricsOffsetMs))
    // Sing and music files
    line(R.string.get_songs_ready_while_sing_is, lsOnOff(s.singAlwaysReady), lsOnOff(d.singAlwaysReady))
    line(R.string.room_for_sung_songs, lsSingRoomText(s.singCacheMb.toFloat().coerceAtMost(LS_SING_ROOM_TOP)), lsSingRoomText(d.singCacheMb.toFloat().coerceAtMost(LS_SING_ROOM_TOP)))
    line(R.string.ls_when_saving, saved(s), saved(d))
}

/**
 * Reset this page: the lyrics' look by its keys ([lyricsSingLookKeys]), and the page's other rows through their own
 * setters. Downloads (Sing's voice separation, the kanji dictionary) and the lyrics font's file stay on the phone.
 */
internal fun resetLyricsSing(prefs: SettingsRepository, d: AppSettings) {
    prefs.resetLookKeys(lyricsSingLookKeys)
    prefs.setLyricsFont(d.lyrics.font)
    prefs.setLyricsTranslate(d.lyrics.translate)
    prefs.setLyricsTranslateTo(d.lyrics.translateTo)
    prefs.setRomanize(d.romanize)
    prefs.setRomanizeAll(d.romanizeAll)
    prefs.setPreferSynced(d.preferSynced)
    prefs.setSkipCensored(d.skipCensored)
    prefs.setLyricsOrder(d.lyrics.order)
    prefs.setLyricsOff(d.lyrics.off)
    prefs.setEmbeddedLyrics(d.behavior.embeddedLyrics)
    prefs.setLyricsSourceButton(d.lyrics.sourceButton)
    prefs.setLyricsOffsetMs(d.behavior.lyricsOffsetMs)
    prefs.setSingAlwaysReady(d.singAlwaysReady)
    prefs.setSingCacheMb(d.singCacheMb)
    prefs.setLyricsFileWords(d.lyrics.fileWords)
    prefs.setLyricsFileTranslation(d.lyrics.fileTranslation)
}
