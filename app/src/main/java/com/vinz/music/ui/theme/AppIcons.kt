package com.vinz.music.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import com.vinz.music.R
import com.vinz.music.tr

/** The app icons to pick from (#20): each is a launcher activity-alias in the manifest, with its two layers. */
enum class AppIcon(val label: String, val alias: String, val background: Int, val foreground: Int) {
    CLASSIC(tr(R.string.classic), ".MainActivity", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    SPECTRUM(tr(R.string.spectrum), ".IconSpectrum", R.drawable.ic_launcher_bg_spectrum, R.drawable.ic_launcher_foreground),
    SOLID(tr(R.string.solid), ".IconSolid", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    LIGHT(tr(R.string.light), ".IconLight", R.drawable.ic_launcher_bg_light, R.drawable.ic_launcher_foreground),
    WAVE(tr(R.string.wave), ".IconWave", R.drawable.ic_launcher_bg_wave, R.drawable.ic_launcher_foreground),
    MIDNIGHT(tr(R.string.midnight), ".IconMidnight", R.drawable.ic_launcher_bg_midnight, R.drawable.ic_launcher_foreground),
    // 1.7.7.
    DUSK(tr(R.string.dusk), ".IconDusk", R.drawable.ic_launcher_bg_dusk, R.drawable.ic_launcher_foreground),
    PAPER(tr(R.string.paper), ".IconPaper", R.drawable.ic_launcher_bg_paper, R.drawable.ic_launcher_foreground),
    NEON(tr(R.string.neon), ".IconNeon", R.drawable.ic_launcher_bg_neon, R.drawable.ic_launcher_foreground),
    PASTEL(tr(R.string.pastel), ".IconPastel", R.drawable.ic_launcher_bg_pastel, R.drawable.ic_launcher_foreground),
    LINE(tr(R.string.line), ".IconLine", R.drawable.ic_launcher_bg_line, R.drawable.ic_launcher_foreground),
    GOLD(tr(R.string.gold), ".IconGold", R.drawable.ic_launcher_bg_gold, R.drawable.ic_launcher_foreground),
}

/**
 * Switches the launcher icon by enabling one alias and disabling the rest. Alias names are in the code's namespace
 * (com.vinz.music), which stays the same when the app id doesn't (the side-by-side test copy).
 */
object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "com.vinz.music" + icon.alias)

    fun current(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { icon ->
            when (pm.getComponentEnabledSetting(component(context, icon))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.CLASSIC
                else -> false
            }
        } ?: AppIcon.CLASSIC
    }

    fun set(context: Context, icon: AppIcon) {
        val pm = context.packageManager
        // The new one on first, so there's never a moment with no launcher entry at all.
        pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}

/**
 * Draws the actual adaptive-icon background and foreground resources in a single Compose Canvas. Using the existing
 * Android Drawables preserves vector and layer-list artwork without creating child Views for each gallery layer.
 */
@Composable
fun AppIconPreview(icon: AppIcon, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val background = remember(context, icon.background) {
        ContextCompat.getDrawable(context, icon.background)?.mutate()
    }
    val foreground = remember(context, icon.foreground) {
        ContextCompat.getDrawable(context, icon.foreground)?.mutate()
    }

    Canvas(modifier) {
        val side = size.minDimension * 1.5f
        val left = ((size.width - side) / 2f).toInt()
        val top = ((size.height - side) / 2f).toInt()
        val bounds = IntOffset(left, top)
        val iconSize = IntSize(side.toInt(), side.toInt())
        val canvas = drawContext.canvas.nativeCanvas
        val saveCount = canvas.save()
        try {
            background?.let { drawable ->
                drawable.setBounds(bounds.x, bounds.y, bounds.x + iconSize.width, bounds.y + iconSize.height)
                drawable.draw(canvas)
            }
            foreground?.let { drawable ->
                drawable.setBounds(bounds.x, bounds.y, bounds.x + iconSize.width, bounds.y + iconSize.height)
                drawable.draw(canvas)
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
}
