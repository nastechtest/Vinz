package com.vinz.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.vinz.music.ui.theme.AppIcon
import com.vinz.music.ui.theme.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removed launcher previews must stay removed; upgrades restore the default launcher safely. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppIconCrashTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pm get() = context.packageManager
    private fun component(icon: AppIcon) = ComponentName(context.packageName, "com.vinz.music" + icon.alias)

    @Test
    fun anOlderSelectedAliasMigratesBackToClassic() {
        pm.setComponentEnabledSetting(component(AppIcon.PAPER), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(component(AppIcon.CLASSIC), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)

        AppIcons.migrateToClassic(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(component(AppIcon.CLASSIC)))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component(AppIcon.PAPER)))
        assertEquals(AppIcon.CLASSIC, AppIcons.current(context))
    }

    @Test
    fun migrationDisablesEveryEnabledLegacyAliasAndIsIdempotent() {
        AppIcon.entries.filter { it != AppIcon.CLASSIC }.forEach {
            pm.setComponentEnabledSetting(component(it), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }
        AppIcons.migrateToClassic(context)
        AppIcons.migrateToClassic(context)
        AppIcon.entries.filter { it != AppIcon.CLASSIC }.forEach {
            assertEquals(it.name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component(it)))
        }
        assertEquals(AppIcon.CLASSIC, AppIcons.current(context))
    }

    @Test
    fun obsoletePickerCallsCannotSelectAnAlternativeIcon() {
        AppIcons.set(context, AppIcon.NEON)
        assertEquals(AppIcon.CLASSIC, AppIcons.current(context))
        val classicState = pm.getComponentEnabledSetting(component(AppIcon.CLASSIC))
        org.junit.Assert.assertTrue(classicState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || classicState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(component(AppIcon.NEON)))
    }
}
