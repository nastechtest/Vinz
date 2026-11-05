package com.vinz.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons

/** Still artwork only: no extra Canvas decoder, timers or decorative motion. */
@Composable
internal fun NextTrackPreview(onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    val song by LocalContainer.current.player.nextPreviewSong.collectAsState()
    val next = song ?: return
    Row(
        modifier.fillMaxWidth().testTag("nextTrackPreview")
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClickLabel = tr(R.string.next_track_preview_open_queue), onClick = onOpenQueue)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(VinzIcons.Song, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
            AsyncImage(next.thumbnail, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(tr(R.string.next_track_preview_label), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
            Text(next.title, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(next.artistText, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
