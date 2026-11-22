package com.vinz.music.data.stream

/**
 * Which file a song's saved bytes (the song cache, a download) came from (1.8.7, plan 4.2): its format (itag), its
 * length, and when YouTube last made it (the link's `lmt`) when the link says. The itag alone wasn't enough: the same
 * itag comes as other files too (another length, or the volume-levelled variant), and bytes of one were joined onto
 * the other's, a malformed WebM that stopped seconds in.
 */
data class CachedFormat(val itag: Int, val length: Long, val lmt: String? = null) {
    /** As recorded: "itag|length|lmt". */
    fun encode(): String = "$itag|$length|${lmt.orEmpty()}"

    /**
     * The same file as [other]: the same itag and length, and the same `lmt` when both say one. A length nobody knows
     * (before 1.8.7 only the itag was recorded) can't vouch for it, unless both links name the same `lmt`.
     */
    fun sameFile(other: CachedFormat): Boolean {
        if (itag != other.itag) return false
        val lmts = lmt != null && other.lmt != null
        if (lmts && lmt != other.lmt) return false
        if (length > 0 && other.length > 0) return length == other.length
        return length <= 0 && other.length <= 0 && lmts
    }

    companion object {
        private val LMT = Regex("[?&]lmt=([^&#]+)")

        /** The file a link with [itag] and [length] serves ([url]'s `lmt`, when it has one). */
        fun of(itag: Int, length: Long, url: String?): CachedFormat =
            CachedFormat(itag, if (length > 0) length else -1, url?.let { LMT.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() })

        fun of(s: ResolvedStream): CachedFormat = of(s.itag, s.contentLength, s.audioUrl)

        /** What was recorded: today's [encode], or before 1.8.7 the itag alone (its length not known); null for none. */
        fun decode(v: Any?): CachedFormat? = when (v) {
            is Int -> v.takeIf { it != 0 }?.let { CachedFormat(it, -1) }
            is String -> v.split('|').let { p ->
                val itag = p.getOrNull(0)?.toIntOrNull()?.takeIf { it != 0 } ?: return null
                CachedFormat(itag, p.getOrNull(1)?.toLongOrNull() ?: -1, p.getOrNull(2)?.takeIf { it.isNotBlank() })
            }
            else -> null
        }

        /**
         * Bytes of [now] may join what's saved of a song: nothing is ([saved] false), or it's the same file ([before];
         * null when nothing was recorded, which isn't taken on trust). Otherwise what's saved goes first.
         */
        fun mayAdd(before: CachedFormat?, now: CachedFormat, saved: Boolean): Boolean = !saved || before?.sameFile(now) == true

        /** The codec an audio itag is, as Stats for nerds names it; null for one Vinz doesn't know. */
        fun codecOf(itag: Int): String? = when (itag) {
            249, 250, 251, 774 -> "Opus"
            139, 140, 141 -> "AAC"
            else -> null
        }
    }
}
