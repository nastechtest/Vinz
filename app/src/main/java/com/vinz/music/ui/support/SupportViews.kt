package com.vinz.music.ui.support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.pane
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags of the asks. */
object SupportTags {
    const val HOME_CARD = "support:home"
    const val STORY_LINE = "support:story"
    const val WHATS_NEW_LINE = "support:whatsNew"
}

/**
 * The 100-hour card on Home (plan-1.8.7.6 §4.1 S4): once the phone's own listening passes [SupportAsks.HOURS] hours and
 * [SupportAsks.due] says so, beside no other offer ([otherOffer]). "Support on Ko-fi" and "Don't show again" each end it
 * for good. Seen and not answered, it stays (as the other offers do), and is the only ask on the days it shows.
 */
@Composable
fun SupportHomeCard(otherOffer: Boolean) {
    val c = LocalContainer.current
    val settings = LocalAppSettings.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val marks = remember { SupportMarks(context) }
    var ended by remember { mutableStateOf(false) }
    // Only asked of the database while this ask could show at all: not once it was answered, and not in the first days.
    val over = remember { SupportAsks.HOME.key in marks.shown() || marks.now() - marks.installedAt() < SupportAsks.QUIET_DAYS * 86_400_000L }
    val quiet = ended || over || settings.behavior.supported || !settings.onboarded
    val listened by produceState<Long?>(null, quiet) {
        value = if (quiet) null else withContext(Dispatchers.IO) { runCatching { c.db.plays().totalMs(0, marks.now()) }.getOrNull() }
    }
    val ms = listened ?: return
    if (quiet) return
    val due = remember(otherOffer, ms) {
        SupportAsks.due(SupportAsks.HOME, marks.state(settings.onboarded, settings.behavior.supported, ms, otherOffer))
    }
    if (!due) return
    LaunchedEffect(Unit) { marks.stamp(SupportAsks.HOME) }
    val ui = com.vinz.music.ui.theme.LocalUi.current
    val end = { marks.end(SupportAsks.HOME); ended = true }
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = ui.gap(14.dp)).fillMaxWidth().pane(ui.shape(16.dp))
            .testTag(SupportTags.HOME_CARD).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VinzIcons.Heart, null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.support_hours_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.support_hours_body),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.align(Alignment.End)) {
            VinzTextButton(onClick = end) { Text(tr(R.string.don_t_show_again)) }
            VinzTextButton(onClick = { end(); SupportAsks.openKofi(uri) }) { Text(tr(R.string.support_on_kofi)) }
        }
    }
}

/**
 * The quiet line under the last slide of a Replay ([rewind] false) or the year's Rewind (plan §4.1 S5, D4): once for
 * each, marked as shown when the slide is reached (this is drawn only then).
 */
@Composable
fun SupportStoryLine(rewind: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val settings = LocalAppSettings.current
    val marks = remember { SupportMarks(context) }
    val ask = if (rewind) SupportAsks.REWIND else SupportAsks.REPLAY
    val due = remember { SupportAsks.due(ask, marks.state(settings.onboarded, settings.behavior.supported)) }
    if (!due) return
    LaunchedEffect(Unit) { marks.end(ask) }
    QuietLine(tr(R.string.support_made_by_one_person), Color.White.copy(alpha = 0.7f), modifier.testTag(SupportTags.STORY_LINE)) { SupportAsks.openKofi(uri) }
}

/**
 * The line on the last What's new card of a release (plan §4.1 S6, D5): once per release ([version]), hidden with the
 * switch on, marked as shown when its card is the one on screen ([current]).
 */
@Composable
fun SupportWhatsNewLine(version: Int, current: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val settings = LocalAppSettings.current
    val marks = remember { SupportMarks(context) }
    val ask = SupportAsks.whatsNew(version)
    // Decided when the card comes on screen, and kept while it is (marking it doesn't take it away).
    var shows by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(current) {
        if (current && shows == null) {
            shows = SupportAsks.due(ask, marks.state(settings.onboarded, settings.behavior.supported))
            if (shows == true) marks.end(ask)
        }
    }
    if (shows != true || settings.behavior.supported) return
    QuietLine(tr(R.string.support_whats_new_line), Color.White.copy(alpha = 0.7f), modifier.testTag(SupportTags.WHATS_NEW_LINE)) { SupportAsks.openKofi(uri) }
}

/** One quiet line and a text button, "Support Vinz". */
@Composable
private fun QuietLine(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        VinzTextButton(onClick = onClick) { Text(tr(R.string.support_vinz), color = Color.White) }
    }
}
