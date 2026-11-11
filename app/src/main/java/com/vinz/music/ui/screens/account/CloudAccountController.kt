package com.vinz.music.ui.screens.account

import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.data.CloudAuthException
import com.vinz.music.data.CloudAuthFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Screen-scoped presentation state. Auth, tokens, network and persistence stay in the existing client. */
internal class CloudAccountController(
    private val gateway: CloudAccountGateway,
    private val scope: CoroutineScope,
    private val resendCooldownSeconds: Int = 60,
    private val usernameDebounceMillis: Long = 400,
) {
    private val mutable = MutableStateFlow(CloudAccountState(
        identity = gateway.identity,
        email = gateway.identity?.email.orEmpty(),
        mode = if (gateway.passwordRecoveryPending()) CloudAccountMode.RESET_PASSWORD else CloudAccountMode.SIGN_IN,
    ))
    val state: StateFlow<CloudAccountState> = mutable.asStateFlow()
    private var usernameCheckJob: Job? = null
    private var resendJob: Job? = null

    init {
        if (gateway.identity == null) gateway.pendingVerificationEmail()?.takeIf { it.isNotBlank() }?.let(::beginVerification)
        scope.launch { gateway.sessionChanges.collect { refreshIdentity() } }
    }

    /** Rebinds the account screen after a browser OAuth callback without retaining tokens in UI state. */
    fun refreshIdentity() {
        if (mutable.value.busy) return
        val identity = gateway.identity
        val recoveryPending = gateway.passwordRecoveryPending()
        if (identity?.userId == mutable.value.identity?.userId) {
            if (recoveryPending && mutable.value.mode != CloudAccountMode.RESET_PASSWORD) {
                mutable.value = mutable.value.copy(mode = CloudAccountMode.RESET_PASSWORD, password = "", confirmPassword = "", message = null, error = null)
            }
            if (identity != null && !mutable.value.profileReady) loadProfile()
            return
        }
        resendJob?.cancel()
        usernameCheckJob?.cancel()
        mutable.value = CloudAccountState(
            identity = identity,
            email = identity?.email.orEmpty(),
            mode = if (recoveryPending) CloudAccountMode.RESET_PASSWORD else CloudAccountMode.SIGN_IN,
        )
        if (identity != null) loadProfile()
    }

    fun beginGoogleOAuth(): Job? {
        if (mutable.value.identity != null || mutable.value.mode != CloudAccountMode.SIGN_IN) return null
        return request(R.string.cloud_account_sign_in_failed) {
            mutable.value = mutable.value.copy(oauthUrl = gateway.beginGoogleOAuth())
        }
    }

    fun consumeGoogleOAuthUrl() {
        mutable.value = mutable.value.copy(oauthUrl = null)
    }

    fun googleOAuthLaunchFailed() {
        mutable.value = mutable.value.copy(error = R.string.cloud_account_sign_in_failed)
    }

    fun mode(mode: CloudAccountMode) {
        if (mutable.value.busy) return
        resendJob?.cancel()
        usernameCheckJob?.cancel()
        mutable.value = mutable.value.copy(mode = mode, password = "", confirmPassword = "", otp = "", resendSecondsRemaining = 0, message = null, error = null)
    }
    fun email(value: String) = edit { copy(email = value, message = null, error = null) }
    fun password(value: String) = edit { copy(password = value, message = null, error = null) }
    fun confirmPassword(value: String) = edit { copy(confirmPassword = value, message = null, error = null) }
    fun otp(value: String) = edit { copy(otp = CloudAccountValidation.otp(value), message = null, error = null) }
    /** Release typed secrets when the account screen is disposed. */
    fun clearPassword() { mutable.value = mutable.value.copy(password = "", confirmPassword = "") }
    fun username(value: String) {
        if (mutable.value.busy) return
        val clean = CloudAccountValidation.username(value)
        usernameCheckJob?.cancel()
        val current = mutable.value
        val valid = clean.matches(Regex("^[a-z0-9_]{3,24}$"))
        val shouldCheck = (current.identity == null && current.mode == CloudAccountMode.SIGN_UP) ||
            (current.identity != null && clean != current.originalUsername)
        val status = when {
            !valid -> UsernameAvailability.INVALID
            current.identity != null && clean == current.originalUsername -> UsernameAvailability.UNCHANGED
            shouldCheck -> UsernameAvailability.CHECKING
            else -> UsernameAvailability.IDLE
        }
        mutable.value = current.copy(
            draft = current.draft.copy(username = clean),
            usernameAvailability = status,
            message = null,
            error = null,
        )
        if (status == UsernameAvailability.CHECKING) scheduleUsernameCheck(clean)
    }

    private fun scheduleUsernameCheck(username: String) {
        usernameCheckJob?.cancel()
        usernameCheckJob = scope.launch {
            delay(usernameDebounceMillis.coerceAtLeast(0))
            try {
                val available = gateway.checkUsernameAvailable(username)
                if (mutable.value.draft.username == username) mutable.value = mutable.value.copy(
                    usernameAvailability = if (available) UsernameAvailability.AVAILABLE else UsernameAvailability.UNAVAILABLE,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutable.value.draft.username == username) mutable.value = mutable.value.copy(
                    usernameAvailability = UsernameAvailability.ERROR,
                )
            }
        }
    }
    fun displayName(value: String) = edit { copy(draft = draft.copy(displayName = value.take(60)), message = null, error = null) }
    fun bio(value: String) = edit { copy(draft = draft.copy(bio = value.take(160)), message = null, error = null) }
    fun fullName(value: String) = edit { copy(draft = draft.copy(fullName = value.take(120)), message = null, error = null) }
    fun publicProfile(value: Boolean) = edit { copy(draft = draft.copy(isPublic = value), message = null, error = null) }
    fun discoverable(value: Boolean) = edit { copy(draft = draft.copy(discoverable = value), message = null, error = null) }

    private fun edit(change: CloudAccountState.() -> CloudAccountState) {
        if (!mutable.value.busy) mutable.value = mutable.value.change()
    }

    fun loadProfile(): Job? {
        val identity = mutable.value.identity ?: return null
        return request(R.string.cloud_account_load_failed) {
            val profile = gateway.loadProfile()
            // An external account change must not paint the old user's private profile.
            if (gateway.identity?.userId != identity.userId || (profile != null && profile.id != identity.userId)) {
                resetForCurrentIdentity()
                return@request
            }
            mutable.value = mutable.value.copy(
                draft = profile?.draft() ?: CloudProfileDraft(), originalUsername = profile?.username.orEmpty(),
                usernameAvailability = if (profile == null) UsernameAvailability.IDLE else UsernameAvailability.UNCHANGED,
                profileReady = true, profileLoadFailed = false,
                message = if (profile == null) R.string.cloud_account_setup else null,
            )
        }
    }

    fun submit(): Job? {
        val before = mutable.value
        if (!before.canSubmit) return null
        val failure = when (before.mode) {
            CloudAccountMode.RECOVERY -> R.string.cloud_account_recovery_failed
            CloudAccountMode.VERIFY_EMAIL -> R.string.cloud_account_otp_verify_failed
            CloudAccountMode.RESET_PASSWORD -> R.string.cloud_account_recovery_failed
            else -> R.string.cloud_account_sign_in_failed
        }
        return request(failure) {
            val email = before.email.trim()
            when (before.mode) {
                CloudAccountMode.RECOVERY -> {
                    gateway.recover(email)
                    mutable.value = mutable.value.copy(email = email, message = R.string.cloud_account_recovery_sent)
                }
                CloudAccountMode.RESET_PASSWORD -> {
                    gateway.updatePassword(before.password)
                    mutable.value = mutable.value.copy(
                        mode = CloudAccountMode.SIGN_IN,
                        password = "",
                        confirmPassword = "",
                        message = R.string.cloud_account_password_updated,
                    )
                }
                CloudAccountMode.VERIFY_EMAIL -> {
                    when (val result = gateway.verifyOtp(email, before.otp)) {
                        is VinzCloudAccount.AuthResult.SignedIn ->
                            acceptSignedIn(result, before, email, failure)
                        is VinzCloudAccount.AuthResult.VerificationRequired ->
                            beginVerification(result.email)
                        VinzCloudAccount.AuthResult.EmailConfirmationRequired ->
                            beginVerification(email)
                    }
                }
                CloudAccountMode.SIGN_UP, CloudAccountMode.SIGN_IN -> {
                    val result = if (before.mode == CloudAccountMode.SIGN_UP) gateway.signUp(
                        email, before.password, before.draft.username, before.draft.displayName.trim(),
                    ) else gateway.signIn(email, before.password)
                    when (result) {
                        is VinzCloudAccount.AuthResult.VerificationRequired -> beginVerification(result.email)
                        VinzCloudAccount.AuthResult.EmailConfirmationRequired -> beginVerification(email)
                        is VinzCloudAccount.AuthResult.SignedIn -> acceptSignedIn(result, before, email, failure)
                    }
                }
            }
        }
    }

    fun resendOtp(): Job? {
        val before = mutable.value
        if (!before.canResendOtp) return null
        return request(R.string.cloud_account_otp_resend_failed) {
            gateway.resendOtp(before.email.trim())
            mutable.value = mutable.value.copy(otp = "", message = R.string.cloud_account_otp_resent)
            startResendCooldown(gateway.otpResendSecondsRemaining() ?: resendCooldownSeconds)
        }
    }

    private fun beginVerification(email: String) {
        resendJob?.cancel()
        val cooldown = gateway.otpResendSecondsRemaining() ?: resendCooldownSeconds
        mutable.value = mutable.value.copy(
            mode = CloudAccountMode.VERIFY_EMAIL, email = email.trim(), password = "", confirmPassword = "", otp = "",
            resendSecondsRemaining = cooldown, message = R.string.cloud_account_otp_sent, error = null,
        )
        startResendCooldown(cooldown)
    }

    private fun startResendCooldown(seconds: Int) {
        resendJob?.cancel()
        mutable.value = mutable.value.copy(resendSecondsRemaining = seconds.coerceAtLeast(0))
        if (seconds <= 0) return
        resendJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1_000)
                if (mutable.value.mode != CloudAccountMode.VERIFY_EMAIL) return@launch
                remaining--
                mutable.value = mutable.value.copy(resendSecondsRemaining = remaining)
            }
        }
    }

    private fun acceptSignedIn(
        result: VinzCloudAccount.AuthResult.SignedIn,
        before: CloudAccountState,
        expectedEmail: String,
        failure: Int,
    ) {
        val identity = gateway.identity
        if (identity == null) {
            mutable.value = mutable.value.copy(password = "", otp = "", error = failure)
        } else if (!identity.email.trim().equals(expectedEmail, ignoreCase = true) ||
            (result.profile != null && result.profile.id != identity.userId)) {
            resetForCurrentIdentity()
        } else {
            resendJob?.cancel()
            val keepSignupDraft = before.mode == CloudAccountMode.SIGN_UP || before.mode == CloudAccountMode.VERIFY_EMAIL
            val draft = result.profile?.draft() ?: if (keepSignupDraft) before.draft else CloudProfileDraft()
            val usernameStatus = when {
                result.profile != null -> UsernameAvailability.UNCHANGED
                keepSignupDraft && draft.username.matches(Regex("^[a-z0-9_]{3,24}$")) -> UsernameAvailability.CHECKING
                keepSignupDraft -> UsernameAvailability.INVALID
                else -> UsernameAvailability.IDLE
            }
            mutable.value = CloudAccountState(
                identity = identity, email = identity.email, busy = true, profileReady = true,
                draft = draft,
                originalUsername = result.profile?.username.orEmpty(),
                usernameAvailability = usernameStatus,
                message = if (result.profile == null) R.string.cloud_account_setup else null,
            )
            if (usernameStatus == UsernameAvailability.CHECKING) scheduleUsernameCheck(draft.username)
        }
    }

    fun save(): Job? {
        val before = mutable.value
        if (!before.canSave) return null
        return request(R.string.cloud_account_save_failed) {
            if (gateway.identity?.userId != before.identity?.userId) {
                resetForCurrentIdentity()
                return@request
            }
            val saved = gateway.save(before.draft.copy(displayName = before.draft.displayName.trim()))
            if (gateway.identity?.userId != before.identity?.userId || saved.id != before.identity?.userId) {
                resetForCurrentIdentity()
                return@request
            }
            mutable.value = mutable.value.copy(
                draft = saved.draft().copy(fullName = before.draft.fullName),
                originalUsername = saved.username,
                usernameAvailability = UsernameAvailability.UNCHANGED,
                message = R.string.cloud_account_saved,
            )
        }
    }

    /** Private identity details are persisted independently of the public music profile. */
    fun savePrivateDetails(): Job? {
        val before = mutable.value
        if (!before.canSavePrivateDetails) return null
        return request(R.string.cloud_account_private_details_save_failed) {
            if (gateway.identity?.userId != before.identity?.userId) {
                resetForCurrentIdentity()
                return@request
            }
            val saved = gateway.savePrivateFullName(before.draft.fullName.trim())
            if (gateway.identity?.userId != before.identity?.userId) {
                resetForCurrentIdentity()
                return@request
            }
            mutable.value = mutable.value.copy(
                draft = mutable.value.draft.copy(fullName = saved),
                message = R.string.cloud_account_private_details_saved,
            )
        }
    }

    fun sendPasswordReset(): Job? {
        val email = mutable.value.identity?.email?.takeIf { it.isNotBlank() } ?: return null
        return request(R.string.cloud_account_recovery_failed) {
            gateway.recover(email)
            mutable.value = mutable.value.copy(message = R.string.cloud_account_recovery_sent)
        }
    }

    fun signOut(): Job? {
        if (mutable.value.identity == null) return null
        usernameCheckJob?.cancel()
        return request(R.string.cloud_account_sign_out_failed) {
            gateway.signOut()
            mutable.value = CloudAccountState(busy = true, message = R.string.cloud_account_signed_out)
        }
    }

    private fun resetForCurrentIdentity() {
        resendJob?.cancel()
        usernameCheckJob?.cancel()
        val identity = gateway.identity
        mutable.value = CloudAccountState(
            identity = identity, email = identity?.email.orEmpty(), busy = true,
            profileLoadFailed = identity != null,
            error = if (identity != null) R.string.cloud_account_load_failed else null,
        )
    }

    /** Set busy before launching: rapid taps cannot enqueue duplicate requests. Called on the UI thread. */
    private fun request(failure: Int, operation: suspend () -> Unit): Job? {
        if (mutable.value.busy) return null
        mutable.value = mutable.value.copy(busy = true, message = null, error = null)
        return scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: CloudAuthException) {
                val mapped = when (auth.failure) {
                    CloudAuthFailure.INVALID_OTP, CloudAuthFailure.OTP_EXPIRED -> R.string.cloud_account_otp_invalid_or_expired
                    CloudAuthFailure.RATE_LIMITED -> R.string.cloud_account_otp_rate_limited
                    CloudAuthFailure.USERNAME_TAKEN -> R.string.cloud_account_username_unavailable
                    CloudAuthFailure.EMAIL_NOT_CONFIRMED -> R.string.cloud_account_confirmation
                }
                val identity = gateway.identity
                val changed = identity?.userId != mutable.value.identity?.userId
                if (auth.failure == CloudAuthFailure.RATE_LIMITED) {
                    gateway.otpResendSecondsRemaining()?.takeIf { it > 0 }?.let(::startResendCooldown)
                }
                mutable.value = if (changed) CloudAccountState(
                    identity = identity, email = identity?.email.orEmpty(), busy = true,
                    profileLoadFailed = identity != null,
                    error = if (identity != null) R.string.cloud_account_load_failed else mapped,
                ) else mutable.value.copy(error = mapped)
            } catch (_: Exception) {
                // Never render raw transport messages, response bodies, tokens or private backend details.
                val identity = gateway.identity
                val changed = identity?.userId != mutable.value.identity?.userId
                mutable.value = if (changed) CloudAccountState(
                    identity = identity, email = identity?.email.orEmpty(), busy = true,
                    profileLoadFailed = identity != null,
                    error = if (identity != null) R.string.cloud_account_load_failed else failure,
                ) else mutable.value.copy(
                    error = failure,
                    profileLoadFailed = failure == R.string.cloud_account_load_failed || mutable.value.profileLoadFailed,
                )
            } finally {
                mutable.value = mutable.value.copy(busy = false, password = "", confirmPassword = "")
            }
        }
    }

    private fun VinzCloudAccount.Profile.draft() =
        CloudProfileDraft(username, displayName, bio, isPublic, discoverable, fullName)
}
