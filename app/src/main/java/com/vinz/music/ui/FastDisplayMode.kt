package com.vinz.music.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

@Suppress("DEPRECATION")
internal fun displayOf(activity: Activity): Display? =
    (if (Build.VERSION.SDK_INT >= 30) runCatching { activity.display }.getOrNull() else null)
        ?: runCatching { activity.windowManager.defaultDisplay }.getOrNull()

internal fun displayModes(display: Display?): Pair<List<DisplayModeInfo>, Int> {
    if (display == null) return emptyList<DisplayModeInfo>() to 0
    return display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) } to display.mode.modeId
}

/** Re-evaluate if the display changes, including an external display or resolution change. */
@Composable
private fun rememberModes(): Pair<List<DisplayModeInfo>, Int> {
    val view = LocalView.current
    val activity = view.context as? Activity
    val config = LocalConfiguration.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val manager = activity?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { revision++ }
            override fun onDisplayRemoved(displayId: Int) { revision++ }
            override fun onDisplayChanged(displayId: Int) { revision++ }
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return remember(activity, config, revision) {
        runCatching { displayModes(activity?.let(::displayOf)) }.getOrDefault(emptyList<DisplayModeInfo>() to 0)
    }
}

@Composable
internal fun rememberFastScreen(): Boolean = rememberModes().let { (modes, current) -> hasFasterMode(modes, current) }

/** This is used only by the phone activity. Performance mode already masks [on]. */
@Composable
fun SmoothScrollingEffect(on: Boolean) {
    val view = LocalView.current
    val activity = view.context as? Activity
    val context = view.context.applicationContext
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember(power) { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = power.isPowerSaveMode }
        }
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        powerSave = power.isPowerSaveMode
        onDispose { context.unregisterReceiver(receiver) }
    }
    val (modes, current) = rememberModes()
    LaunchedEffect(activity, on, powerSave, modes, current) {
        val window = activity?.window ?: return@LaunchedEffect
        val id = fastestModeId(modes, current, on && !powerSave)
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != id) {
            attrs.preferredDisplayModeId = id
            window.attributes = attrs
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                val attrs = window.attributes
                if (attrs.preferredDisplayModeId != 0) {
                    attrs.preferredDisplayModeId = 0
                    window.attributes = attrs
                }
            }
        }
    }
}
