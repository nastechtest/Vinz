package com.vinz.music

import com.vinz.music.data.Backup
import com.vinz.music.data.BackupPart
import com.vinz.music.ui.support.SupportAsks
import com.vinz.music.ui.support.SupportAsks.Ask
import com.vinz.music.ui.support.SupportMarks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 1.8.7.6, Support Vinz (plan-1.8.7.6 §4.2): every rule of when an ask may show, on a fake clock, with no screen; the
 * marks kept on the phone; and a backup carrying the marks and the switch so a restored phone doesn't ask again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SupportAsksTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun at(day: Int, hour: Int = 12, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val day = 86_400_000L
    private val installed = at(1)

    /** Vinz set up, installed long enough ago, 100 hours heard, nothing shown yet: every ask is due. */
    private fun ready(now: Long = at(20)) = SupportAsks.State(
        now = now, installedAt = installed, onboarded = true, supported = false, listenedMs = SupportAsks.HOURS_MS, zone = zone,
    )

    private val all = listOf(SupportAsks.HOME, SupportAsks.REPLAY, SupportAsks.REWIND, SupportAsks.whatsNew(67))

    @After fun clear() {
        SupportMarks.testNow = null
        SupportMarks.testInstalledAt = null
        RuntimeEnvironment.getApplication().getSharedPreferences(SupportMarks.PREFS, 0).edit().clear().commit()
    }

    @Test fun readyEveryAskIsDue() {
        for (a in all) assertTrue(a.key, SupportAsks.due(a, ready()))
    }

    @Test fun neverWithTheSwitchOn() {
        for (a in all) assertFalse(a.key, SupportAsks.due(a, ready().copy(supported = true)))
    }

    @Test fun neverOnATv() {
        for (a in all) assertFalse(a.key, SupportAsks.due(a, ready().copy(tv = true)))
    }

    @Test fun neverBeforeTheFirstRunIsFinished() {
        for (a in all) assertFalse(a.key, SupportAsks.due(a, ready().copy(onboarded = false)))
    }

    @Test fun notInTheFirstSevenDays() {
        for (a in all) {
            assertFalse(a.key, SupportAsks.due(a, ready(now = installed)))
            assertFalse(a.key, SupportAsks.due(a, ready(now = installed + 7 * day - 1)))
            assertTrue(a.key, SupportAsks.due(a, ready(now = installed + 7 * day)))
        }
    }

    @Test fun eachAskOnce() {
        for (a in all) {
            val shown = ready().copy(shown = setOf(a.key))
            assertFalse(a.key, SupportAsks.due(a, shown))
            // Its mark is its own: the others are still due.
            for (b in all - a) assertTrue("${b.key} after ${a.key}", SupportAsks.due(b, shown))
        }
        // A monthly Replay's and the year's Rewind's are two marks; each release's What's new line is its own.
        assertTrue(SupportAsks.due(SupportAsks.REWIND, ready().copy(shown = setOf(SupportAsks.REPLAY.key))))
        assertTrue(SupportAsks.due(SupportAsks.whatsNew(68), ready().copy(shown = setOf(SupportAsks.whatsNew(67).key))))
        assertEquals(4, all.map { it.key }.toSet().size)
    }

    @Test fun neverTwoAsksOnOneDay() {
        // What's new showed its line this morning: the 100-hour card waits for tomorrow.
        val morning = at(20, 8)
        val s = ready(now = at(20, 23, 59)).copy(lastAskKey = SupportAsks.whatsNew(67).key, lastAskAt = morning, shown = setOf(SupportAsks.whatsNew(67).key))
        assertFalse(SupportAsks.due(SupportAsks.HOME, s))
        assertFalse(SupportAsks.due(SupportAsks.REPLAY, s))
        assertTrue("tomorrow", SupportAsks.due(SupportAsks.HOME, s.copy(now = at(21, 0, 0))))
        // The Home card seen this morning and not answered may stay on today (it is the same ask).
        val seen = ready(now = at(20, 18)).copy(lastAskKey = SupportAsks.HOME.key, lastAskAt = morning)
        assertTrue(SupportAsks.due(SupportAsks.HOME, seen))
        assertFalse("but no other ask that day", SupportAsks.due(SupportAsks.REPLAY, seen))
        // A calendar day, in the phone's zone: 23:30 and 00:30 are two days.
        val late = ready(now = at(21, 0, 30)).copy(lastAskKey = SupportAsks.HOME.key, lastAskAt = at(20, 23, 30))
        assertTrue(SupportAsks.due(SupportAsks.REPLAY, late))
    }

    @Test fun theHomeCardWaitsFor100HoursOfThisPhonesListening() {
        assertFalse(SupportAsks.due(SupportAsks.HOME, ready().copy(listenedMs = SupportAsks.HOURS_MS - 1)))
        assertTrue(SupportAsks.due(SupportAsks.HOME, ready().copy(listenedMs = SupportAsks.HOURS_MS)))
        assertEquals(360_000_000L, SupportAsks.HOURS_MS)
        // The other asks don't count hours.
        assertTrue(SupportAsks.due(SupportAsks.REPLAY, ready().copy(listenedMs = 0)))
    }

    @Test fun theHomeCardNeverBesideAnotherOffer() {
        assertFalse(SupportAsks.due(SupportAsks.HOME, ready().copy(otherHomeOffer = true)))
        assertTrue("the other asks aren't on Home", SupportAsks.due(SupportAsks.REWIND, ready().copy(otherHomeOffer = true)))
    }

    // ------------------------------------------------------------------ The marks on the phone

    @Test fun theMarksAreKept() {
        val app = RuntimeEnvironment.getApplication()
        SupportMarks.testNow = at(20, 9)
        SupportMarks.testInstalledAt = installed
        val marks = SupportMarks(app)
        var s = marks.state(onboarded = true, supported = false, listenedMs = SupportAsks.HOURS_MS, tv = false)
        assertEquals(installed, s.installedAt)
        assertTrue(SupportAsks.due(SupportAsks.HOME, s))
        // Seen: still due today (the card stays until answered), nothing else today.
        marks.stamp(SupportAsks.HOME)
        s = marks.state(onboarded = true, supported = false, listenedMs = SupportAsks.HOURS_MS, tv = false)
        assertTrue(SupportAsks.due(SupportAsks.HOME, s.copy(zone = ZoneId.systemDefault())))
        assertFalse(SupportAsks.due(SupportAsks.REPLAY, s))
        // Answered: gone for good, also tomorrow and with a fresh store object.
        marks.end(SupportAsks.HOME)
        SupportMarks.testNow = at(25)
        s = SupportMarks(app).state(onboarded = true, supported = false, listenedMs = SupportAsks.HOURS_MS, tv = false)
        assertEquals(setOf(SupportAsks.HOME.key), s.shown)
        assertFalse(SupportAsks.due(SupportAsks.HOME, s))
        assertTrue(SupportAsks.due(SupportAsks.REPLAY, s))
    }

    /** The marks go in a backup with the settings and come back with a restore; the switch travels as a setting. */
    @Test fun aBackupCarriesTheMarksAndTheSwitch(): Unit = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val data = app.filesDir.parentFile!!
        assertEquals(BackupPart.SETTINGS, Backup.prefsPart(SupportMarks.PREFS))
        SupportMarks.testNow = at(20)
        val marks = SupportMarks(app)
        marks.end(SupportAsks.HOME)
        marks.end(SupportAsks.REWIND)
        // Written through (apply is lazy): a commit writes what's in memory.
        app.getSharedPreferences(SupportMarks.PREFS, 0).edit().commit()
        val file = File(data, "shared_prefs/${SupportMarks.PREFS}.xml")
        assertTrue(file.isFile)
        val taken = Backup.filesToBackUp(data, app.filesDir, setOf(BackupPart.SETTINGS)).map { it.relativeTo(data).invariantSeparatorsPath }
        assertTrue(taken.toString(), "shared_prefs/${SupportMarks.PREFS}.xml" in taken)
        assertFalse("not with the history alone", "shared_prefs/${SupportMarks.PREFS}.xml" in Backup.filesToBackUp(data, app.filesDir, setOf(BackupPart.HISTORY)).map { it.relativeTo(data).invariantSeparatorsPath })
        val saved = file.readText()
        assertTrue(saved, "home_hours" in saved && "rewind_year" in saved)

        // Restored on a phone that never asked: the backup's marks are put in place.
        file.writeText("""<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map />""")
        File(app.filesDir, "restore_pending/shared_prefs").mkdirs()
        File(app.filesDir, "restore_pending/vinz-backup.json").writeText("""{"format":2,"app":"${app.packageName}","parts":["SETTINGS"]}""")
        File(app.filesDir, "restore_pending/shared_prefs/${SupportMarks.PREFS}.xml").writeText(saved)
        Backup.applyPending(app)
        val back = file.readText()
        assertTrue(back, "home_hours" in back && "rewind_year" in back)

        // The switch is a setting: a settings backup holds it and puts it back.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val settings = com.vinz.music.data.prefs.SettingsRepository(app, scope)
        try {
            settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("supported")] = true }
            val values = settings.forBackup(settings = true, accounts = false)
            assertEquals("b:true", values["supported"])
            settings.editNow { it.clear() }
            settings.fromBackup(values, settings = true, accounts = false)
            val until = System.currentTimeMillis() + 5_000
            while (!settings.stored.value.behavior.supported && System.currentTimeMillis() < until) Thread.sleep(10)
            assertTrue(settings.stored.value.behavior.supported)
        } finally {
            settings.editNow { it.clear() }
            File(app.filesDir, "restore_settings.json").delete()
        }
    }

    @Test fun theSwitchIsOffAndNeverInALook() {
        assertFalse(com.vinz.music.data.prefs.AppSettings().behavior.supported)
        val prefs = com.vinz.music.data.prefs.SettingsRepository(RuntimeEnvironment.getApplication(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertTrue("supported" in prefs.notLookNames)
        assertFalse("supported" in prefs.lookKinds)
    }
}
