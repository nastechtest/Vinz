package com.vinz.music.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vinz.music.R
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzSwitch
import com.vinz.music.ui.components.VinzTextField

@Composable
internal fun CloudProfileFields(state: CloudAccountState, actions: CloudAccountController) {
    val focus = LocalFocusManager.current
    val registering = state.identity == null && state.mode == CloudAccountMode.SIGN_UP
    VinzTextField(
        state.draft.username, actions::username, Modifier.fillMaxWidth().testTag("cloud-account-username"),
        enabled = !state.busy, label = { Text(tr(R.string.cloud_account_username)) },
        supportingText = { Text(tr(R.string.cloud_account_username_hint) + when (state.usernameAvailability) {
            UsernameAvailability.CHECKING -> " · Checking availability with Vinz…"
            UsernameAvailability.AVAILABLE -> " · Username available"
            UsernameAvailability.UNAVAILABLE -> " · Username unavailable or reserved"
            UsernameAvailability.INVALID -> " · Enter 3–24 lowercase letters, numbers or underscores"
            UsernameAvailability.ERROR -> " · Could not reach Vinz. Try again."
            else -> ""
        }) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
    )
    VinzTextField(
        state.draft.displayName, actions::displayName, Modifier.fillMaxWidth().testTag("cloud-account-display-name"),
        enabled = !state.busy, label = { Text(tr(R.string.cloud_account_display_name)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (registering) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Next) },
            onDone = {
                if (registering && state.canSubmit) {
                    focus.clearFocus()
                    actions.submit()
                }
            },
        ),
    )
}

/** The whole labelled row is one switch, with one accessible action and a generous tap target. */
@Composable
internal fun CloudPrivacySwitch(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VinzSwitch(checked, null, Modifier.clearAndSetSemantics { }, enabled = enabled)
    }
}
