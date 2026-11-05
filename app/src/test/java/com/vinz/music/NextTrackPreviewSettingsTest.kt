package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NextTrackPreviewSettingsTest {
    private fun repo() = SettingsRepository(
        org.robolectric.RuntimeEnvironment.getApplication(),
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    )

    @Test fun defaultPreservesTheExistingPlayer() {
        assertFalse(AppSettings().showNextTrack)
    }
    @Test fun thePreferencePersistsAndCanBeDisabled(): Unit = runBlocking {
        val repo = repo()
        repo.setShowNextTrack(true)
        withTimeout(5000) { repo.flow.first { it.showNextTrack } }
        repo.setShowNextTrack(false)
        withTimeout(5000) { repo.flow.first { !it.showNextTrack } }
    }
    @Test fun looksKnowItsBooleanType() {
        assertEquals('b', repo().lookKinds["show_next_track"])
    }
    @Test fun importedLooksCanEnableItAndOldLooksKeepItOff() {
        val repo = repo()
        assertTrue(repo.previewLook(mapOf("show_next_track" to "b:1")).showNextTrack)
        assertFalse(repo.previewLook(emptyMap()).showNextTrack)
    }
}
