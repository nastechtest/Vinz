package com.vinz.music.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudBackupZipSafetyTest {
    private val files = mutableListOf<File>()

    @After fun tearDown() {
        files.forEach { it.delete() }
    }

    @Test fun validCloudArchiveReturnsManifestAfterReadingEveryEntry() {
        val manifest = """{"app":"com.vinz.music","format":2}""".toByteArray()
        val file = archive(
            "vinz-backup.json" to manifest,
            "settings.json" to """{"theme":"dark"}""".toByteArray(),
            "files/library/vinz.json" to byteArrayOf(1, 2, 3),
        )
        var cancellationChecks = 0

        val actual = CloudBackupZipSafety.readManifest(file, checkActive = { cancellationChecks++ })

        assertArrayEquals(manifest, actual)
        assertTrue("The validator must consume the payload after the manifest.", cancellationChecks > 3)
    }

    @Test fun rejectsPathTraversalEntries() {
        val file = archive(
            "vinz-backup.json" to byteArrayOf(1),
            "files/../../shared_prefs/private.xml" to byteArrayOf(2),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupZipSafety.readManifest(file)
        }
    }

    @Test fun rejectsAbsoluteAndBackslashPaths() {
        val absolute = archive("vinz-backup.json" to byteArrayOf(1), "/files/library/data" to byteArrayOf(2))
        val windows = archive("vinz-backup.json" to byteArrayOf(1), "files\\..\\private.xml" to byteArrayOf(2))

        assertThrows(IllegalArgumentException::class.java) { CloudBackupZipSafety.readManifest(absolute) }
        assertThrows(IllegalArgumentException::class.java) { CloudBackupZipSafety.readManifest(windows) }
    }

    @Test fun rejectsEntriesOutsideVinzBackupRoots() {
        val file = archive("vinz-backup.json" to byteArrayOf(1), "secrets/token.json" to byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) { CloudBackupZipSafety.readManifest(file) }
    }

    @Test fun rejectsExpandedArchivesAboveTheCloudSafetyLimit() {
        val file = archive(
            "vinz-backup.json" to byteArrayOf(1),
            "files/library/expanded.bin" to ByteArray(256) { 7 },
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupZipSafety.readManifest(file, maxUncompressedBytes = 64)
        }
    }

    @Test fun rejectsOversizedManifestBeforeParsingIt() {
        val file = archive("vinz-backup.json" to ByteArray(16) { 1 })
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupZipSafety.readManifest(file, maxManifestBytes = 8)
        }
    }

    @Test fun rejectsArchivesWithTooManyEntries() {
        val file = archive(
            "vinz-backup.json" to byteArrayOf(1),
            "files/library/a" to byteArrayOf(2),
            "files/library/b" to byteArrayOf(3),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupZipSafety.readManifest(file, maxEntries = 2)
        }
    }

    @Test fun rejectsArchivesWithoutAManifest() {
        val file = archive("files/library/vinz.json" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { CloudBackupZipSafety.readManifest(file) }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = Files.createTempFile("vinz-cloud-zip-safety", ".zip").toFile()
        files += file
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
