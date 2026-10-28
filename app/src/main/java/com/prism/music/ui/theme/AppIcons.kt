package com.prism.music.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.prism.music.R

/** The app icons to pick from (#20): each is a launcher activity-alias in the manifest, with its two layers. */
enum class AppIcon(val label: String, val alias: String, val background: Int, val foreground: Int) {
    CLASSIC("Classic", ".MainActivity", R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    SPECTRUM("Spectrum", ".IconSpectrum", R.drawable.ic_launcher_bg_spectrum, R.drawable.ic_launcher_fg_white),
    SOLID("Solid", ".IconSolid", R.drawable.ic_launcher_background, R.drawable.ic_launcher_fg_solid),
    LIGHT("Light", ".IconLight", R.drawable.ic_launcher_bg_light, R.drawable.ic_launcher_fg_dark),
    WAVE("Wave", ".IconWave", R.drawable.ic_launcher_bg_wave, R.drawable.ic_launcher_fg_wave),
    MIDNIGHT("Midnight", ".IconMidnight", R.drawable.ic_launcher_bg_midnight, R.drawable.ic_launcher_fg_midnight),
    // 1.7.7.
    DUSK("Dusk", ".IconDusk", R.drawable.ic_launcher_bg_dusk, R.drawable.ic_launcher_fg_white),
    PAPER("Paper", ".IconPaper", R.drawable.ic_launcher_bg_paper, R.drawable.ic_launcher_fg_paper),
    NEON("Neon", ".IconNeon", R.drawable.ic_launcher_bg_neon, R.drawable.ic_launcher_fg_neon),
    PASTEL("Pastel", ".IconPastel", R.drawable.ic_launcher_bg_pastel, R.drawable.ic_launcher_fg_pastel),
    LINE("Line", ".IconLine", R.drawable.ic_launcher_bg_line, R.drawable.ic_launcher_fg_line),
    GOLD("Gold", ".IconGold", R.drawable.ic_launcher_bg_gold, R.drawable.ic_launcher_fg_gold),
}

/**
 * Switches the launcher icon by enabling one alias and disabling the rest. Alias names are in the code's namespace
 * (com.prism.music), which stays the same when the app id doesn't (the side-by-side test copy).
 */
object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "com.prism.music" + icon.alias)

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
