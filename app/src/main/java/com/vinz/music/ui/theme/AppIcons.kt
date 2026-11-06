package com.vinz.music.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Legacy aliases remain only for upgrade and Android Auto service compatibility; there is no icon picker. */
enum class AppIcon(val alias: String) {
    CLASSIC(".MainActivity"),
    SPECTRUM(".IconSpectrum"),
    SOLID(".IconSolid"),
    LIGHT(".IconLight"),
    WAVE(".IconWave"),
    MIDNIGHT(".IconMidnight"),
    DUSK(".IconDusk"),
    PAPER(".IconPaper"),
    NEON(".IconNeon"),
    PASTEL(".IconPastel"),
    LINE(".IconLine"),
    GOLD(".IconGold"),
}

/** Restores the default Vinz launcher entry without changing playback-service components. */
object AppIcons {
    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, "com.vinz.music" + icon.alias)

    /** Launcher and Android Auto routing now always use Vinz's shipped icon. */
    fun current(context: Context): AppIcon = AppIcon.CLASSIC

    /**
     * Converts an older icon choice on upgrade. The classic alias is made available before a selected legacy alias is
     * disabled, so the launcher never has no entry. Each component is handled independently: an alias omitted by a
     * future build or a package-manager failure cannot prevent the remaining migration.
     *
     * Alias changes use [PackageManager.DONT_KILL_APP] and deliberately do not touch PlaybackServices: disabling a
     * running legacy media service would stop music mid-playback.
     */
    @Synchronized
    fun migrateToClassic(context: Context) {
        val pm = context.packageManager
        val classic = component(context, AppIcon.CLASSIC)
        val classicReady = runCatching {
            when (pm.getComponentEnabledSetting(classic)) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> Unit // Already enabled by manifest or override.
                else -> pm.setComponentEnabledSetting(
                    classic,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            true
        }.getOrDefault(false)
        if (!classicReady) return

        AppIcon.entries.filter { it != AppIcon.CLASSIC }.forEach { legacy ->
            runCatching {
                val alias = component(context, legacy)
                // Legacy aliases are manifest-disabled, so DEFAULT is already the desired state.
                if (pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    pm.setComponentEnabledSetting(
                        alias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }

    /** Kept only for callers compiled against the old picker; selecting anything now restores Classic. */
    fun set(context: Context, icon: AppIcon) = migrateToClassic(context)
}
