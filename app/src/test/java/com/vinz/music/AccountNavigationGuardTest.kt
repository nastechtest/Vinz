package com.vinz.music

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-wiring guards complement offline Compose tests; no auth transport or production requests. */
class AccountNavigationGuardTest {
    private val source = File("src/main/java/com/vinz/music")
    private fun read(path: String) = File(source, path).readText()

    @Test fun settingsHasOneCanonicalCloudBackedVinzAccountDestination() {
        val settings = read("ui/screens/SettingsScreen.kt")
        assertEquals(1, Regex("nav\\.go\\(Routes\\.ACCOUNT\\)").findAll(settings).count())
        assertFalse(settings.contains("nav.go(Routes.VINZ_CLOUD_ACCOUNT)"))
        assertTrue(settings.contains("R.string.your_vinz_account"))
        assertTrue(settings.contains("ExistingCloudAccountGateway(c.vinzCloudAccount).identity"))
        assertFalse(settings.contains("VinzCloudAccountSection"))
    }

    @Test fun startupSettingsAndLegacyCloudRouteUseTheSameAccountHost() {
        val activity = read("MainActivity.kt")
        assertTrue(activity.contains("onAccount = { navController.navigate(Routes.ACCOUNT + \"?onboarding=true\") }"))
        assertTrue(activity.contains("onSkip = { navController.navigate(Routes.PICK_ARTISTS + \"?onboarding=true\") }"))

        val legacyRoute = activity.substringAfter("composable(Routes.VINZ_CLOUD_ACCOUNT)")
            .substringBefore("composable(")
        assertTrue(legacyRoute.contains("VinzAccountScreen(false, bottomPadding)"))
        assertTrue(legacyRoute.contains("navController.popBackStack()"))
        assertFalse(activity.contains(".VinzCloudAccountScreen"))
    }

    @Test fun accountHostIsRouteScopedCloudOnlyAndCleansUpPasswords() {
        val account = read("ui/screens/AccountScreens.kt")
        val host = account.substringAfter("fun VinzAccountScreen(")
            .substringBefore("private fun LocalMusicSummary")
        assertTrue(account.contains("fun VinzAccountScreen(onboarding: Boolean, bottomPadding: Dp, onDone: () -> Unit)"))
        assertTrue(host.contains("CloudAccountController(ExistingCloudAccountGateway(cloud), scope)"))
        assertTrue(host.contains("if (onboarding) it.mode(CloudAccountMode.SIGN_UP)"))
        assertTrue(host.contains("LaunchedEffect(controller) { controller.loadProfile() }"))
        assertTrue(host.contains("DisposableEffect(controller) { onDispose { controller.clearPassword() } }"))
        assertTrue(host.contains("BackHandler(enabled = state.busy)"))
        assertTrue(host.contains("onContinue = if (onboarding) onDone else null"))
        assertTrue(host.contains("bottomPadding = bottomPadding"))
        assertFalse(host.contains("VinzCloudAccountSection"))
        listOf("c.account.create(", "c.account.delete(", "c.account.name", "c.account.avatar").forEach {
            assertFalse("Account host must not mutate local identity through $it", host.contains(it))
        }
    }

    @Test fun localSummaryShowsPhoneLibraryCountsWithoutSeparateYoutubeImport() {
        val account = read("ui/screens/AccountScreens.kt")
        val summary = account.substringAfter("private fun LocalMusicSummary()")
        assertTrue(summary.contains("c.vinzLibrary.lists"))
        assertTrue(summary.contains("c.library.liked"))
        assertFalse(summary.contains("c.account.bringOver"))
        assertFalse(summary.contains("legacyImport"))
        listOf("c.account.create(", "c.account.delete(", "c.account.name", "c.account.avatar").forEach {
            assertFalse("Local summary must not mutate identity through $it", summary.contains(it))
        }
    }

    @Test fun cloudContentHasNoEmbeddedSectionAndGatesOnboardingContinue() {
        val cloud = read("ui/screens/VinzCloudAccountScreen.kt")
        assertFalse(cloud.contains("fun VinzCloudAccountSection("))
        assertTrue(cloud.contains("fun CloudAccountContent("))
        assertTrue(cloud.contains("onContinue: (() -> Unit)? = null"))
        assertTrue(cloud.contains("bottomPadding: Dp = 0.dp"))
        assertTrue(cloud.contains("supplementalContent: @Composable () -> Unit = {}"))
        assertTrue(cloud.contains("cloud-account-skip"))
        assertTrue(cloud.contains("cloud-account-back"))
        assertTrue(cloud.contains("cloud-account-continue"))
        assertTrue(cloud.contains("if (onContinue != null && state.canContinue)"))
        assertTrue(cloud.contains("enabled = !state.busy"))
        assertFalse(cloud.contains("embedded: Boolean"))
    }

    @Test fun credentialsHaveExplicitHintsAndSignupFieldsFollowCredentials() {
        val cloud = read("ui/screens/VinzCloudAccountScreen.kt")
        assertTrue(cloud.contains("R.string.cloud_account_email_hint"))
        assertTrue(cloud.contains("R.string.cloud_account_password_hint"))
        assertTrue(cloud.contains("R.string.cloud_account_password_sign_in_hint"))
        val unsigned = cloud.substringAfter("if (state.identity == null)")
            .substringBefore("VinzButton(onClick = { actions.submit() }")
        assertTrue(unsigned.indexOf("cloud-account-email") < unsigned.indexOf("CloudProfileFields(state, actions)"))
        assertTrue(unsigned.indexOf("cloud-account-password") < unsigned.indexOf("CloudProfileFields(state, actions)"))
    }

    @Test fun welcomeUsesVinzAccountAsPrimarySignIn() {
        val welcome = read("ui/screens/LoginScreen.kt").substringAfter("fun WelcomeScreen(")
        assertTrue(welcome.contains("R.string.cloud_account_intro"))
        assertFalse(welcome.contains("R.string.no_email"))
        assertFalse(welcome.contains("on_this_phone"))
        assertTrue(welcome.contains("R.string.cloud_account_sign_in_cta"))
        assertFalse(welcome.contains("R.string.sign_in_to_youtube_music"))

        val activity = read("MainActivity.kt")
        assertTrue(activity.contains("onSignIn = { navController.navigate(Routes.ACCOUNT) }"))
    }

    @Test fun optionalYoutubePlaybackSessionRetainsItsWebViewCookieLifecycle() {
        val login = read("ui/screens/LoginScreen.kt")
        assertTrue(login.contains("fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit, addAccount: Boolean = false)"))
        assertTrue(login.contains("AndroidView("))
        assertTrue(login.contains("WebView(ctx).apply"))
        assertTrue(login.contains("CookieManager.getInstance().flush()"))
        assertTrue(login.contains("CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)"))
        assertTrue(login.contains("onDone()"))
        assertTrue(login.contains("R.string.sign_in_to_youtube_music"))
    }

    @Test fun cloudPresentationCannotMutateLocalOrYoutubeIdentity() {
        val cloud = read("ui/screens/VinzCloudAccountScreen.kt")
        listOf(
            "settings.logout(", "settings.saveAccount(", "CookieManager", "account.delete(",
            "account.create(", "bringOver(", "Routes.LOGIN", "rememberSaveable",
        ).forEach {
            assertFalse("Cloud presentation must not use $it", cloud.contains(it))
        }
        assertFalse(cloud.contains("fun VinzCloudAccountScreen("))
    }
}
