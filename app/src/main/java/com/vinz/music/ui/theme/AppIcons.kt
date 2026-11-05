package com.vinz.music.ui.theme

import com.vinz.music.tr
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vinz.music.R

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

/** Android launcher layers can be XML layer-lists, which Compose painterResource doesn't support. */
@Composable
fun AppIconLayer(resource: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> ImageView(context).apply { setImageResource(resource); tag = resource } },
        update = { view ->
            if (view.tag != resource) {
                view.setImageResource(resource)
                view.tag = resource
            }
        },
        modifier = modifier,
    )
}
