package com.vinz.music

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.8.7, "Smoother scrolling" (#336) on a real screen: the unit tests hand the root "this screen is fast" and never ask
 * a screen. Here the device is asked: where it offers apps a faster mode the switch is there, where it doesn't it isn't.
 * The Galaxy A16 (90 Hz) is of the second kind: it tells apps of one mode only, 90 Hz, which Vinz already draws at
 * (1.8.7 release check's settings walk).
 */
@RunWith(AndroidJUnit4::class)
class SmoothScrollingAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun start() {
        Fixture.ready()
        Fixture.launch()
    }

    private fun screenIsFast(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val now = display.mode
        // What this device tells an app, for the test's log (the A16 lists one mode, 90 Hz, and no alternative rates).
        android.util.Log.i("SmoothScrollingAppTest", "now ${now.modeId} ${now.refreshRate} Hz; modes " +
            display.supportedModes.joinToString { m ->
                "${m.modeId}:${m.physicalWidth}x${m.physicalHeight}@${m.refreshRate}" +
                    (if (android.os.Build.VERSION.SDK_INT >= 31) " alt=" + m.alternativeRefreshRates.joinToString("/") else "")
            })
        val same = display.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
        return same.maxOf { it.refreshRate } - same.minOf { it.refreshRate } >= 1f
    }

    @Test fun theSwitchIsOnTheRootOfAFastScreen() {
        if (!screenIsFast()) return   // nothing to check here; an assumption shows as a failure in the suite's report
        compose.openSettings()
        compose.waitForText("Performance mode")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Smoother scrolling")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aScreenWithOneSpeedIsNotOfferedIt() {
        if (screenIsFast()) return
        compose.openSettings()
        compose.waitForText("Performance mode")
        assertEquals(0, compose.onAllNodes(hasText("Smoother scrolling")).fetchSemanticsNodes().size)
    }
}
