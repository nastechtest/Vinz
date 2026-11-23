package com.vinz.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.VinzOutlinedButton
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.ExistingCloudAccountGateway
import com.vinz.music.ui.theme.LocalContainer
import androidx.compose.material3.Text

/** One route-scoped cloud account host; local profiles and playback credentials remain independent. */
@Composable
fun VinzAccountScreen(onboarding: Boolean, bottomPadding: Dp, signIn: Boolean = false, onDone: () -> Unit) {
    val cloud = LocalContainer.current.vinzCloudAccount
    val scope = rememberCoroutineScope()
    val controller = remember(cloud, scope, onboarding, signIn) {
        CloudAccountController(ExistingCloudAccountGateway(cloud), scope).also {
            if (onboarding && !signIn) it.mode(CloudAccountMode.SIGN_UP)
        }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    LaunchedEffect(controller) { controller.loadProfile() }
    DisposableEffect(controller) { onDispose { controller.clearPassword() } }
    BackHandler(enabled = state.busy) { }
    CloudAccountContent(
        state = state, actions = controller, onBack = onDone,
        onContinue = if (onboarding) onDone else null, bottomPadding = bottomPadding,
    ) {
        if (!onboarding) VinzOutlinedButton(onClick = { nav.go(Routes.LOCAL_ACCOUNT) },
            modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text(tr(R.string.vinz_account_on_this_phone))
        }
    }
}
