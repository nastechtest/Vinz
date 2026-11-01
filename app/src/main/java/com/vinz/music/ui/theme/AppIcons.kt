package com.vinz.music.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.vinz.music.R

/**
 * The app icons to pick from (#20): each is a launcher activity-alias in the manifest, with its two layers.
 *
 * 1.8 gave every one of them the same drawing - the brand mark from ic_launcher_foreground - so picking an icon
 * changes the ground the mark stands on (ink, paper, gold, the spectrum) and never the mark itself. Two pictures of
 * one app used to read as two apps.
 */
enum class AppIcon(val label: String, val alias: String, val background: Int, val foreground: Int) {
    CLASSIC("Classic", ".MainActivity", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    SPECTRUM("Spectrum", ".IconSpectrum", R.drawable.ic_launcher_bg_spectrum, R.drawable.ic_launcher_foreground),
    SOLID("Solid", ".IconSolid", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    LIGHT("Light", ".IconLight", R.drawable.ic_launcher_bg_light, R.drawable.ic_launcher_foreground),
    WAVE("Wave", ".IconWave", R.drawable.ic_launcher_bg_wave, R.drawable.ic_launcher_foreground),
    MIDNIGHT("Midnight", ".IconMidnight", R.drawable.ic_launcher_bg_midnight, R.drawable.ic_launcher_foreground),
    // 1.7.7.
    DUSK("Dusk", ".IconDusk", R.drawable.ic_launcher_bg_dusk, R.drawable.ic_launcher_foreground),
    PAPER("Paper", ".IconPaper", R.drawable.ic_launcher_bg_paper, R.drawable.ic_launcher_foreground),
    NEON("Neon", ".IconNeon", R.drawable.ic_launcher_bg_neon, R.drawable.ic_launcher_foreground),
    PASTEL("Pastel", ".IconPastel", R.drawable.ic_launcher_bg_pastel, R.drawable.ic_launcher_foreground),
    LINE("Line", ".IconLine", R.drawable.ic_launcher_bg_line, R.drawable.ic_launcher_foreground),
    GOLD("Gold", ".IconGold", R.drawable.ic_launcher_bg_gold, R.drawable.ic_launcher_foreground),
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
