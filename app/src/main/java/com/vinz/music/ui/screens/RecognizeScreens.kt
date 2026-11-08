package com.vinz.music.ui.screens

import com.vinz.music.R
import com.vinz.music.tr
import androidx.compose.foundation.border
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.VinzTextButton
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vinz.music.data.db.RecognitionEntity
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.recognize.Recognizer
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.Artwork
import com.vinz.music.ui.components.RoundAction
import com.vinz.music.ui.components.SubPage
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** A recognized song as one to play: the YouTube Music song it was matched to (null when there wasn't one). */
private fun RecognitionEntity.song(): Song? = videoId?.let {
    Song(id = it, title = title ?: "", artists = listOf(ArtistRef(artist ?: "")), thumbnail = cover)
}

/**
 * Song recognition: a big button to listen (up to 12 s), then what it is, with ways to play it. [autoStart]: from the
 * Quick Settings tile, listening starts at once.
 */
@Composable
fun RecognizeScreen(autoStart: Boolean, bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val state by c.recognizer.state.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    var pauseVinz by remember { mutableStateOf(true) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var startWhenGranted by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && startWhenGranted) c.recognizer.start(pauseVinz)
    }
    fun listen() {
        if (granted) c.recognizer.start(pauseVinz) else { startWhenGranted = true; ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
    LaunchedEffect(Unit) {
        c.recognizer.reset()
        if (autoStart) listen()
    }
    // Leaving the page stops listening (it never listens unseen).
    DisposableEffect(Unit) { onDispose { if (c.recognizer.state.value is Recognizer.State.Listening) c.recognizer.cancel() } }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF9B7BFF), onPrimary = Color.White,
            background = Color.Black, surface = Color(0xFF08080D),
            onSurface = Color.White, onSurfaceVariant = Color(0xFFB8B4C5),
        ),
    ) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomPadding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundAction(VinzIcons.ArrowBack, tr(R.string.back), glass = true) { nav.back() }
                Spacer(Modifier.weight(1f))
                VinzTextButton({ nav.go(Routes.RECOGNIZED) }) { Text(tr(R.string.widget_recognize_open)) }
            }
            Spacer(Modifier.weight(0.6f))
            when (val s = state) {
                is Recognizer.State.Found -> FoundCard(s.recognition, s.song)
                else -> {
                    val listening = s as? Recognizer.State.Listening
                    ListenButton(listening != null, listening?.level ?: 0f) { if (listening != null) c.recognizer.cancel() else listen() }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        when (s) {
                            is Recognizer.State.Listening -> tr(R.string.listening_s, s.heardMs / 1000)
                            Recognizer.State.NotFound -> tr(R.string.couldn_t_recognize_that_one)
                            Recognizer.State.Saved -> tr(R.string.you_re_offline_so_vinz_kept)
                            Recognizer.State.RateLimited -> tr(R.string.shazam_asks_to_wait_a_little)
                            is Recognizer.State.Failed -> s.message
                            else -> tr(R.string.tap_to_find_out_what_s)
                        },
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (s) {
                            is Recognizer.State.Listening -> tr(R.string.hold_your_phone_near_the_music)
                            Recognizer.State.NotFound -> tr(R.string.try_again_closer_to_the_music)
                            Recognizer.State.Saved -> tr(R.string.it_ll_be_looked_up_when)
                            Recognizer.State.RateLimited -> tr(R.string.try_again_in_a_minute)
                            else -> tr(R.string.vinz_listens_for_up_to_12_2)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    if (playing && s !is Recognizer.State.Listening) {
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr(R.string.pause_vinz_while_listening), style = MaterialTheme.typography.bodyLarge)
                                Text(tr(R.string.off_it_can_recognize_what_vinz), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            VinzSwitch(pauseVinz, { pauseVinz = it })
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
    }
}

/** A luminous, fluid recognition orb with sound-reactive rings. */
@Composable
internal fun ListenButton(listening: Boolean, level: Float, size: Dp = 260.dp, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "vinz-orb")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (listening) 2600 else 5200), RepeatMode.Restart),
        label = "orb-phase",
    )
    val loud by animateFloatAsState(level.coerceIn(0f, 1f), tween(110), label = "orb-level")
    val accent = Color(0xFF9B7BFF)
    val cyan = Color(0xFF43D9FF)
    val magenta = Color(0xFFFF63C8)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
            val reach = this.size.minDimension * 0.47f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (listening) 0.25f else 0.16f), Color.Transparent),
                    center = center,
                    radius = reach * (0.82f + 0.08f * kotlin.math.sin(phase * Math.PI * 2).toFloat()),
                ),
                radius = reach, center = center,
            )
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color = (if (i == 1) cyan else accent).copy(alpha = (1f - p) * if (listening) 0.48f else 0.18f),
                    radius = reach * (0.60f + p * 0.34f + loud * 0.07f),
                    center = center,
                    style = Stroke(width = if (listening) 1.8.dp.toPx() else 1.1.dp.toPx()),
                )
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.9f), cyan.copy(alpha = 0.8f), Color.Transparent), center),
                startAngle = phase * 360f, sweepAngle = 118f + loud * 42f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - reach * 0.88f, center.y - reach * 0.88f),
                size = androidx.compose.ui.geometry.Size(reach * 1.76f, reach * 1.76f),
                style = Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawArc(
                color = magenta.copy(alpha = if (listening) 0.72f else 0.28f),
                startAngle = 210f - phase * 220f, sweepAngle = 64f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(center.x - reach * 0.72f, center.y - reach * 0.72f),
                size = androidx.compose.ui.geometry.Size(reach * 1.44f, reach * 1.44f),
                style = Stroke(width = 1.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Box(
            Modifier.size(size * 0.54f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF51417E), Color(0xFF171323), Color(0xFF08080D))))
                .border(1.dp, accent.copy(alpha = 0.72f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) VinzIcons.Close else VinzIcons.Recognize,
                if (listening) tr(R.string.stop_listening) else tr(R.string.listen),
                tint = Color.White, modifier = Modifier.size(size * 0.22f),
            )
        }
    }
}

@Composable
private fun FoundCard(r: RecognitionEntity, song: Song?) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val liked by c.library.likedIds.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(r.cover ?: song?.thumbnail, Modifier.size(240.dp), LocalUi.current.art, placeholderIcon = VinzIcons.Song)
        Spacer(Modifier.height(20.dp))
        Text(r.title ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(r.artist ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        r.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        if (song != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VinzButton({ c.player.playSingle(song); nav.openPlayer() }) { Icon(VinzIcons.Play, null); Spacer(Modifier.width(6.dp)); Text(tr(R.string.widget_play)) }
                val isLiked = song.id in liked
                VinzOutlinedButton({ c.library.toggleLike(song) }) {
                    Icon(if (isLiked) VinzIcons.HeartFilled else VinzIcons.Heart, null); Spacer(Modifier.width(6.dp)); Text(if (isLiked) tr(R.string.liked) else tr(R.string.widget_like))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                song.album?.id?.let { album -> VinzTextButton({ nav.go(Routes.album(album)) }) { Text(tr(R.string.album)) } }
                VinzTextButton({ c.player.playSingle(song); nav.openPlayer() }) { Text(tr(R.string.lyrics)) }
                VinzTextButton({ c.recognizer.reset() }) { Text(tr(R.string.again)) }
            }
        } else {
            Text(tr(R.string.not_found_in_vinz_music), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VinzTextButton({ nav.go(Routes.SEARCH) }) { Text(tr(R.string.search_for_it)) }
            VinzTextButton({ c.recognizer.reset() }) { Text(tr(R.string.again)) }
        }
    }
}

/** Library → Recognized: every song found by listening, newest first (and any still waiting for a network). */
@Composable
fun RecognizedScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val history by c.recognizer.history.collectAsState(initial = emptyList())
    // The Recognize widget, offered here (the launcher pins it after asking) until one is on the home screen.
    val context = LocalContext.current
    val widgets = remember { android.appwidget.AppWidgetManager.getInstance(context) }
    val provider = remember { android.content.ComponentName(context, com.vinz.music.widget.RecognizeWidget::class.java) }
    var placed by remember { mutableStateOf(widgets?.getAppWidgetIds(provider)?.isNotEmpty() == true) }
    SubPage(
        tr(R.string.recognized), bottomPadding, subtitle = tr(R.string.songs_vinz_found_by_listening),
        actions = { RoundAction(VinzIcons.Recognize, tr(R.string.widget_recognize_empty), glass = true) { nav.go(Routes.RECOGNIZE) } },
    ) {
        if (widgets?.isRequestPinAppWidgetSupported == true && !placed) item("widget") {
            Row(
                Modifier.fillMaxWidth().clickable {
                    placed = widgets.requestPinAppWidget(provider, null, null)
                }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(com.vinz.music.ui.theme.VinzTokens.spectrumRing()), contentAlignment = Alignment.Center) {
                    Icon(VinzIcons.Recognize, null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(R.string.add_the_widget), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr(R.string.recognize_songs_from_your_home_screen), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (history.isEmpty()) item("empty") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                com.vinz.music.ui.components.EmptyState(
                    VinzIcons.Recognize, tr(R.string.nothing_recognized_yet),
                    tr(R.string.hear_a_song_you_like_let),
                )
                VinzButton({ nav.go(Routes.RECOGNIZE + "?start=true") }) {
                    Icon(VinzIcons.Recognize, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr(R.string.widget_recognize_empty))
                }
            }
        }
        history.forEach { r ->
            item(r.id) {
                val song = r.song()
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = song != null) { song?.let { c.player.playSingle(it) } }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(r.cover, Modifier.size(52.dp), LocalUi.current.art, placeholderIcon = if (r.status == "pending") VinzIcons.Recognize else VinzIcons.Song)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (r.status == "pending") tr(R.string.waiting_for_a_network) else r.title ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(r.artist?.takeIf { r.status != "pending" }, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.at))).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    androidx.compose.material3.IconButton({
                        scope.launch {
                            c.db.recognitions().delete(r.id)
                            com.vinz.music.widget.RecognizeWidgets.refresh(c.app)
                        }
                    }) {
                        Icon(VinzIcons.Close, tr(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
