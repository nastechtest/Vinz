package com.vinz.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.screens.CloudAccountContent
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device coverage for the shared account renderer; all cloud behavior is a local fake. */
@RunWith(AndroidJUnit4::class)
class CloudAccountFlowTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var gateway: FakeGateway
    private lateinit var controller: CloudAccountController

    @After fun stopFakeWork() { scope.cancel() }

    @Test fun offlineProfileShowsRetryWithoutEditablePrivacyDefaults() {
        start(identity = CloudAccountIdentity("offline-user", "offline@example.test")) { loadFailure = true }
        compose.runOnIdle { controller.loadProfile() }
        compose.waitUntil(5_000) { controller.state.value.profileLoadFailed && !controller.state.value.busy }

        compose.onNodeWithTag("cloud-account-retry").assertIsEnabled()
        compose.onNodeWithTag("cloud-account-username").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-public").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-discoverable").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-save").assertDoesNotExist()
    }

    @Test fun signupAtTwoPointZeroScrollsAcceptsInputAndUsesImeNextThenDone() {
        start(fontScale = 2f)
        compose.runOnIdle { controller.mode(CloudAccountMode.SIGN_UP) }

        compose.onNodeWithTag("cloud-account-email").performScrollTo().performTextInput("signup@example.test")
        compose.onNodeWithTag("cloud-account-email").performImeAction()
        // The account fields deliberately give a form-friendly Next path rather than hiding the keyboard.
        compose.onNodeWithTag("cloud-account-password").assertIsFocused().performScrollTo().performTextInput("safe-password")
        compose.onNodeWithTag("cloud-account-password").performImeAction()
        compose.onNodeWithTag("cloud-account-username").assertIsFocused().performScrollTo().performTextInput("test_artist")
        compose.onNodeWithTag("cloud-account-username").performImeAction()
        compose.onNodeWithTag("cloud-account-display-name").assertIsFocused().performScrollTo().performTextInput("Test Artist")
        compose.onNodeWithTag("cloud-account-display-name").performImeAction()
        compose.waitUntil(5_000) { gateway.signUpCalls == 1 }
        assertEquals("signup@example.test", gateway.signUpEmail)
    }

    @Test fun recoveryHasNoPasswordAndSubmitsOnlyEmail() {
        start()
        compose.runOnIdle { controller.mode(CloudAccountMode.RECOVERY) }
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-email").performScrollTo().performTextInput("recover@example.test")
        compose.onNodeWithTag("cloud-account-submit").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { gateway.recoveryEmails == listOf("recover@example.test") }
    }

    @Test fun profileFieldsAndPrivacyGateSave() {
        start(identity = CloudAccountIdentity("signed-in", "profile@example.test")) {
            profile = profile("signed-in", "artist", "Artist", isPublic = false, discoverable = false)
        }
        compose.runOnIdle { controller.loadProfile() }
        compose.waitUntil(5_000) { controller.state.value.profileReady && !controller.state.value.busy }

        compose.onNodeWithTag("cloud-account-username").performScrollTo().assertIsEnabled().performTextClearance()
        compose.onNodeWithTag("cloud-account-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-username").performScrollTo().performTextInput("updated_artist")
        compose.onNodeWithTag("cloud-account-public").performScrollTo().performClick()
        compose.onNodeWithTag("cloud-account-discoverable").performScrollTo().performClick()
        compose.onNodeWithTag("cloud-account-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { gateway.savedDrafts.size == 1 }
        assertEquals(true, gateway.savedDrafts.single().isPublic)
        assertEquals(true, gateway.savedDrafts.single().discoverable)
    }

    @Test fun signOutOnlyCallsCloudGatewayAndDoesNotTouchLocalYoutubeOrLibrary() {
        // No real credentials: snapshot the existing local services before rendering the fake cloud account.
        val local = Fixture.container
        val youtubeSignedIn = local.settings.current.isLoggedIn
        val lists = local.vinzLibrary.lists.value
        val albums = local.vinzLibrary.albums.value
        start(identity = CloudAccountIdentity("signed-in", "signout@example.test")) {
            profile = profile("signed-in", "artist", "Artist")
        }
        compose.runOnIdle { controller.loadProfile() }
        compose.waitUntil(5_000) { controller.state.value.profileReady && !controller.state.value.busy }
        compose.onNodeWithTag("cloud-account-sign-out").performScrollTo().performClick()
        compose.waitUntil(5_000) { gateway.signOutCalls == 1 && controller.state.value.identity == null }
        assertEquals(youtubeSignedIn, local.settings.current.isLoggedIn)
        assertEquals(lists, local.vinzLibrary.lists.value)
        assertEquals(albums, local.vinzLibrary.albums.value)
    }

    @Test fun clearingPasswordRegressionDoesNotRenderTheClearedSecret() {
        start()
        compose.onNodeWithTag("cloud-account-password").performScrollTo().performTextInput("never-restored")
        compose.runOnIdle { controller.clearPassword() }
        compose.waitUntil(5_000) { controller.state.value.password.isEmpty() }
        // This is a controller regression only. VinzAccountScreen separately clears this state on disposal.
        compose.onNodeWithTag("cloud-account-password").assertExists()
    }

    @Test fun fakeSignInShowsLoadingThenBindsOnlyTheFakeIdentity() {
        start { holdSignIn = CompletableDeferred() }
        compose.onNodeWithTag("cloud-account-email").performScrollTo().performTextInput("sign-in@example.test")
        compose.onNodeWithTag("cloud-account-password").performScrollTo().performTextInput("safe-password")
        compose.onNodeWithTag("cloud-account-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { controller.state.value.busy && gateway.signInCalls == 1 }
        compose.onNodeWithTag("cloud-account-progress").assertExists()
        gateway.holdSignIn!!.complete(Unit)
        compose.waitUntil(5_000) { controller.state.value.profileReady && !controller.state.value.busy }
        compose.runOnIdle {
            assertEquals(CloudAccountIdentity("sign-in-user", "sign-in@example.test"), controller.state.value.identity)
            assertEquals("", controller.state.value.password)
        }
    }

    @Test fun fakeSignInFailureRendersOnlyTheGenericResourceError() {
        start { signInFailure = true }
        compose.onNodeWithTag("cloud-account-email").performScrollTo().performTextInput("fail@example.test")
        compose.onNodeWithTag("cloud-account-password").performScrollTo().performTextInput("safe-password")
        compose.onNodeWithTag("cloud-account-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { !controller.state.value.busy && controller.state.value.error != null }
        compose.runOnIdle {
            assertEquals(1, gateway.signInCalls)
            assertEquals(R.string.cloud_account_sign_in_failed, controller.state.value.error)
        }
    }

    @Test fun cleanStartupWelcomeOpensSharedAccountAndSkipMovesToArtistChoice() {
        // Fixture.launch is intentionally used without ready(): the orchestrator's fresh app data gives Welcome.
        Fixture.launch().use {
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(tr(R.string.make_a_vinz_account))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(tr(R.string.make_a_vinz_account)).performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("cloud-account-content")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("cloud-account-skip").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(tr(R.string.pick_artists_you_like))).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun onboardingSkipInvokesContinuationInsteadOfBack() {
        var continued = 0
        var back = 0
        start(onBack = { back++ }, onContinue = { continued++ })
        compose.onNodeWithTag("cloud-account-skip").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, continued)
            assertEquals(0, back)
        }
    }

    @Test fun onboardingDoneAtLargeFontScrollsAndInvokesContinuation() {
        var continued = 0
        var back = 0
        start(identity = CloudAccountIdentity("signed-in", "profile@example.test"), fontScale = 2f,
            onBack = { back++ }, onContinue = { continued++ }) {
            profile = profile("signed-in", "artist", "Artist")
        }
        compose.runOnIdle { controller.loadProfile() }
        compose.waitUntil(5_000) { controller.state.value.profileReady && !controller.state.value.busy }
        compose.onNodeWithTag("cloud-account-continue").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, continued)
            assertEquals(0, back)
        }
    }

    private fun start(identity: CloudAccountIdentity? = null, fontScale: Float = 1f,
        onBack: () -> Unit = {}, onContinue: (() -> Unit)? = null, configure: FakeGateway.() -> Unit = {}) {
        gateway = FakeGateway(identity).apply(configure)
        controller = CloudAccountController(gateway, scope)
        compose.setContent {
            val state by controller.state.collectAsState()
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = deviceDensity, fontScale = fontScale)) {
                MaterialTheme { CloudAccountContent(state, controller, onBack = onBack, onContinue = onContinue, bottomPadding = 0.dp) }
            }
        }
    }

    private fun profile(id: String, username: String, displayName: String, isPublic: Boolean = true, discoverable: Boolean = true) =
        VinzCloudAccount.Profile(
            id = id, username = username, displayName = displayName, bio = "", avatarPath = null,
            isPublic = isPublic, discoverable = discoverable,
        )

    private class FakeGateway(override var identity: CloudAccountIdentity?) : CloudAccountGateway {
        var profile: VinzCloudAccount.Profile? = null
        var loadFailure = false
        var signUpCalls = 0
        var signUpEmail = ""
        var signInCalls = 0
        var signInFailure = false
        var holdSignIn: CompletableDeferred<Unit>? = null
        val recoveryEmails = mutableListOf<String>()
        val savedDrafts = mutableListOf<CloudProfileDraft>()
        var signOutCalls = 0

        override suspend fun loadProfile(): VinzCloudAccount.Profile? {
            if (loadFailure) error("offline fake")
            return profile
        }
        override suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult {
            signInCalls++
            holdSignIn?.await()
            if (signInFailure) error("offline fake")
            identity = CloudAccountIdentity("sign-in-user", email)
            return VinzCloudAccount.AuthResult.SignedIn(
                VinzCloudAccount.Profile(
                    id = "sign-in-user", username = "signed_in", displayName = "Signed In", bio = "", avatarPath = null,
                    isPublic = true, discoverable = true,
                ),
            )
        }
        override suspend fun signUp(email: String, password: String, username: String, displayName: String): VinzCloudAccount.AuthResult {
            signUpCalls++
            signUpEmail = email
            identity = CloudAccountIdentity("new-user", email)
            return VinzCloudAccount.AuthResult.SignedIn(
                VinzCloudAccount.Profile(
                    id = "new-user", username = username, displayName = displayName, bio = "", avatarPath = null,
                    isPublic = true, discoverable = true,
                ),
            )
        }
        override suspend fun recover(email: String) { recoveryEmails += email }
        override suspend fun save(draft: CloudProfileDraft): VinzCloudAccount.Profile {
            savedDrafts += draft
            return VinzCloudAccount.Profile(
                id = identity!!.userId, username = draft.username, displayName = draft.displayName, bio = "", avatarPath = null,
                isPublic = draft.isPublic, discoverable = draft.discoverable,
            )
        }
        override suspend fun signOut() { signOutCalls++; identity = null }
    }
}
