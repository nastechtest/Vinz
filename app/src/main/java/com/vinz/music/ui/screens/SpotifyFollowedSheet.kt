package com.vinz.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.data.spotify.SpotifyPlaylist
import com.vinz.music.data.spotify.SpotifyPlaylists
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.ActionGroup
import com.vinz.music.ui.components.ActionHeader
import com.vinz.music.ui.components.ActionItem
import com.vinz.music.ui.components.ActionSheet
import com.vinz.music.ui.components.runAction
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.launch

/**
 * A followed or saved Spotify playlist tapped in Library (1.8.1): Spotify doesn't share its songs with a developer app,
 * so the sheet says so in one line and gives the two ways to bring it: a copy of your own in Spotify (which "Your
 * playlists" then mirrors; "Refresh now" reads the list again at once), or TuneMyMusic through Library → Import
 * playlists. "Open in Spotify" hands the playlist's link to the Spotify app.
 */
@Composable
fun SpotifyFollowedSheet(p: SpotifyPlaylist, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(false) }
    ActionSheet(p.image, onDismiss) { close ->
        ActionHeader(p.image, p.name, p.ownerName.ifBlank { null }, placeholderIcon = VinzIcons.QueueMusic)
        Text(tr(R.string.spot_followed_why), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            tr(R.string.spot_followed_steps), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
        )
        ActionGroup(null, listOf(
            ActionItem(VinzIcons.OpenInNew, tr(R.string.spot_open_in_spotify)) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyPlaylists.openLink(p.id)))
                runCatching { context.startActivity(view) }.onFailure { runCatching { context.startActivity(view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            },
            ActionItem(
                VinzIcons.Sync, tr(R.string.spot_refresh_now), keepOpen = true,
                value = when { refreshing -> tr(R.string.spot_refreshing); refreshed -> tr(R.string.spot_refreshed); else -> null },
            ) {
                if (!refreshing) {
                    refreshing = true
                    scope.launch { c.spotify.refreshPlaylistsNow().join(); refreshing = false; refreshed = true }
                }
            },
        )) { runAction(it, close) }
        Spacer(Modifier.height(14.dp))
        Text(
            tr(R.string.spot_followed_tunemymusic), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
        )
        ActionGroup(null, listOf(
            ActionItem(VinzIcons.PlaylistAdd, tr(R.string.import_playlists)) { nav.go(Routes.IMPORT) },
        )) { runAction(it, close) }
        ActionGroup(null, listOf(
            ActionItem(VinzIcons.PlaylistRemove, tr(R.string.spot_hide_from_library)) { c.spotify.hideFollowed(p.id) },
        )) { runAction(it, close) }
    }
}
