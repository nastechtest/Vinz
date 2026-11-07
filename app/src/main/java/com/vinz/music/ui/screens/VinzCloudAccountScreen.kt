package com.vinz.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.VinzTextField
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudAccountState
import com.vinz.music.ui.screens.account.CloudPrivacySwitch
import com.vinz.music.ui.screens.account.CloudProfileFields

/** One full-page renderer shared by startup, Settings and the legacy route alias. */
@Composable
internal fun CloudAccountContent(
    state: CloudAccountState,
    actions: CloudAccountController,
    onBack: () -> Unit,
    onContinue: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    supplementalContent: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = bottomPadding + 24.dp)
                .testTag("cloud-account-content"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Onboarding Skip advances the setup flow; ordinary account Back only navigates away.
            VinzTextButton(onClick = onContinue ?: onBack, enabled = !state.busy,
                modifier = Modifier.testTag(if (onContinue == null) "cloud-account-back" else "cloud-account-skip")) {
                Text(tr(if (onContinue == null) R.string.back else R.string.skip_for_now))
            }
            Text(tr(R.string.your_vinz_account), style = MaterialTheme.typography.headlineMedium)
            Text(tr(R.string.cloud_account_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CloudAccountFormBody(state, actions)
            if (onContinue != null && state.canContinue) {
                VinzButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("cloud-account-continue")) {
                    Text(tr(R.string.done))
                }
            }
            supplementalContent()
        }
    }
}

@Composable
private fun CloudAccountFormBody(state: CloudAccountState, actions: CloudAccountController) {
    val focus = LocalFocusManager.current
    val submitFromKeyboard: () -> Unit = {
        if (state.canSubmit) {
            focus.clearFocus()
            actions.submit()
        }
    }
    if (state.busy) {
        CircularProgressIndicator(Modifier.testTag("cloud-account-progress"))
        Text(tr(R.string.loading_2), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    // Put feedback before long forms so errors remain visible with large text and the keyboard open.
    state.error?.let {
        Text(tr(it), Modifier.testTag("cloud-account-error").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    state.message?.let {
        Text(tr(it), Modifier.testTag("cloud-account-message").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.identity == null) {
        val recovery = state.mode == CloudAccountMode.RECOVERY
        val registering = state.mode == CloudAccountMode.SIGN_UP
        VinzTextField(
            state.email, actions::email, Modifier.fillMaxWidth().testTag("cloud-account-email"),
            enabled = !state.busy, label = { Text(tr(R.string.cloud_account_email)) }, singleLine = true,
            supportingText = { Text(tr(R.string.cloud_account_email_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email,
                imeAction = if (recovery) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(FocusDirection.Next) },
                onDone = { submitFromKeyboard() },
            ),
        )
        if (!recovery) {
            // Password transformation is applied by the existing Material field; never save its value.
            androidx.compose.material3.OutlinedTextField(
                state.password, actions::password, Modifier.fillMaxWidth().testTag("cloud-account-password"),
                enabled = !state.busy, label = { Text(tr(R.string.cloud_account_password)) },
                supportingText = { Text(tr(if (registering) R.string.cloud_account_password_hint else R.string.cloud_account_password_sign_in_hint)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                    imeAction = if (registering) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onNext = { focus.moveFocus(FocusDirection.Next) },
                    onDone = { submitFromKeyboard() },
                ),
            )
        }
        if (registering) CloudProfileFields(state, actions)
        VinzButton(onClick = { actions.submit() }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-submit"), enabled = state.canSubmit) {
            Text(tr(when (state.mode) {
                CloudAccountMode.RECOVERY -> R.string.cloud_account_recovery
                CloudAccountMode.SIGN_UP -> R.string.cloud_account_create
                CloudAccountMode.SIGN_IN -> R.string.sign_in
            }))
        }
        // Stack secondary actions: both remain readable on narrow screens and at large font scales.
        VinzTextButton(
            onClick = { actions.mode(if (registering || recovery) CloudAccountMode.SIGN_IN else CloudAccountMode.SIGN_UP) },
            modifier = Modifier.testTag("cloud-account-mode"), enabled = !state.busy,
        ) { Text(tr(if (registering || recovery) R.string.cloud_account_existing else R.string.cloud_account_create)) }
        if (!registering && !recovery) VinzTextButton(
            onClick = { actions.mode(CloudAccountMode.RECOVERY) }, modifier = Modifier.testTag("cloud-account-recovery"), enabled = !state.busy,
        ) { Text(tr(R.string.cloud_account_forgot)) }
    } else {
        Text(tr(R.string.cloud_account_signed_in, state.identity.email), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.profileLoadFailed) {
            VinzButton(onClick = { actions.loadProfile() }, modifier = Modifier.testTag("cloud-account-retry"), enabled = !state.busy) {
                Text(tr(R.string.try_again))
            }
        } else if (state.profileReady) {
            CloudProfileFields(state, actions)
            VinzTextField(
                state.draft.bio, actions::bio, Modifier.fillMaxWidth().testTag("cloud-account-bio"), enabled = !state.busy,
                label = { Text(tr(R.string.cloud_account_bio)) },
                supportingText = { Text(tr(R.string.cloud_account_bio_count, state.draft.bio.length)) }, minLines = 3,
            )
            CloudPrivacySwitch(state.draft.isPublic, !state.busy, tr(R.string.cloud_account_public),
                tr(R.string.cloud_account_public_hint), "cloud-account-public", actions::publicProfile)
            CloudPrivacySwitch(state.draft.discoverable, !state.busy, tr(R.string.cloud_account_discoverable),
                tr(R.string.cloud_account_discoverable_hint), "cloud-account-discoverable", actions::discoverable)
            VinzButton(onClick = { actions.save() }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-save"), enabled = state.canSave) {
                Text(tr(R.string.save))
            }
        }
        VinzTextButton(onClick = { actions.signOut() }, modifier = Modifier.testTag("cloud-account-sign-out"), enabled = !state.busy) {
            Text(tr(R.string.sign_out))
        }
    }
}
