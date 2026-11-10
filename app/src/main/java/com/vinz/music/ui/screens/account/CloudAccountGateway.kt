package com.vinz.music.ui.screens.account

import com.vinz.music.data.VinzCloudAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A frontend test seam around existing methods, not a new API or auth/session implementation. */
internal interface CloudAccountGateway {
    val identity: CloudAccountIdentity?
    val sessionChanges: Flow<Long> get() = emptyFlow()
    fun pendingVerificationEmail(): String? = null
    fun otpResendSecondsRemaining(): Int? = null
    suspend fun loadProfile(): VinzCloudAccount.Profile?
    suspend fun checkUsernameAvailable(username: String): Boolean = true
    suspend fun beginGoogleOAuth(): String = throw UnsupportedOperationException()
    suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult
    suspend fun signUp(email: String, password: String, username: String, displayName: String): VinzCloudAccount.AuthResult
    suspend fun verifyOtp(email: String, code: String): VinzCloudAccount.AuthResult = throw UnsupportedOperationException()
    suspend fun resendOtp(email: String) { throw UnsupportedOperationException() }
    suspend fun recover(email: String)
    suspend fun save(draft: CloudProfileDraft): VinzCloudAccount.Profile
    suspend fun signOut()
}

internal class ExistingCloudAccountGateway(private val cloud: VinzCloudAccount) : CloudAccountGateway {
    override val identity: CloudAccountIdentity? get() = cloud.session?.let { CloudAccountIdentity(it.userId, it.email) }
    override val sessionChanges: Flow<Long> get() = cloud.sessionChanges
    override fun pendingVerificationEmail() = cloud.pendingVerificationEmail()
    override suspend fun loadProfile() = cloud.loadProfile()
    override suspend fun checkUsernameAvailable(username: String) = cloud.checkUsernameAvailable(username)
    override suspend fun beginGoogleOAuth() = cloud.beginGoogleOAuth()
    override suspend fun signIn(email: String, password: String) = cloud.signIn(email, password)
    override suspend fun signUp(email: String, password: String, username: String, displayName: String) =
        cloud.signUp(email, password, username, displayName)
    override suspend fun verifyOtp(email: String, code: String) = cloud.verifyEmailOtp(email, code)
    override suspend fun resendOtp(email: String) { cloud.resendEmailOtp(email) }
    override suspend fun recover(email: String) { cloud.sendPasswordRecovery(email) }
    override suspend fun save(draft: CloudProfileDraft) = cloud.saveProfile(
        draft.username, draft.displayName, draft.bio, draft.isPublic, draft.discoverable,
    )
    override suspend fun signOut() { cloud.signOut() }
}
