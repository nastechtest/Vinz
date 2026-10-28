package com.prism.music.ui.components

import android.app.Activity
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The system bars as Prism's settings want them (GitHub #115): the status bar hidden when "Hide the status bar" is on (a
 * swipe from the top shows it for a moment), the navigation bar always there. Immersive mode and full-screen video hide
 * both while they're up and come back to this when they close.
 */
object SystemBars {
    fun apply(view: View, hideStatus: Boolean) {
        val window = (view.context as? Activity)?.window ?: return
        apply(WindowCompat.getInsetsController(window, view), hideStatus)
    }

    fun apply(ctl: WindowInsetsControllerCompat?, hideStatus: Boolean) {
        ctl ?: return
        ctl.show(WindowInsetsCompat.Type.systemBars())
        if (hideStatus) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}
