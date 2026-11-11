package com.vinz.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.ui.screens.CloudAccountContent
import com.vinz.music.ui.screens.account.CloudAccountController
import com.vinz.music.ui.screens.account.CloudAccountGateway
import com.vinz.music.ui.screens.account.CloudAccountIdentity
import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudAccountState
import com.vinz.music.ui.screens.account.CloudProfileDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp", application = android.app.Application::class)
class CloudAccountScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob())
    private val controller = CloudAccountController(NoRequestsGateway, scope)

    private var renderedState by mutableStateOf(CloudAccountState())
    private var renderControllerState by mutableStateOf(false)
    private var onContinue by mutableStateOf<(() -> Unit)?>(null)
    private var fontScale by mutableStateOf(1f)
    private var backCalls = 0

    @Before fun renderOnce() {
        // A Compose rule accepts one root per test. Each assertion below changes observable state instead
        // of replacing that root, which also catches recomposition regressions.
        compose.setContent {
            val controllerState by controller.state.collectAsState()
            val deviceDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = deviceDensity, fontScale = fontScale)) {
                MaterialTheme {
                    CloudAccountContent(
                        state = if (renderControllerState) controllerState else renderedState,
                        actions = controller,
                        onBack = { backCalls++ },
                        onContinue = onContinue,
                        bottomPadding = 0.dp,
                    )
                }
            }
        }
    }

    @After fun tearDown() { scope.cancel() }

    @Test fun profileLoadFailureShowsRetryAndNeverEditableDefaultPrivacy() {
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"),
            profileLoadFailed = true,
            error = R.string.cloud_account_load_failed,
        ))
        compose.onNodeWithTag("cloud-account-retry").assertIsEnabled()
        compose.onNodeWithTag("cloud-account-username").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-public").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-save").assertDoesNotExist()
    }

    @Test fun savingLocksCloudProfileFieldsAndSignOutAtLargeFont() {
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"),
            profileReady = true,
            busy = true,
            draft = CloudProfileDraft("test_artist", "Test Artist", isPublic = false, discoverable = false),
        ), scale = 1.5f)
        compose.onNodeWithTag("cloud-account-username").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-public").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-discoverable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-save").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-full-name").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-save-private-details").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-sign-out").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
    }

    @Test fun privateFullNameIsClearlySeparateFromThePublicMusicProfile() {
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"),
            profileReady = true,
            draft = CloudProfileDraft("test_artist", "Test Artist", fullName = "Ada Lovelace"),
        ))
        compose.onNodeWithTag("cloud-account-full-name").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Visible only to you. It is not shown on your public music profile.").assertExists()
        compose.onNodeWithTag("cloud-account-save-private-details").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("cloud-account-save").performScrollTo().assertIsEnabled()
    }

    @Test fun recoveryNeedsNoPasswordAndEmailUnlocksSubmit() {
        render(CloudAccountState(mode = CloudAccountMode.RECOVERY, email = "test@example.test"))
        compose.onNodeWithTag("cloud-account-email").assertIsEnabled()
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-submit").assertIsEnabled()
    }

    @Test fun verificationModeOnlyShowsNumericCodeAndHonorsResendCooldown() {
        render(CloudAccountState(mode = CloudAccountMode.VERIFY_EMAIL, email = "verify@example.test", resendSecondsRemaining = 60))
        compose.onNodeWithTag("cloud-account-otp").assertExists()
        compose.onNodeWithTag("cloud-account-email").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-verify").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-resend-otp").assertIsNotEnabled()

        render(CloudAccountState(mode = CloudAccountMode.VERIFY_EMAIL, email = "verify@example.test", otp = "123456", resendSecondsRemaining = 0))
        compose.onNodeWithTag("cloud-account-verify").assertIsEnabled()
        compose.onNodeWithTag("cloud-account-resend-otp").assertIsEnabled()
    }

    @Test fun credentialsShowClearModeSpecificHints() {
        render(CloudAccountState(mode = CloudAccountMode.SIGN_IN))
        compose.onNodeWithText("Enter the email address for your Vinz Account.").assertExists()
        compose.onNodeWithText("Enter your Vinz Account password.").assertExists()

        render(CloudAccountState(mode = CloudAccountMode.SIGN_UP))
        compose.onNodeWithText("Enter the email address for your Vinz Account.").assertExists()
        compose.onNodeWithText("Use at least 8 characters.").assertExists()
    }

    @Test fun onboardingShowsSkipInsteadOfBackAndDisablesSkipWhileBusy() {
        var skipped = 0
        render(CloudAccountState(), continuation = { skipped++ })
        compose.onNodeWithTag("cloud-account-skip").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("cloud-account-back").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, skipped)
            assertEquals(0, backCalls)
        }

        render(CloudAccountState(busy = true), continuation = { skipped++ })
        compose.onNodeWithTag("cloud-account-skip").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, skipped)
            assertEquals(0, backCalls)
        }

        render(CloudAccountState())
        compose.onNodeWithTag("cloud-account-back").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("cloud-account-skip").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, skipped)
            assertEquals(1, backCalls)
        }
    }

    @Test fun onboardingContinueRequiresLoadedSignedInProfile() {
        render(CloudAccountState(), continuation = {})
        compose.onNodeWithTag("cloud-account-continue").assertDoesNotExist()

        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"), profileReady = true, busy = true,
        ), continuation = {})
        compose.onNodeWithTag("cloud-account-continue").assertDoesNotExist()

        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"), profileReady = true, profileLoadFailed = true,
        ), continuation = {})
        compose.onNodeWithTag("cloud-account-continue").assertDoesNotExist()
    }

    @Test fun onboardingContinueInvokesCallbackForLoadedSignedInProfile() {
        var continued = 0
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"), profileReady = true,
        ), continuation = { continued++ }, scale = 2f)
        compose.onNodeWithTag("cloud-account-continue").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, continued)
            assertEquals(0, backCalls)
        }
    }

    @Test fun credentialInputsRemainEditableWithoutRequestsOrKeystore() {
        compose.runOnIdle { renderControllerState = true }
        compose.onNodeWithTag("cloud-account-email").assertIsEnabled().performTextInput("test@example.test")
        compose.onNodeWithTag("cloud-account-password").assertIsEnabled().performTextInput("safe-password")
        compose.runOnIdle {
            assertEquals("test@example.test", controller.state.value.email)
            assertEquals("safe-password", controller.state.value.password)
        }
    }

    private fun render(state: CloudAccountState, continuation: (() -> Unit)? = null, scale: Float = 1f) {
        compose.runOnIdle {
            renderedState = state
            renderControllerState = false
            onContinue = continuation
            fontScale = scale
        }
    }

    /** Rendering never reaches network, credentials storage, or Android Keystore. */
    private object NoRequestsGateway : CloudAccountGateway {
        override val identity: CloudAccountIdentity? = null
        override suspend fun loadProfile(): VinzCloudAccount.Profile? = error("unexpected request")
        override suspend fun signIn(email: String, password: String): VinzCloudAccount.AuthResult = error("unexpected request")
        override suspend fun signUp(email: String, password: String, username: String, displayName: String): VinzCloudAccount.AuthResult = error("unexpected request")
        override suspend fun recover(email: String) { error("unexpected request") }
        override suspend fun save(draft: CloudProfileDraft): VinzCloudAccount.Profile = error("unexpected request")
        override suspend fun signOut() { error("unexpected request") }
    }
}
