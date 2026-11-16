package com.vinz.music

import com.vinz.music.data.Backup
import com.vinz.music.data.BackupPart
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Backups move between Vinz copies (#204, 1.7.9): one made by a Vinz with another app id is still a Vinz backup. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private fun zip(manifest: String?): Uri {
        val f = File(Fixture.context.cacheDir, "test-backup-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            manifest?.let { z.putNextEntry(ZipEntry("vinz-backup.json")); z.write(it.toByteArray()); z.closeEntry() }
            z.putNextEntry(ZipEntry("files/library/vinz.json")); z.write("{}".toByteArray()); z.closeEntry()
        }
        return Uri.fromFile(f)
    }

    @Test fun aBackupFromAnotherVinzCopyIsRecognised() = runBlocking {
        val parts = Fixture.container.backup.partsOf(zip("""{"format":2,"app":"com.netease.cloudmusic","parts":["LIBRARY"]}"""))
        assertEquals(setOf(com.vinz.music.data.BackupPart.LIBRARY), parts)
    }

    @Test fun aZipThatIsntABackupIsnt() = runBlocking {
        assertNull(Fixture.container.backup.partsOf(zip(null)))
        assertNull(Fixture.container.backup.partsOf(zip("""{"format":2,"parts":["LIBRARY"]}""")))
    }

    @Test fun cloudBackupArchiveRoundTripsThroughProductionAdapter() = runBlocking {
        val staging = File(Fixture.context.filesDir, "restore_pending")
        val archive = File(Fixture.context.cacheDir, "cloud-backup-round-trip-${System.nanoTime()}.zip")
        var restartCount = 0
        val backup = Backup(Fixture.container) { restartCount++ }
        val parts = setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY)
        staging.deleteRecursively()

        try {
            backup.export(archive, parts)

            assertTrue("the production cloud archive should be created", archive.isFile && archive.length() > 0L)
            val preview = backup.inspect(archive)
            assertEquals(2, preview.formatVersion)
            assertEquals(parts, preview.parts)
            assertEquals(false, preview.withDownloads)

            backup.restore(archive)

            assertEquals("restore should reach the post-staging restart boundary", 1, restartCount)
            assertTrue("the validated manifest must be staged before restart", File(staging, "vinz-backup.json").isFile)
            assertFalse("the temporary cloud archive should be removed after staging", archive.exists())
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

}
