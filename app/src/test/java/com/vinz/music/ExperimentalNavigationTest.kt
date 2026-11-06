package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.Corners
import com.vinz.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The split navigation switch is an explicit preference, never a Look choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExperimentalNavigationTest {
    private fun repository() = SettingsRepository(
        RuntimeEnvironment.getApplication(),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun freshSettingsKeepExperimentalSplitNavigationOff() {
        assertFalse(AppSettings().experimentalSplitNavigation)
    }

    @Test
    fun experimentalSplitNavigationPersistsInBothDirections(): Unit = runBlocking {
        val repo = repository()

        repo.setExperimentalSplitNavigation(true)
        assertTrue(withTimeout(5_000) {
            repo.flow.first { it.experimentalSplitNavigation }.experimentalSplitNavigation
        })

        repo.setExperimentalSplitNavigation(false)
        assertFalse(withTimeout(5_000) {
            repo.flow.first { !it.experimentalSplitNavigation }.experimentalSplitNavigation
        })
    }

    @Test
    fun experimentalSplitNavigationIsNotALookOrResetSetting() {
        val repo = repository()

        assertTrue("The opt-in is explicitly excluded from Looks", "experimental_split_navigation" in repo.notLookNames)
        assertFalse("A Look must not carry the opt-in", "experimental_split_navigation" in repo.lookKinds)
        assertFalse("Reset Look must not clear the opt-in", "experimental_split_navigation" in repo.resetNames)
    }

    @Test
    fun applyingAndResettingLooksLeaveExperimentalSplitNavigationAlone(): Unit = runBlocking {
        val repo = repository()
        // This uses SettingsRepository directly, avoiding LookStore's independent restore timeout.
        repo.setExperimentalSplitNavigation(false)
        withTimeout(5_000) { repo.flow.first { !it.experimentalSplitNavigation } }

        repo.applyLook(mapOf("corners" to "s:SHARP"))
        assertFalse(withTimeout(5_000) {
            repo.flow.first { it.corners == Corners.SHARP && !it.experimentalSplitNavigation }.experimentalSplitNavigation
        })
        repo.resetLook()
        assertFalse(withTimeout(5_000) {
            repo.flow.first {
                it.corners == AppSettings().corners && !it.experimentalSplitNavigation
            }.experimentalSplitNavigation
        })

        repo.setExperimentalSplitNavigation(true)
        withTimeout(5_000) { repo.flow.first { it.experimentalSplitNavigation } }
        repo.applyLook(mapOf("corners" to "s:SHARP"))
        assertTrue(withTimeout(5_000) {
            repo.flow.first { it.corners == Corners.SHARP && it.experimentalSplitNavigation }.experimentalSplitNavigation
        })
        repo.resetLook()
        assertTrue(withTimeout(5_000) {
            repo.flow.first {
                it.corners == AppSettings().corners && it.experimentalSplitNavigation
            }.experimentalSplitNavigation
        })

        // Shared Robolectric's conventional app DataStore is left in the default state for other tests.
        repo.setExperimentalSplitNavigation(false)
        withTimeout(5_000) { repo.flow.first { !it.experimentalSplitNavigation } }
    }
}
