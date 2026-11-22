package com.vinz.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.model.Song
import com.vinz.music.tr
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.launch

/**
 * "Go to the playing song" (1.8.4, D54): what a long list of songs needs to bring the one that's playing back on
 * screen. The decisions are here on their own, so they can be checked without a screen.
 */
object LocatePlaying {
    /**
     * The playing song's place in the songs a list is showing (after its search, filter and sort); -1: not one of them.
     * [playingVideoId]: the music video the playing song stands in for (Music videos → Songs in radios plays a video row
     * as its song, under the song's id: the queue's counterparts). Its row counts when the song's own isn't there.
     */
    fun indexIn(shown: List<Song>, playingId: String?, playingVideoId: String? = null): Int {
        if (playingId == null) return -1
        val at = shown.indexOfFirst { it.id == playingId }
        if (at >= 0 || playingVideoId == null) return at
        return shown.indexOfFirst { it.id == playingVideoId }
    }

    /** The id of the row that is the playing song ([at] from [indexIn]): the row's own, or, not in the list, [playingId]. */
    fun rowId(shown: List<Song>, at: Int, playingId: String?): String? = shown.getOrNull(at)?.id ?: playingId

    /**
     * Where a row is against the part of the list that can be seen ([top] to [bottom]: under the page's bar, above the
     * mini player): -1 above it, 0 in it, 1 below. A row counts as seen once its middle is. [offset] null: the row isn't
     * laid out at all, so it's on the side [row] is of the [first] one that is.
     */
    fun side(row: Int, first: Int, offset: Int?, size: Int, top: Int, bottom: Int): Int {
        if (offset == null) return if (row < first) -1 else 1
        val middle = offset + size / 2
        return when { middle < top -> -1; middle > bottom -> 1; else -> 0 }
    }

    /** The button above the mini player: only when asked for, with the playing song in the list and out of sight. */
    fun offersButton(setting: Boolean, inList: Boolean, side: Int): Boolean = setting && inList && side != 0
}

/** One list's "Go to the playing song": what goes there (set by the list itself), and the row that pulses after. */
@Stable
class LocateState {
    /** The song whose row is pulsing, and which pulse it is (asked again, it pulses again). */
    var pulsing by mutableStateOf<String?>(null)
        private set
    var beat by mutableIntStateOf(0)
        private set

    /** Scrolls to the playing song's row; null while that song isn't one of the list's (nothing to offer). */
    var go by mutableStateOf<(() -> Unit)?>(null)
        internal set

    fun pulse(songId: String) { pulsing = songId; beat++ }
    internal fun rested(of: Int) { if (of == beat) pulsing = null }
}

/** The row "Go to the playing song" arrived at lights up once in the theme's color and settles (never with Reduce motion). */
@Composable
fun Modifier.locatePulse(state: LocateState?, songId: String): Modifier {
    if (state == null || state.pulsing != songId) return this
    val beat = state.beat
    val glow = remember(beat) { Animatable(0f) }
    LaunchedEffect(beat) {
        glow.animateTo(1f, tween(220))
        glow.animateTo(0f, tween(700))
        state.rested(beat)
    }
    val color = MaterialTheme.colorScheme.primary
    return drawBehind { drawRect(color, alpha = glow.value * 0.26f) }
}

/**
 * A LazyColumn's own scope that counts what's put in it, so a page with a header, a search and whatever else above its
 * songs can tell which place a song's row has ([added] read just before the rows are added).
 */
class CountingLazyListScope(private val inner: LazyListScope) : LazyListScope by inner {
    var added = 0
        private set

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        added++
        inner.item(key, contentType, content)
    }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) {
        added += count
        inner.items(count, key, contentType, itemContent)
    }
}

/**
 * Where a page's song rows are in its list: from [start] on in the order shown, or, under headings (a playlist grouped
 * by genre), each song's first row in [grouped]. Written as the list is built, read when a row is asked for.
 */
class SongRowPlaces {
    var start = 0
    var grouped: Map<String, Int>? = null

    /** The row of the song that's [shownAt] among the songs shown (-1: it isn't shown). */
    fun of(songId: String?, shownAt: Int): Int = when {
        shownAt < 0 || songId == null -> -1
        else -> grouped?.let { it[songId] ?: -1 } ?: (start + shownAt)
    }
}

/**
 * A list of songs' part in "Go to the playing song": while the playing song is one of its rows ([inList]), [state] can
 * go to it, and with Settings' locate button on, a small button sits above the mini player for as long as that row is
 * out of sight. [row] is the row's place among everything the list holds (asked when needed: a page counts it as it
 * builds the list); [topInset] is how much of the list's top is under the page's bar.
 */
@Composable
fun BoxScope.LocatePlayingRow(
    state: LocateState, list: LazyListState, inList: Boolean, songId: String?, bottomPadding: Dp, topInset: Dp = 0.dp, row: () -> Int,
) {
    val inset = with(LocalDensity.current) { topInset.roundToPx() }
    LocatePlayingRow(
        state, inList, songId, bottomPadding,
        side = {
            val at = row()
            val info = list.layoutInfo
            val seen = info.visibleItemsInfo.firstOrNull { it.index == at }
            LocatePlaying.side(
                at, info.visibleItemsInfo.firstOrNull()?.index ?: 0, seen?.offset, seen?.size ?: 0,
                info.viewportStartOffset + inset, info.viewportEndOffset - info.afterContentPadding,
            )
        },
        scroll = { animated ->
            val info = list.layoutInfo
            // A third of the way down: clear of the page's bar, with the songs before it in view too.
            val lead = -((info.viewportEndOffset - info.viewportStartOffset) / 3)
            if (animated) list.animateScrollToItem(row(), lead) else list.scrollToItem(row(), lead)
        },
    )
}

/** The same for a grid of one column (Library's Songs). */
@Composable
fun BoxScope.LocatePlayingRow(
    state: LocateState, grid: LazyGridState, inList: Boolean, songId: String?, bottomPadding: Dp, topInset: Dp = 0.dp, row: () -> Int,
) {
    val inset = with(LocalDensity.current) { topInset.roundToPx() }
    LocatePlayingRow(
        state, inList, songId, bottomPadding,
        side = {
            val at = row()
            val info = grid.layoutInfo
            val seen = info.visibleItemsInfo.firstOrNull { it.index == at }
            LocatePlaying.side(
                at, info.visibleItemsInfo.firstOrNull()?.index ?: 0, seen?.offset?.y, seen?.size?.height ?: 0,
                info.viewportStartOffset + inset, info.viewportEndOffset - info.afterContentPadding,
            )
        },
        scroll = { animated ->
            val info = grid.layoutInfo
            val lead = -((info.viewportEndOffset - info.viewportStartOffset) / 3)
            if (animated) grid.animateScrollToItem(row(), lead) else grid.scrollToItem(row(), lead)
        },
    )
}

@Composable
private fun BoxScope.LocatePlayingRow(
    state: LocateState, inList: Boolean, songId: String?, bottomPadding: Dp, side: () -> Int, scroll: suspend (animated: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Reduce motion: straight there, and no pulse.
    val still = LocalUi.current.reduceMotion
    val goNow: () -> Unit = {
        scope.launch {
            scroll(!still)
            if (!still && songId != null) state.pulse(songId)
        }
    }
    val go by rememberUpdatedState(goNow)
    // Only its own way there is taken back when it goes: pages that share one state (Local files' sections in a pager)
    // leave after the next page came, and that page's way there stays.
    DisposableEffect(state, inList) {
        val mine: (() -> Unit)? = if (inList) ({ go() }) else null
        if (mine != null) state.go = mine
        onDispose { if (state.go === mine) state.go = null }
    }
    if (!inList || !LocalAppSettings.current.behavior.locatePlaying) return
    val where by rememberUpdatedState(side)
    // Read where the scroll is only here, so the page around it isn't composed again as the list moves.
    val away by remember { derivedStateOf { where() } }
    if (!LocatePlaying.offersButton(setting = true, inList = true, side = away)) return
    val scheme = MaterialTheme.colorScheme
    val label = tr(R.string.go_to_the_playing_song)
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding + 10.dp).testTag("locatePlaying")
            .height(36.dp).clip(CircleShape).background(scheme.surfaceContainerHighest).border(1.dp, glassEdge(), CircleShape)
            .clickable(onClickLabel = label) { go() }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VinzIcons.Song, label, Modifier.size(18.dp), tint = scheme.primary)
        Spacer(Modifier.width(2.dp))
        // Which way the song is: up the list or down it.
        Icon(if (away < 0) VinzIcons.ChevronUp else VinzIcons.ChevronDown, null, Modifier.size(18.dp), tint = scheme.onSurface)
    }
}
