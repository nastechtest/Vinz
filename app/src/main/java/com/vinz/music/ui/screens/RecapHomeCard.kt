package com.vinz.music.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.RecapCalendar
import com.vinz.music.data.RecapCard
import com.vinz.music.tr
import com.vinz.music.ui.components.Artwork
import com.vinz.music.ui.components.glassEdge
import com.vinz.music.ui.components.pane
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.LocalIsDark
import com.vinz.music.ui.theme.LocalUi
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/**
 * Which month's recap card the listener closed on Home (1.8.5). Plain SharedPreferences, not the settings model: it's one
 * month at a time (the next month's card comes back by itself), never backed up or put in a Look.
 */
internal object RecapCardDismissals {
    private const val PREFS = "home_cards"
    private const val KEY = "recap_dismissed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDismissed(context: Context, month: YearMonth): Boolean = prefs(context).getString(KEY, null) == month.toString()

    fun dismiss(context: Context, month: YearMonth) { prefs(context).edit().putString(KEY, month.toString()).apply() }

    /** For tests: as on a fresh install. */
    fun clear(context: Context) { prefs(context).edit().clear().commit() }
}

/** Home's recap card: [card] once loaded (null while loading, outside a month's first week, after a light month or once closed). */
@Stable
internal class RecapCardState(private val context: Context) {
    var card by mutableStateOf<RecapCard?>(null)
        internal set
    /** The card has been looked for (whether or not there is one). */
    var loaded by mutableStateOf(false)
        internal set

    /** Put away for its month; next month's comes back. */
    fun dismiss() {
        card?.let { RecapCardDismissals.dismiss(context, it.month) }
        card = null
    }
}

/**
 * The recap card for Home, looked for once while Home is open (not again each time the Replay section scrolls back into
 * view), off the main thread, and only when Home shows the Replay section ([wanted]): the card is part of it.
 */
@Composable
internal fun rememberRecapCard(wanted: Boolean, now: () -> Long = System::currentTimeMillis): RecapCardState {
    val c = LocalContainer.current
    val state = remember { RecapCardState(c.app) }
    val clock by rememberUpdatedState(now)
    LaunchedEffect(wanted) {
        if (!wanted || state.loaded) return@LaunchedEffect
        state.card = loadRecapCard(c, clock())
        state.loaded = true
    }
    return state
}

/** The card to show at [now], off the main thread: [com.vinz.music.data.Recaps.card], unless closed for its month. */
internal suspend fun loadRecapCard(c: com.vinz.music.AppContainer, now: Long): RecapCard? = withContext(Dispatchers.IO) {
    runCatching { c.recaps.card(now) }.getOrNull()?.takeIf { !RecapCardDismissals.isDismissed(c.app, it.month) }
}

/** Tags for tests. */
internal object RecapHomeCardTags {
    const val CARD = "recapCard"
    const val CLOSE = "recapCard:close"
}

/**
 * Last month at a glance (1.8.5), under the Replay card for a month's first week: "Your October", the time listened
 * (as Replay shows it: minutes, or hours with "Show time in hours") and the top song (else the top artist), with its
 * cover. Tap opens that month's story on Replay; × puts it away for the month. Still: nothing on it moves.
 */
@Composable
internal fun RecapHomeCard(card: RecapCard, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val scheme = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val title = tr(R.string.recap_card_title, RecapCalendar.name(card.month))
    val top = card.topSong?.title ?: card.topArtist?.artistName
    val line = listOfNotNull(listenTotal(card.totalMs).full, top).joinToString(" · ")
    val shape = ui.shape(16.dp)
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = ui.gap(14.dp)).fillMaxWidth()
            .pane(shape).border(0.7.dp, glassEdge(), shape)
            .clickable(onClickLabel = title, onClick = onOpen)
            .testTag(RecapHomeCardTags.CARD)
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val song = card.topSong
        Artwork(
            song?.thumbnail ?: card.topArtist?.thumbnail, Modifier.size(56.dp), shape = ui.shape(10.dp), size = 226,
            placeholderIcon = if (song != null) VinzIcons.Song else VinzIcons.Person,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // Replay's mark: a short dash of split light, as on the Replay and Rewind cards.
            Box(Modifier.padding(bottom = 6.dp).size(20.dp, 2.dp).clip(CircleShape).background(VinzTokens.spectrum(dark)))
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(RecapHomeCardTags.CLOSE)) {
            Icon(VinzIcons.Close, tr(R.string.dismiss), tint = scheme.onSurfaceVariant)
        }
    }
}
