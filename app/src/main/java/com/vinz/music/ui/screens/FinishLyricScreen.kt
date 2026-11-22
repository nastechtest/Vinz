package com.vinz.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vinz.music.R
import com.vinz.music.data.FinishLyric
import com.prism.music.data.db.mergeUploads
import com.vinz.music.data.model.Song
import com.vinz.music.playback.QueueKind
import com.vinz.music.playback.QueueSource
import com.vinz.music.tr
import com.vinz.music.ui.components.Artwork
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Top songs looked at for lines (most played first), and how many with usable lyrics are enough for variety. */
private const val LOOK_AT = 40
private const val ENOUGH = 20

/** What the game plays from: songs with saved lyrics, and the songs themselves for playing them from the result. */
class FinishLyricPool(val lyrics: List<FinishLyric.SongLyrics>, val songs: Map<String, Song>) {
    val playable: Boolean by lazy { FinishLyric.canPlay(lyrics) }
}

/**
 * The listener's most played songs between [from] and [to] with lyrics already saved on the phone. Nothing is
 * looked up: [com.vinz.music.data.lyrics.LyricsRepository.savedOnly] reads the saved copies (and a local file's own).
 */
internal suspend fun loadFinishLyricPool(c: AppContainer, from: Long, to: Long): FinishLyricPool = withContext(Dispatchers.IO) {
    val top = c.imports.replay().topSongs(from, to, LOOK_AT * 2).mergeUploads().take(LOOK_AT)
    val lyrics = ArrayList<FinishLyric.SongLyrics>()
    val songs = HashMap<String, Song>()
    for (p in top) {
        if (lyrics.size >= ENOUGH) break
        val song = p.toSong()
        val saved = runCatching { c.lyrics.savedOnly(song) }.getOrNull() ?: continue
        val lines = saved.lines.filter { !it.isGap && it.text.isNotBlank() }.map { it.text }
        if (FinishLyric.usable(lines).isEmpty()) continue
        lyrics += FinishLyric.SongLyrics(p.songId, p.title, p.artistName, p.thumbnail, lines)
        songs[p.songId] = song
    }
    FinishLyricPool(lyrics, songs)
}

/**
 * The "Finish the lyric" card (plan §3.8), for Replay and the end of a story: it shows only when the setting is on and
 * there are enough songs with saved lyrics for a round, and opens the game itself. Nothing loads while the setting is
 * off. [from]..[to] are the plays the songs come from (all time unless given).
 */
@Composable
fun FinishLyricCard(modifier: Modifier = Modifier, from: Long = 0L, to: Long = -1L) {
    val c = LocalContainer.current
    val on = LocalAppSettings.current.behavior.finishLyric
    val pool by produceState<FinishLyricPool?>(null, on, from, to) {
        value = null
        if (!on) return@produceState
        value = runCatching { loadFinishLyricPool(c, from, if (to < 0) System.currentTimeMillis() else to) }.getOrNull()
    }
    var open by rememberSaveable { mutableStateOf(false) }
    FinishLyricCardContent(on, pool, modifier) { open = true }
    val p = pool
    if (open && p != null && on) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                FinishLyricGame(
                    p.lyrics,
                    onPlay = { round, i ->
                        val list = round.mapNotNull { p.songs[it.songId] }
                        val at = list.indexOfFirst { it.id == round[i].songId }.coerceAtLeast(0)
                        if (list.isNotEmpty()) c.player.playQueue(list, at, QueueSource(QueueKind.REPLAY, tr(R.string.finish_lyric_title)))
                    },
                    onClose = { open = false },
                )
            }
        }
    }
}

/** The card itself: nothing when [enabled] is off, while loading, or with too few songs for a round. */
@Composable
internal fun FinishLyricCardContent(enabled: Boolean, pool: FinishLyricPool?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    if (!enabled || pool == null || !pool.playable) return
    val scheme = MaterialTheme.colorScheme
    val title = tr(R.string.finish_lyric_title)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(onClickLabel = tr(R.string.finish_lyric_start), role = Role.Button, onClick = onOpen)
            .padding(16.dp)
            .testTag(FinishLyricTags.CARD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VinzIcons.Lyrics, null, Modifier.size(32.dp), tint = scheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(tr(R.string.finish_lyric_card_text), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(VinzIcons.Play, null, Modifier.size(24.dp), tint = scheme.primary)
    }
}

internal object FinishLyricTags {
    const val CARD = "finish_lyric_card"
    const val LINE = "finish_lyric_line"
    const val RESULT = "finish_lyric_result"
    fun choice(i: Int) = "finish_lyric_choice_$i"
}

/**
 * The game: [FinishLyric.ROUND] lines one after another, four endings each, then the score with the round's songs
 * ([onPlay] plays them, from the one tapped). Still: no animation at all, so Performance mode and reduced motion need
 * nothing of their own. The round is rebuilt from a kept seed, so it survives the screen turning.
 */
@Composable
fun FinishLyricGame(
    songs: List<FinishLyric.SongLyrics>,
    onPlay: (List<FinishLyric.SongLyrics>, Int) -> Unit,
    onClose: () -> Unit,
    seed: Long? = null,
) {
    val start = rememberSaveable { seed ?: System.nanoTime() }
    var roundNo by rememberSaveable { mutableIntStateOf(0) }
    val questions = remember(songs, start, roundNo) { FinishLyric.round(songs, Random(start + roundNo)) }
    var index by rememberSaveable(roundNo) { mutableIntStateOf(0) }
    var picks by rememberSaveable(roundNo) { mutableStateOf(List<Int?>(questions.size) { null }) }
    var done by rememberSaveable(roundNo) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.finish_lyric_title), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onClose) { Icon(VinzIcons.Close, tr(R.string.close)) }
        }
        if (questions.isEmpty()) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (done) {
                Result(questions, picks, onPlay = { i -> onPlay(questions.map { it.song }, i) }, onAgain = { roundNo++ }, onClose = onClose)
                return@Column
            }
            val q = questions[index]
            val pick = picks[index]
            val rtl = FinishLyric.isRtl(q.prompt + q.answer)
            val direction = if (rtl) TextDirection.Rtl else TextDirection.Ltr
            Text(
                tr(R.string.finish_lyric_question, index + 1, questions.size),
                style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
            )
            // The line with a gap where its ending goes (filled in once picked); read out as the line and "then a missing word".
            val gap = if (pick == null) "_____" else q.answer
            val sep = if (q.prompt.isNotEmpty() && FinishLyric.scriptOf(q.answer) != "CJK") " " else ""
            Text(
                q.prompt + sep + gap,
                Modifier.fillMaxWidth().testTag(FinishLyricTags.LINE).semantics {
                    if (pick == null) contentDescription = tr(R.string.finish_lyric_line_a11y, q.prompt)
                },
                style = MaterialTheme.typography.headlineSmall.copy(textDirection = direction),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.heightIn(min = 4.dp))
            q.choices.forEachIndexed { i, choice ->
                val right = i == q.answerIndex
                val state = when {
                    pick == null -> null
                    right -> tr(R.string.finish_lyric_right_one)
                    i == pick -> tr(R.string.finish_lyric_your_pick)
                    else -> null
                }
                val (fill, ink, edge) = when {
                    pick != null && right -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
                    pick == i -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
                    else -> Triple(scheme.surfaceContainerHigh, scheme.onSurface, scheme.outlineVariant)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(fill)
                        .border(1.dp, edge, RoundedCornerShape(16.dp))
                        .clickable(enabled = pick == null, role = Role.Button) {
                            picks = picks.toMutableList().also { it[index] = i }
                        }
                        .semantics { if (state != null) stateDescription = state }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag(FinishLyricTags.choice(i)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(choice, style = MaterialTheme.typography.titleMedium.copy(textDirection = direction), color = ink)
                }
            }
            if (pick != null) {
                val right = pick == q.answerIndex
                // Said aloud as soon as it shows.
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(
                        if (right) tr(R.string.finish_lyric_right) else tr(R.string.finish_lyric_wrong, q.answer),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = if (right) scheme.primary else scheme.error,
                    )
                    Text(
                        tr(R.string.finish_lyric_from, q.song.title, q.song.artist),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    )
                }
                val last = index == questions.lastIndex
                VinzButton(onClick = { if (last) done = true else index++ }, Modifier.fillMaxWidth()) {
                    Text(if (last) tr(R.string.finish_lyric_see_score) else tr(R.string.finish_lyric_next))
                }
            }
        }
    }
}

@Composable
private fun Result(
    questions: List<FinishLyric.Question>,
    picks: List<Int?>,
    onPlay: (Int) -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val score = FinishLyric.score(questions, picks)
    Text(
        tr(R.string.finish_lyric_score, score, questions.size),
        Modifier.fillMaxWidth().testTag(FinishLyricTags.RESULT).semantics { heading(); liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
    )
    Text(tr(R.string.finish_lyric_songs), style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
    questions.forEachIndexed { i, q ->
        val right = picks.getOrNull(i) == q.answerIndex
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = tr(R.string.play, q.song.title)) { onPlay(i) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(q.song.thumbnail, Modifier.size(48.dp), RoundedCornerShape(8.dp), size = 226)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(q.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(q.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(
                if (right) VinzIcons.Check else VinzIcons.Close,
                if (right) tr(R.string.finish_lyric_right) else tr(R.string.finish_lyric_wrong, q.answer),
                Modifier.size(20.dp), tint = if (right) scheme.primary else scheme.error,
            )
        }
    }
    Spacer(Modifier.heightIn(min = 4.dp))
    VinzButton(onClick = onAgain, Modifier.fillMaxWidth()) { Text(tr(R.string.finish_lyric_again)) }
    VinzOutlinedButton(onClick = onClose, Modifier.fillMaxWidth()) { Text(tr(R.string.close)) }
}
