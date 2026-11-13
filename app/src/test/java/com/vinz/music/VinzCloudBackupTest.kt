package com.vinz.music

import com.vinz.music.data.BackupPart
import com.vinz.music.data.BackupPreview
import com.vinz.music.data.CloudBackupArchive
import com.vinz.music.data.CloudBackupException
import com.vinz.music.data.CloudBackupRecord
import com.vinz.music.data.CloudBackupSessionProvider
import com.vinz.music.data.VinzCloudAccount
import com.vinz.music.data.VinzCloudBackup
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class VinzCloudBackupTest {
    private lateinit var server: MockWebServer
    private lateinit var cache: File
    private lateinit var sessions: FakeSessions
    private lateinit var archive: FakeArchive

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        cache = Files.createTempDirectory("vinz-cloud-backup-test").toFile()
        sessions = FakeSessions()
        archive = FakeArchive()
    }

    @After fun tearDown() {
        server.close()
        cache.deleteRecursively()
    }

    @Test fun uploadUsesTheAuthenticatedWorkerAndExcludesCredentialsAndAudio() = runBlocking {
        val digest = sha256(archive.bytes)
        server.enqueue(jsonResponse(201, uploadJson(digest)))

        val record = service().create()
        val request = server.takeRequest()

        assertEquals("PUT", request.method)
        assertEquals("Bearer old-access-token", request.headers["Authorization"])
        assertEquals("full", request.headers["X-Vinz-Backup-Scope"])
        assertEquals("2", request.headers["X-Vinz-Format-Version"])
        assertEquals(digest, request.headers["X-Vinz-Sha256"])
        assertEquals(archive.bytes.toList(), request.body?.toByteArray()?.toList())
        assertEquals(setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY), archive.exportedParts)
        assertFalse(BackupPart.ACCOUNTS in archive.exportedParts)
        assertFalse(BackupPart.DOWNLOADS in archive.exportedParts)
        assertEquals(digest, record.sha256)
        assertEquals(3L, record.byteSize)
    }

    @Test fun a401RefreshesTheSessionAndRetriesOnce() = runBlocking {
        val digest = sha256(archive.bytes)
        server.enqueue(jsonResponse(401, """{"error":"unauthorized"}"""))
        server.enqueue(jsonResponse(201, uploadJson(digest)))

        service().create()

        assertEquals("Bearer old-access-token", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer rotated-access-token", server.takeRequest().headers["Authorization"])
        assertEquals(1, sessions.refreshCalls)
    }

    @Test fun backupsAreListedFromTheAuthenticatedWorker() = runBlocking {
        val digest = sha256(archive.bytes)
        val body = """{"backups":[{"id":"11111111-1111-4111-8111-111111111111","byte_size":3,"sha256":"$digest","format_version":2,"app_version":"1.8.4","backup_scope":"full","created_at":"2026-11-13T10:00:00Z"}]}"""
        server.enqueue(jsonResponse(200, body))

        val records = service().list()

        assertEquals(1, records.size)
        assertEquals("full", records.single().scope)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun previewVerifiesAndInspectsTheArchiveWithoutRestoringIt() = runBlocking {
        val digest = sha256(archive.bytes)
        server.enqueue(binaryResponse(archive.bytes, digest))

        val preview = service().preview(record(digest))

        assertEquals(2, preview.formatVersion)
        assertEquals(setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY), preview.parts)
        assertFalse(archive.restored)
        assertTrue(cache.listFiles().orEmpty().none { it.name.startsWith("vinz-cloud-restore-") })
    }

    @Test fun previewRejectsChecksumMismatchWithoutChangingLocalData() {
        server.enqueue(binaryResponse(archive.bytes, "0".repeat(64)))
        assertThrows(CloudBackupException::class.java) {
            runBlocking { service().preview(record(sha256(archive.bytes))) }
        }
        assertFalse(archive.restored)
    }
    @Test fun restoreVerifiesSha256BeforeApplyingTheArchive() = runBlocking {
        val digest = sha256(archive.bytes)
        server.enqueue(binaryResponse(archive.bytes, digest))
        service().restore(record(digest))
        assertTrue(archive.restored)
    }

    @Test fun checksumMismatchPreventsRestore() {
        server.enqueue(binaryResponse(archive.bytes, "0".repeat(64)))
        assertThrows(CloudBackupException::class.java) {
            runBlocking { service().restore(record(sha256(archive.bytes))) }
        }
        assertFalse(archive.restored)
    }

    @Test fun oversizeArchiveIsRejectedBeforeNetworkUpload() {
        assertThrows(CloudBackupException::class.java) { runBlocking { service(maxBytes = 2).create() } }
        assertEquals(0, server.requestCount)
    }

    @Test fun cloudArchiveCannotContainProviderCredentialsOrDownloadedAudio() {
        archive.preview = archive.preview.copy(parts = archive.preview.parts + BackupPart.ACCOUNTS)
        assertThrows(CloudBackupException::class.java) { runBlocking { service().create() } }
        assertEquals(0, server.requestCount)
    }

    @Test fun deleteUsesTheBackupIdAndAuthenticatedSession() = runBlocking {
        val id = "11111111-1111-4111-8111-111111111111"
        server.enqueue(jsonResponse(200, """{"deleted":true,"id":"11111111-1111-4111-8111-111111111111"}"""))
        service().delete(id)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/backups/" + id, request.target)
        assertEquals("Bearer old-access-token", request.headers["Authorization"])
    }

    private fun service(maxBytes: Long = VinzCloudBackup.MAX_BACKUP_BYTES) = VinzCloudBackup(
        sessions, archive, OkHttpClient(), cache, server.url("/").toString(), maxBytes,
    )

    private fun record(hash: String) = CloudBackupRecord(
        id = "11111111-1111-4111-8111-111111111111",
        byteSize = archive.bytes.size.toLong(),
        sha256 = hash,
        formatVersion = 2,
        appVersion = "1.8.4",
        scope = "full",
        createdAt = "2026-11-13T10:00:00Z",
    )

    private fun uploadJson(digest: String): String =
        """{"backup":{"id":"11111111-1111-4111-8111-111111111111","byte_size":3,"sha256":"$digest","format_version":2,"app_version":"1.8.4","created_at":"2026-11-13T10:00:00Z"}}"""

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .body(Buffer().writeUtf8(body))
            .addHeader("content-type", "application/json; charset=utf-8")
            .build()

    private fun binaryResponse(bytes: ByteArray, digest: String): MockResponse =
        MockResponse.Builder()
            .body(Buffer().write(bytes))
            .setHeader("content-type", "application/octet-stream")
            .setHeader("x-vinz-sha256", digest)
            .setHeader("x-vinz-format-version", "2")
            .setHeader("x-vinz-app-version", "1.8.4")
            .build()

    private class FakeSessions : CloudBackupSessionProvider {
        var userId: String = "22222222-2222-4222-8222-222222222222"
        var accessToken = "old-access-token"
        var refreshCalls = 0

        override suspend fun requireFreshSession(expectedUserId: String?): VinzCloudAccount.Session {
            if (expectedUserId != null && expectedUserId != userId) error("Account changed.")
            return VinzCloudAccount.Session(accessToken, "refresh-token", userId, "listener@example.test", Long.MAX_VALUE)
        }

        override suspend fun refreshSession(): Boolean {
            refreshCalls++
            accessToken = "rotated-access-token"
            return true
        }
    }

    private class FakeArchive : CloudBackupArchive {
        override val formatVersion = 2
        var bytes = byteArrayOf(1, 2, 3)
        var exportedParts: Set<BackupPart> = emptySet()
        var preview = BackupPreview(
            formatVersion = 2,
            appVersion = "1.8.4",
            createdAtEpochMillis = 1_794_568_000_000L,
            parts = setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY),
            withDownloads = false,
        )
        var restored = false

        override suspend fun export(file: File, parts: Set<BackupPart>) {
            exportedParts = parts
            file.writeBytes(bytes)
        }

        override suspend fun inspect(file: File): BackupPreview {
            if (!file.exists() || file.length() == 0L) error("Invalid archive.")
            return preview
        }

        override suspend fun restore(file: File) {
            restored = true
            file.delete()
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
