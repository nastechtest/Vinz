package com.vinz.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.vinz.music.R
import com.vinz.music.data.CloudAvatarImage
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.components.VinzTextField
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudAccountState
import com.vinz.music.ui.screens.account.CloudPrivacySwitch
import com.vinz.music.ui.screens.account.CloudProfileFields

/** Full-page native account experience shared by onboarding, Settings and the legacy route alias. */
@Composable
internal fun CloudAccountContent(
    state: CloudAccountState,
    actions: CloudAccountController,
    onBack: () -> Unit,
    onContinue: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    supplementalContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(state.oauthUrl) {
        val url = state.oauthUrl ?: return@LaunchedEffect
        val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        actions.consumeGoogleOAuthUrl()
        if (!opened) actions.googleOAuthLaunchFailed()
    }
    var section by remember(state.identity?.userId) { mutableStateOf("profile") }
    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = bottomPadding + 24.dp)
                .testTag("cloud-account-content"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VinzTextButton(onClick = onContinue ?: onBack, enabled = !state.busy,
                modifier = Modifier.testTag(if (onContinue == null) "cloud-account-back" else "cloud-account-skip")) {
                Text(tr(if (onContinue == null) R.string.back else R.string.skip_for_now))
            }
            Text(tr(when (state.mode) {
                CloudAccountMode.VERIFY_EMAIL -> R.string.cloud_account_otp_title
                CloudAccountMode.RESET_PASSWORD -> R.string.cloud_account_reset_password_title
                else -> R.string.your_vinz_account
            }), style = MaterialTheme.typography.headlineMedium)
            Text(
                when (state.mode) {
                    CloudAccountMode.VERIFY_EMAIL -> tr(R.string.cloud_account_otp_intro, state.email)
                    CloudAccountMode.RESET_PASSWORD -> tr(R.string.cloud_account_reset_password_intro)
                    else -> tr(R.string.cloud_account_intro)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloudAccountFormBody(state, actions, section = section, onSection = { section = it })
            if (onContinue != null && state.canContinue) {
                VinzButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("cloud-account-continue")) {
                    Text(tr(R.string.done))
                }
            }
            supplementalContent()
            if (state.identity != null) VinzTextButton(
                onClick = { actions.signOut() }, modifier = Modifier.testTag("cloud-account-sign-out"), enabled = !state.busy,
            ) { Text(tr(R.string.sign_out)) }
        }
    }
}

@Composable
private fun CloudAccountFormBody(
    state: CloudAccountState,
    actions: CloudAccountController,
    section: String,
    onSection: (String) -> Unit,
) {
    val focus = LocalFocusManager.current

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutineScope.launch {
            try {
                val image = CloudAvatarImage.fromUri(context.contentResolver, uri)
                actions.uploadAvatar(image.bytes, image.contentType)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actions.avatarInputFailed()
            }
        }
    }
    LaunchedEffect(section, state.identity?.userId) {
        if (section == "settings" && state.identity != null && !state.settingsReady) actions.loadSettings()
    }
    val submitFromKeyboard: () -> Unit = {
        if (state.canSubmit) { focus.clearFocus(); actions.submit() }
    }
    if (state.busy) {
        CircularProgressIndicator(Modifier.testTag("cloud-account-progress"))
        Text(tr(R.string.loading_2), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.error?.let {
        Text(tr(it), Modifier.testTag("cloud-account-error").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    state.message?.let {
        Text(tr(it), Modifier.testTag("cloud-account-message").semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
    if (state.mode == CloudAccountMode.RESET_PASSWORD) {
        androidx.compose.material3.OutlinedTextField(
            state.password, actions::password, Modifier.fillMaxWidth().testTag("cloud-account-new-password"),
            enabled = !state.busy, label = { Text(tr(R.string.cloud_account_password)) },
            supportingText = { Text(tr(R.string.cloud_account_password_hint)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitFromKeyboard() }),
        )
        androidx.compose.material3.OutlinedTextField(
            state.confirmPassword, actions::confirmPassword,
            Modifier.fillMaxWidth().testTag("cloud-account-confirm-password"),
            enabled = !state.busy, label = { Text(tr(R.string.cloud_account_confirm_password)) },
            supportingText = { Text(tr(R.string.cloud_account_confirm_password_hint)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitFromKeyboard() }),
        )
        VinzButton(
            onClick = { actions.submit() },
            modifier = Modifier.fillMaxWidth().testTag("cloud-account-update-password"),
            enabled = state.canSubmit,
        ) { Text(tr(R.string.cloud_account_update_password)) }
        return
    }
    if (state.mode == CloudAccountMode.VERIFY_EMAIL) {
        androidx.compose.material3.OutlinedTextField(
            state.otp, actions::otp, Modifier.fillMaxWidth().testTag("cloud-account-otp"),
            enabled = !state.busy, label = { Text(tr(R.string.cloud_account_otp_code)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitFromKeyboard() }),
        )
        Text(tr(R.string.cloud_account_otp_expiry_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        VinzButton(onClick = { actions.submit() }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-verify"),
            enabled = state.canSubmit) { Text(tr(R.string.cloud_account_otp_verify)) }
        VinzTextButton(onClick = { actions.resendOtp() }, modifier = Modifier.testTag("cloud-account-resend-otp"),
            enabled = state.canResendOtp) {
            Text(if (state.resendSecondsRemaining > 0) tr(R.string.cloud_account_otp_resend_countdown, state.resendSecondsRemaining)
                else tr(R.string.cloud_account_otp_resend))
        }
        VinzTextButton(onClick = { actions.mode(CloudAccountMode.SIGN_IN) },
            modifier = Modifier.testTag("cloud-account-back-to-sign-in"), enabled = !state.busy) {
            Text(tr(R.string.cloud_account_back_to_sign_in))
        }
        return
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
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { submitFromKeyboard() }),
        )
        if (!recovery) {
            androidx.compose.material3.OutlinedTextField(
                state.password, actions::password, Modifier.fillMaxWidth().testTag("cloud-account-password"),
                enabled = !state.busy, label = { Text(tr(R.string.cloud_account_password)) },
                supportingText = { Text(tr(if (registering) R.string.cloud_account_password_hint else R.string.cloud_account_password_sign_in_hint)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                    imeAction = if (registering) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { submitFromKeyboard() }),
            )
        }
        if (registering) CloudProfileFields(state, actions)
        VinzButton(onClick = { actions.submit() }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-submit"), enabled = state.canSubmit) {
            Text(tr(when (state.mode) {
                CloudAccountMode.RECOVERY -> R.string.cloud_account_recovery
                CloudAccountMode.SIGN_UP -> R.string.cloud_account_create
                CloudAccountMode.SIGN_IN -> R.string.sign_in
                CloudAccountMode.VERIFY_EMAIL -> R.string.cloud_account_otp_verify
                CloudAccountMode.RESET_PASSWORD -> R.string.cloud_account_update_password
            }))
        }
        if (state.mode == CloudAccountMode.SIGN_IN) VinzTextButton(
            onClick = { actions.beginGoogleOAuth() },
            modifier = Modifier.fillMaxWidth().testTag("cloud-account-google"),
            enabled = !state.busy,
        ) { Text(tr(R.string.sign_in_with_google)) }
        VinzTextButton(
            onClick = { actions.mode(if (registering || recovery) CloudAccountMode.SIGN_IN else CloudAccountMode.SIGN_UP) },
            modifier = Modifier.testTag("cloud-account-mode"), enabled = !state.busy,
        ) { Text(tr(if (registering || recovery) R.string.cloud_account_existing else R.string.cloud_account_create)) }
        if (!registering && !recovery) VinzTextButton(
            onClick = { actions.mode(CloudAccountMode.RECOVERY) },
            modifier = Modifier.testTag("cloud-account-recovery"), enabled = !state.busy,
        ) { Text(tr(R.string.cloud_account_forgot)) }
        return
    }

    Text(tr(R.string.cloud_account_signed_in, state.identity.email), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.profileLoadFailed) {
        VinzButton(onClick = { actions.loadProfile() }, modifier = Modifier.testTag("cloud-account-retry"), enabled = !state.busy) {
            Text(tr(R.string.try_again))
        }
        return
    }
    if (!state.profileReady) return

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("cloud-account-sections"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            "overview" to R.string.cloud_account_tab_overview,
            "profile" to R.string.kind_profile,
            "security" to R.string.cloud_account_tab_security,
            "connections" to R.string.connections,
            "settings" to R.string.cloud_account_tab_settings,
        ).forEach { (id, title) ->
            VinzTextButton(onClick = { onSection(id) }, enabled = !state.busy, modifier = Modifier.testTag("cloud-account-tab-$id")) {
                Text(if (section == id) tr(R.string.cloud_account_selected_tab, tr(title)) else tr(title))
            }
        }
    }
    when (section) {
        "overview" -> {
            CloudAvatarPreview(state.draft.avatarPath)
            Text(tr(R.string.cloud_account_overview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(state.draft.displayName.ifBlank { tr(R.string.cloud_account_profile_default) }, style = MaterialTheme.typography.headlineSmall)
            Text("@${state.draft.username}", style = MaterialTheme.typography.titleMedium)
            if (state.draft.bio.isNotBlank()) Text(state.draft.bio, style = MaterialTheme.typography.bodyMedium)
            Text(tr(R.string.cloud_account_overview_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VinzButton(onClick = { onSection("profile") }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-edit-profile")) {
                Text(tr(R.string.cloud_account_edit_profile))
            }
        }
        "profile" -> {
            Text(tr(R.string.cloud_account_music_profile), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            CloudAvatarPreview(state.draft.avatarPath)
            Text(tr(R.string.cloud_account_avatar_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VinzButton(
                onClick = { avatarPicker.launch(CloudAvatarImage.PICKER_MIME_TYPE) },
                modifier = Modifier.fillMaxWidth().testTag("cloud-account-avatar-pick"),
                enabled = !state.busy,
            ) { Text(tr(R.string.cloud_account_avatar_choose)) }
            if (state.draft.avatarPath != null) VinzTextButton(
                onClick = { actions.removeAvatar() },
                modifier = Modifier.testTag("cloud-account-avatar-remove"),
                enabled = state.canRemoveAvatar,
            ) { Text(tr(R.string.cloud_account_avatar_remove)) }
            Text(tr(R.string.cloud_account_private_details), style = MaterialTheme.typography.titleMedium)
            VinzTextField(
                state.draft.fullName, actions::fullName,
                Modifier.fillMaxWidth().testTag("cloud-account-full-name"), enabled = !state.busy,
                label = { Text(tr(R.string.cloud_account_full_name)) },
                supportingText = { Text(tr(R.string.cloud_account_full_name_private_hint)) },
                singleLine = true,
            )
            VinzButton(
                onClick = { actions.savePrivateDetails() },
                modifier = Modifier.fillMaxWidth().testTag("cloud-account-save-private-details"),
                enabled = state.canSavePrivateDetails,
            ) { Text(tr(R.string.cloud_account_save_private_details)) }
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
        "security" -> {
            Text(tr(R.string.cloud_account_security), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(tr(R.string.cloud_account_password_and_recovery), style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.cloud_account_password_reset_description, state.identity.email),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VinzButton(onClick = { actions.sendPasswordReset() }, modifier = Modifier.fillMaxWidth().testTag("cloud-account-send-reset"), enabled = !state.busy) {
                Text(tr(R.string.cloud_account_send_password_reset))
            }
        }
        "connections" -> {
            Text(tr(R.string.cloud_account_connected_services), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(tr(R.string.cloud_account_connections_subtitle),
                style = MaterialTheme.typography.titleMedium)
            Text(tr(R.string.cloud_account_connections_description),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        "settings" -> {
            Text(tr(R.string.cloud_account_tab_settings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (state.settingsLoadFailed) {
                VinzButton(
                    onClick = { actions.loadSettings() },
                    modifier = Modifier.fillMaxWidth().testTag("cloud-account-settings-retry"),
                    enabled = !state.busy,
                ) { Text(tr(R.string.try_again)) }
            } else if (!state.settingsReady) {
                if (!state.busy) CircularProgressIndicator(Modifier.testTag("cloud-account-settings-loading"))
            } else {
                Text(tr(R.string.cloud_account_privacy_settings), style = MaterialTheme.typography.titleMedium)
                CloudPrivacySwitch(
                    state.settingsDraft.allowFriendRequests, !state.busy,
                    tr(R.string.cloud_account_allow_friend_requests), tr(R.string.cloud_account_allow_friend_requests_hint),
                    "cloud-account-allow-friend-requests", actions::allowFriendRequests,
                )
                CloudPrivacySwitch(
                    state.settingsDraft.showListeningActivity, !state.busy,
                    tr(R.string.cloud_account_show_listening_activity), tr(R.string.cloud_account_show_listening_activity_hint),
                    "cloud-account-show-listening-activity", actions::showListeningActivity,
                )
                Text(tr(R.string.cloud_account_notification_settings), style = MaterialTheme.typography.titleMedium)
                CloudPrivacySwitch(
                    state.settingsDraft.notifyNewReleases, !state.busy,
                    tr(R.string.cloud_account_notify_new_releases), tr(R.string.cloud_account_notify_new_releases_hint),
                    "cloud-account-notify-new-releases", actions::notifyNewReleases,
                )
                CloudPrivacySwitch(
                    state.settingsDraft.notifyFriendActivity, !state.busy,
                    tr(R.string.cloud_account_notify_friend_activity), tr(R.string.cloud_account_notify_friend_activity_hint),
                    "cloud-account-notify-friend-activity", actions::notifyFriendActivity,
                )
                CloudPrivacySwitch(
                    state.settingsDraft.notifyPodcastUpdates, !state.busy,
                    tr(R.string.cloud_account_notify_podcast_updates), tr(R.string.cloud_account_notify_podcast_updates_hint),
                    "cloud-account-notify-podcast-updates", actions::notifyPodcastUpdates,
                )
                VinzButton(
                    onClick = { actions.saveSettings() },
                    modifier = Modifier.fillMaxWidth().testTag("cloud-account-save-settings"),
                    enabled = state.canSaveSettings,
                ) { Text(tr(R.string.cloud_account_save_settings)) }
            }
        }
    }
}

@Composable
private fun CloudAvatarPreview(path: String?) {
    val url = remember(path) { VinzCloudAccount.publicAvatarUrl(path) }
    if (url == null) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("cloud-account-avatar-empty"),
            contentAlignment = Alignment.Center,
        ) {
            Text(tr(R.string.cloud_account_avatar_empty), style = MaterialTheme.typography.labelSmall)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = tr(R.string.cloud_account_avatar_description),
            modifier = Modifier.size(104.dp).clip(CircleShape).testTag("cloud-account-avatar-preview"),
        )
    }
}
