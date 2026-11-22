package com.vinz.music

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.BehaviorPrefs
import com.vinz.music.ui.DisplayModeInfo
import com.vinz.music.ui.SmoothScrollingEffect
import com.vinz.music.ui.displayOf
import com.vinz.music.ui.fastestModeId
import com.vinz.music.ui.hasFasterMode
import com.vinz.music.ui.screens.settings.SettingsRootContent
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.rootIndex
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/**
 * #336 (1.8.7), "Smoother scrolling": nothing in Vinz asked for the screen's fast mode, so some 120 Hz phones ran it at
 * 60. The switch (off by default, on the root's Performance card, the phone's own matter and not part of a Look) has the
 * phone activity's window ask for the fastest mode at the current resolution; Performance mode and battery saver read it
 * as off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class SmoothScrollingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private val prefs get() = container.settings
    private val stored get() = container.settings.stored.value

    @Before fun fresh() = runBlocking { container.settings.editNow { it.clear() } }
    @After fun close() { runBlocking { container.settings.editNow { it.clear() } }; made.closeAfterTest() }

    // ------------------------------------------------------------------ The choice

    private fun mode(id: Int, w: Int, h: Int, hz: Float) = DisplayModeInfo(id, w, h, hz)
    private val panel = listOf(mode(1, 1080, 2400, 60f), mode(2, 1080, 2400, 90f), mode(3, 1080, 2400, 120f))

    @Test fun theFastestModeAtTheCurrentResolutionIsAskedFor() {
        assertEquals(3, fastestModeId(panel, currentId = 1, wanted = true))
        // Already granted: the request stays the same (it doesn't flip back to 0).
        assertEquals(3, fastestModeId(panel, currentId = 3, wanted = true))
        assertTrue(hasFasterMode(panel, 1))
    }

    @Test fun aFasterModeAtAnotherResolutionIsNotTaken() {
        val modes = panel + listOf(mode(4, 720, 1600, 144f), mode(5, 1440, 3200, 165f))
        assertEquals(3, fastestModeId(modes, currentId = 1, wanted = true))
        // At the lower resolution, only its own modes count: 144 Hz there.
        assertEquals(4, fastestModeId(modes + mode(6, 720, 1600, 60f), currentId = 6, wanted = true))
    }

    @Test fun aSixtyHertzScreenIsLeftToTheSystem() {
        val only60 = listOf(mode(1, 1080, 2400, 60f))
        assertEquals(0, fastestModeId(only60, currentId = 1, wanted = true))
        assertFalse(hasFasterMode(only60, 1))
        // 59.94 and 60 are the same speed; a faster mode at another resolution doesn't make this one fast.
        assertEquals(0, fastestModeId(listOf(mode(1, 1080, 2400, 59.94f), mode(2, 1080, 2400, 60f), mode(3, 720, 1600, 120f)), 2, true))
        // A current mode that isn't listed: nothing asked.
        assertEquals(0, fastestModeId(panel, currentId = 9, wanted = true))
    }

    @Test fun switchedOffTheSystemDecides() {
        assertEquals(0, fastestModeId(panel, currentId = 1, wanted = false))
        assertEquals(0, fastestModeId(emptyList(), currentId = 0, wanted = true))
    }

    // ------------------------------------------------------------------ The setting

    @Test fun offByDefaultStoredAndNotPartOfALook() {
        assertFalse(AppSettings().behavior.smoothScrolling)
        assertFalse(BehaviorPrefs().smoothScrolling)
        assertFalse(stored.behavior.smoothScrolling)
        prefs.setSmoothScrolling(true)
        compose.waitUntil(10_000) { stored.behavior.smoothScrolling }
        assertTrue(container.settings.flow.value.behavior.smoothScrolling)
        prefs.setSmoothScrolling(false)
        compose.waitUntil(10_000) { !stored.behavior.smoothScrolling }
        assertTrue("smooth_scrolling" in prefs.allKeyNames)
        assertTrue("smooth_scrolling" in prefs.notLookNames)
        assertFalse("smooth_scrolling" in prefs.lookKinds.keys)
    }

    @Test fun performanceModeReadsItAsOff() {
        val on = AppSettings(behavior = BehaviorPrefs(smoothScrolling = true))
        assertTrue(on.effective().behavior.smoothScrolling)
        val light = on.copy(performanceMode = true)
        assertFalse(light.effective().behavior.smoothScrolling)
        // What is stored stays.
        assertTrue(light.behavior.smoothScrolling)
    }

    // ------------------------------------------------------------------ The window's request

    private val fastPanel by lazy {
        // modeBuilder's argument is the mode's id; the display is in mode 0 (the 60 Hz one here), at its own resolution.
        val d = displayOf(compose.activity)!!.mode
        listOf(60f, 90f, 120f).mapIndexed { i, hz -> ShadowDisplayManager.ModeBuilder.modeBuilder(d.modeId + i).setWidth(d.physicalWidth).setHeight(d.physicalHeight).setRefreshRate(hz).build() } +
            ShadowDisplayManager.ModeBuilder.modeBuilder(4).setWidth(d.physicalWidth / 2).setHeight(d.physicalHeight / 2).setRefreshRate(144f).build()
    }

    private fun requested() = compose.activity.window.attributes.preferredDisplayModeId

    @Test fun theWindowFollowsTheSwitchAndBatterySaver() {
        ShadowDisplayManager.setSupportedModes(0, *fastPanel.toTypedArray())
        val display = displayOf(compose.activity)!!
        val current = display.mode
        val want = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }.maxByOrNull { it.refreshRate }!!
        assertTrue("the test's panel has a faster mode at the current resolution ($current)", want.refreshRate > 100f && want.physicalWidth == current.physicalWidth)

        val on = mutableStateOf(false)
        compose.setContent { SmoothScrollingEffect(on.value) }
        compose.waitForIdle()
        assertEquals("off: the system decides", 0, requested())

        on.value = true
        compose.waitForIdle()
        assertEquals("on: the fastest mode at the current resolution", want.modeId, requested())

        // Battery saver: not asked.
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(true)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals("battery saver: the system decides", 0, requested())
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsPowerSaveMode(false)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
        assertEquals(want.modeId, requested())

        on.value = false
        compose.waitForIdle()
        assertEquals("switched off: back to 0", 0, requested())
    }

    // ------------------------------------------------------------------ The row (the root's Performance card)

    private fun showRoot(fastScreen: Boolean) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            VinzTheme(s, artworkColor = null) {
                CompositionLocalProvider(LocalContainer provides container, LocalAppSettings provides s) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SettingsRootContent(
                            0.dp, s, dark = true, showBack = false, selected = null, account = {}, onPerformance = {},
                            onOpen = { _, _ -> }, onRoute = {}, fastScreen = fastScreen, onSmoothScrolling = { prefs.setSmoothScrolling(it) },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val title = "Smoother scrolling"
    private fun rowCount() = compose.onAllNodes(hasTestTag(SettingsTags.ROW_SWITCH) and hasAnyDescendant(hasText(title)), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun theRowIsOnThePerformanceCardAndTogglesTheSetting() {
        showRoot(fastScreen = true)
        assertEquals(1, rowCount())
        // Its explanation is only behind the ⓘ.
        val info = "Asks the screen for its fastest refresh rate while Vinz is open"
        assertTrue(compose.onAllNodes(hasText(info, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasContentDescription("More about $title"), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(info, substring = true, useUnmergedTree = true).assertExists()

        compose.onNode(hasTestTag(SettingsTags.ROW_SWITCH) and hasAnyDescendant(hasText(title)), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { stored.behavior.smoothScrolling }
        compose.onNode(hasTestTag(SettingsTags.ROW_SWITCH) and hasAnyDescendant(hasText(title)), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { !stored.behavior.smoothScrolling }
        // Settings' search finds it on the root.
        assertTrue(rootIndex.any { it.title == title && it.page == null })
    }

    @Test fun aSixtyHertzScreenLeavesTheRowOutUnlessItIsOn() {
        showRoot(fastScreen = false)
        assertEquals(0, rowCount())
        // Switched on elsewhere (a backup from a 120 Hz phone): shown, so it can be turned off.
        prefs.setSmoothScrolling(true)
        compose.waitUntil(10_000) { rowCount() == 1 }
    }
}
