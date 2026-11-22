package com.vinz.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.ModelStore
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.FontChoice
import com.vinz.music.data.prefs.LyricsAhead
import com.vinz.music.data.prefs.LyricsAlign
import com.vinz.music.data.prefs.LyricsBackground
import com.vinz.music.data.prefs.LyricsColor
import com.vinz.music.data.prefs.LyricsExtraPlace
import com.vinz.music.data.prefs.LyricsGapMark
import com.vinz.music.data.prefs.LyricsMotion
import com.vinz.music.data.prefs.LyricsPosition
import com.vinz.music.data.prefs.LyricsSource
import com.vinz.music.data.prefs.LyricsStyle
import com.vinz.music.data.prefs.LyricsWeight
import com.vinz.music.data.prefs.SING_ROOM_UNLIMITED
import com.vinz.music.tr
import com.vinz.music.ui.components.ColorPickerSheet
import com.vinz.music.ui.components.DragGrip
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.ReorderList
import com.vinz.music.ui.player.LyricsLook
import com.vinz.music.ui.player.cardColor
import com.vinz.music.ui.player.readableOnDark
import com.vinz.music.ui.player.rememberLyricsLook
import com.vinz.music.ui.screens.GapSample
import com.vinz.music.ui.screens.InfoMark
import com.vinz.music.ui.screens.InfoText
import com.vinz.music.ui.screens.InstalledFontsSheet
import com.vinz.music.ui.screens.LyricsSample
import com.vinz.music.ui.screens.PositionSample
import com.vinz.music.ui.screens.ShowOption
import com.vinz.music.ui.screens.StripSample
import com.vinz.music.ui.screens.backgroundSample
import com.vinz.music.ui.screens.lookFor
import com.vinz.music.ui.theme.AppFonts
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Settings → Lyrics & Sing: the rows of its six groups and its pinned preview (the page, its search lines and the
 * merged controls' mappings are in LyricsSingPage.kt). Every row is one of the kit's five; the source order is the
 * one list (the kit has no list row yet). [d] is Vinz as it comes, for each row's "changed" dot.
 */

/** The look as the settings have it now, for the song playing (its cover's colors). */
@Composable
private fun lsLook(): LyricsLook = rememberLyricsLook(LocalAppSettings.current, LocalContainer.current.player.currentSong.value?.thumbnail)

/** What's behind the lyrics page now, drawn small: Bold card's color, or the background picked. */
@Composable
private fun lsGround(look: LyricsLook): Brush = look.card?.let { SolidColor(it) } ?: backgroundSample(LocalAppSettings.current.lyricsLook.pageBackground)

/**
 * The page's pinned preview: the lyrics sample of the old look page, drawn as the lyrics are now on the background
 * picked, with background vocals, a translated line and (when on) the progress line. As a strip: the sung line alone.
 * (It doesn't follow "Lyrics earlier / later": the sample has no music to be early or late against.)
 */
@Composable
internal fun LsPreview(strip: Boolean) {
    val s = LocalAppSettings.current
    val look = lsLook()
    Box(Modifier.fillMaxSize().background(Color(0xFF15141B)).background(lsGround(look)), contentAlignment = Alignment.CenterStart) {
        LyricsSample(
            look, Modifier.fillMaxWidth(), ground = SolidColor(Color.Transparent), size = if (strip) 0.9f else 1f, lines = if (strip) 1 else 3,
            extra = if (strip) null else tr(R.string.cada_color_encuentra_su_camino), backing = !strip, progress = s.lyrics.progressLine,
        )
    }
}

// ---------------------------------------------------------------------------- Style

@Composable
internal fun LsStyleRows(d: AppSettings) {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val q = d.lyricsLook
    val prefs = LocalContainer.current.settings

    // Start from: a template, not a lock. With parts changed since, no card is the pick, so the same one can be
    // started from again (as its card could be tapped again before).
    var starting by rememberSaveable { mutableStateOf(false) }
    val startLabel: (LyricsStyle?) -> String = { st ->
        if (st == p.style && p.changedFromStart) p.style.label + " · " + tr(R.string.with_your_changes) else st?.label.orEmpty()
    }
    PickRow(tr(R.string.start_from), startLabel(p.style), info = tr(R.string.start_from_info), changed = p.style != q.style || p.changedFromStart) { starting = true }
    if (starting) PickSheet<LyricsStyle?>(
        tr(R.string.start_from), LyricsStyle.entries, p.style.takeUnless { p.changedFromStart }, startLabel,
        onPick = { it?.let(prefs::setLyricsStartFrom) }, onDismiss = { starting = false }, info = tr(R.string.start_from_info),
    ) { st -> if (st != null) LyricsSample(lookFor(p.startFrom(st)), Modifier.fillMaxWidth(), size = 0.7f, lines = 2) }

    PickCardsRow(
        tr(R.string.animation), LyricsMotion.entries, p.motionNow, { it.label }, info = tr(R.string.animation_info),
        changed = p.motionNow != q.motionNow, onPick = { prefs.setLyricsMotion(it) },
    ) { motion -> LyricsSample(lookFor(p.copy(motion = motion)), Modifier.fillMaxWidth(), size = 0.7f, lines = 2) }

    if (p.motionNow != LyricsMotion.NONE) SwitchRow(
        tr(R.string.word_by_word_on_every_line), p.wordByWordNow, info = tr(R.string.word_by_word_on_every_line_info),
        changed = p.wordByWordNow != q.wordByWordNow,
    ) { prefs.setLyricsWordByWord(it) }

    val behind = if (p.onCard) LsBehind(LyricsBackground.PLAYER, true) else LsBehind(p.background, false)
    val behindDefault = if (q.onCard) LsBehind(LyricsBackground.PLAYER, true) else LsBehind(q.background, false)
    PickCardsRow(
        tr(R.string.background), LS_BEHIND, behind, ::behindLabel,
        info = if (p.background == LyricsBackground.CANVAS) tr(R.string.animated_cover_songs_without_a_moving) else null,
        changed = behind != behindDefault,
        thumbnail = { Box(Modifier.fillMaxSize().background(lsGround(lsLook()))) },
        onPick = { prefs.setLyricsBehind(it.ground, it.card) },
    ) { b ->
        val look = lookFor(p.copy(background = b.ground, card = b.card))
        LyricsSample(look, Modifier.fillMaxWidth(), ground = if (b.card) null else backgroundSample(b.ground), size = 0.7f, lines = 2)
    }
    // Your colors: with that background picked here, or for Immersive (its row is on Player; the colors are these).
    if (s.lyricsBackground == LyricsBackground.COLOR || s.immersiveBackground == LyricsBackground.COLOR) LsYourColorsRow(d)

    PickCardsRow(
        tr(R.string.the_line_on_the_player), STRIP_LINES, stripLineToMerged(s.lyrics.stripAlign, s.lyrics.stripTwoLines), ::stripLineLabel,
        info = tr(R.string.the_lyric_over_the_progress_bar) + " " + tr(R.string.the_line_keeps_room_for_two),
        changed = s.lyrics.stripAlign != d.lyrics.stripAlign || s.lyrics.stripTwoLines != d.lyrics.stripTwoLines,
        onPick = { stripLineToStored(it).let { (align, two) -> prefs.setLyricsStripAlign(align); prefs.setLyricsStripTwoLines(two) } },
    ) { StripSample(it.align == LyricsAlign.CENTER, if (it.twoLines) 2 else 1) }

    PickCardsRow(
        tr(R.string.instrumental_breaks), LyricsGapMark.entries, p.gapMark, { it.label }, info = tr(R.string.what_shows_between_lines_while_the),
        changed = p.gapMark != q.gapMark, onPick = { prefs.setLyricsGapMark(it) },
    ) { GapSample(it == LyricsGapMark.NOTE, s.lyrics.align == LyricsAlign.CENTER) }
}

/** Your colors behind the lyrics: the row opens "Top color / Bottom color (/ One color)", and those the color picker. */
@Composable
private fun LsYourColorsRow(d: AppSettings) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    var choosing by rememberSaveable { mutableStateOf(false) }
    // 1 the top color, 2 the bottom one.
    var picking by remember { mutableStateOf<Int?>(null) }
    PickRow(
        tr(R.string.your_colors), if (s.lyricsBgColor2 == 0) tr(R.string.one_color) else tr(R.string.ls_top_and_bottom),
        info = tr(R.string.kept_dark_enough_for_white_words),
        changed = s.lyricsBgColor != d.lyricsBgColor || s.lyricsBgColor2 != d.lyricsBgColor2,
        thumbnail = { Box(Modifier.fillMaxSize().background(backgroundSample(LyricsBackground.COLOR))) },
    ) { choosing = true }
    if (choosing) PickListSheet(
        tr(R.string.your_colors), listOf(1, 2) + if (s.lyricsBgColor2 != 0) listOf(0) else emptyList(), -1,
        label = { when (it) { 1 -> tr(R.string.top_color); 2 -> tr(R.string.bottom_color); else -> tr(R.string.one_color) } },
        onPick = { if (it == 0) prefs.setLyricsBgColors(s.lyricsBgColor, 0) else picking = it },
        onDismiss = { choosing = false }, info = tr(R.string.kept_dark_enough_for_white_words), searchable = false,
    )
    picking?.let { which ->
        ColorPickerSheet(
            if (which == 1) tr(R.string.top_color) else tr(R.string.bottom_color),
            if (which == 1) s.lyricsBgColor else s.lyricsBgColor2.takeIf { it != 0 } ?: s.lyricsBgColor,
            onDismiss = { picking = null },
            reset = if (which == 2 && s.lyricsBgColor2 != 0) tr(R.string.one_color) to { prefs.setLyricsBgColors(s.lyricsBgColor, 0) } else null,
            preview = { col ->
                val top = cardColor(Color(if (which == 1) col else s.lyricsBgColor))
                val bottom = if (which == 2) cardColor(Color(col)) else if (s.lyricsBgColor2 == 0) androidx.compose.ui.graphics.lerp(top, Color.Black, 0.3f) else cardColor(Color(s.lyricsBgColor2))
                LyricsSample(lsLook(), Modifier.fillMaxWidth(), ground = Brush.verticalGradient(listOf(top, bottom)), lines = 2)
            },
        ) { col -> if (which == 1) prefs.setLyricsBgColors(col, s.lyricsBgColor2) else prefs.setLyricsBgColors(s.lyricsBgColor, col) }
    }
}

// ---------------------------------------------------------------------------- Text

@Composable
internal fun LsTextRows(d: AppSettings) {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val q = d.lyricsLook
    val prefs = LocalContainer.current.settings
    LsFontRow(d)
    SliderRow(tr(R.string.lyrics_size), s.lyrics.scale, 0.75f..1.4f, ::lsPercent, changed = s.lyrics.scale != d.lyrics.scale) { prefs.setLyricsScale(it) }
    ChoiceRow(tr(R.string.alignment), LyricsAlign.entries, s.lyrics.align, { it.label }, changed = s.lyrics.align != d.lyrics.align) { prefs.setLyricsAlign(it) }
    ChoiceRow(tr(R.string.weight), LyricsWeight.entries, p.weightNow, { it.label }, changed = p.weightNow != q.weightNow) { prefs.setLyricsWeight(it) }
    SliderRow(tr(R.string.line_spacing), p.spacing, 0.6f..1.8f, ::lsSpacingText, changed = p.spacing != q.spacing) { prefs.setLyricsSpacing(it) }
    SwitchRow(tr(R.string.capitals), p.uppercase, changed = p.uppercase != q.uppercase) { prefs.setLyricsUppercase(it) }
    PickCardsRow(
        tr(R.string.where_the_sung_line_sits), LyricsPosition.entries, p.position, { it.label }, changed = p.position != q.position, columns = 3,
        onPick = { prefs.setLyricsPosition(it) },
    ) { PositionSample(it.fraction, p.ahead.lines) }
}

/** The values of the font pick that aren't fonts: an installed font, another file, the file removed. */
private const val FONT_INSTALLED = "\u0000installed"
private const val FONT_OTHER_FILE = "\u0000file"
private const val FONT_REMOVE = "\u0000remove"

/**
 * The lyrics' font (from the Text page, plan §2.11): the app's, one of Vinz's, or a font of the listener's own, from a
 * file (#12) or from the phone's installed fonts (#318, Android 10 and later). The sheet's last lines pick another
 * file and remove it.
 */
@Composable
private fun LsFontRow(d: AppSettings) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val fontTypes = arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream")
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppFonts.import(context, uri, AppFonts.FontSlot.LYRICS) } }
            r.onSuccess { error = null; prefs.setLyricsFontFile(it); prefs.setLyricsFont(FontChoice.CUSTOM.name) }
                .onFailure { error = it.message ?: tr(R.string.that_font_couldn_t_be_used) }
        }
    }
    val hasFile = remember(s.fontStamp) { AppFonts.customFile(AppFonts.FontSlot.LYRICS) != null }
    var installed by remember { mutableStateOf(false) }
    // "App font" keeps each lyric view's own face (the app's); the rest are Vinz's fonts, or a file just for lyrics.
    val fonts = listOf("") + FontChoice.entries.filter { it != FontChoice.CUSTOM && AppFonts.available(it) }.map { it.name } + FontChoice.CUSTOM.name
    val more = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 29) add(FONT_INSTALLED)
        if (hasFile) { add(FONT_OTHER_FILE); add(FONT_REMOVE) }
    }
    val label: (String) -> String = { v ->
        when (v) {
            FONT_INSTALLED -> tr(R.string.installed_fonts_for_lyrics)
            FONT_OTHER_FILE -> tr(R.string.ls_pick_a_lyrics_font_file)
            FONT_REMOVE -> tr(R.string.remove_the_lyrics_font_file)
            FontChoice.CUSTOM.name -> if (hasFile) s.lyrics.fontName.ifBlank { tr(R.string.your_font_2) } else tr(R.string.from_a_file)
            else -> lsFontText(v, "")
        }
    }
    val now = s.lyrics.font.takeIf { it in fonts } ?: ""
    PickListRow(
        tr(R.string.lyrics_font), fonts + more, now, label,
        // A file that couldn't be used says so here, until one can.
        value = error ?: label(now),
        changed = s.lyrics.font != d.lyrics.font,
        detail = { v ->
            when (v) {
                FONT_INSTALLED -> tr(R.string.installed_fonts_detail)
                FONT_OTHER_FILE -> tr(R.string.lyrics_font_file_2, s.lyrics.fontName)
                else -> null
            }
        },
        searchable = false,
        onPick = { v ->
            when (v) {
                FONT_INSTALLED -> installed = true
                FONT_OTHER_FILE -> pickFile.launch(fontTypes)
                FONT_REMOVE -> {
                    AppFonts.remove(AppFonts.FontSlot.LYRICS)
                    if (s.lyrics.font == FontChoice.CUSTOM.name) prefs.setLyricsFont("")
                    prefs.setLyricsFontFile("")
                }
                FontChoice.CUSTOM.name -> if (hasFile) prefs.setLyricsFont(v) else pickFile.launch(fontTypes)
                else -> prefs.setLyricsFont(v)
            }
        },
    )
    if (installed) InstalledFontsSheet(AppFonts.FontSlot.LYRICS, onPicked = { name -> prefs.setLyricsFontFile(name); prefs.setLyricsFont(FontChoice.CUSTOM.name) }) { installed = false }
}

// ---------------------------------------------------------------------------- Color and effects

@Composable
internal fun LsColorRows(d: AppSettings) {
    val s = LocalAppSettings.current
    val p = s.lyricsLook
    val q = d.lyricsLook
    val prefs = LocalContainer.current.settings
    val old = android.os.Build.VERSION.SDK_INT < 31
    // Whose "Your color" is being picked: the sung line's (true) or the other lines' (false).
    var picking by remember { mutableStateOf<Boolean?>(null) }

    ChoiceRow(
        tr(R.string.the_line_being_sung), LyricsColor.entries, p.sungColor, { it.label },
        info = if (p.motionNow == LyricsMotion.FILL && p.sungColor == LyricsColor.WHITE) tr(R.string.with_color_fills_white_fills_with_your_light) else null,
        changed = p.sungColor != q.sungColor || (p.sungColor == LyricsColor.CUSTOM && p.sungCustom != q.sungCustom),
    ) { if (it == LyricsColor.CUSTOM) picking = true else prefs.setLyricsSungColor(it) }
    ChoiceRow(
        tr(R.string.the_other_lines), LyricsColor.entries.filter { it != LyricsColor.SPECTRUM }, p.otherColor, { it.label },
        info = if (p.onCard) tr(R.string.on_bold_card_s_color_the) else null,
        changed = p.otherColor != q.otherColor || (p.otherColor == LyricsColor.CUSTOM && p.otherCustom != q.otherCustom),
    ) { if (it == LyricsColor.CUSTOM) picking = false else prefs.setLyricsOtherColor(it) }
    SliderRow(tr(R.string.other_lines_brightness), p.othersAlpha, 0.2f..1f, ::lsAlphaText, changed = p.othersAlpha != q.othersAlpha) { prefs.setLyricsOthersAlpha(it) }
    SliderRow(
        tr(R.string.focus_blur), p.blurAmount, 0f..2f, ::lsBlurText,
        info = if (old) tr(R.string.focus_blur_needs_android_12_or) else null, changed = p.blurAmount != q.blurAmount,
    ) { prefs.setLyricsBlurAmount(if (it < 0.05f) 0f else it) }
    ChoiceRow(tr(R.string.lines_to_come), LyricsAhead.entries, p.ahead, { it.label }, info = tr(R.string.how_many_of_the_next_lines), changed = p.ahead != q.ahead) { prefs.setLyricsAhead(it) }

    val effects = effectsToMerged(p.haloNow, p.shadow, s.lyrics.progressLine)
    fun set(e: LyricsEffect, on: Boolean) {
        val (glow, shadow, line) = effectsToStored(if (on) effects + e else effects - e)
        when (e) {
            LyricsEffect.GLOW -> prefs.setLyricsHalo(glow)
            LyricsEffect.SHADOW -> prefs.setLyricsShadow(shadow)
            LyricsEffect.PROGRESS_LINE -> prefs.setLyricsProgressLine(line)
        }
    }
    ShowChipsRow(
        tr(R.string.effects), LyricsEffect.entries.map { e -> ShowOption(effectLabel(e), e in effects) { set(e, it) } },
        info = listOfNotNull(
            tr(R.string.glow_round_the_words), tr(R.string.helps_the_words_read_over_photos), tr(R.string.split_light_under_the_line_being),
            if (old) tr(R.string.glow_needs_android_12_or_later) else null,
        ).joinToString("\n"),
        changed = effects != effectsToMerged(q.haloNow, q.shadow, d.lyrics.progressLine),
    )
    SliderRow(tr(R.string.background_vocals), p.backingSize, 0.4f..0.9f, ::lsBackingText, changed = p.backingSize != q.backingSize) { prefs.setLyricsBackingSize(it) }

    picking?.let { sung ->
        ColorPickerSheet(
            if (sung) tr(R.string.the_line_being_sung) else tr(R.string.the_other_lines),
            if (sung) p.sungCustom else p.otherCustom,
            onDismiss = { picking = null },
            preview = { col ->
                val look = lsLook()
                val color = readableOnDark(Color(col))
                LyricsSample(if (sung) look.copy(sung = SolidColor(color), sungSolid = color) else look.copy(others = color), Modifier.fillMaxWidth(), lines = 2)
            },
        ) { col -> if (sung) prefs.setLyricsSungCustom(col) else prefs.setLyricsOtherCustom(col) }
    }
}

// ---------------------------------------------------------------------------- Language and translations

@Composable
internal fun LsLanguageRows(d: AppSettings) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val translate = translateToMerged(s.lyrics.translate, s.lyrics.translateTo)
    PickListRow(
        tr(R.string.translate_into), listOf(LS_TRANSLATE_OFF) + LS_TRANSLATE_LANGUAGES.map { it.first }, translate, ::translateLabel,
        info = tr(R.string.for_songs_in_another_language_machine),
        changed = translate != translateToMerged(d.lyrics.translate, d.lyrics.translateTo), searchable = true,
        onPick = { code -> if (code == LS_TRANSLATE_OFF) prefs.setLyricsTranslate(false) else { prefs.setLyricsTranslateTo(code); prefs.setLyricsTranslate(true) } },
    )
    val romanize = romanizeToMerged(s.romanize, s.romanizeAll)
    PickListRow(
        tr(R.string.romanize_lyrics), romanizeOptions(s.romanizeAll), romanize, ::romanizeLabel,
        info = tr(R.string.other_scripts_in_latin_letters_to) + " " + tr(R.string.korean_japanese_russian_and_greek_are),
        changed = romanizeLabel(romanize) != romanizeLabel(romanizeToMerged(d.romanize, d.romanizeAll)), searchable = false,
        onPick = { romanizeToStored(it).let { (mode, all) -> prefs.setRomanize(mode); prefs.setRomanizeAll(all) } },
    )
    LsKanjiRow()
    ChoiceRow(
        tr(R.string.translation_place), LyricsExtraPlace.entries, s.lyricsExtraPlace, { it.label }, info = tr(R.string.how_a_translation_or_a_line),
        changed = s.lyricsExtraPlace != d.lyricsExtraPlace,
    ) { prefs.setLyricsExtraPlace(it) }
    SliderRow(tr(R.string.translation_size), s.lyricsExtraSize, 0.7f..1.4f, ::lsPercent, changed = s.lyricsExtraSize != d.lyricsExtraSize) { prefs.setLyricsExtraSize(it) }
    ChoiceRow(
        tr(R.string.translation_color), LyricsColor.entries, s.lyricsExtraColor, { it.label },
        info = if (s.lyricsExtraColor == LyricsColor.CUSTOM) tr(R.string.your_color_here_is_the_one) else null,
        changed = s.lyricsExtraColor != d.lyricsExtraColor,
    ) { prefs.setLyricsExtraColor(it) }
    SwitchRow(
        tr(R.string.translation_in_italics), s.lyricsExtraItalic, info = tr(R.string.sets_them_apart_from_the_lyrics),
        changed = s.lyricsExtraItalic != d.lyricsExtraItalic,
    ) { prefs.setLyricsExtraItalic(it) }
}

// In the same (decimal) megabytes as the download sizes, so "74 MB on the phone" and the installed size agree.
private fun formatMb(bytes: Long) = "${bytes / 1_000_000} MB"

/** Kuromoji's dictionary, for Japanese lyrics' kanji: download, progress, or remove. */
@Composable
private fun LsKanjiRow() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val states by c.models.states.collectAsState()
    val m = ModelStore.Model.JAPANESE
    val state = states[m]
    val value = when (state) {
        ModelStore.State.Ready -> tr(R.string.on_this_phone_tap_to_remove, formatMb(c.models.sizeOnPhone(m)))
        is ModelStore.State.Downloading -> tr(R.string.downloading_5, (state.fraction * 100).toInt())
        is ModelStore.State.Failed -> tr(R.string.couldn_t_download_it_tap_to)
        else -> tr(R.string.only_for_japanese_songs, ModelStore.Model.sizeText(m))
    }
    LinkRow(tr(R.string.japanese_kanji_readings), value = value, info = tr(R.string.only_japanese_needs_this_its_kanji), action = true) {
        when (state) {
            ModelStore.State.Ready -> c.models.delete(m)
            is ModelStore.State.Downloading -> {}
            else -> scope.launch { c.models.download(m) }
        }
    }
}

// ---------------------------------------------------------------------------- Sources and timing

@Composable
internal fun LsSourceRows(d: AppSettings) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    SwitchRow(tr(R.string.prefer_karaoke_synced_lyrics), s.preferSynced, info = tr(R.string.karaoke_lyrics_lit_up_word_by), changed = s.preferSynced != d.preferSynced) { prefs.setPreferSynced(it) }
    SwitchRow(tr(R.string.uncensor_lyrics), s.skipCensored, info = tr(R.string.swear_words_starred_out_f_k), changed = s.skipCensored != d.skipCensored) { prefs.setSkipCensored(it) }
    LsSourceOrder(d)
    // Its only control anywhere (plan §2.6): kept.
    SwitchRow(tr(R.string.lyrics_source_button), s.lyrics.sourceButton, info = tr(R.string.lyrics_source_button_info), changed = s.lyrics.sourceButton != d.lyrics.sourceButton) { prefs.setLyricsSourceButton(it) }
    // #404: every song's lyrics a little earlier or later; applied at once.
    val offset = s.behavior.lyricsOffsetMs
    SliderRow(
        tr(R.string.ls_offset), offset.toFloat(), LS_OFFSET_RANGE, { lyricsOffsetText(lyricsOffsetSnap(it)) }, steps = LS_OFFSET_STEPS,
        info = tr(R.string.ls_offset_info), changed = offset != d.behavior.lyricsOffsetMs,
    ) { prefs.setLyricsOffsetMs(lyricsOffsetSnap(it)) }
}

/**
 * The lyric sources in the order they're asked, each with its switch (#51); hold one to move it. "Your music files"
 * among them is the switch for reading lyrics from the listener's own files (#344), its only one on the page now.
 * As it was on the old page (the arrows go when reorder lists change, plan §2.8). A list in a group: the kit has no
 * row type for it, so it carries its own tag and says the first source asked as its value.
 */
@Composable
private fun LsSourceOrder(d: AppSettings) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val title = tr(R.string.ls_source_order)
    var open by rememberSaveable { mutableStateOf(false) }
    val changed = s.lyrics.order != d.lyrics.order || s.lyrics.off != d.lyrics.off || s.behavior.embeddedLyrics != d.behavior.embeddedLyrics
    Column(
        Modifier.fillMaxWidth().testTag("lyricsSing:sourceOrder").semantics {
            settingsRowValue = s.lyrics.order.firstOrNull(s::lyricsSourceOn)?.label.orEmpty()
            settingsRowChanged = changed
        },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (changed) ChangedDot()
            InfoMark(open, title) { open = !open }
        }
        InfoText(open, tr(R.string.tried_top_to_bottom_hold_one), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        // The grip moves a source (drag, TalkBack's moves, D-pad / keyboard; 1.8.4: the up / down arrows went).
        ReorderList(s.lyrics.order, { it }, { prefs.setLyricsOrder(it) }) { src, i, _, mod ->
            val on = s.lyricsSourceOn(src)
            Row(mod.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(if (i == 0 && on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = if (i == 0 && on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).alpha(if (on) 1f else 0.45f)) {
                    Text(src.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(src.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Turned off, a source is never asked (#51); at least one stays on.
                VinzSwitch(
                    on, { v -> if (src == LyricsSource.EMBEDDED) prefs.setEmbeddedLyrics(v) else prefs.setLyricsOff(if (v) s.lyrics.off - src else s.lyrics.off + src) },
                    enabled = !on || s.lyrics.order.count(s::lyricsSourceOn) > 1,
                    modifier = Modifier.scale(0.8f).testTag("lyricsSing:source:" + src.name),
                )
                DragGrip(Modifier.dragHandle().padding(start = 2.dp), tr(R.string.drag, src.label))
            }
        }
        Spacer(Modifier.heightIn(min = 6.dp))
    }
}

// ---------------------------------------------------------------------------- Sing and music files

@Composable
internal fun LsSingRows(d: AppSettings) {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val scope = rememberCoroutineScope()
    val states by c.models.states.collectAsState()
    LaunchedEffect(Unit) { c.models.refresh() }
    val runtime = ModelStore.Model.singRuntime()
    val model = ModelStore.Model.SING_MODEL
    var used by remember { mutableStateOf(c.sing.store.bytes()) }
    if (runtime == null) {
        // Sing's full separation doesn't run on this phone's processor: the row says so, the why behind its ⓘ.
        LinkRow(tr(R.string.sing_s_voice_separation), value = tr(R.string.ls_sing_quick_way), info = tr(R.string.sing_doesn_t_run_on_this), enabled = false) {}
    } else {
        val ready = states[runtime] == ModelStore.State.Ready && states[model] == ModelStore.State.Ready
        val downloading = listOf(runtime, model).mapNotNull { states[it] as? ModelStore.State.Downloading }.firstOrNull()
        LinkRow(
            tr(R.string.sing_s_voice_separation),
            value = when {
                ready -> tr(R.string.on_this_phone_tap_to_remove, formatMb(c.models.sizeOnPhone(runtime) + c.models.sizeOnPhone(model)))
                downloading != null -> tr(R.string.downloading_5, (downloading.fraction * 100).toInt())
                else -> tr(R.string.needed_for_sing, ModelStore.Model.sizeText(runtime, model))
            },
            info = tr(R.string.takes_the_singer_s_voice_out), action = true,
        ) {
            if (ready) { c.sing.setOn(false); c.models.delete(runtime); c.models.delete(model) }
            else if (downloading == null) scope.launch { c.sing.downloadEngine() }
        }
        SwitchRow(
            tr(R.string.get_songs_ready_while_sing_is), s.singAlwaysReady, info = tr(R.string.the_playing_song_and_the_next),
            changed = s.singAlwaysReady != d.singAlwaysReady,
        ) { prefs.setSingAlwaysReady(it) }
        SliderRow(
            tr(R.string.room_for_sung_songs), s.singCacheMb.toFloat().coerceAtMost(LS_SING_ROOM_TOP), 256f..LS_SING_ROOM_TOP, ::lsSingRoomText, steps = 15,
            changed = s.singCacheMb != d.singCacheMb,
        ) { prefs.setSingCacheMb(if (it >= LS_SING_ROOM_TOP - 1f) SING_ROOM_UNLIMITED else it.roundToInt()) }
        LinkRow(tr(R.string.clear_sung_songs), value = tr(R.string.using, formatMb(used)), info = tr(R.string.songs_are_worked_out_again_the), action = true) { c.sing.store.clear(); used = 0 }
    }
    LinkRow(
        tr(R.string.save_lyrics_into_your_music_files), value = tr(R.string.so_other_players_show_them_too), info = tr(R.string.synced_lyrics_go_into_your_flac), action = true,
    ) { c.lyricsToFiles.request(null) }
    val with = savedWithToMerged(s.lyrics.fileWords, s.lyrics.fileTranslation)
    fun save(w: SavedWith, on: Boolean) {
        val (words, translation) = savedWithToStored(if (on) with + w else with - w)
        if (w == SavedWith.WORD_TIMINGS) prefs.setLyricsFileWords(words) else prefs.setLyricsFileTranslation(translation)
    }
    ShowChipsRow(
        tr(R.string.ls_when_saving),
        listOf(
            ShowOption(tr(R.string.ls_word_timings), SavedWith.WORD_TIMINGS in with) { save(SavedWith.WORD_TIMINGS, it) },
            ShowOption(tr(R.string.ls_translation), SavedWith.TRANSLATION in with) { save(SavedWith.TRANSLATION, it) },
        ),
        info = tr(R.string.ls_when_saving_info),
        changed = with != savedWithToMerged(d.lyrics.fileWords, d.lyrics.fileTranslation),
    )
}
