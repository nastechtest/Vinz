package com.vinz.music.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.lyrics.Lyrics
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.CarLyricsLines
import com.vinz.music.data.prefs.CarLyricsStyle
import com.vinz.music.playback.CarLyricsCard
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.Artwork
import com.vinz.music.ui.screens.Needs
import com.vinz.music.ui.screens.SettingEntry
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val page = SettingsPageId.CAR

/**
 * Whether the rows that shape the lyric line outside Vinz show (Style, Keep the song title, Long lines): with
 * "Lyrics in the car" on, or with "Lyrics in the notification" on (Gestures & buttons), since the one media session
 * feeds both (#392).
 */
internal fun carLyricRowsShow(s: AppSettings): Boolean = s.carLyrics || s.carLyricsOnPhone

/** "Keep the song title" only means something with a lyric card on the cover (as `CarLyrics.tick` reads it). */
internal fun carKeepsTitle(s: AppSettings): Boolean = s.carLyricsKeepTitle && s.carLyricsStyle != CarLyricsStyle.TEXT

/**
 * Whether "Long lines" does anything: not while the song's own title is kept (`carLyricText` then never looks at it:
 * the title is the song's, there is no lyric in it to wrap).
 */
internal fun carLongLinesApply(s: AppSettings): Boolean = !carKeepsTitle(s)

/** Where "Buttons in the car" leads: the buttons' one home, Gestures & buttons → Notification and car. */
internal val carButtonsRoute: String get() = Routes.settings(SettingsPageId.GESTURES_BUTTONS.key, tr(R.string.notification_and_car))

/**
 * Settings → Car (1.8.4's map, plan §2.3), built with the kit: Android Auto's lyrics (their switch, style, whether the
 * song's title stays, and what a long line does), its home as a grid, and a link to its buttons. This file is the only
 * place for the page: its composable, its lines in Settings' search ([carIndex]), its "Reset this page" keys
 * ([carLookKeys]), its line on the root ([carValue]) and the old group names it answers to ([carOldGroups]).
 *
 * "Lyrics in the notification" ("On this phone too" before 1.8.4) is on Gestures & buttons, its one home. The old
 * page's Connections list is the Settings root now, and Sharing and links is on Accounts & sharing.
 */
@Composable
fun CarPage(bottomPadding: Dp, find: String? = null, showBack: Boolean = true, onSearch: (() -> Unit)? = null) {
    val s = LocalAppSettings.current
    val prefs = LocalContainer.current.settings
    val nav = LocalNavigator.current
    val d = remember { AppSettings() }
    val reset = ListeningResets()
    val onOff: (Boolean) -> String = { ListeningResets.onOff(it) }

    val lyricsChanged = reset.add(tr(R.string.lyrics_in_the_car), s.carLyrics, d.carLyrics, onOff) { prefs.setCarLyrics(it) }
    val styleChanged = reset.add(tr(R.string.car_lyrics_style), s.carLyricsStyle, d.carLyricsStyle, { it.label }) { prefs.setCarLyricsStyle(it) }
    val keepTitleChanged = reset.add(tr(R.string.keep_the_song_title), s.carLyricsKeepTitle, d.carLyricsKeepTitle, onOff) { prefs.setCarLyricsKeepTitle(it) }
    val linesChanged = reset.add(tr(R.string.car_long_lines), s.behavior.carLyricsLines, d.behavior.carLyricsLines, { it.label }) { prefs.setCarLyricsLines(it) }
    val gridChanged = reset.add(tr(R.string.covers_in_a_grid), s.autoGrid, d.autoGrid, onOff) { prefs.setAutoGrid(it) }

    SettingsPageScaffold(page.title, bottomPadding, showBack = showBack, onSearch = onSearch, find = find, reset = reset.pageReset()) {
        group(tr(R.string.android_auto)) {
            SwitchRow(tr(R.string.lyrics_in_the_car), s.carLyrics, info = tr(R.string.in_place_of_the_song_title), changed = lyricsChanged) { prefs.setCarLyrics(it) }
            // These three shape the notification's lyrics too, so they also show with only that switch on.
            if (carLyricRowsShow(s)) {
                PickCardsRow(
                    tr(R.string.car_lyrics_style), CarLyricsStyle.entries, s.carLyricsStyle, { it.label },
                    info = tr(R.string.car_style_info), changed = styleChanged, exampleScale = 1f,
                    onPick = { prefs.setCarLyricsStyle(it) },
                ) { CarLyricsExample(it) }
                // With "Title only" there'd be nothing left to show.
                if (s.carLyricsStyle != CarLyricsStyle.TEXT) SwitchRow(
                    tr(R.string.keep_the_song_title), s.carLyricsKeepTitle, info = tr(R.string.the_car_s_small_media_card), changed = keepTitleChanged,
                ) { prefs.setCarLyricsKeepTitle(it) }
                // PR #400 (1.8.4). Greyed while the song's own title is kept: there is no lyric in the title to wrap.
                ChoiceRow(
                    tr(R.string.car_long_lines), CarLyricsLines.entries, s.behavior.carLyricsLines, { it.label },
                    info = tr(R.string.car_long_lines_info), changed = linesChanged, enabled = carLongLinesApply(s),
                ) { prefs.setCarLyricsLines(it) }
            }
            SwitchRow(tr(R.string.covers_in_a_grid), s.autoGrid, info = tr(R.string.the_car_shows_home_s_sections), changed = gridChanged) { prefs.setAutoGrid(it) }
            // 1.8.7.5: the value is the buttons themselves (as on Gestures & buttons); the sentence moved behind the ⓘ.
            LinkRow(
                tr(R.string.buttons_in_the_car), value = s.sessionButtons.joinToString(" · ") { it.label },
                info = tr(R.string.beside_play_in_the_order_the),
            ) { nav.go(carButtonsRoute) }
        }
    }
}

/**
 * A style's card in the Style sheet: what the car shows right now in that style (or would), drawn by the same code
 * that draws it for the car, over what the style does in words. Without a song, or without synced lyrics for it, the
 * cover as the car shows it then.
 *
 * The lyric card is only drawn again when the line (or, in Karaoke, the word) changes.
 */
@Composable
private fun CarLyricsExample(style: CarLyricsStyle) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val song by c.player.currentSong.collectAsState()
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var bg by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var card by remember(style) { mutableStateOf<ImageBitmap?>(null) }
    var line by remember(style) { mutableStateOf<String?>(null) }
    LaunchedEffect(song?.id) {
        lyrics = null; card = null; line = null
        val now = song ?: return@LaunchedEffect
        lyrics = runCatching { c.lyrics.auto(now) }.getOrNull()?.takeIf { it.synced }
        bg = runCatching { CarLyricsCard.background(context, now.thumbnail) }.getOrNull()
    }
    LaunchedEffect(lyrics, style, bg) {
        val l = lyrics ?: return@LaunchedEffect
        var drawn: Pair<Int, Int>? = null
        while (true) {
            val pos = c.player.lyricPosition
            var idx = CarLyricsCard.lineAt(l.lines, pos)
            // Before the first line, or in a gap: the line to come.
            if (idx < 0 || l.lines[idx].isGap) idx = l.lines.indexOfFirst { !it.isGap && it.text.isNotBlank() && it.timeMs >= pos }.takeIf { it >= 0 } ?: idx
            val ln = l.lines.getOrNull(idx)
            if (ln != null && !ln.isGap) {
                val sung = if (style == CarLyricsStyle.KARAOKE && ln.isWordSynced) ln.words.count { it.startMs <= pos } else -1
                if (drawn != idx to sung) {
                    drawn = idx to sung
                    line = ln.text
                    card = if (style == CarLyricsStyle.TEXT) null else withContext(Dispatchers.Default) { CarLyricsCard.render(bg, l.lines, idx, sung).asImageBitmap() }
                }
            }
            delay(300)
        }
    }
    Column {
        val shape = LocalUi.current.shape(12.dp)
        val img = card
        if (img != null) Image(img, tr(R.string.lyric_card), Modifier.fillMaxWidth().aspectRatio(1f).clip(shape))
        else Artwork(song?.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), shape, size = 300)
        Spacer(Modifier.height(6.dp))
        // The title as the car reads it in this style.
        (line ?: song?.title)?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Text(style.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The root's line for Car: lyrics in the car and their style when on, else just "Android Auto". */
fun carValue(s: AppSettings, dark: Boolean): String =
    if (s.carLyrics) tr(R.string.lyrics_in_the_car) + " · " + s.carLyricsStyle.label else tr(R.string.android_auto)

/** The look settings "Reset this page" puts back: none (the car isn't part of a look; the page resets its rows itself). */
val carLookKeys: List<String> = emptyList()

/** Old group titles → this page's groups: none to translate ("Android Auto" is still called that). */
val carOldGroups: Map<String, String> = emptyMap()

/** Style, Keep the song title and Long lines only show with lyrics on in the car or in the notification. */
private val lyricsSomewhere by lazy { Needs(tr(R.string.car_needs_lyrics)) { carLyricRowsShow(it) } }

/** The page's lines in Settings' search (each names the group it is drawn in). */
val carIndex: List<SettingEntry> by lazy {
    val auto = tr(R.string.android_auto)
    listOf(
        SettingEntry(page, auto, tr(R.string.lyrics_in_the_car), tr(R.string.android_auto_2)),
        SettingEntry(page, auto, tr(R.string.car_lyrics_style), tr(R.string.android_auto_car), needs = lyricsSomewhere),
        // "Lyrics on the cover only" was the switch's second line.
        SettingEntry(page, auto, tr(R.string.keep_the_song_title), tr(R.string.android_auto_car) + " " + tr(R.string.lyrics_on_the_cover_only), needs = lyricsSomewhere),
        SettingEntry(page, auto, tr(R.string.car_long_lines), tr(R.string.car_long_lines_keywords), needs = lyricsSomewhere),
        SettingEntry(page, auto, tr(R.string.covers_in_a_grid), tr(R.string.android_auto_car_home_tiles_grid)),
        SettingEntry(page, auto, tr(R.string.buttons_in_the_car), tr(R.string.android_auto_car_buttons_shuffle_like)),
    )
}
