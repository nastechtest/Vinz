package com.vinz.music.ui.screens.account

import androidx.annotation.StringRes

/** Presentation-only identity: never retain access or refresh tokens in UI state. */
internal data class CloudAccountIdentity(val userId: String, val email: String)

internal enum class CloudAccountMode { SIGN_IN, SIGN_UP, RECOVERY }

internal enum class UsernameAvailability { IDLE, CHECKING, AVAILABLE, UNAVAILABLE, INVALID, ERROR, UNCHANGED }

internal data class CloudProfileDraft(
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val isPublic: Boolean = true,
    val discoverable: Boolean = true,
)

internal data class CloudAccountState(
    val identity: CloudAccountIdentity? = null,
    val mode: CloudAccountMode = CloudAccountMode.SIGN_IN,
    val email: String = "",
    // Passwords intentionally stay in memory, never rememberSaveable or persistent storage.
    val password: String = "",
    val draft: CloudProfileDraft = CloudProfileDraft(),
    val originalUsername: String = "",
    val usernameAvailability: UsernameAvailability = UsernameAvailability.IDLE,
    val busy: Boolean = false,
    val profileReady: Boolean = false,
    val profileLoadFailed: Boolean = false,
    val youtubeConnected: Boolean = false,
    val youtubeChannelTitle: String = "",
    val youtubeAuthorizationUrl: String? = null,
    val youtubeConnectPending: Boolean = false,
    val youtubeImportedCount: Int? = null,
    val youtubePlaylistCount: Int? = null,
    @param:StringRes val message: Int? = null,
    @param:StringRes val error: Int? = null,
) {
    val canContinue: Boolean get() = !busy && identity != null && profileReady && !profileLoadFailed
    val canSubmit: Boolean get() = !busy && identity == null && CloudAccountValidation.canSubmit(this)
    val canSave: Boolean get() = !busy && identity != null && profileReady && !profileLoadFailed && CloudAccountValidation.validProfile(draft)
}
