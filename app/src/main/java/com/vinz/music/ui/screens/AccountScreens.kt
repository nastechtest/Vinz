package com.vinz.music.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.trp
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.ExistingCloudAccountGateway
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import com.vinz.music.ui.theme.VinzTokens
import com.vinz.music.ui.theme.drawVinzAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Vinz identity never falls back to the old phone-only profile or the YouTube Music identity. */
@Composable
fun ProfilePicture(modifier: Modifier = Modifier, description: String = tr(R.string.your_account)) {
    val identity = ExistingCloudAccountGateway(LocalContainer.current.vinzCloudAccount).identity
    BoxWithConstraints(
        modifier.clip(CircleShape).background(com.vinz.music.ui.components.glassFill(1.4f))
            .border(if (identity != null) 1.5.dp else 1.dp,
                if (identity != null) VinzTokens.spectrumRing() else com.vinz.music.ui.components.glassEdge(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (identity != null && identity.email.isNotBlank()) Text(
            identity.email.trim().take(1).uppercase(),
            Modifier.clearAndSetSemantics { contentDescription = description },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (maxWidth.value * 0.42f).sp, fontWeight = FontWeight.Light),
        ) else Icon(VinzIcons.Person, description, Modifier.size(maxWidth * 0.52f), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Retained shared artwork; no local profile editor or avatar upload contract is introduced. */
@Composable
fun AvatarArt(avatar: com.vinz.music.ui.theme.VinzAvatar, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) { drawVinzAvatar(avatar) }
}

/** The sole Vinz Account host, used by startup, Settings and the legacy route alias. */
@Composable
fun VinzAccountScreen(onboarding: Boolean, bottomPadding: Dp, onDone: () -> Unit) {
    val cloud = LocalContainer.current.vinzCloudAccount
    val scope = rememberCoroutineScope()
    val controller = remember(cloud, scope, onboarding) {
        CloudAccountController(ExistingCloudAccountGateway(cloud), scope).also {
            if (onboarding) it.mode(CloudAccountMode.SIGN_UP)
        }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(controller) { controller.loadProfile() }
    DisposableEffect(controller) { onDispose { controller.clearPassword() } }
    BackHandler(enabled = state.busy) { /* Do not abandon an active account operation. */ }
    CloudAccountContent(
        state = state, actions = controller, onBack = onDone,
        onContinue = if (onboarding) onDone else null,
        bottomPadding = bottomPadding,
        cinematicOnboarding = onboarding,
        supplementalContent = {
        if (!onboarding) LocalMusicSummary()
        },
    )
}

/** Local library information is separate from account connections and requires no provider login. */
@Composable
private fun LocalMusicSummary() {
    val c = LocalContainer.current
    val lists by c.vinzLibrary.lists.collectAsState()
    val albums by c.vinzLibrary.albums.collectAsState()
    val liked by c.library.liked.collectAsState(initial = emptyList())
    val stars by c.artistPrefs.favorites.collectAsState()
    Column(Modifier.fillMaxWidth().clip(com.vinz.music.ui.theme.LocalUi.current.card)
        .background(com.vinz.music.ui.components.glassFill(1.2f)).padding(18.dp)) {
        com.vinz.music.ui.components.Eyebrow(tr(R.string.on_this_phone))
        Spacer(Modifier.height(8.dp))
        Text(listOf(
            trp(R.plurals.n_liked_songs, liked.size), trp(R.plurals.n_playlists, lists.size),
            trp(R.plurals.n_saved_albums, albums.size), trp(R.plurals.n_starred_artists, stars.size),
        ).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
    }
}
