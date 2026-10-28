package com.prism.music.playback.sing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Songs' separated vocals, kept on the phone: 16-bit stereo PCM at the song's own sample rate (the rate the player
 * hands Sing's processor), written as the separation goes, so Sing can start before a song is done. Least recently
 * used ones go when the store is over its size.
 */
class StemStore(root: File) {
    @Serializable
    data class Meta(val rate: Int, val totalFrames: Long, val complete: Boolean = false, val version: Int = VERSION)

    val dir = File(root, "stems").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    fun pcm(id: String) = File(dir, "${safe(id)}.pcm")
    private fun metaFile(id: String) = File(dir, "${safe(id)}.json")

    fun meta(id: String): Meta? = runCatching { json.decodeFromString<Meta>(metaFile(id).readText()) }.getOrNull()?.takeIf { it.version == VERSION }

    fun writeMeta(id: String, meta: Meta) = metaFile(id).writeText(json.encodeToString(meta))

    /** Fully separated already. */
    fun complete(id: String) = meta(id)?.complete == true && pcm(id).isFile

    /** How far the vocals go so far, 0..1. */
    fun progress(id: String): Float {
        val m = meta(id) ?: return 0f
        if (m.complete) return 1f
        return (pcm(id).length() / BYTES_PER_FRAME).toFloat() / m.totalFrames.coerceAtLeast(1)
    }

    fun delete(id: String) { pcm(id).delete(); metaFile(id).delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    fun bytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Keeps the store within [limit] bytes, oldest used first, never [keep] (the ones playing or coming up). */
    fun trim(limit: Long, keep: Set<String>) {
        val files = dir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedBy { it.lastModified() } ?: return
        var total = bytes()
        for (f in files) {
            if (total <= limit) break
            val id = f.nameWithoutExtension
            if (keep.any { safe(it) == id }) continue
            total -= f.length()
            f.delete()
            File(dir, "$id.json").delete()
        }
    }

    /** Opens [id]'s vocals for reading (while they're still being written, too), or null if there are none. */
    fun reader(id: String): Reader? {
        val m = meta(id) ?: return null
        val f = pcm(id)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return Reader(RandomAccessFile(f, "r").channel, m)
    }

    class Reader(private val channel: FileChannel, val meta: Meta) : AutoCloseable {
        private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        /** Frames written so far. */
        fun available(): Long = runCatching { channel.size() / BYTES_PER_FRAME }.getOrDefault(0L)

        /**
         * [frames] stereo frames from [start] into [out] (interleaved floats). Frames before the start, or not
         * written yet, read as silence. Returns how many were real.
         */
        fun read(start: Long, frames: Int, out: FloatArray): Int {
            java.util.Arrays.fill(out, 0, frames * 2, 0f)
            val avail = available()
            val from = maxOf(start, 0L)
            val to = minOf(start + frames, avail)
            if (to <= from) return 0
            val n = (to - from).toInt()
            val need = (n * BYTES_PER_FRAME).toInt()
            if (bytes.capacity() < need) bytes = ByteBuffer.allocate(need).order(ByteOrder.LITTLE_ENDIAN)
            bytes.clear(); bytes.limit(need)
            var pos = from * BYTES_PER_FRAME
            while (bytes.hasRemaining()) {
                val r = channel.read(bytes, pos)
                if (r <= 0) break
                pos += r
            }
            bytes.flip()
            val offset = ((from - start) * 2).toInt()
            var i = 0
            while (bytes.remaining() >= 2) { out[offset + i] = bytes.short / 32768f; i++ }
            return n
        }

        override fun close() { runCatching { channel.close() } }
    }

    companion object {
        /** Bumped when the separation changes, so older vocals are made again. */
        const val VERSION = 1
        const val BYTES_PER_FRAME = 4L
    }
}
