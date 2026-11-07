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

    @Test fun settingsHasOneCanonicalVinzAccountDestination() {
        val settings = read("ui/screens/SettingsScreen.kt")
        assertEquals(1, Regex("nav\\.go\\(Routes\\.ACCOUNT\\)").findAll(settings).count())
        assertFalse(settings.contains("nav.go(Routes.VINZ_CLOUD_ACCOUNT)"))
        assertTrue(settings.contains("Modifier.clickable { nav.go(Routes.LOGIN) }"))
        assertTrue(settings.contains("nav.go(Routes.LOGIN_ADD)"))
    }

    @Test fun oldCloudDestinationUsesExistingAccountHubAndBackStack() {
        val activity = read("MainActivity.kt")
        val alias = activity.substringAfter("composable(Routes.VINZ_CLOUD_ACCOUNT)")
            .substringBefore("composable(")
        assertTrue(alias.contains("VinzAccountScreen(false, bottomPadding, cloudExpanded = true)"))
        assertTrue(alias.contains("navController.popBackStack()"))
        assertFalse(activity.contains(".VinzCloudAccountScreen"))
    }

    @Test fun onboardingKeepsExistingLocalProfileAndArtistSelection() {
        val activity = read("MainActivity.kt")
        assertTrue(activity.contains("onAccount = { navController.navigate(Routes.ACCOUNT + \"?onboarding=true\") }"))
        assertTrue(activity.contains("onSkip = { navController.navigate(Routes.PICK_ARTISTS + \"?onboarding=true\") }"))
        val account = read("ui/screens/AccountScreens.kt")
        assertTrue(account.contains("if (!onboarding) Column"))
        assertTrue(account.contains("VinzCloudAccountSection(initiallyExpanded = cloudExpanded)"))
        assertTrue(account.contains("c.account.create(name)"))
        assertTrue(account.contains("c.account.bringOver"))
        assertTrue(account.contains("c.account.delete()"))
        assertTrue(account.contains("Modifier.fillMaxSize().imePadding().verticalScroll"))
    }

    @Test fun welcomeNamesTheExistingYoutubeSignInExplicitly() {
        val welcome = read("ui/screens/LoginScreen.kt").substringAfter("fun WelcomeScreen(")
        assertTrue(welcome.contains("Text(tr(R.string.sign_in_to_youtube_music)"))
        assertFalse(welcome.contains("Text(tr(R.string.sign_in_with_google)"))
        val activity = read("MainActivity.kt")
        assertTrue(activity.contains("onSignIn = { navController.navigate(Routes.LOGIN) }"))
    }

    @Test fun canonicalYoutubeLoginRetainsWebViewAndCookieLifecycle() {
        val login = read("ui/screens/LoginScreen.kt")
        assertTrue(login.contains("fun LoginScreen(onDone: () -> Unit, onBack: () -> Unit, addAccount: Boolean = false)"))
        assertTrue(login.contains("AndroidView("))
        assertTrue(login.contains("WebView(ctx).apply"))
        assertTrue(login.contains("CookieManager.getInstance().flush()"))
        assertTrue(login.contains("CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)"))
        assertTrue(login.contains("onDone()"))
        assertTrue(login.contains("R.string.sign_in_to_youtube_music"))
    }

    @Test fun cloudControllerIsLazyAndRetainedOutsideCollapsibleContent() {
        val cloud = read("ui/screens/VinzCloudAccountScreen.kt")
        val section = cloud.substringAfter("fun VinzCloudAccountSection(")
            .substringBefore("internal fun CloudAccountContent(")
        assertTrue(section.contains("var activated by remember { mutableStateOf(initiallyExpanded) }"))
        assertTrue(section.indexOf("if (activated)") < section.indexOf("LocalContainer.current"))
        assertTrue(section.indexOf("CloudAccountController(") < section.indexOf("if (expanded)"))
        assertTrue(section.contains("LaunchedEffect(controller) { controller.loadProfile() }"))
        assertTrue(section.contains("if (!expanded) activated = true"))
        assertTrue(section.contains("if (expanded) activeAccount?.let"))
    }

    @Test fun cloudPresentationCannotMutateLocalOrYoutubeIdentity() {
        val cloud = read("ui/screens/VinzCloudAccountScreen.kt")
        listOf("settings.logout(", "settings.saveAccount(", "CookieManager", "account.delete(",
            "account.create(", "bringOver(", "Routes.LOGIN", "rememberSaveable").forEach {
            assertFalse("Cloud presentation must not use $it", cloud.contains(it))
        }
        assertFalse(cloud.contains("fun VinzCloudAccountScreen("))
        assertTrue(cloud.contains("enabled = activeAccount?.second?.busy != true"))
    }
}
