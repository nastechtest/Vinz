package com.vinz.music

import com.vinz.music.R
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountGateway
import com.vinz.music.ui.screens.account.CloudAccountIdentity
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudProfileDraft
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
import org.junit.Test

class CloudAccountOtpControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun tearDown() { scope.cancel() }

    @Test fun `signup waits for OTP then binds only the verified identity`() = runBlocking {
        val gateway = FakeGateway()
        val controller = CloudAccountController(gateway, scope, resendCooldownSeconds = 0, usernameDebounceMillis = 0)
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("safe-password-123")
        controller.username("new_artist")
        controller.displayName("New Artist")
        controller.submit()!!.join()

        assertEquals(CloudAccountMode.VERIFY_EMAIL, controller.state.value.mode)
        assertEquals("", controller.state.value.password)
        assertEquals(CloudProfileDraft("new_artist", "New Artist"), controller.state.value.draft)
        assertNull(gateway.identity)

        controller.otp("12a34567")
        assertEquals("123456", controller.state.value.otp)
        controller.submit()!!.join()

        assertEquals("new@example.test", gateway.verifiedEmail)
        assertEquals("123456", gateway.verifiedCode)
        assertEquals(CloudAccountIdentity("verified-user", "new@example.test"), controller.state.value.identity)
        assertEquals(CloudProfileDraft("new_artist", "New Artist"), controller.state.value.draft)
        assertTrue(controller.state.value.profileReady)
        assertEquals(CloudAccountMode.SIGN_IN, controller.state.value.mode)
    }

    @Test fun `resend is unavailable during the server cooldown`() = runBlocking {
        val gateway = FakeGateway()
        val controller = CloudAccountController(gateway, scope, resendCooldownSeconds = 60, usernameDebounceMillis = 0)
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("safe-password-123")
        controller.username("new_artist")
        controller.displayName("New Artist")
        controller.submit()!!.join()
        assertFalse(controller.state.value.canResendOtp)
        assertNull(controller.resendOtp())
        assertEquals(0, gateway.resendCalls)
    }

    @Test fun `resend requests a new server code after cooldown`() = runBlocking {
        val gateway = FakeGateway()
        val controller = CloudAccountController(gateway, scope, resendCooldownSeconds = 0, usernameDebounceMillis = 0)
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("safe-password-123")
        controller.username("new_artist")
        controller.displayName("New Artist")
        controller.submit()!!.join()
        assertTrue(controller.state.value.canResendOtp)
        controller.resendOtp()!!.join()
        assertEquals(1, gateway.resendCalls)
        assertEquals("new@example.test", gateway.resendEmail)
        assertEquals(R.string.cloud_account_otp_resent, controller.state.value.message)
    }

    @Test fun `OTP verification response that still requires confirmation never unlocks the account`() = runBlocking {
        val gateway = FakeGateway().apply {
            nextVerificationResult = VinzCloudAccount.AuthResult.VerificationRequired("new@example.test")
        }
        val controller = CloudAccountController(gateway, scope, resendCooldownSeconds = 0, usernameDebounceMillis = 0)
        controller.mode(CloudAccountMode.SIGN_UP)
        controller.email("new@example.test")
        controller.password("safe-password-123")
        controller.username("new_artist")
        controller.displayName("New Artist")
        controller.submit()!!.join()

        controller.otp("123456")
        controller.submit()!!.join()

        assertEquals(CloudAccountMode.VERIFY_EMAIL, controller.state.value.mode)
        assertNull(controller.state.value.identity)
        assertEquals("", controller.state.value.otp)
        assertFalse(controller.state.value.canContinue)
    }

    private class FakeGateway : CloudAccountGateway {
        override var identity: CloudAccountIdentity? = null
        var verifiedEmail = ""
        var verifiedCode = ""
        var resendEmail = ""
        var resendCalls = 0
        var nextVerificationResult: VinzCloudAccount.AuthResult? = null
        override suspend fun loadProfile(): VinzCloudAccount.Profile? = null
        override suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult = error("unexpected sign-in")
        override suspend fun signUp(email: String, password: String, username: String, displayName: String) =
            VinzCloudAccount.AuthResult.VerificationRequired(email)
        override suspend fun verifyOtp(email: String, code: String): VinzCloudAccount.AuthResult {
            verifiedEmail = email
            verifiedCode = code
            val next = nextVerificationResult
            if (next != null) return next
            identity = CloudAccountIdentity("verified-user", email)
            return VinzCloudAccount.AuthResult.SignedIn(
                VinzCloudAccount.Profile("verified-user", "new_artist", "New Artist", "", null, true, true),
            )
        }
        override suspend fun resendOtp(email: String) { resendCalls++; resendEmail = email }
        override suspend fun recover(email: String) { error("unexpected recovery") }
        override suspend fun save(draft: CloudProfileDraft) =
            VinzCloudAccount.Profile("verified-user", draft.username, draft.displayName, draft.bio, null, draft.isPublic, draft.discoverable)
        override suspend fun signOut() { identity = null }
    }
}
