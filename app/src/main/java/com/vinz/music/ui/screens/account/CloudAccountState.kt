package com.vinz.music.ui.screens.account

import androidx.annotation.StringRes

/** Presentation-only identity: never retain access or refresh tokens in UI state. */
internal data class CloudAccountIdentity(val userId: String, val email: String)

internal enum class CloudAccountMode { SIGN_IN, SIGN_UP, RECOVERY, VERIFY_EMAIL }

internal enum class UsernameAvailability { IDLE, CHECKING, AVAILABLE, UNAVAILABLE, INVALID, ERROR, UNCHANGED }

internal data class CloudProfileDraft(
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val isPublic: Boolean = true,
    val discoverable: Boolean = true,
    val fullName: String = "",
)

internal data class CloudAccountState(
    val identity: CloudAccountIdentity? = null,
    val mode: CloudAccountMode = CloudAccountMode.SIGN_IN,
    val email: String = "",
    // Passwords and typed OTPs stay in memory only.
    val password: String = "",
    val otp: String = "",
    val resendSecondsRemaining: Int = 0,
    val draft: CloudProfileDraft = CloudProfileDraft(),
    val originalUsername: String = "",
    val usernameAvailability: UsernameAvailability = UsernameAvailability.IDLE,
    val busy: Boolean = false,
    val profileReady: Boolean = false,
    val profileLoadFailed: Boolean = false,
    val oauthUrl: String? = null,
    @param:StringRes val message: Int? = null,
    @param:StringRes val error: Int? = null,
) {
    val canContinue: Boolean get() = !busy && identity != null && profileReady && !profileLoadFailed
    val canSubmit: Boolean get() = !busy && identity == null && CloudAccountValidation.canSubmit(this)
    val canResendOtp: Boolean get() = !busy && identity == null && mode == CloudAccountMode.VERIFY_EMAIL && resendSecondsRemaining == 0
    val canSave: Boolean get() = !busy && identity != null && profileReady && !profileLoadFailed &&
        CloudAccountValidation.validProfile(draft) &&
        usernameAvailability in setOf(UsernameAvailability.AVAILABLE, UsernameAvailability.UNCHANGED)
    val canSavePrivateDetails: Boolean get() = !busy && identity != null && profileReady && !profileLoadFailed &&
        draft.fullName.length <= 120
}
