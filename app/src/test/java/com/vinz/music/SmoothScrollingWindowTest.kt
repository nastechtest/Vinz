package com.vinz.music

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vinz.music.ui.SmoothScrollingEffect
import com.vinz.music.ui.displayOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

/** Adapted from Prism #336: exercise the window request rather than just the mode-selection rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h2000dp-xhdpi", application = android.app.Application::class)
class SmoothScrollingWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun windowFollowsSwitchAndBatterySaver() {
        val display = displayOf(compose.activity)!!
        val mode = display.mode
        val modes = listOf(60f, 90f, 120f).mapIndexed { i, hz ->
            ShadowDisplayManager.ModeBuilder.modeBuilder(mode.modeId + i)
                .setWidth(mode.physicalWidth).setHeight(mode.physicalHeight).setRefreshRate(hz).build()
        }
        ShadowDisplayManager.setSupportedModes(0, *modes.toTypedArray())
        val wanted = display.supportedModes.filter {
            it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
        }.maxByOrNull { it.refreshRate }!!
        assertTrue(wanted.refreshRate > 100f)
        val on = mutableStateOf(false)
        compose.setContent { SmoothScrollingEffect(on.value) }
        compose.waitForIdle()
        fun requested() = compose.activity.window.attributes.preferredDisplayModeId
        assertEquals(0, requested())
        compose.runOnIdle { on.value = true }
        compose.waitForIdle()
        assertEquals(wanted.modeId, requested())

        val app = RuntimeEnvironment.getApplication()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            shadowOf(power).setIsPowerSaveMode(true)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            assertEquals(0, requested())
            shadowOf(power).setIsPowerSaveMode(false)
            app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            assertEquals(wanted.modeId, requested())
            compose.runOnIdle { on.value = false }
            compose.waitForIdle()
            assertEquals(0, requested())
        } finally {
            shadowOf(power).setIsPowerSaveMode(false)
        }
    }
}
