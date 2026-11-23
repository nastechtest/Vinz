package com.vinz.music

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring guards supplement the controller and offline Compose tests. */
class CloudNavigationIntegrationTest {
    private val source = File("src/main/java/com/vinz/music")
    private fun text(path: String) = File(source, path).readText()

    @Test fun cloudAndLocalRoutesAreDistinctAndReachable() {
        val activity = text("MainActivity.kt")
        val settings = text("ui/screens/SettingsScreen.kt")
        assertTrue(activity.contains("composable(Routes.VINZ_CLOUD_ACCOUNT)"))
        assertTrue(activity.contains("composable(Routes.LOCAL_ACCOUNT)"))
        assertTrue(activity.contains("VinzAccountScreen(onboarding"))
        assertTrue(activity.contains("LocalVinzAccountScreen(false"))
        assertTrue(settings.contains("nav.go(Routes.ACCOUNT)"))
        assertTrue(settings.contains("nav.go(Routes.LOCAL_ACCOUNT)"))
        assertTrue(settings.contains("nav.go(Routes.LOGIN)"))
    }

    @Test fun cloudHostScopesControllerAndClearsSecrets() {
        val host = text("ui/screens/CloudAccountHost.kt")
        assertTrue(host.contains("CloudAccountController(ExistingCloudAccountGateway(cloud), scope)"))
        assertTrue(host.contains("controller.loadProfile()"))
        assertTrue(host.contains("controller.clearPassword()"))
        assertTrue(host.contains("BackHandler(enabled = state.busy)"))
        assertTrue(host.contains("onContinue = if (onboarding) onDone else null"))
    }
}
