package com.vinz.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.data.VinzPublicProfile
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.theme.LocalContainer
import kotlinx.coroutines.CancellationException

private sealed interface PublicProfileUiState {
    data object Loading : PublicProfileUiState
    data class Loaded(val profile: VinzPublicProfile) : PublicProfileUiState
    data object Missing : PublicProfileUiState
    data object Failed : PublicProfileUiState
}

/** Public profile viewer. The query is anonymous and the database only grants public columns under RLS. */
@Composable
internal fun PublicVinzProfileScreen(username: String, bottomPadding: Dp) {
    val container = LocalContainer.current
    var retry by remember(username) { mutableIntStateOf(0) }
    var state by remember(username) { mutableStateOf<PublicProfileUiState>(PublicProfileUiState.Loading) }

    LaunchedEffect(username, retry) {
        state = PublicProfileUiState.Loading
        state = try {
            container.vinzCloudAccount.loadPublicProfile(username)
                ?.let { PublicProfileUiState.Loaded(it) } ?: PublicProfileUiState.Missing
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PublicProfileUiState.Failed
        }
    }

    Column(
        Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 28.dp, bottom = bottomPadding + 32.dp)
            .testTag("vinz-public-profile"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(tr(R.string.vinz_public_profile_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        when (val current = state) {
            PublicProfileUiState.Loading -> {
                CircularProgressIndicator(Modifier.testTag("vinz-public-profile-loading"))
                Text(tr(R.string.vinz_public_profile_loading))
            }
            PublicProfileUiState.Missing -> {
                Text(tr(R.string.vinz_public_profile_not_found), Modifier.testTag("vinz-public-profile-not-found"),
                    style = MaterialTheme.typography.bodyLarge)
            }
            PublicProfileUiState.Failed -> {
                Text(tr(R.string.vinz_public_profile_load_failed), Modifier.testTag("vinz-public-profile-error"),
                    style = MaterialTheme.typography.bodyLarge)
                VinzButton(onClick = { retry++ }, modifier = Modifier.testTag("vinz-public-profile-retry")) {
                    Text(tr(R.string.vinz_public_profile_retry))
                }
            }
            is PublicProfileUiState.Loaded -> {
                val profile = current.profile
                val avatarUrl = remember(profile.avatarPath) { VinzCloudAccount.publicAvatarUrl(profile.avatarPath) }
                if (avatarUrl == null) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("vinz-public-profile-avatar-empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tr(R.string.cloud_account_avatar_empty), style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = tr(R.string.vinz_public_profile_avatar),
                        modifier = Modifier.size(112.dp).clip(CircleShape).testTag("vinz-public-profile-avatar"),
                    )
                }
                Text(profile.displayName, Modifier.testTag("vinz-public-profile-name"),
                    style = MaterialTheme.typography.headlineMedium)
                Text(tr(R.string.vinz_public_profile_username, profile.username),
                    Modifier.testTag("vinz-public-profile-username"), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    profile.bio.ifBlank { tr(R.string.vinz_public_profile_no_bio) },
                    Modifier.testTag("vinz-public-profile-bio"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
