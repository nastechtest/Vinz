package com.vinz.music

import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountGateway
import com.vinz.music.ui.screens.account.CloudAccountIdentity
import com.vinz.music.ui.screens.account.CloudAccountMode
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
        controller = CloudAccountController(gateway, scope)
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
        controller = CloudAccountController(gateway, scope)
        gateway.profile = profile(username = "artist_7", displayName = "  Artist  ", bio = "bio", isPublic = false, discoverable = false)

        controller.loadProfile()!!.join()

        assertEquals(CloudProfileDraft("artist_7", "  Artist  ", "bio", false, false), controller.state.value.draft)
        assertTrue(controller.state.value.profileReady)
        assertFalse(controller.state.value.profileLoadFailed)
        assertNull(controller.state.value.message)
    }

    @Test fun `null loaded profile enters setup state`() = runBlocking {
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope)

        controller.loadProfile()!!.join()

        assertTrue(controller.state.value.profileReady)
        assertEquals(R.string.cloud_account_setup, controller.state.value.message)
        assertEquals(CloudProfileDraft(), controller.state.value.draft)
    }

    @Test fun `failed profile load prevents unsafe save`() = runBlocking {
        gateway.identityValue = identity()
        gateway.onLoad = { throw IllegalStateException("private backend detail") }
        controller = CloudAccountController(gateway, scope)

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

    @Test fun `signup confirmation clears password and returns to sign in`() = runBlocking {
        gateway.onSignUp = { _, _, _, _ -> VinzCloudAccount.AuthResult.EmailConfirmationRequired }
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("  new@example.test ")
        controller.password("password8")
        controller.username("new_artist")
        controller.displayName("New Artist")

        controller.submit()!!.join()

        assertEquals(CloudAccountMode.SIGN_IN, controller.state.value.mode)
        assertEquals("new@example.test", controller.state.value.email)
        assertEquals("", controller.state.value.password)
        assertEquals(R.string.cloud_account_confirmation, controller.state.value.message)
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
        controller = CloudAccountController(gateway, scope)
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

    @Test fun `save requires ready valid signed in profile`() = runBlocking {
        assertNull(controller.save())
        gateway.identityValue = identity()
        controller = CloudAccountController(gateway, scope)
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
        controller = CloudAccountController(gateway, scope)
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
        controller = CloudAccountController(gateway, scope)
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
        controller = CloudAccountController(gateway, scope)

        controller.loadProfile()!!.join()

        assertEquals(identity("next-user", "next@example.test"), controller.state.value.identity)
        assertEquals("", controller.state.value.draft.bio)
        assertTrue(controller.state.value.profileLoadFailed)
        assertFalse(controller.state.value.profileReady)
    }

    @Test fun `failed reload blocks previously valid profile from saving`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope)
        controller.loadProfile()!!.join()
        assertTrue(controller.state.value.canSave)
        gateway.onLoad = { throw IllegalStateException("temporary failure") }

        controller.loadProfile()!!.join()

        assertFalse(controller.state.value.canSave)
        assertNull(controller.save())
        assertEquals(0, gateway.saveCalls)
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
        controller = CloudAccountController(gateway, scope)
        controller.loadProfile()!!.join()
        assertForeignProfileBlocked()
        assertNull(controller.save())
    }

    @Test fun `save never paints response owned by another identity`() = runBlocking {
        gateway.identityValue = identity()
        gateway.profile = profile()
        controller = CloudAccountController(gateway, scope)
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

    private fun profile(
        username: String = "artist_7", displayName: String = "Artist", bio: String = "",
        isPublic: Boolean = true, discoverable: Boolean = true,
    ) = VinzCloudAccount.Profile("user-1", username, displayName, bio, null, isPublic, discoverable)

    private class FakeGateway : CloudAccountGateway {
        override var identity: CloudAccountIdentity? = null
        var identityValue: CloudAccountIdentity?
            get() = identity
            set(value) { identity = value }
        var profile: VinzCloudAccount.Profile? = null
        var savedProfile: VinzCloudAccount.Profile? = null
        var recoverCalls = 0
        var saveCalls = 0
        var lastSignInEmail: String? = null
        var lastRecoveryEmail: String? = null
        var lastSavedDraft: CloudProfileDraft? = null
        var onLoad: suspend () -> VinzCloudAccount.Profile? = { profile }
        var onSignIn: suspend (String, String) -> VinzCloudAccount.AuthResult = { _, _ -> error("not configured") }
        var onSignUp: suspend (String, String, String, String) -> VinzCloudAccount.AuthResult = { _, _, _, _ -> error("not configured") }
        var onRecover: suspend (String) -> Unit = { }

        override suspend fun loadProfile() = onLoad()
        override suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult {
            lastSignInEmail = email
            return onSignIn(email, password)
        }
        override suspend fun signUp(email: String, password: String, username: String, displayName: String) =
            onSignUp(email, password, username, displayName)
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
        override suspend fun signOut() { identity = null }
    }
}
