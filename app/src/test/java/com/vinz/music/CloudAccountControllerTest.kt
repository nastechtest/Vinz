package com.vinz.music

import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountGateway
import com.vinz.music.ui.screens.account.CloudAccountIdentity
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.UsernameAvailability
import com.vinz.music.ui.screens.account.CloudProfileDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudAccountControllerTest {
    private lateinit var gateway: FakeGateway
    private lateinit var scope: CoroutineScope
    private lateinit var controller: CloudAccountController

    @Before fun setUp() {
        gateway = FakeGateway()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
    }

    @After fun tearDown() = runBlocking { scope.cancel() }

    @Test fun `leaving account clears typed password without changing the email or mode`() {
        controller.email("user@example.test")
        controller.password("temporary-password")
        controller.clearPassword()
        assertEquals("", controller.state.value.password)
        assertEquals("user@example.test", controller.state.value.email)
        assertEquals(CloudAccountMode.SIGN_IN, controller.state.value.mode)
    }


    @Test fun `duplicate requests are synchronously rejected while first is in flight`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.onRecover = { started.complete(Unit); release.await() }
        recovery("user@example.test")

        val first = controller.submit()
        started.await()
        assertTrue(controller.state.value.busy)
        assertNull(controller.submit())
        assertEquals(1, gateway.recoverCalls)

        release.complete(Unit)
        first!!.join()
        assertFalse(controller.state.value.busy)
    }

    @Test fun `cancellation is rethrown without error and clears busy password`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<VinzCloudAccount.AuthResult>()
        gateway.onSignIn = { _, _ -> started.complete(Unit); never.await() }
        signIn("user@example.test", "test-password")

        val job = controller.submit()!!
        started.await()
        job.cancel()
        job.join()

        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.error)
        assertEquals("", controller.state.value.password)
    }

    @Test fun `load profile prefills all editable profile fields`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        gateway.profile = profile(username = "artist_7", displayName = "  Artist  ", bio = "bio", isPublic = false, discoverable = false, fullName = "Ada Lovelace")

        controller.loadProfile()!!.join()

        assertEquals(CloudProfileDraft("artist_7", "  Artist  ", "bio", false, false, "Ada Lovelace"), controller.state.value.draft)
        assertTrue(controller.state.value.profileReady)
        assertFalse(controller.state.value.profileLoadFailed)
        assertNull(controller.state.value.message)
    }

    @Test fun `null loaded profile enters setup state`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        controller.loadProfile()!!.join()

        assertTrue(controller.state.value.profileReady)
        assertEquals(R.string.cloud_account_setup, controller.state.value.message)
        assertEquals(CloudProfileDraft(), controller.state.value.draft)
    }

    @Test fun `failed profile load prevents unsafe save`() = runBlocking {
        gateway.identityValue = identity()
        gateway.onLoad = { throw IllegalStateException("private backend detail") }
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        controller.loadProfile()!!.join()

        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.profileReady)
        assertEquals(R.string.cloud_account_load_failed, controller.state.value.error)
        controller.username("artist_7")
        controller.displayName("Artist")
        assertNull(controller.save())
        assertEquals(0, gateway.saveCalls)
    }

    @Test fun `successful sign in uses returned session and profile`() = runBlocking {
        gateway.onSignIn = { _, _ ->
            gateway.identityValue = identity("signed-in", "signed@example.test")
            VinzCloudAccount.AuthResult.SignedIn(profile(username = "signed_in", displayName = "Signed", isPublic = false, discoverable = false).copy(id = "signed-in"))
        }
        signIn("  signed@example.test  ", "test-password")

        controller.submit()!!.join()

        assertEquals(identity("signed-in", "signed@example.test"), controller.state.value.identity)
        assertEquals("signed@example.test", controller.state.value.email)
        assertEquals(CloudProfileDraft("signed_in", "Signed", "", false, false), controller.state.value.draft)
        assertTrue(controller.state.value.profileReady)
        assertEquals("", controller.state.value.password)
        assertEquals("signed@example.test", gateway.lastSignInEmail)
    }

    @Test fun `signed in response without session reports resource error`() = runBlocking {
        gateway.onSignIn = { _, _ -> VinzCloudAccount.AuthResult.SignedIn(profile()) }
        signIn("user@example.test", "test-password")

        controller.submit()!!.join()

        assertNull(controller.state.value.identity)
        assertEquals(R.string.cloud_account_sign_in_failed, controller.state.value.error)
        assertEquals("", controller.state.value.password)
        assertFalse(controller.state.value.busy)
    }

    @Test fun `signup checks username while typing and blocks until it is available`() = runBlocking {
        gateway.onSignUp = { email, _, _, _ -> VinzCloudAccount.AuthResult.VerificationRequired(email) }
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("password8")
        controller.displayName("New Artist")

        gateway.usernameAvailable = false
        controller.username("new_artist")
        assertEquals(UsernameAvailability.UNAVAILABLE, controller.state.value.usernameAvailability)
        assertFalse(controller.state.value.canSubmit)
        assertNull(controller.submit())
        assertEquals(0, gateway.signUpCalls)

        gateway.usernameAvailable = true
        controller.username("new_artist2")
        assertEquals(UsernameAvailability.AVAILABLE, controller.state.value.usernameAvailability)
        controller.submit()!!.join()

        assertEquals(1, gateway.signUpCalls)
        assertEquals(listOf("new_artist", "new_artist2"), gateway.usernameChecks)
        assertEquals(CloudAccountMode.VERIFY_EMAIL, controller.state.value.mode)
    }

    @Test fun `signup username check failure fails closed`() = runBlocking {
        gateway.usernameCheckFailure = IllegalStateException("private backend detail")
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("password8")
        controller.username("new_artist")
        controller.displayName("New Artist")

        assertEquals(UsernameAvailability.ERROR, controller.state.value.usernameAvailability)
        assertFalse(controller.state.value.canSubmit)
        assertNull(controller.submit())
        assertEquals(0, gateway.signUpCalls)
    }

    @Test fun `signup confirmation clears password and returns to sign in`() = runBlocking {
        gateway.onSignUp = { _, _, _, _ -> VinzCloudAccount.AuthResult.EmailConfirmationRequired }
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("  new@example.test ")
        controller.password("password8")
        controller.username("new_artist")
        controller.displayName("New Artist")

        controller.submit()!!.join()

        assertEquals(CloudAccountMode.VERIFY_EMAIL, controller.state.value.mode)
        assertEquals("new@example.test", controller.state.value.email)
        assertEquals("", controller.state.value.password)
        assertEquals(R.string.cloud_account_otp_sent, controller.state.value.message)
        assertEquals(60, controller.state.value.resendSecondsRemaining)
    }

    @Test fun `identity stored before sign in failure requires profile retry`() = runBlocking {
        gateway.onSignIn = { _, _ ->
            gateway.identityValue = identity("partial", "partial@example.test")
            throw IllegalStateException("session profile response included secret-token")
        }
        signIn("partial@example.test", "test-password")

        controller.submit()!!.join()

        assertEquals(identity("partial", "partial@example.test"), controller.state.value.identity)
        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.profileReady)
        assertEquals(R.string.cloud_account_load_failed, controller.state.value.error)
        assertFalse(controller.state.value.canSave)
    }

    @Test fun `recovery callback switches an existing account into password update mode`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        gateway.recoveryPending = true

        controller.refreshIdentity()

        assertEquals(CloudAccountMode.RESET_PASSWORD, controller.state.value.mode)
        controller.password("new-password")
        controller.confirmPassword("new-password")
        controller.submit()!!.join()

        assertEquals(1, gateway.updatePasswordCalls)
        assertEquals("new-password", gateway.lastUpdatedPassword)
        assertEquals("user-1", gateway.lastUpdatedUserId)
        assertEquals(CloudAccountMode.SIGN_IN, controller.state.value.mode)
        assertEquals(R.string.cloud_account_password_updated, controller.state.value.message)
        assertFalse(gateway.recoveryPending)
    }

    @Test fun `recovery password update is blocked after an account switch`() = runBlocking {
        gateway.identityValue = identity()
        gateway.recoveryPending = true
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.password("new-password")
        controller.confirmPassword("new-password")
        gateway.identityValue = identity("other-user", "other@example.test")

        controller.submit()!!.join()

        assertEquals(0, gateway.updatePasswordCalls)
        assertTrue(gateway.recoveryPending)
        assertEquals(R.string.cloud_account_load_failed, controller.state.value.error)
    }

    @Test fun `recovery always gives anti enumeration success feedback`() = runBlocking {
        recovery("  unknown@example.test ")

        controller.submit()!!.join()

        assertEquals("unknown@example.test", gateway.lastRecoveryEmail)
        assertEquals(R.string.cloud_account_recovery_sent, controller.state.value.message)
        assertNull(controller.state.value.error)
    }

    @Test fun `raw sensitive gateway failures are never placed in rendered state`() = runBlocking {
        val sensitive = "access_token=not-a-real-token response body"
        gateway.onRecover = { throw IllegalStateException(sensitive) }
        recovery("user@example.test")

        controller.submit()!!.join()

        assertEquals(R.string.cloud_account_recovery_failed, controller.state.value.error)
        assertNull(controller.state.value.message)
        assertFalse(controller.state.value.toString().contains(sensitive))
    }

    @Test fun `sign out clears identity draft password and private flags`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.email("user@example.test")
        controller.password("test-password")
        controller.username("private_7")
        controller.displayName("Private")
        controller.bio("private bio")
        controller.publicProfile(false)
        controller.discoverable(false)

        controller.signOut()!!.join()

        assertNull(controller.state.value.identity)
        assertEquals(CloudProfileDraft(), controller.state.value.draft)
        assertEquals("", controller.state.value.email)
        assertEquals("", controller.state.value.password)
        assertFalse(controller.state.value.profileReady)
        assertEquals(R.string.cloud_account_signed_out, controller.state.value.message)
    }

    @Test fun `private full name is saved separately from the public music profile`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile(fullName = "Ada Lovelace")
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        assertEquals("Ada Lovelace", controller.state.value.draft.fullName)

        controller.fullName("  Grace Hopper  ")
        assertEquals(0, gateway.privateSaveCalls)
        controller.savePrivateDetails()!!.join()

        assertEquals(1, gateway.privateSaveCalls)
        assertEquals("Grace Hopper", gateway.lastPrivateFullName)
        assertEquals("Grace Hopper", controller.state.value.draft.fullName)
        assertEquals(R.string.cloud_account_private_details_saved, controller.state.value.message)
        assertEquals(0, gateway.saveCalls)
    }

    @Test fun `private full name save is blocked after the active account changes`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile(fullName = "Private Name")
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        controller.fullName("Changed Name")
        gateway.identityValue = identity("another-user", "another@example.test")

        controller.savePrivateDetails()!!.join()

        assertEquals(0, gateway.privateSaveCalls)
        assertEquals(identity("another-user", "another@example.test"), controller.state.value.identity)
        assertEquals("", controller.state.value.draft.fullName)
        assertTrue(controller.state.value.profileLoadFailed)
    }

    @Test fun `save requires ready valid signed in profile`() = runBlocking {
        assertNull(controller.save())
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        assertNull(controller.save())
        controller.loadProfile()!!.join()
        assertNull(controller.save())
        controller.username("artist_7")
        controller.displayName("Artist")
        gateway.savedProfile = profile()
        val save = controller.save()
        assertTrue(save != null)
        save!!.join()
        assertEquals(1, gateway.saveCalls)
    }

    @Test fun `save trims name and preserves returned privacy fields`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile(username = "artist_7", displayName = "Artist")
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        controller.displayName("  Artist Updated  ")
        controller.publicProfile(false)
        controller.discoverable(false)
        gateway.savedProfile = profile(username = "artist_7", displayName = "Artist Updated", bio = "saved", isPublic = false, discoverable = false)

        controller.save()!!.join()

        assertEquals("Artist Updated", gateway.lastSavedDraft!!.displayName)
        assertEquals(false, gateway.lastSavedDraft!!.isPublic)
        assertEquals(false, gateway.lastSavedDraft!!.discoverable)
        assertEquals(CloudProfileDraft("artist_7", "Artist Updated", "saved", false, false), controller.state.value.draft)
        assertEquals(R.string.cloud_account_saved, controller.state.value.message)
    }

    @Test fun `different active account never receives old profile edits`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        controller.bio("private previous user bio")
        gateway.identityValue = identity("another-user", "another@example.test")

        controller.save()!!.join()

        assertEquals(0, gateway.saveCalls)
        assertEquals(identity("another-user", "another@example.test"), controller.state.value.identity)
        assertEquals(CloudProfileDraft(), controller.state.value.draft)
        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.canSave)
    }

    @Test fun `identity change during profile load clears stale data and offers retry`() = runBlocking {
        gateway.identityValue = identity()
        gateway.onLoad = {
            gateway.identityValue = identity("next-user", "next@example.test")
            profile(bio = "previous user private bio")
        }
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        controller.loadProfile()!!.join()

        assertEquals(identity("next-user", "next@example.test"), controller.state.value.identity)
        assertEquals("", controller.state.value.draft.bio)
        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.profileReady)
    }

    @Test fun `failed reload blocks previously valid profile from saving`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        assertTrue(controller.state.value.canSave)
        gateway.onLoad = { throw IllegalStateException("temporary failure") }

        controller.loadProfile()!!.join()

        assertFalse(controller.state.value.canSave)
        assertNull(controller.save())
        assertEquals(0, gateway.saveCalls)
    }

    @Test fun `pending verification restores the persisted resend cooldown`() {
        gateway.pendingEmail = "pending@example.test"
        gateway.cooldownRemaining = 37
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        assertEquals(CloudAccountMode.VERIFY_EMAIL, controller.state.value.mode)
        assertEquals("pending@example.test", controller.state.value.email)
        assertEquals(37, controller.state.value.resendSecondsRemaining)
        assertFalse(controller.state.value.canResendOtp)
    }

    @Test fun `successful resend uses the stored deadline rather than a fresh hardcoded timer`() = runBlocking {
        gateway.pendingEmail = "pending@example.test"
        gateway.cooldownRemaining = 0
        gateway.cooldownAfterResend = 23
        controller = CloudAccountController(gateway, scope, resendCooldownSeconds = 60, usernameDebounceMillis = 0)

        controller.resendOtp()!!.join()

        assertEquals(1, gateway.resendCalls)
        assertEquals(23, controller.state.value.resendSecondsRemaining)
        assertFalse(controller.state.value.canResendOtp)
    }

    private fun signIn(email: String, password: String) {
        controller.email(email)
        controller.password(password)
    }

    private fun recovery(email: String) {
        controller.mode(CloudAccountMode.RECOVERY)
        controller.email(email)
    }

    @Test fun `sign in never binds another users returned profile to active identity`() = runBlocking {
        gateway.onSignIn = { _, _ ->
            gateway.identityValue = identity("other-user")
            VinzCloudAccount.AuthResult.SignedIn(profile(username = "private_artist"))
        }
        signIn("user@example.test", "test-password")
        controller.submit()!!.join()
        assertEquals(identity("other-user"), controller.state.value.identity)
        assertForeignProfileBlocked()
        assertNull(controller.save())
        assertEquals(0, gateway.saveCalls)
    }

    @Test fun `sign up never carries its draft to a different account without returned profile`() = runBlocking {
        gateway.onSignUp = { _, _, _, _ ->
            gateway.identityValue = identity("other-user", "other@example.test")
            VinzCloudAccount.AuthResult.SignedIn(null)
        }
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("user@example.test")
        controller.password("test-password")
        controller.username("private_artist")
        controller.displayName("Private Artist")
        controller.submit()!!.join()
        assertEquals(identity("other-user", "other@example.test"), controller.state.value.identity)
        assertForeignProfileBlocked()
    }

    @Test fun `load rejects profile owned by another identity even when session stays stable`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile(username = "private_artist").copy(id = "other-user")
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        assertForeignProfileBlocked()
        assertNull(controller.save())
    }

    @Test fun `save never paints response owned by another identity`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        gateway.savedProfile = profile(username = "private_artist").copy(id = "other-user")
        controller.save()!!.join()
        assertEquals(1, gateway.saveCalls)
        assertForeignProfileBlocked()
        assertNull(controller.save())
    }

    private fun assertForeignProfileBlocked() {
        assertEquals(CloudProfileDraft(), controller.state.value.draft)
        assertFalse(controller.state.value.profileReady)
        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.canSave)
        assertEquals(R.string.cloud_account_load_failed, controller.state.value.error)
        assertEquals("", controller.state.value.password)
    }

    private fun identity(userId: String = "user-1", email: String = "user@example.test") = CloudAccountIdentity(userId, email)


    @Test fun `avatar upload updates the active profile draft`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        val path = "user-1/123e4567-e89b-12d3-a456-426614174000.webp"
        gateway.onAvatarUpload = { _, type ->
            assertEquals("image/webp", type)
            profile().copy(avatarPath = path)
        }

        controller.uploadAvatar(byteArrayOf(1, 2, 3), "image/webp")!!.join()

        assertEquals(1, gateway.avatarUploadCalls)
        assertEquals(path, controller.state.value.draft.avatarPath)
        assertEquals(R.string.cloud_account_avatar_saved, controller.state.value.message)
    }

    @Test fun `avatar removal clears the active profile draft`() = runBlocking {
        val path = "user-1/123e4567-e89b-12d3-a456-426614174000.webp"
        gateway.identityValue = identity()
        gateway.profile = profile().copy(avatarPath = path)
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        gateway.onAvatarRemove = { profile() }

        controller.removeAvatar()!!.join()

        assertEquals(1, gateway.avatarRemoveCalls)
        assertNull(controller.state.value.draft.avatarPath)
        assertEquals(R.string.cloud_account_avatar_removed, controller.state.value.message)
    }

    @Test fun `avatar upload result is discarded if the account changes mid request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        controller.loadProfile()!!.join()
        gateway.onAvatarUpload = { _, _ ->
            started.complete(Unit)
            release.await()
            profile().copy(avatarPath = "user-1/123e4567-e89b-12d3-a456-426614174000.webp")
        }

        val job = controller.uploadAvatar(byteArrayOf(1), "image/webp")!!
        started.await()
        gateway.identityValue = identity("other-user", "other@example.test")
        release.complete(Unit)
        job.join()

        assertEquals(identity("other-user", "other@example.test"), controller.state.value.identity)
        assertTrue(controller.state.value.profileLoadFailed)
        assertNull(controller.state.value.draft.avatarPath)
    }


    @Test fun `account settings load and persist the selected preferences`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)
        gateway.accountSettings = VinzCloudAccount.AccountSettings(
            allowFriendRequests = false,
            showListeningActivity = true,
            notifyNewReleases = false,
            notifyFriendActivity = true,
            notifyPodcastUpdates = false,
        )

        controller.loadSettings()!!.join()

        assertTrue(controller.state.value.settingsReady)
        assertFalse(controller.state.value.settingsLoadFailed)
        assertFalse(controller.state.value.settingsDraft.allowFriendRequests)
        assertTrue(controller.state.value.settingsDraft.showListeningActivity)

        controller.notifyNewReleases(true)
        controller.notifyPodcastUpdates(true)
        controller.saveSettings()!!.join()

        assertEquals(1, gateway.settingsSaveCalls)
        assertTrue(gateway.savedAccountSettings?.notifyNewReleases == true)
        assertTrue(gateway.savedAccountSettings?.notifyPodcastUpdates == true)
        assertFalse(gateway.savedAccountSettings?.allowFriendRequests ?: true)
        assertEquals(R.string.cloud_account_settings_saved, controller.state.value.message)
    }

    @Test fun `settings load failure is retryable without breaking the profile`() = runBlocking {
        gateway.identityValue = identity()
        gateway.settingsLoadFailure = IllegalStateException("private backend detail")
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        controller.loadSettings()!!.join()

        assertTrue(controller.state.value.settingsLoadFailed)
        assertFalse(controller.state.value.profileLoadFailed)
        assertEquals(R.string.cloud_account_settings_load_failed, controller.state.value.error)
        assertNull(controller.saveSettings())

        gateway.settingsLoadFailure = null
        controller.loadSettings()!!.join()
        assertTrue(controller.state.value.settingsReady)
        assertFalse(controller.state.value.settingsLoadFailed)
    }


    @Test fun `settings auth failure shows retry instead of an endless loading state`() = runBlocking {
        gateway.identityValue = identity()
        gateway.settingsLoadFailure = com.vinz.music.data.CloudAuthException(
            com.vinz.music.data.CloudAuthFailure.EMAIL_NOT_CONFIRMED,
        )
        controller = CloudAccountController(gateway, scope, usernameDebounceMillis = 0)

        controller.loadSettings()!!.join()

        assertTrue(controller.state.value.settingsLoadFailed)
        assertFalse(controller.state.value.profileLoadFailed)
        assertEquals(R.string.cloud_account_confirmation, controller.state.value.error)
    }

    private fun profile(
        username: String = "artist_7", displayName: String = "Artist", bio: String = "",
        isPublic: Boolean = true, discoverable: Boolean = true, fullName: String = "",
    ) = VinzCloudAccount.Profile("user-1", username, displayName, bio, null, isPublic, discoverable, fullName)

    private class FakeGateway : CloudAccountGateway {
        override var identity: CloudAccountIdentity? = null
        var identityValue: CloudAccountIdentity?
            get() = identity
            set(value) { identity = value }
        var profile: VinzCloudAccount.Profile? = null
        var savedProfile: VinzCloudAccount.Profile? = null
        var recoverCalls = 0
        var saveCalls = 0
        var privateSaveCalls = 0
        var accountSettings = VinzCloudAccount.AccountSettings()
        var savedAccountSettings: VinzCloudAccount.AccountSettings? = null
        var settingsSaveCalls = 0
        var settingsLoadFailure: Exception? = null
        var avatarUploadCalls = 0
        var avatarRemoveCalls = 0
        var onAvatarUpload: suspend (ByteArray, String) -> VinzCloudAccount.Profile = { _, _ -> error("not configured") }
        var onAvatarRemove: suspend () -> VinzCloudAccount.Profile = { error("not configured") }
        var lastPrivateFullName: String? = null
        var signUpCalls = 0
        var usernameAvailable = true
        val usernameChecks = mutableListOf<String>()
        var usernameCheckFailure: Exception? = null
        var lastSignInEmail: String? = null
        var lastRecoveryEmail: String? = null
        var lastSavedDraft: CloudProfileDraft? = null
        var pendingEmail: String? = null
        var cooldownRemaining: Int? = null
        var cooldownAfterResend: Int? = null
        var resendCalls = 0
        var recoveryPending = false
        var updatePasswordCalls = 0
        var lastUpdatedPassword: String? = null
        var lastUpdatedUserId: String? = null
        var onLoad: suspend () -> VinzCloudAccount.Profile? = { profile }
        var onSignIn: suspend (String, String) -> VinzCloudAccount.AuthResult = { _, _ -> error("not configured") }
        var onSignUp: suspend (String, String, String, String) -> VinzCloudAccount.AuthResult = { _, _, _, _ -> error("not configured") }
        var onRecover: suspend (String) -> Unit = { }

        override suspend fun loadProfile() = onLoad()
        override fun pendingVerificationEmail() = pendingEmail
        override fun otpResendSecondsRemaining() = cooldownRemaining
        override fun passwordRecoveryPending() = recoveryPending
        override suspend fun updatePassword(password: String, expectedUserId: String) {
            if (identity?.userId != expectedUserId || !recoveryPending) error("recovery identity changed")
            updatePasswordCalls++
            lastUpdatedPassword = password
            lastUpdatedUserId = expectedUserId
            recoveryPending = false
        }
        override suspend fun checkUsernameAvailable(username: String): Boolean {
            usernameChecks += username
            usernameCheckFailure?.let { throw it }
            return usernameAvailable
        }
        override suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult {
            lastSignInEmail = email
            return onSignIn(email, password)
        }
        override suspend fun signUp(email: String, password: String, username: String, displayName: String): VinzCloudAccount.AuthResult {
            signUpCalls++
            return onSignUp(email, password, username, displayName)
        }
        override suspend fun resendOtp(email: String) {
            resendCalls++
            cooldownRemaining = cooldownAfterResend
        }
        override suspend fun recover(email: String) {
            recoverCalls++
            lastRecoveryEmail = email
            onRecover(email)
        }
        override suspend fun save(draft: CloudProfileDraft): VinzCloudAccount.Profile {
            saveCalls++
            lastSavedDraft = draft
            return savedProfile ?: profile ?: error("not configured")
        }
        override suspend fun savePrivateFullName(fullName: String): String {
            privateSaveCalls++
            lastPrivateFullName = fullName
            return fullName
        }
        override suspend fun loadSettings(): VinzCloudAccount.AccountSettings {
            settingsLoadFailure?.let { throw it }
            return accountSettings
        }
        override suspend fun saveSettings(settings: VinzCloudAccount.AccountSettings): VinzCloudAccount.AccountSettings {
            settingsSaveCalls++
            savedAccountSettings = settings
            accountSettings = settings
            return settings
        }
        override suspend fun uploadAvatar(imageBytes: ByteArray, contentType: String): VinzCloudAccount.Profile {
            avatarUploadCalls++
            return onAvatarUpload(imageBytes, contentType)
        }
        override suspend fun removeAvatar(): VinzCloudAccount.Profile {
            avatarRemoveCalls++
            return onAvatarRemove()
        }
        override suspend fun signOut() { identity = null }
    }
}
