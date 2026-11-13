package com.vinz.music.data

import com.vinz.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

interface CloudBackupSessionProvider {
    suspend fun requireFreshSession(expectedUserId: String? = null): VinzCloudAccount.Session
    suspend fun refreshSession(): Boolean
}

interface CloudBackupArchive {
    val formatVersion: Int
    suspend fun export(file: File, parts: Set<BackupPart>)
    suspend fun inspect(file: File): BackupPreview
    suspend fun restore(file: File)
}

data class BackupPreview(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtEpochMillis: Long,
    val parts: Set<BackupPart>,
    val withDownloads: Boolean,
)

data class CloudBackupRecord(
    val id: String,
    val byteSize: Long,
    val sha256: String,
    val formatVersion: Int,
    val appVersion: String,
    val scope: String,
    val createdAt: String,
)

enum class CloudBackupPhase { PREPARING, UPLOADING, DOWNLOADING, VALIDATING, RESTORING, DELETING }

data class CloudBackupProgress(
    val phase: CloudBackupPhase,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f)
}

class CloudBackupException(message: String) : IllegalStateException(message)

/**
 * Cloud backups use the existing authenticated Cloudflare Worker API. Supabase verifies the user and
 * applies RLS to metadata; the Worker stores opaque ZIP files in the private R2 bucket.
 *
 * Only settings, library and listening history are uploaded. Provider credentials and downloaded audio
 * never enter a cloud archive. The Worker and metadata table both enforce the 50 MiB ceiling.
 */
class VinzCloudBackup(
    private val sessions: CloudBackupSessionProvider,
    private val archive: CloudBackupArchive,
    http: OkHttpClient,
    private val cacheDir: File,
    apiBaseUrl: String = VinzCloudAccount.VINZ_API_URL,
    private val maxBytes: Long = MAX_BACKUP_BYTES,
) {
    private val client = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(3, TimeUnit.MINUTES)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
    private val baseUrl = apiBaseUrl.trimEnd('/')
    private val operation = Mutex()
    private val mutableProgress = MutableStateFlow<CloudBackupProgress?>(null)
    val progress: StateFlow<CloudBackupProgress?> = mutableProgress.asStateFlow()

    suspend fun list(): List<CloudBackupRecord> = operation.withLock {
        val userId = sessions.requireFreshSession().userId
        authenticated(userId, { session ->
            Request.Builder()
                .url(baseUrl + "/v1/backups")
                .header("Authorization", "Bearer " + session.accessToken)
                .header("Accept", "application/json")
                .get()
                .build()
        }) { response ->
            val root = JSONObject(response.body?.string().orEmpty())
            val rows = root.optJSONArray("backups") ?: throw CloudBackupException("Cloud backup list is unavailable.")
            (0 until rows.length()).map { index ->
                parseRecord(rows.getJSONObject(index), defaultScope = "full")
            }
        }
    }

    suspend fun create(): CloudBackupRecord = operation.withLock {
        val userId = sessions.requireFreshSession().userId
        cacheDir.mkdirs()
        val file = File(cacheDir, "vinz-cloud-backup-" + UUID.randomUUID() + ".zip")
        try {
            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.PREPARING)
            archive.export(file, CLOUD_PARTS)
            val size = file.length()
            if (size <= 0) throw CloudBackupException("The cloud backup is empty.")
            if (size > maxBytes) throw CloudBackupException("This backup is larger than 50 MB. Use a local backup or remove large items.")

            val preview = archive.inspect(file)
            validatePreview(preview)
            if (preview.parts != CLOUD_PARTS) {
                throw CloudBackupException("The cloud backup did not contain the expected settings, library and history.")
            }
            if (preview.formatVersion != archive.formatVersion) {
                throw CloudBackupException("The cloud backup format changed while it was being prepared. Try again.")
            }
            val digest = sha256(file)
            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.UPLOADING, 0, size)
            val body = ProgressFileRequestBody(file) { sent, total ->
                mutableProgress.value = CloudBackupProgress(CloudBackupPhase.UPLOADING, sent, total)
            }
            val record = authenticated(userId, { session ->
                Request.Builder()
                    .url(baseUrl + "/v1/backups")
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("Accept", "application/json")
                    .header("X-Vinz-Format-Version", archive.formatVersion.toString())
                    .header("X-Vinz-App-Version", BuildConfig.VERSION_NAME)
                    .header("X-Vinz-Backup-Scope", "full")
                    .header("X-Vinz-Sha256", digest)
                    .put(body)
                    .build()
            }) { response ->
                val root = JSONObject(response.body?.string().orEmpty())
                val saved = root.optJSONObject("backup") ?: throw CloudBackupException("Cloud backup storage returned invalid metadata.")
                parseRecord(saved, defaultScope = "full")
            }
            if (record.byteSize != size || record.sha256 != digest || record.formatVersion != archive.formatVersion) {
                throw CloudBackupException("Cloud backup storage returned mismatched metadata. The backup was not accepted.")
            }
            record
        } finally {
            file.delete()
            mutableProgress.value = null
        }
    }

    suspend fun restore(record: CloudBackupRecord) = operation.withLock {
        requireValidId(record.id)
        val userId = sessions.requireFreshSession().userId
        val file = download(record, userId)
        try {
            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.VALIDATING, file.length(), file.length())
            val preview = archive.inspect(file)
            validatePreview(preview)
            if (preview.formatVersion != record.formatVersion) {
                throw CloudBackupException("Cloud backup format metadata does not match its archive.")
            }
            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.RESTORING, file.length(), file.length())
            archive.restore(file)
        } finally {
            file.delete()
            mutableProgress.value = null
        }
    }

    suspend fun delete(id: String) = operation.withLock {
        requireValidId(id)
        val userId = sessions.requireFreshSession().userId
        mutableProgress.value = CloudBackupProgress(CloudBackupPhase.DELETING)
        try {
            authenticated(userId, { session ->
                Request.Builder()
                    .url(baseUrl + "/v1/backups/" + id)
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("Accept", "application/json")
                    .delete()
                    .build()
            }) { response ->
                val result = JSONObject(response.body?.string().orEmpty())
                if (!result.optBoolean("deleted") || result.optString("id") != id) {
                    throw CloudBackupException("Cloud backup could not be deleted.")
                }
            }
        } finally {
            mutableProgress.value = null
        }
    }

    private suspend fun download(record: CloudBackupRecord, userId: String): File {
        val file = File(cacheDir, "vinz-cloud-restore-" + record.id + ".zip")
        try {
            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.DOWNLOADING, 0, record.byteSize)
            authenticated(userId, { session ->
                Request.Builder()
                    .url(baseUrl + "/v1/backups/" + record.id)
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("Accept", "application/octet-stream")
                    .get()
                    .build()
            }) { response ->
                val body = response.body ?: throw CloudBackupException("Cloud backup download was empty.")
                val headerHash = response.header("x-vinz-sha256")?.lowercase()
                    ?: throw CloudBackupException("Cloud backup is missing its integrity header.")
                val headerFormat = response.header("x-vinz-format-version")?.toIntOrNull()
                    ?: throw CloudBackupException("Cloud backup is missing its format header.")
                val headerAppVersion = response.header("x-vinz-app-version").orEmpty()
                val contentLength = body.contentLength().takeIf { it >= 0 } ?: record.byteSize
                if (contentLength <= 0 || contentLength > maxBytes || contentLength != record.byteSize) {
                    throw CloudBackupException("Cloud backup size does not match its metadata.")
                }
                if (headerHash != record.sha256 || headerFormat != record.formatVersion ||
                    (record.appVersion.isNotBlank() && headerAppVersion != record.appVersion)
                ) {
                    throw CloudBackupException("Cloud backup metadata does not match its saved record.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var transferred = 0L
                body.byteStream().use { input ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            transferred += count
                            if (transferred > maxBytes) throw CloudBackupException("Cloud backup exceeds the 50 MB limit.")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            mutableProgress.value = CloudBackupProgress(CloudBackupPhase.DOWNLOADING, transferred, contentLength)
                        }
                    }
                }
                val actualHash = hex(digest.digest())
                if (transferred != record.byteSize || actualHash != record.sha256) {
                    throw CloudBackupException("Cloud backup failed its SHA-256 integrity check. It was not restored.")
                }
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun validatePreview(preview: BackupPreview) {
        if (preview.formatVersion > archive.formatVersion) {
            throw CloudBackupException("This backup was created by a newer version of Vinz. Update Vinz before restoring it.")
        }
        if (preview.formatVersion <= 0 || preview.parts.isEmpty()) {
            throw CloudBackupException("This cloud backup is invalid or unavailable.")
        }
        if (preview.withDownloads || BackupPart.DOWNLOADS in preview.parts || BackupPart.ACCOUNTS in preview.parts ||
            preview.parts.any { it !in CLOUD_PARTS }
        ) {
            throw CloudBackupException("This cloud backup contains unsupported private data and was not restored.")
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> authenticated(
        expectedUserId: String,
        request: (VinzCloudAccount.Session) -> Request,
        block: suspend (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var session = sessions.requireFreshSession(expectedUserId)
        for (attempt in 0..1) {
            val call = client.newCall(request(session))
            val job = currentCoroutineContext()[Job]
            val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                val response = call.execute()
                try {
                    if (response.code == 401 && attempt == 0) {
                        if (!sessions.refreshSession()) {
                            throw CloudBackupException("Your Vinz Account session expired. Sign in again.")
                        }
                        session = sessions.requireFreshSession(expectedUserId)
                        continue
                    }
                    if (!response.isSuccessful) throw CloudBackupException(errorMessage(response))
                    sessions.requireFreshSession(expectedUserId)
                    return@withContext block(response)
                } finally {
                    response.close()
                }
            } finally {
                cancellation?.dispose()
            }
        }
        throw CloudBackupException("Your Vinz Account session expired. Sign in again.")
    }

    private fun parseRecord(json: JSONObject, defaultScope: String): CloudBackupRecord {
        val id = json.optString("id")
        requireValidId(id)
        val byteSize = json.optLong("byte_size", -1L)
        if (byteSize !in 1..maxBytes) throw CloudBackupException("Cloud backup metadata contains an invalid size.")
        val digest = json.optString("sha256").lowercase()
        if (!SHA256_PATTERN.matches(digest)) throw CloudBackupException("Cloud backup metadata contains an invalid checksum.")
        val format = json.optInt("format_version", 0)
        if (format <= 0) throw CloudBackupException("Cloud backup metadata contains an invalid format version.")
        val scope = json.optString("backup_scope", defaultScope)
        if (scope !in BACKUP_SCOPES) throw CloudBackupException("Cloud backup metadata contains an invalid scope.")
        return CloudBackupRecord(
            id = id,
            byteSize = byteSize,
            sha256 = digest,
            formatVersion = format,
            appVersion = json.optString("app_version"),
            scope = scope,
            createdAt = json.optString("created_at"),
        )
    }

    private fun errorMessage(response: Response): String {
        val code = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrDefault("")
        return when {
            response.code == 413 || code == "backup_too_large" ->
                "This backup is larger than 50 MB. Use a local backup or remove large items."
            response.code == 422 || code.contains("integrity") || code == "backup_checksum_mismatch" ->
                "Cloud backup failed its integrity check. No data was restored."
            response.code == 401 || code == "unauthorized" ->
                "Your Vinz Account session expired. Sign in again."
            response.code == 404 || code == "backup_not_found" || code == "backup_object_missing" ->
                "This cloud backup is no longer available."
            response.code == 429 ->
                "Cloud backup is temporarily rate limited. Try again later."
            code == "backup_storage_not_configured" ->
                "Cloud backup storage is not configured yet."
            response.code >= 500 ->
                "Cloud backup service is temporarily unavailable. Try again later."
            else -> "Cloud backup request failed. Please try again."
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun requireValidId(id: String) {
        if (!UUID_PATTERN.matches(id)) throw CloudBackupException("Cloud backup ID is invalid.")
    }

    private class ProgressFileRequestBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        private val mediaType: MediaType = "application/octet-stream".toMediaType()
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    sent += count
                    onProgress(sent, total)
                }
            }
        }
    }

    companion object {
        const val MAX_BACKUP_BYTES = 50L * 1024L * 1024L
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        private val BACKUP_SCOPES = setOf("full", "library", "settings", "history")
        private val CLOUD_PARTS = setOf(BackupPart.SETTINGS, BackupPart.LIBRARY, BackupPart.HISTORY)
    }
}
