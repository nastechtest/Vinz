package com.vinz.music

import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.5's new stored settings: the monthly recap's notice, the milestone notice, "Finish the lyric", pausing face
 * down and the small cover's tap over the lyrics (#464). All off until the listener turns them on, each comes back from the store, none travels in a Look (they're how
 * Vinz behaves), all travel in a backup.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class Settings185Test {
    private val names = setOf("recap_notify", "milestone_notify", "finish_lyric", "face_down_pause", "lyrics_cover_plays")

    private fun withStore(block: suspend (SettingsRepository) -> Unit): Unit = runBlocking {
        val r = SettingsRepository(org.robolectric.RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        r.editNow { it.clear() }
        try { block(r) } finally { r.editNow { it.clear() } }
    }

    private suspend fun SettingsRepository.storedWhen(until: (AppSettings) -> Boolean): AppSettings = withTimeout(5_000) { stored.first(until) }

    @Test
    fun allOffByDefault() {
        val b = AppSettings().behavior
        assertFalse(b.recapNotify)
        assertFalse(b.milestoneNotify)
        assertFalse(b.finishLyric)
        assertFalse(b.faceDownPause)
        assertFalse(b.lyricsCoverPlays)
    }

    @Test
    fun eachSetterComesBackFromTheStore() = withStore { r ->
        assertEquals(AppSettings().behavior, r.storedWhen { true }.behavior)
        r.setRecapNotify(true)
        r.setMilestoneNotify(true)
        r.setFinishLyric(true)
        r.setFaceDownPause(true)
        r.setLyricsCoverPlays(true)
        val s = r.storedWhen { it.behavior.recapNotify && it.behavior.milestoneNotify && it.behavior.finishLyric && it.behavior.faceDownPause && it.behavior.lyricsCoverPlays }
        assertTrue(s.behavior.faceDownPause)
        r.setFaceDownPause(false)
        assertTrue("the others stay", r.storedWhen { !it.behavior.faceDownPause }.behavior.recapNotify)
    }

    @Test
    fun noneTravelsInALookAllInABackup() = withStore { r ->
        assertTrue(r.allKeyNames.containsAll(names))
        assertTrue(r.notLookNames.containsAll(names))
        assertTrue((r.lookKinds.keys intersect names).isEmpty())
        r.setRecapNotify(true)
        r.setMilestoneNotify(true)
        r.setFinishLyric(true)
        r.setFaceDownPause(true)
        r.setLyricsCoverPlays(true)
        r.storedWhen { it.behavior.faceDownPause && it.behavior.recapNotify && it.behavior.milestoneNotify && it.behavior.finishLyric && it.behavior.lyricsCoverPlays }
        val backup = r.forBackup(settings = true, accounts = false)
        assertEquals(names.associateWith { "b:true" }, backup.filterKeys { it in names })
        // A Look applied over them leaves them as they are.
        r.applyLook(emptyMap())
        assertTrue(r.storedWhen { true }.behavior.faceDownPause)
    }
}
