package com.vinz.music.ui.components

import com.vinz.music.ui.theme.lit
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.drawscope.rotate
import com.vinz.music.ui.theme.VinzIcons
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vinz.music.data.model.AlbumItem
import com.vinz.music.data.model.ArtistItem
import com.vinz.music.data.model.BrowseItem
import com.vinz.music.data.model.MoodItem
import com.vinz.music.data.model.PlaylistItem
import com.vinz.music.data.model.Song
import com.vinz.music.data.model.SongItem
import com.vinz.music.data.model.formatDuration
import com.vinz.music.data.model.hiRes
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.hairline
import com.vinz.music.ui.theme.ink3
import androidx.compose.foundation.border
import kotlinx.coroutines.launch

@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    size: Int = 544,
    placeholderIcon: ImageVector = VinzIcons.Song,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            // Spectrum: a flat tile with a quiet icon until the picture arrives (color belongs to the artwork).
            .background(scheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholderIcon, null, tint = scheme.onSurface.copy(alpha = 0.28f), modifier = Modifier.fillMaxSize(0.36f))
        if (url != null) AsyncImage(
            model = hiRes(url, size),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A section title's style: medium weight, so headings read as headings beside the rows under them (#104). */
@Composable
fun sectionTitleStyle(): androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)

/**
 * A shelf's heading, as Apple Music sets them (and the artist pages do): a quiet strapline over the title. When it opens
 * the whole shelf, the whole row is the button, its chevron at the far end where a thumb reaches it (#107, as on
 * YouTube Music).
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, strapline: String? = null, onMore: (() -> Unit)? = null) {
    val ui = com.vinz.music.ui.theme.LocalUi.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = ui.gap(18.dp), bottom = ui.gap(2.dp))
            .clip(RoundedCornerShape(12.dp))
            .then(if (onMore != null) Modifier.clickable(onClickLabel = "See all", onClick = onMore) else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = ui.gap(8.dp), bottom = ui.gap(8.dp)),
    ) {
        if (!strapline.isNullOrBlank()) Text(
            strapline, style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f), style = sectionTitleStyle(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (onMore != null) Icon(VinzIcons.ChevronRight, "See all", Modifier.size(26.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailingInfo: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Marks one of an album's most-played tracks with a star, as Apple Music does. */
    starred: Boolean = false,
    /** More rows for the song's menu (e.g. "Remove from this playlist"). */
    extraActions: List<SheetItem> = emptyList(),
    onClick: () -> Unit,
) {
    val c = LocalContainer.current
    val likedIds = c.library.likedIds.collectAsState()
    val downloads = c.downloads.downloads.collectAsState()
    val videos = c.downloads.videos.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val done = androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    // Each row recomposes only when its own marks change, not every time any download, like or video changes anywhere
    // (a list on screen while Smart Downloads ran was rebuilt row by row at each step).
    val liked by remember(song.id) { derivedStateOf { song.id in likedIds.value } }
    val downloaded by remember(song.id) { derivedStateOf { downloads.value[song.id]?.state == done } }
    val videoSaved by remember(song.id) { derivedStateOf { videos.value[song.id]?.state == done } }
    SongRowLayout(
        song, modifier, index, playing, trailingInfo, starred,
        liked = liked, downloaded = downloaded, videoSaved = videoSaved,
        onLongClick = onLongClick ?: { menu = true }, onMore = { menu = true }, onClick = onClick,
    )
    if (menu) SongActionsSheet(song, extraActions) { menu = false }
}

/** [SongRow]'s look, given what it shows (so it can be drawn without the app around it: the Design gallery, tests). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRowLayout(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    trailingInfo: String? = null,
    starred: Boolean = false,
    liked: Boolean = false,
    downloaded: Boolean = false,
    videoSaved: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val ui = com.vinz.music.ui.theme.LocalUi.current
    val art = ui.songArt
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = ui.gap(6.dp), bottom = ui.gap(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Row(Modifier.width(38.dp), verticalAlignment = Alignment.CenterVertically) {
                if (starred) Icon(VinzIcons.StarFilled, "Popular", Modifier.size(11.dp), tint = scheme.ink3)
                else Spacer(Modifier.width(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    "$index", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (playing) scheme.onSurface else scheme.ink3,
                )
            }
        }
        Artwork(song.thumbnail, Modifier.size(48.dp), art, size = 226)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // The playing song's title is written in split light (deeper on light backgrounds), beside the spectrum bars.
            val dark = com.vinz.music.ui.theme.LocalIsDark.current
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (playing) MaterialTheme.typography.bodyLarge.copy(brush = com.vinz.music.ui.theme.VinzTokens.spectrum(dark), fontWeight = FontWeight.Medium)
                    else MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mark = Modifier.size(13.dp).padding(end = 3.dp)
                if (song.explicit) Icon(VinzIcons.Explicit, "Explicit", mark, tint = scheme.ink3)
                if (liked) Icon(VinzIcons.HeartFilled, "Liked", mark, tint = scheme.ink3)
                if (downloaded) Icon(VinzIcons.DownloadDone, "Downloaded", mark, tint = scheme.ink3)
                if (videoSaved) Icon(VinzIcons.Video, "Video saved", mark, tint = scheme.ink3)
                Text(
                    listOfNotNull(song.artistText, trailingInfo ?: song.album?.title).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
        if (playing) PlayingBars(Modifier.padding(start = 8.dp).size(13.dp, 14.dp), spectrum = true)
        IconButton(onClick = onMore) { Icon(VinzIcons.More, "More", Modifier.size(20.dp), tint = scheme.ink3) }
    }
}

/**
 * Three bouncing bars: what's playing. [spectrum]: Spectrum's mark, the bars in the split light (red, green, violet)
 * instead of [color]. Still with reduced motion.
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = Color.White, spectrum: Boolean = false) {
    val still = com.vinz.music.ui.theme.LocalUi.current.reduceMotion
    val colors = if (spectrum && !com.vinz.music.ui.theme.VinzTokens.white) listOf(Color(0xFFFF6B5E), Color(0xFF3DDC97), Color(0xFF7C8CFF)) else listOf(color, color, color)
    // With reduced motion there's no animation running at all (it used to tick every frame and just not show it); nor
    // while the full player hides the list it's in.
    val hidden = LocalHiddenByPlayer.current()
    val heights: List<androidx.compose.runtime.State<Float>> = if (still || hidden) STILL_BARS else {
        val t = rememberInfiniteTransition(label = "bars")
        listOf(
            t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a"),
            t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "b"),
            t.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(370), RepeatMode.Reverse), label = "d"),
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEachIndexed { i, h ->
            // The heights are read as the bars are drawn: each frame moves them without recomposing the row.
            Box(Modifier.weight(1f).fillMaxSize().graphicsLayer {
                scaleY = h.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }.clip(RoundedCornerShape(1.dp)).background(colors[i]))
        }
    }
}

private val STILL_BARS: List<androidx.compose.runtime.State<Float>> = listOf(0.6f, 1f, 0.75f).map { androidx.compose.runtime.mutableFloatStateOf(it) }

@Composable
fun ItemCard(item: BrowseItem, modifier: Modifier = Modifier, width: Dp = Dp.Unspecified, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val round = item is ArtistItem
    val ui = com.vinz.music.ui.theme.LocalUi.current
    val w = if (width == Dp.Unspecified) ui.cardWidth else width
    // Without a long press of its own, holding a card offers to play or shuffle it.
    val menu = rememberPlayMenu()
    val longPress = onLongClick ?: item.playable()?.let { p -> { menu(p) } }
    Column(modifier.width(w).clip(ui.tile).combinedClickable(onClick = onClick, onLongClick = longPress).padding(4.dp)) {
        when (item) {
            is MoodItem -> MoodTile(item, Modifier.fillMaxWidth().aspectRatio(1.6f), onClick)
            else -> {
                val video = item is SongItem && item.song.isVideo
                Artwork(
                    LocalContainer.current.covers.art(item),
                    Modifier.fillMaxWidth().aspectRatio(if (video) 16f / 9f else 1f),
                    // Each kind in its own shape (1.7.7); a wide video keeps rounded corners.
                    when {
                        round -> ui.artistArt
                        video -> ui.art
                        item is SongItem -> ui.songArt
                        else -> ui.albumArt
                    },
                    placeholderIcon = when (item) {
                        is ArtistItem -> VinzIcons.Person
                        is AlbumItem -> VinzIcons.Album
                        is PlaylistItem -> VinzIcons.QueueMusic
                        is com.vinz.music.data.model.PodcastItem -> VinzIcons.Podcasts
                        else -> VinzIcons.Song
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title, maxLines = if (video) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(lineHeight = MaterialTheme.typography.titleSmall.fontSize * 1.25f),
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
                if (item.subtitle.isNotBlank()) Text(
                    item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (round) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                )
            }
        }
    }
}

@Composable
fun MoodTile(item: MoodItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(item.color or 0xFF000000).lit()
    val ui = com.vinz.music.ui.theme.LocalUi.current
    // The mood's own color, deepening across the tile, with a soft sheen; its name set light.
    Box(
        modifier
            .clip(ui.tile)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f).compositeOverBlack())))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), radius = 260f, center = androidx.compose.ui.geometry.Offset(0f, 0f))))
        Text(
            item.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)

@Composable
fun ItemCarousel(items: List<BrowseItem>, onClick: (BrowseItem) -> Unit, cardWidth: Dp = Dp.Unspecified) {
    val w = if (cardWidth == Dp.Unspecified) com.vinz.music.ui.theme.LocalUi.current.cardWidth else cardWidth
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.id + it.title }) { item ->
            ItemCard(item, width = if (item is SongItem && item.song.isVideo) w * 1.5f else w) { onClick(item) }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) { VinzSpinner(Modifier.size(30.dp)) }
}

/** Loading: an arc of split light going round. */
@Composable
fun VinzSpinner(modifier: Modifier = Modifier, stroke: androidx.compose.ui.unit.Dp = 2.5.dp) {
    val still = com.vinz.music.ui.theme.LocalUi.current.reduceMotion
    // With reduced motion it doesn't turn, and no animation runs for it.
    val turn = if (still) null else rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "turn")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    androidx.compose.foundation.Canvas(modifier.then(Modifier.semantics { contentDescription = "Loading" })) {
        val w = stroke.toPx()
        val inset = w / 2
        val arc = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        rotate(turn?.value ?: 0f) {
            drawArc(
                com.vinz.music.ui.theme.VinzTokens.spectrumRing(center), -90f, 270f, false, androidx.compose.ui.geometry.Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/**
 * A thin progress line in split light on a faint track: [progress] (0…1) fills from the start, or with none a short
 * run of light travels along it.
 */
@Composable
fun VinzProgressBar(progress: (() -> Float)?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 3.dp) {
    val dark = com.vinz.music.ui.theme.LocalIsDark.current
    val still = com.vinz.music.ui.theme.LocalUi.current.reduceMotion
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // Read as it's drawn (it used to recompose the bar every frame).
    val run = if (progress == null && !still) {
        rememberInfiniteTransition(label = "bar").animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1300, easing = androidx.compose.animation.core.FastOutSlowInEasing)), label = "run")
    } else null
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(height).clip(CircleShape).semantics { contentDescription = "Loading" }) {
        drawRect(track)
        val brush = com.vinz.music.ui.theme.VinzTokens.spectrum(dark, 0f, size.width)
        if (progress != null) {
            drawRect(brush, size = size.copy(width = size.width * progress().coerceIn(0f, 1f)))
        } else {
            val from = (run?.value ?: 0f) * size.width
            drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(from, 0f), size = size.copy(width = size.width * 0.35f))
        }
    }
}

/** Something didn't load: a quiet icon on glass, one plain line, and one way to try again. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape), contentAlignment = Alignment.Center) {
            Icon(VinzIcons.CloudOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Text(
            message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            VinzOutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Nothing here yet: the icon on a round of glass ringed in split light, a light title and what to do about it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(glassFill(1.4f)).spectrumRing(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun MarqueeText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified) {
    Text(text, modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2500), style = style, color = color, maxLines = 1)
}

/** A search pill for filtering a list in place ("Search in playlist"), as you type. [container] defaults to the theme's. */
@Composable
fun SearchField(query: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, container: Color = Color.Unspecified) {
    val scheme = MaterialTheme.colorScheme
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // A glass pill (or the page's [container] fill).
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(CircleShape)
            .background(if (container != Color.Unspecified) container else glassFill(1.4f)).border(1.dp, glassEdge(), CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VinzIcons.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                query, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onChange(""); focus.clearFocus() }) {
            Icon(VinzIcons.Close, "Clear search", Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Sheets

/**
 * A bottom sheet that always lets go. Material's sheet could be left half-dismissed (blocking the
 * screen) when flicked or tapped away while it was still opening; this watches where the sheet
 * actually lands and finishes the dismissal itself, exactly once. [content] gets a `close { … }`
 * that slides the sheet away first and then runs the action, so a follow-up sheet never overlaps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VinzSheet(
    onDismiss: () -> Unit,
    containerColor: Color = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    dragHandle: (@Composable () -> Unit)? = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val then = remember { arrayOfNulls<() -> Unit>(1) }
    val finish = remember {
        {
            if (done.compareAndSet(false, true)) {
                dismiss()
                then[0]?.invoke()
            }
        }
    }
    LaunchedEffect(state) {
        var shown = false
        androidx.compose.runtime.snapshotFlow { state.currentValue to state.targetValue }.collect { (now, target) ->
            if (now != androidx.compose.material3.SheetValue.Hidden) shown = true
            else if (shown && target == androidx.compose.material3.SheetValue.Hidden) finish()
        }
    }
    val close: (() -> Unit) -> Unit = remember {
        { action ->
            if (then[0] == null) then[0] = action
            scope.launch { try { state.hide() } finally { finish() } }
        }
    }
    ModalBottomSheet(onDismissRequest = finish, sheetState = state, containerColor = containerColor, contentColor = contentColor, dragHandle = dragHandle) {
        content(close)
    }
}

// ---------------------------------------------------------------- Song actions

/** "Download video": saves the song's music video (and the song) for offline; tapped again, takes the video back. */
@Composable
fun videoDownloadAction(song: Song, video: com.vinz.music.download.VideoDownload?): ActionItem? {
    if (song.isLive) return null
    val c = LocalContainer.current
    val done = video?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    val failed = video?.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED
    return ActionItem(
        VinzIcons.Video, if (done) "Remove downloaded video" else "Download video",
        value = when {
            done -> "Saved"
            failed -> "Failed: tap to remove"
            video != null -> "${video.percent.toInt().coerceAtLeast(0)}%"
            else -> null
        },
        active = done, keepOpen = true,
    ) { if (video != null) c.downloads.removeVideo(song.id) else c.downloads.downloadVideo(song) }
}

/**
 * A song's menu, wherever it's held or its ⋯ is tapped: like and share at hand, the queue
 * actions as tiles, then where to go. [extra] adds rows (e.g. "Remove from this playlist").
 */
@Composable
fun SongActionsSheet(song: Song, extra: List<SheetItem> = emptyList(), onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val liked by c.library.likedIds.collectAsState()
    val downloads by c.downloads.downloads.collectAsState()
    val videos by c.downloads.videos.collectAsState()
    val disliked by c.library.dislikedIds.collectAsState()
    var pickPlaylist by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var credits by remember { mutableStateOf(false) }
    val isLiked = song.id in liked
    val dl = downloads[song.id]
    val downloaded = dl?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    val together by c.together.state.collectAsState()
    // A file on the phone isn't on YouTube: no radio, download, sharing, YouTube playlists, or sending it to a session.
    val local = song.isLocal
    val guest = (together as? com.vinz.music.playback.together.TogetherState.Guest)?.takeIf { !local }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    // The playlist picker and the share sheet take over from the sheet.
    if (pickPlaylist) { PlaylistPicker(song) { pickPlaylist = false; onDismiss() }; return }
    if (sharing) { ShareSheet(song.shareTarget()) { sharing = false; onDismiss() }; return }
    if (credits) { SongCreditsSheet(song) { credits = false; onDismiss() }; return }

    // In someone's Listen Together session, songs go into their queue.
    val quick = listOf(
        if (guest != null) ActionItem(VinzIcons.Groups, "Play next") { c.together.suggest(song, next = true); toast("Sent to ${guest.hostName}") }
        else ActionItem(VinzIcons.PlaylistPlay, "Play next") { c.player.playNext(song) },
        if (guest != null) ActionItem(VinzIcons.PersonAdd, "Add to queue") { c.together.suggest(song, next = false); toast("Sent to ${guest.hostName}") }
        else ActionItem(VinzIcons.QueueMusic, "Add to queue") { c.player.addToQueue(song) },
        // A radio of a podcast episode is music radio: not offered for episodes.
        if (song.isEpisode || local) null else ActionItem(VinzIcons.Radio, "Radio") { c.player.playRadio(song) },
        if (local) null else ActionItem(
            if (downloaded) VinzIcons.DownloadDone else VinzIcons.Download, "Download",
            value = when { downloaded -> "Downloaded"; dl != null -> "${dl.percent.toInt().coerceAtLeast(0)}%"; else -> null },
            active = downloaded, keepOpen = true,
        ) { if (dl != null) c.downloads.remove(song.id) else c.downloads.download(song) },
    ).filterNotNull()
    val go = listOfNotNull(
        // Straight to its most replayed stretch (or its chorus).
        if (song.isEpisode || song.isLive || guest != null) null else ActionItem(VinzIcons.FlameFilled, "Play from the best part") { c.player.playFromBestPart(song) },
        song.podcastId?.let { id -> ActionItem(VinzIcons.Podcasts, "Go to podcast") { nav.go(Routes.podcast(id)) } },
        if (song.isEpisode && song.id in c.episodes.played.value) ActionItem(VinzIcons.Refresh, "Mark as unplayed") { c.episodes.forget(song.id); c.episodes.unmarkPlayed(song.id) }
        else if (song.isEpisode) ActionItem(VinzIcons.DoneAll, "Mark as played") { c.episodes.markPlayed(song.id) } else null,
        ActionItem(VinzIcons.PlaylistAdd, "Add to playlist", keepOpen = true) { pickPlaylist = true },
        song.album?.id?.let { id -> ActionItem(VinzIcons.Album, "Go to album", value = song.album?.title?.takeIf { it.length <= 22 }) { nav.go(Routes.album(id)) } },
    ) + goToArtists(song) { route -> nav.go(route) } + listOfNotNull(
        // Who performed, wrote and produced it (Reddit 1.7).
        if (song.isEpisode || local || song.isLive) null else ActionItem(VinzIcons.Info, "Song credits", keepOpen = true) { credits = true },
    )
    val more = extra.map { it.toAction() } + listOfNotNull(
        // Your own FLAC / MP3 files (a Local file, or the lossless file played for this song) can keep its lyrics (#16).
        if (local || dl?.lossless == true) ActionItem(VinzIcons.Lyrics, "Save lyrics into the file") { c.lyricsToFiles.request(listOf(song)) } else null,
        if (song.isEpisode || local) null else videoDownloadAction(song, videos[song.id]),
        if (song.isEpisode) null else if (song.id in disliked) ActionItem(VinzIcons.ThumbDownFilled, "Not for me", active = true, keepOpen = true) { c.library.setDisliked(song, false) }
        else ActionItem(VinzIcons.ThumbDown, "Not for me") { c.library.setDisliked(song, true); toast("Got it — Vinz will steer clear of this song") },
        // Plays as usual, but its plays don't shape recommendations (a song played for someone else).
        if (song.isEpisode || local) null else {
            val out = c.exclusions.isSongExcluded(song.id)
            ActionItem(VinzIcons.Block, if (out) "Back in my taste" else "Leave out of my taste") {
                c.exclusions.setSong(com.vinz.music.data.foryou.Excluded(song.id, "${song.title} · ${song.artistText}", song.thumbnail), !out)
                toast(if (out) "It counts towards your recommendations again" else "Its plays won't shape your recommendations")
            }
        },
    )

    ActionSheet(song.thumbnail, onDismiss) { close ->
        ActionHeader(
            song.thumbnail, song.title,
            listOfNotNull(song.artistText, formatDuration(song.durationSec).takeIf { it.isNotEmpty() }).joinToString(" · "),
            placeholderIcon = VinzIcons.Song,
        ) {
            SheetIconButton(if (isLiked) VinzIcons.HeartFilled else VinzIcons.Heart, if (isLiked) "Remove from liked songs" else "Add to liked songs", active = isLiked) {
                c.library.toggleLike(song)
            }
            if (!local) SheetIconButton(VinzIcons.Share, "Share") { sharing = true }
        }
        if (guest != null) Text(
            "Adding to ${guest.hostName}'s session", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        QuickActions(quick) { runAction(it, close) }
        ActionGroup(null, go) { runAction(it, close) }
        ActionGroup(null, more) { runAction(it, close) }
    }
}

/** One extra row for [SongActionsSheet] or [PlayActionsSheet]; [danger] marks a delete. */
data class SheetItem(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(com.vinz.music.ui.theme.LocalUi.current.shape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface) }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * Adds [song] to one of the listener's playlists, or to a new one: Vinz playlists (on the phone) and, when signed in, the
 * account's YouTube Music ones. A new one is a YouTube Music playlist when signed in, else a Vinz one.
 */
@Composable
fun PlaylistPicker(song: Song, onDismiss: () -> Unit) = PlaylistPicker(listOf(song), song.title, onDismiss)

/**
 * Adds [songs] (a song, or a whole album or playlist: Reddit R8) to one of the listener's playlists, or to a new one.
 * [what] names them under the sheet's title.
 */
@Composable
fun PlaylistPicker(songs: List<Song>, what: String, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val playlists by c.library.playlists.collectAsState()
    val vinzLists by c.vinzLibrary.lists.collectAsState()
    // A file on the phone can only go in a Vinz playlist (YouTube Music has no copy of it).
    val signedIn = c.settings.current.isLoggedIn && songs.none { it.isLocal }
    val ids = songs.map { it.id }
    val many = songs.size > 1
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false; onDismiss() }, local = !signedIn) { name, privacy ->
            creating = false
            onDismiss()
            if (!signedIn) { c.vinzLibrary.create(name, songs); toast("Added to $name"); return@NewPlaylistDialog }
            c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                // A long list (Liked songs, a big playlist) goes in 50 at a time after the first: one request with
                // thousands of songs could be refused.
                val id = runCatching {
                    c.ytm.createPlaylist(name, ids.take(50), privacy = privacy)?.also { if (ids.size > 50) c.ytm.addAllToPlaylist(it, ids.drop(50)) }
                }.getOrNull()
                toast(if (id != null) "Added to $name" else "Couldn't make the playlist")
                if (id != null) c.library.refreshCollections()
            }
        }
        return
    }
    val mine = vinzLists.map { it.item() } +
        (if (signedIn) playlists.filterIsInstance<PlaylistItem>().filter { !it.id.startsWith("LM") && !it.isMix } else emptyList())
    val shown = if (query.isBlank()) mine else mine.filter { it.title.contains(query.trim(), ignoreCase = true) }
    VinzSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(bottom = 20.dp)) {
            Text("Add to playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                if (many) "$what · ${com.vinz.music.data.model.songCount(songs.size)}" else what,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            SheetAction(VinzIcons.Add, "New playlist") { creating = true }
            if (mine.size > 8) SearchField(query, "Find a playlist", { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (mine.isEmpty()) Text(
                if (signedIn) "Your playlists show up here once your library has synced." else "Make your first playlist: it's kept on this phone, no account needed.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
            )
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(shown, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            close {
                                if (com.vinz.music.data.VinzLibrary.isVinzList(p.id)) {
                                    val added = c.vinzLibrary.add(p.id, songs)
                                    toast(
                                        when {
                                            added == 0 -> "Already in ${p.title}"
                                            many -> "${com.vinz.music.data.model.songCount(added)} added to ${p.title}"
                                            else -> "Added to ${p.title}"
                                        },
                                    )
                                    return@close
                                }
                                c.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    val ok = runCatching { if (many) c.ytm.addAllToPlaylist(p.id, ids) else c.ytm.addToPlaylist(p.id, ids.single()) }.isSuccess
                                    toast(if (ok) "Added to ${p.title}" else "Couldn't add to ${p.title}")
                                }
                            }
                        }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(c.covers.art(p), Modifier.size(46.dp), com.vinz.music.ui.theme.LocalUi.current.smallArt, size = 120, placeholderIcon = VinzIcons.QueueMusic)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.subtitle.isNotBlank()) Text(p.subtitle.removePrefix("Playlist • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Name a new playlist: a YouTube Music one (and who can open it), or with [local] a Vinz one kept on this phone. */
@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, local: Boolean = false, onCreate: (name: String, privacy: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("PRIVATE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.vinz.music.ui.components.VinzTextField(name, { name = it.take(150) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (local) {
                    Text(
                        "Kept on this phone (and in your backups), no account needed. Sign in to YouTube Music for playlists on your account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Segmented(listOf("PRIVATE", "UNLISTED", "PUBLIC"), privacy, { when (it) { "PRIVATE" -> "Private"; "UNLISTED" -> "Unlisted"; else -> "Public" } }) { privacy = it }
                Text(
                    when (privacy) {
                        "PRIVATE" -> "Only you can see it."
                        "UNLISTED" -> "Anyone with the link can open it, so it's easy to share."
                        else -> "Anyone can find it."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { VinzTextButton(onClick = { onCreate(name.trim(), privacy) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { VinzTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chips that follow a pager: tap to jump, or swipe the pages and the chip moves along. */
@Composable
fun PagerChips(labels: List<String>, pager: androidx.compose.foundation.pager.PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager.currentPage) { row.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(
        modifier, state = row,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.size) { i ->
            VinzChip(pager.currentPage == i, { scope.launch { pager.animateScrollToPage(i) } }, labels[i])
        }
    }
}
/** "⇅ Title" — opens a menu of sort orders; the current one is ticked. */
@Composable
fun <T> SortButton(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VinzIcons.Sort, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label(selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label(o), fontWeight = if (o == selected) FontWeight.Medium else FontWeight.Normal) },
                    onClick = { onSelect(o); open = false },
                    trailingIcon = if (o == selected) ({ Icon(VinzIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                )
            }
        }
    }
}

/**
 * The artists of a song that can be opened: linked ones, and (#78) ones YouTube named without a link, which open by
 * name ([com.vinz.music.ui.Routes.artist]). A file on the phone has no YouTube artists.
 */
fun openableArtists(song: Song): List<com.vinz.music.data.model.ArtistRef> =
    if (song.isLocal) emptyList()
    else song.artists.filter { it.name.isNotBlank() }.distinctBy { it.id ?: com.vinz.music.data.innertube.SearchRank.norm(it.name) }

/** "Go to …" for each of a song's artists (#42): a song with features lists every one, not only the first. */
fun goToArtists(song: Song, go: (String) -> Unit): List<ActionItem> =
    openableArtists(song).take(5).map { a -> ActionItem(VinzIcons.Person, "Go to ${a.name}") { go(com.vinz.music.ui.Routes.artist(a)) } }

/** A list's count on the left and its [SortButton] on the right. */
@Composable
fun <T> SortBar(count: String, options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        SortButton(options, selected, label, onSelect = onSelect)
    }
}