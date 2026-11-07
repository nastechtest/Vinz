package com.vinz.music.ui.screens.account

import com.vinz.music.data.VinzCloudAccount

/** A frontend test seam around existing methods, not a new API or auth/session implementation. */
internal interface CloudAccountGateway {
    val identity: CloudAccountIdentity?
    suspend fun loadProfile(): VinzCloudAccount.Profile?
    suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult
    suspend fun signUp(email: String, password: String, username: String, displayName: String): VinzCloudAccount.AuthResult
    suspend fun recover(email: String)
    suspend fun save(draft: CloudProfileDraft): VinzCloudAccount.Profile
    suspend fun signOut()
}

internal class ExistingCloudAccountGateway(private val cloud: VinzCloudAccount) : CloudAccountGateway {
    override val identity: CloudAccountIdentity? get() = cloud.session?.let { CloudAccountIdentity(it.userId, it.email) }
    override suspend fun loadProfile() = cloud.loadProfile()
    override suspend fun signIn(email: String, password: String) = cloud.signIn(email, password)
    override suspend fun signUp(email: String, password: String, username: String, displayName: String) =
        cloud.signUp(email, password, username, displayName)
    override suspend fun recover(email: String) { cloud.sendPasswordRecovery(email) }
    override suspend fun save(draft: CloudProfileDraft) = cloud.saveProfile(
        draft.username, draft.displayName, draft.bio, draft.isPublic, draft.discoverable,
    )
    override suspend fun signOut() { cloud.signOut() }
}
