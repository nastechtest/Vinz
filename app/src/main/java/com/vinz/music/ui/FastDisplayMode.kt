package com.vinz.music.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.vinz.music.ui.player.rememberPhoneStrain

/** One display mode of the screen, as [Display.Mode] gives it (plain values, so the choice can be tested). */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * #336 (1.8.7), "Smoother scrolling": the display mode to ask the window for ([android.view.WindowManager.LayoutParams.preferredDisplayModeId]).
 *
 * - 0 ("the system decides") when it isn't [wanted], when the current mode isn't among [modes], or when the screen has
 *   no faster mode than its slowest at the current resolution (a 60 Hz screen).
 * - Otherwise the mode with the highest refresh rate at the CURRENT mode's resolution, also when that is the current
 *   mode already: once asked for, the request stays the same (it doesn't flip back to 0 because it was granted). A mode
 *   of another resolution is never taken, however fast.
 */
internal fun fastestModeId(modes: List<DisplayModeInfo>, currentId: Int, wanted: Boolean): Int {
    if (!wanted) return 0
    val current = modes.firstOrNull { it.id == currentId } ?: return 0
    val same = modes.filter { it.width == current.width && it.height == current.height }
    val fastest = same.maxByOrNull { it.refreshRate } ?: return 0
    val slowest = same.minOf { it.refreshRate }
    // Rates differ by fractions (59.94 / 60.0): under 1 Hz apart is the same speed.
    return if (fastest.refreshRate - slowest >= 1f) fastest.id else 0
}

/** The screen has a faster mode than its slowest at the current resolution (90 or 120 Hz): the switch can do something. */
internal fun hasFasterMode(modes: List<DisplayModeInfo>, currentId: Int): Boolean = fastestModeId(modes, currentId, wanted = true) != 0

/** The modes of [display] and the id of the one it is in now. */
internal fun displayModes(display: Display?): Pair<List<DisplayModeInfo>, Int> {
    if (display == null) return emptyList<DisplayModeInfo>() to 0
    val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
    return modes to display.mode.modeId
}

/** The display [activity]'s window is on. */
@Suppress("DEPRECATION")
internal fun displayOf(activity: Activity): Display? =
    (if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull() else null)
        ?: runCatching { activity.windowManager.defaultDisplay }.getOrNull()

/**
 * The phone activity's window asks for the screen's fastest mode while [on] (the setting, as Performance mode leaves
 * it: off there) and battery saver is off; otherwise 0, the system's own choice. Not used on the TV.
 */
@Composable
fun SmoothScrollingEffect(on: Boolean) {
    val view = LocalView.current
    val powerSave by rememberPhoneStrain()
    // A new resolution or a move to another screen comes with a new configuration: choose again.
    val config = LocalConfiguration.current
    LaunchedEffect(on, powerSave.powerSave, config) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        val window = activity.window ?: return@LaunchedEffect
        val wanted = on && !powerSave.powerSave
        val id = if (!wanted) 0 else displayModes(displayOf(activity)).let { (modes, current) -> fastestModeId(modes, current, true) }
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != id) {
            attrs.preferredDisplayModeId = id
            window.attributes = attrs
        }
    }
}
