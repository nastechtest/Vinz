package com.vinz.music.ui.screens

import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.theme.VinzIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vinz.music.data.SyncState
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.EmptyState
import com.vinz.music.ui.components.SubPage
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

private const val TMM = "https://www.tunemymusic.com/transfer"

/**
 * Library → Import playlist. TuneMyMusic (the service YouTube Music's own app uses) moves playlists
 * from Spotify, Apple Music and others straight into the YouTube Music account; Vinz opens it in the
 * browser and syncs the library when the listener comes back. Vinz never shares its sign-in with it.
 */
@Composable
fun ImportScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val sync by c.library.syncState.collectAsState()
    // Set once TuneMyMusic was opened, so coming back to Vinz pulls in the new playlists.
    var opened by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && opened) { opened = false; scope.launch { c.library.sync() } }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(path: String) { opened = true; uri.openUri("$TMM$path") }

    SubPage("Import playlist", bottomPadding, subtitle = "From Spotify, Apple Music and more") {
        if (!settings.isLoggedIn) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(VinzIcons.PlaylistAdd, "Sign in to import playlists", "Imported playlists go to your YouTube Music account, then show up in Vinz.")
                    VinzButton(onClick = { nav.go(Routes.LOGIN) }) { Icon(VinzIcons.Login, null); Spacer(Modifier.width(8.dp)); Text("Sign in") }
                }
            }
            return@SubPage
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "TuneMyMusic copies your playlists, Liked Songs included, into your YouTube Music account. " +
                        "It's the same service YouTube Music's own app uses for transfers.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                VinzButton(onClick = { open("/spotify-to-youtube-music") }, Modifier.fillMaxWidth()) { Text("From Spotify") }
                VinzButton(onClick = { open("/apple-music-to-youtube-music") }, Modifier.fillMaxWidth()) { Text("From Apple Music") }
                VinzOutlinedButton(onClick = { open("") }, Modifier.fillMaxWidth()) {
                    Text("From another service or a file"); Spacer(Modifier.width(8.dp)); Icon(VinzIcons.OpenInNew, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.size(4.dp))
                Text("How it works", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Sign in to Spotify or Apple Music on TuneMyMusic and pick the playlists.\n" +
                        "2. Choose YouTube Music as the destination and sign in with the Google account you use in Vinz" +
                        settings.accountEmail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + ".\n" +
                        "3. When the transfer finishes, come back here. Vinz syncs your library and the playlists appear under Library → Playlists.",
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                VinzTextButton(onClick = { scope.launch { c.library.sync() } }, enabled = sync != SyncState.SYNCING) {
                    if (sync == SyncState.SYNCING) com.vinz.music.ui.components.VinzSpinner(Modifier.size(16.dp), stroke = 2.dp)
                    else Icon(VinzIcons.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sync == SyncState.SYNCING) "Syncing…" else "Sync library now")
                }
                Text(
                    "TuneMyMusic is a separate service, not part of Vinz. It asks for access to the accounts you connect and sees the " +
                        "playlists you move; its free plan has limits. Vinz doesn't share anything with it.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
