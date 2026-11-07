package com.vinz.music.ui.screens.account

import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Screen-scoped presentation state. Auth, tokens, network and persistence stay in the existing client. */
internal class CloudAccountController(
    private val gateway: CloudAccountGateway,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(CloudAccountState(identity = gateway.identity).let {
        it.copy(email = it.identity?.email.orEmpty())
    })
    val state: StateFlow<CloudAccountState> = mutable.asStateFlow()

    fun mode(mode: CloudAccountMode) = edit { copy(mode = mode, password = "", message = null, error = null) }
    fun email(value: String) = edit { copy(email = value, message = null, error = null) }
    fun password(value: String) = edit { copy(password = value, message = null, error = null) }
    fun username(value: String) = edit { copy(draft = draft.copy(username = CloudAccountValidation.username(value)), message = null, error = null) }
    fun displayName(value: String) = edit { copy(draft = draft.copy(displayName = value.take(60)), message = null, error = null) }
    fun bio(value: String) = edit { copy(draft = draft.copy(bio = value.take(160)), message = null, error = null) }
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
                draft = profile?.draft() ?: CloudProfileDraft(), profileReady = true, profileLoadFailed = false,
                message = if (profile == null) R.string.cloud_account_setup else null,
            )
        }
    }

    fun submit(): Job? {
        val before = mutable.value
        if (!before.canSubmit) return null
        val failure = if (before.mode == CloudAccountMode.RECOVERY) R.string.cloud_account_recovery_failed
            else R.string.cloud_account_sign_in_failed
        return request(failure) {
            val email = before.email.trim()
            if (before.mode == CloudAccountMode.RECOVERY) {
                gateway.recover(email)
                mutable.value = mutable.value.copy(email = email, message = R.string.cloud_account_recovery_sent)
            } else {
                val result = if (before.mode == CloudAccountMode.SIGN_UP) gateway.signUp(
                    email, before.password, before.draft.username, before.draft.displayName.trim(),
                ) else gateway.signIn(email, before.password)
                when (result) {
                    VinzCloudAccount.AuthResult.EmailConfirmationRequired -> mutable.value = mutable.value.copy(
                        mode = CloudAccountMode.SIGN_IN, email = email, password = "",
                        message = R.string.cloud_account_confirmation,
                    )
                    is VinzCloudAccount.AuthResult.SignedIn -> {
                        val identity = gateway.identity
                        if (identity == null) {
                            mutable.value = mutable.value.copy(password = "", error = failure)
                        } else if (!identity.email.trim().equals(email, ignoreCase = true) ||
                            (result.profile != null && result.profile.id != identity.userId)) {
                            resetForCurrentIdentity()
                        } else {
                            mutable.value = CloudAccountState(
                                identity = identity, email = identity.email, busy = true, profileReady = true,
                                draft = result.profile?.draft() ?: if (before.mode == CloudAccountMode.SIGN_UP) before.draft else CloudProfileDraft(),
                                message = if (result.profile == null) R.string.cloud_account_setup else null,
                            )
                        }
                    }
                }
            }
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
            mutable.value = mutable.value.copy(draft = saved.draft(), message = R.string.cloud_account_saved)
        }
    }

    fun signOut(): Job? {
        if (mutable.value.identity == null) return null
        return request(R.string.cloud_account_sign_out_failed) {
            gateway.signOut()
            mutable.value = CloudAccountState(busy = true, message = R.string.cloud_account_signed_out)
        }
    }

    private fun resetForCurrentIdentity() {
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
                mutable.value = mutable.value.copy(busy = false, password = "")
            }
        }
    }

    private fun VinzCloudAccount.Profile.draft() = CloudProfileDraft(username, displayName, bio, isPublic, discoverable)
}
