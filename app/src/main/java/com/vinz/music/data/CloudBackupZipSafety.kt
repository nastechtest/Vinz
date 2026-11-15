package com.vinz.music.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Validates the full cloud-backup ZIP stream before restore.
 *
 * The compressed archive is capped separately by VinzCloudBackup. This expansion cap protects
 * the staging directory from highly-compressed ZIP bombs without changing local backup limits.
 */
internal object CloudBackupZipSafety {
    const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    private const val MAX_ENTRIES = 10_000
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val MANIFEST = "vinz-backup.json"
    private const val SETTINGS = "settings.json"
    private val allowedRoots = listOf("databases/", "shared_prefs/", "datastore/", "files/")

    fun readManifest(
        file: File,
        checkActive: () -> Unit = {},
        maxUncompressedBytes: Long = MAX_UNCOMPRESSED_BYTES,
        maxEntries: Int = MAX_ENTRIES,
        maxManifestBytes: Int = MAX_MANIFEST_BYTES,
    ): ByteArray {
        require(file.isFile && file.length() > 0L)
        require(maxUncompressedBytes > 0L && maxEntries > 0 && maxManifestBytes > 0)

        val seen = HashSet<String>()
        var entryCount = 0
        var totalBytes = 0L
        var manifestBytes: ByteArray? = null

        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > maxEntries || entry.isDirectory || !isSafeName(entry.name)) {
                    throw IllegalArgumentException("Unsafe cloud backup archive.")
                }
                val name = entry.name
                val allowed = name == MANIFEST || name == SETTINGS || allowedRoots.any(name::startsWith)
                if (!allowed || !seen.add(name)) throw IllegalArgumentException("Unsupported cloud backup entry.")

                val captureManifest = name == MANIFEST
                val manifestOutput = if (captureManifest) ByteArrayOutputStream() else null
                var currentEntryBytes = 0
                while (true) {
                    checkActive()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count.toLong()
                    currentEntryBytes += count
                    if (totalBytes > maxUncompressedBytes ||
                        (captureManifest && currentEntryBytes > maxManifestBytes)
                    ) {
                        throw IllegalArgumentException("Cloud backup archive exceeds its safety limit.")
                    }
                    manifestOutput?.write(buffer, 0, count)
                }
                if (captureManifest) manifestBytes = manifestOutput!!.toByteArray()
                zip.closeEntry()
            }
        }
        return manifestBytes ?: throw IllegalArgumentException("Cloud backup manifest is missing.")
    }

    private fun isSafeName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || '\\' in name || '\u0000' in name) return false
        return name.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }
}
