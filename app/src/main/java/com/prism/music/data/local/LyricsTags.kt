package com.prism.music.data.local

import com.prism.music.data.lyrics.Lyrics
import java.io.File
import java.io.RandomAccessFile

/**
 * Lyrics written into music files' own tags (#16), so other players and devices show them too: FLAC files get a
 * LYRICS Vorbis comment, MP3s an ID3v2 USLT frame, both holding LRC text ("[01:02.34]A line") when the lyrics are
 * synced, or plain text when they aren't. Any lyrics already in the tag are replaced; everything else is kept as it
 * was. The file is written to [output]; the caller swaps it in once it's whole.
 */
object LyricsTags {
    enum class Kind { FLAC, MP3 }

    fun kindOf(mime: String, name: String = ""): Kind? = when {
        mime.contains("flac", true) || name.endsWith(".flac", true) -> Kind.FLAC
        mime.contains("mpeg", true) || mime.contains("mp3", true) || name.endsWith(".mp3", true) -> Kind.MP3
        else -> null
    }

    /** The lyrics as LRC (one "[mm:ss.xx]line" per line, an empty one for each instrumental break), or plain text. */
    fun text(l: Lyrics): String {
        if (!l.synced) return l.lines.joinToString("\n") { it.text }.trim()
        return l.lines.filter { it.timeMs >= 0 }.joinToString("\n") { line ->
            val cs = line.timeMs / 10
            // Western digits whatever the phone's language: players read LRC times as those.
            "[%02d:%02d.%02d]%s".format(java.util.Locale.ROOT, cs / 6000, cs / 100 % 60, cs % 100, if (line.isGap) "" else line.text)
        }
    }

    /** Writes [input] with [lyrics] in its tag to [output]. False (and nothing written) for a file it can't read safely. */
    fun write(input: File, output: File, kind: Kind, lyrics: String): Boolean = runCatching {
        when (kind) {
            Kind.FLAC -> flac(input, output, lyrics)
            Kind.MP3 -> mp3(input, output, lyrics)
        }
    }.getOrDefault(false).also { if (!it) output.delete() }

    // ------------------------------------------------------------ FLAC

    private class Block(val type: Int, val data: ByteArray)

    private fun flac(input: File, output: File, lyrics: String): Boolean {
        RandomAccessFile(input, "r").use { f ->
            // Some FLAC files carry an ID3v2 tag in front: kept as it is.
            var start = 0L
            val head = ByteArray(10)
            f.readFully(head)
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                start = 10L + syncsafe(head, 6) + if (head[5].toInt() and 0x10 != 0) 10 else 0
            }
            f.seek(start)
            val magic = ByteArray(4).also { f.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "fLaC") return false
            val blocks = ArrayList<Block>()
            while (true) {
                val h = f.readUnsignedByte()
                val len = (f.readUnsignedByte() shl 16) or (f.readUnsignedByte() shl 8) or f.readUnsignedByte()
                blocks += Block(h and 0x7F, ByteArray(len).also { f.readFully(it) })
                if (h and 0x80 != 0) break
                if (blocks.size > 1000) return false
            }
            if (blocks.firstOrNull()?.type != 0) return false // STREAMINFO comes first in every valid file
            val audioAt = f.filePointer
            val oldComment = blocks.firstOrNull { it.type == 4 }
            val comment = Block(4, vorbisComment(oldComment?.data, lyrics))
            val kept = blocks.filter { it.type != 4 && it.type != 1 }.toMutableList()
            kept.add(1, comment)
            // Room to grow next time: the padding there was, or 4 KB.
            kept += Block(1, ByteArray(blocks.firstOrNull { it.type == 1 }?.data?.size?.coerceIn(1024, 65536) ?: 4096))
            output.outputStream().buffered().use { out ->
                if (start > 0) { val pre = ByteArray(start.toInt()); f.seek(0); f.readFully(pre); out.write(pre) }
                out.write(magic)
                kept.forEachIndexed { i, b ->
                    if (b.data.size >= 1 shl 24) return false
                    out.write((if (i == kept.lastIndex) 0x80 else 0) or b.type)
                    out.write(b.data.size shr 16 and 0xFF); out.write(b.data.size shr 8 and 0xFF); out.write(b.data.size and 0xFF)
                    out.write(b.data)
                }
                f.seek(audioAt)
                copyRest(f, out)
            }
        }
        return true
    }

    /** A Vorbis comment block with every comment kept except old lyrics, and LYRICS=[lyrics] added. */
    private fun vorbisComment(old: ByteArray?, lyrics: String): ByteArray {
        var vendor = "Vinz".toByteArray()
        val comments = ArrayList<ByteArray>()
        if (old != null) {
            var p = 0
            fun int(): Int { val v = (old[p].toInt() and 0xFF) or ((old[p + 1].toInt() and 0xFF) shl 8) or ((old[p + 2].toInt() and 0xFF) shl 16) or ((old[p + 3].toInt() and 0xFF) shl 24); p += 4; return v }
            val vl = int(); vendor = old.copyOfRange(p, p + vl); p += vl
            repeat(int()) {
                val cl = int()
                val c = old.copyOfRange(p, p + cl); p += cl
                val key = String(c, Charsets.UTF_8).substringBefore('=').uppercase()
                if (key != "LYRICS" && key != "UNSYNCEDLYRICS" && key != "SYNCEDLYRICS") comments += c
            }
        }
        comments += "LYRICS=$lyrics".toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        le(vendor.size); out.write(vendor)
        le(comments.size); comments.forEach { le(it.size); out.write(it) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ MP3

    private fun mp3(input: File, output: File, lyrics: String): Boolean {
        RandomAccessFile(input, "r").use { f ->
            val head = ByteArray(10)
            val hasTag = f.length() >= 10 && run { f.readFully(head); String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" }
            val major = if (hasTag) head[3].toInt() else 4
            val flags = if (hasTag) head[5].toInt() and 0xFF else 0
            // ID3v2.2, and tags that are unsynchronised or have extended headers or footers, are left alone (rare
            // today, and rewriting them wrong could spoil the file).
            if (hasTag && (major !in 3..4 || flags != 0)) return false
            val tagSize = if (hasTag) syncsafe(head, 6) else 0
            val frames = java.io.ByteArrayOutputStream()
            if (hasTag) {
                val body = ByteArray(tagSize).also { f.seek(10); f.readFully(it) }
                var p = 0
                while (p + 10 <= body.size && body[p].toInt() != 0) {
                    val id = String(body, p, 4, Charsets.ISO_8859_1)
                    val size = if (major == 4) syncsafe(body, p + 4) else be(body, p + 4)
                    if (size < 0 || p + 10 + size > body.size) return false
                    if (id != "USLT") frames.write(body, p, 10 + size)
                    p += 10 + size
                }
            }
            frames.write(uslt(major, lyrics))
            val padding = 2048
            val newSize = frames.size() + padding
            output.outputStream().buffered().use { out ->
                out.write("ID3".toByteArray(Charsets.ISO_8859_1))
                out.write(major); out.write(0); out.write(0)
                out.write(syncsafeBytes(newSize))
                frames.writeTo(out)
                out.write(ByteArray(padding))
                f.seek(if (hasTag) 10L + tagSize else 0L)
                copyRest(f, out)
            }
        }
        return true
    }

    /** An unsynchronised-lyrics frame: UTF-8 in ID3v2.4, UTF-16 in v2.3 (which has no UTF-8). */
    private fun uslt(major: Int, lyrics: String): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        if (major == 4) {
            body.write(3); body.write("eng".toByteArray()); body.write(0)
            body.write(lyrics.toByteArray(Charsets.UTF_8))
        } else {
            body.write(1); body.write("eng".toByteArray())
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)) // an empty description, with its BOM
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())); body.write(lyrics.toByteArray(Charsets.UTF_16LE))
        }
        val data = body.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("USLT".toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) syncsafeBytes(data.size) else byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(0); out.write(0)
        out.write(data)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ Helpers

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun syncsafeBytes(v: Int) = byteArrayOf((v shr 21 and 0x7F).toByte(), (v shr 14 and 0x7F).toByte(), (v shr 7 and 0x7F).toByte(), (v and 0x7F).toByte())

    private fun be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun copyRest(f: RandomAccessFile, out: java.io.OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = f.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }
}
