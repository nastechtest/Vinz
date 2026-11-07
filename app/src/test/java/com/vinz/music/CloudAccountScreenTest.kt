package com.vinz.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @After fun tearDown() { scope.cancel() }

    @Test fun profileLoadFailureShowsRetryAndNeverEditableDefaultPrivacy() {
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"),
            profileLoadFailed = true, error = R.string.cloud_account_load_failed,
        ))
        compose.onNodeWithTag("cloud-account-retry").assertIsEnabled()
        compose.onNodeWithTag("cloud-account-username").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-public").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-save").assertDoesNotExist()
    }

    @Test fun savingLocksFieldsPrivacyAndSignOutTogether() {
        render(CloudAccountState(
            identity = CloudAccountIdentity("test-user", "test@example.test"),
            profileReady = true, busy = true,
            draft = CloudProfileDraft("test_artist", "Test Artist", isPublic = false, discoverable = false),
        ))
        compose.onNodeWithTag("cloud-account-username").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-public").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-discoverable").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-save").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-sign-out").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
    }

    @Test fun recoveryNeedsNoPasswordAndEmailUnlocksSubmit() {
        render(CloudAccountState(mode = CloudAccountMode.RECOVERY, email = "test@example.test"))
        compose.onNodeWithTag("cloud-account-email").assertExists()
        compose.onNodeWithTag("cloud-account-password").assertDoesNotExist()
        compose.onNodeWithTag("cloud-account-submit").assertIsEnabled()
    }

    private fun render(state: CloudAccountState) {
        compose.setContent { MaterialTheme { CloudAccountContent(state, controller, {}) } }
    }

    /** Rendering never reaches the network, credentials or Android Keystore. */
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
