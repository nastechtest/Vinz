package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.data.prefs.SettingsRepository
import com.vinz.music.ui.DisplayModeInfo
import com.vinz.music.ui.fastestModeId
import com.vinz.music.ui.hasFasterMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmoothScrollingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var repository: SettingsRepository? = null
    private fun repo() = repository ?: SettingsRepository(RuntimeEnvironment.getApplication(), scope).also { repository = it }

    @After fun clean() = runBlocking {
        try {
            repository?.let { r ->
                r.setPerformanceMode(false)
                r.setSmoothScrolling(false)
                withTimeout(5000) { r.stored.first { !it.behavior.smoothScrolling && !it.performanceMode } }
            }
        } finally { scope.cancel() }
    }

    private val modes = listOf(
        DisplayModeInfo(1, 1080, 2400, 60f), DisplayModeInfo(2, 1080, 2400, 90f),
        DisplayModeInfo(3, 1080, 2400, 120f), DisplayModeInfo(4, 720, 1600, 144f),
    )

    @Test fun fastestAtCurrentResolutionAndStableAfterGrant() {
        assertEquals(3, fastestModeId(modes, 1, true))
        assertEquals(3, fastestModeId(modes, 3, true))
        assertTrue(hasFasterMode(modes, 1))
        assertEquals(0, fastestModeId(modes, 4, true))
        assertEquals(4, fastestModeId(modes + DisplayModeInfo(5, 720, 1600, 60f), 5, true))
    }

    @Test fun offUnknownOrSingleSpeedLeavesSystemInControl() {
        assertEquals(0, fastestModeId(modes, 1, false))
        assertEquals(0, fastestModeId(modes, 99, true))
        assertEquals(0, fastestModeId(emptyList(), 0, true))
        val similarRates = listOf(DisplayModeInfo(1, 1080, 2400, 59.94f), DisplayModeInfo(2, 1080, 2400, 60f), modes.last())
        assertFalse(hasFasterMode(similarRates, 1))
        assertEquals(0, fastestModeId(similarRates, 2, true))
    }

    @Test fun defaultAndPerformanceMaskPreserveChoice() {
        assertFalse(BehaviorPrefs().smoothScrolling)
        val on = AppSettings(behavior = BehaviorPrefs(smoothScrolling = true))
        assertTrue(on.effective().behavior.smoothScrolling)
        val light = on.copy(performanceMode = true)
        assertFalse(light.effective().behavior.smoothScrolling)
        assertTrue(light.behavior.smoothScrolling)
        assertTrue(light.copy(performanceMode = false).effective().behavior.smoothScrolling)
    }

    @Test fun preferenceIsStoredAndExcludedFromLooks() = runBlocking {
        val r = repo()
        assertTrue("smooth_scrolling" in r.allKeyNames)
        assertTrue("smooth_scrolling" in r.notLookNames)
        assertFalse("smooth_scrolling" in r.lookKinds)
        assertFalse("smooth_scrolling" in r.resetNames)
        r.setSmoothScrolling(true)
        withTimeout(5000) { r.stored.first { it.behavior.smoothScrolling } }
        r.setPerformanceMode(true)
        withTimeout(5000) { r.flow.first { it.performanceMode && !it.behavior.smoothScrolling } }
        assertTrue(r.stored.value.behavior.smoothScrolling)
        r.setPerformanceMode(false)
        withTimeout(5000) { r.flow.first { !it.performanceMode && it.behavior.smoothScrolling } }
        r.setSmoothScrolling(false)
        withTimeout(5000) { r.stored.first { !it.behavior.smoothScrolling } }
    }
}
