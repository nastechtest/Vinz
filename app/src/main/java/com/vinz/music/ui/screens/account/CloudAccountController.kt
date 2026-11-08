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
import kotlinx.coroutines.delay

/** Screen-scoped presentation state. Auth, tokens, network and persistence stay in the existing client. */
internal class CloudAccountController(
    private val gateway: CloudAccountGateway,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(CloudAccountState(identity = gateway.identity).let {
        it.copy(email = it.identity?.email.orEmpty())
    })
    val state: StateFlow<CloudAccountState> = mutable.asStateFlow()
    private var usernameCheckJob: Job? = null

    fun mode(mode: CloudAccountMode) = edit { copy(mode = mode, password = "", message = null, error = null) }
    fun email(value: String) = edit { copy(email = value, message = null, error = null) }
    fun password(value: String) = edit { copy(password = value, message = null, error = null) }
    /** Release typed secrets when the account screen is disposed. */
    fun clearPassword() { mutable.value = mutable.value.copy(password = "") }
    fun username(value: String) {
        if (mutable.value.busy) return
        val clean = CloudAccountValidation.username(value)
        usernameCheckJob?.cancel()
        val current = mutable.value
        val status = when {
            current.identity == null -> UsernameAvailability.IDLE
            clean == current.originalUsername -> UsernameAvailability.UNCHANGED
            !clean.matches(Regex("^[a-z0-9_]{3,24}$")) -> UsernameAvailability.INVALID
            else -> UsernameAvailability.CHECKING
        }
        mutable.value = current.copy(draft = current.draft.copy(username = clean),
            usernameAvailability = status, message = null, error = null)
        if (status == UsernameAvailability.CHECKING) {
            usernameCheckJob = scope.launch {
                delay(400)
                try {
                    val available = gateway.checkUsernameAvailable(clean)
                    if (mutable.value.draft.username == clean) mutable.value = mutable.value.copy(
                        usernameAvailability = if (available) UsernameAvailability.AVAILABLE else UsernameAvailability.UNAVAILABLE,
                    )
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (mutable.value.draft.username == clean) mutable.value = mutable.value.copy(usernameAvailability = UsernameAvailability.ERROR)
                }
            }
        }
    }
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
            val youtube = try { gateway.youtubeStatus() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
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
                youtubeConnected = youtube?.connected ?: false,
                youtubeChannelTitle = youtube?.channelTitle.orEmpty(),
            )
        }
    }

    fun submit(): Job? {
        val before = mutable.value
        if (!before.canSubmit) return null
        val failure = when (before.mode) {
            CloudAccountMode.RECOVERY -> R.string.cloud_account_recovery_failed
            CloudAccountMode.SIGN_UP -> R.string.cloud_account_sign_up_failed
            CloudAccountMode.SIGN_IN -> R.string.cloud_account_sign_in_failed
        }
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
                                originalUsername = result.profile?.username.orEmpty(),
                                usernameAvailability = if (result.profile == null) UsernameAvailability.AVAILABLE else UsernameAvailability.UNCHANGED,
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
            mutable.value = mutable.value.copy(draft = saved.draft(), originalUsername = saved.username, usernameAvailability = UsernameAvailability.UNCHANGED, message = R.string.cloud_account_saved)
        }
    }

    fun sendPasswordReset(): Job? {
        val email = mutable.value.identity?.email?.takeIf { it.isNotBlank() } ?: return null
        return request(R.string.cloud_account_recovery_failed) {
            gateway.recover(email)
            mutable.value = mutable.value.copy(message = R.string.cloud_account_recovery_sent)
        }
    }

    fun clearYouTubeAuthorizationUrl() {
        mutable.value = mutable.value.copy(youtubeAuthorizationUrl = null)
    }

    fun connectYouTube(): Job? = request(R.string.cloud_account_youtube_failed) {
        val authorizationUrl = gateway.youtubeConnectUrl()
        mutable.value = mutable.value.copy(youtubeAuthorizationUrl = authorizationUrl, youtubeConnectPending = true)
    }

    fun refreshYouTubeStatus(): Job? = request(R.string.cloud_account_youtube_failed) {
        val status = gateway.youtubeStatus()
        mutable.value = mutable.value.copy(
            youtubeConnected = status.connected,
            youtubeChannelTitle = status.channelTitle,
            youtubeAuthorizationUrl = null,
        )
    }

    fun importYouTubeMusic(): Job? = request(R.string.cloud_account_youtube_failed) {
        val result = gateway.importYouTubeMusic()
        mutable.value = mutable.value.copy(
            youtubeImportedCount = result.importedCount,
            youtubePlaylistCount = result.playlistCount,
            youtubeConnected = true,
        )
    }

    fun disconnectYouTubeMusic(): Job? = request(R.string.cloud_account_youtube_failed) {
        gateway.disconnectYouTubeMusic()
        mutable.value = mutable.value.copy(
            youtubeConnected = false,
            youtubeChannelTitle = "",
            youtubeImportedCount = null,
            youtubePlaylistCount = null,
        )
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
