package com.vinz.music.data.stream

import android.content.SharedPreferences
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * GitHub #408 (1.8.7): a link that plays on mobile data and is refused (403) on one Wi-Fi. The guess, still unproven:
 * YouTube ties a link to the address that asked for it, and on that network the media leaves by another one (the API
 * request over IPv6, the stream over IPv4, or another address of a carrier's pool). What's written down to tell is only
 * an address's family, v4 or v6: an address itself is read to classify it and dropped, never logged or kept.
 */
internal object NetFamily {
    const val V4 = "v4"
    const val V6 = "v6"

    /** The response header [Tag] puts the connection's family in, for the stream interceptor to read. */
    const val HEADER = "X-Vinz-Family"

    fun of(address: InetAddress?): String? = when (address) {
        is Inet4Address -> V4
        is Inet6Address -> V6
        else -> null
    }

    /**
     * The family of an address written as text (a link's `ip=`), without looking anything up: dotted digits are v4,
     * anything with a colon is v6. Anything else (a host name, nothing) is unknown.
     */
    fun ofLiteral(text: String?): String? = when {
        text.isNullOrBlank() -> null
        text.contains(':') -> V6
        Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(text) -> V4
        else -> null
    }

    /** The family a googlevideo link was made for (its `ip=`), or null when it doesn't say. */
    fun ofLink(url: HttpUrl): String? = ofLiteral(url.queryParameter("ip"))
    fun ofLink(url: String): String? = url.toHttpUrlOrNull()?.let(::ofLink)

    /** The family the last request on this thread went out by ([Tag] sets it); synchronous calls run on their caller's thread. */
    private val last = ThreadLocal<String?>()

    fun clearLast() = last.remove()
    fun last(): String? = last.get()

    /**
     * A network interceptor: notes which family each request's connection uses, for this thread ([last]) and on the
     * response ([HEADER]). Only the family: the address goes no further than this function.
     */
    object Tag : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val family = of(chain.connection()?.route()?.socketAddress?.address)
            if (family != null) last.set(family)
            val resp = chain.proceed(chain.request())
            return if (family == null) resp else resp.newBuilder().header(HEADER, family).build()
        }
    }

    /**
     * One part of a diagnostics line: which family the link was asked for over ([asked], the player request), which one
     * it was made for ([link], its `ip=`), and which one its bytes were fetched over ([fetched]). Unknown ones read "?".
     * "(families differ)" ends it when two known ones don't match, so a reporter's file shows it at a glance.
     */
    /** A link as it's handed out: [line] without the fetch, which hasn't happened yet. */
    fun linkLine(asked: String?, link: String?): String =
        "asked over ${asked ?: "?"}, link for ${link ?: "?"}" + if (asked != null && link != null && asked != link) " (families differ)" else ""

    fun line(asked: String?, link: String?, fetched: String?): String {
        val known = listOfNotNull(asked, link, fetched).toSet()
        return "asked over ${asked ?: "?"}, link for ${link ?: "?"}, fetched over ${fetched ?: "?"}" +
            if (known.size > 1) " (families differ)" else ""
    }
}

/** IPv4 addresses only (all of them when a host has none), so a link and its stream leave by the same family (#408). */
class Ipv4OnlyDns(private val inner: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = inner.lookup(hostname)
        return all.filterIsInstance<Inet4Address>().ifEmpty { all }
    }
}

/**
 * Links asked for over IPv4 only carry a mark ([PARAM]), so the stream interceptor fetches them over IPv4 too
 * ([YouTubeStreamInterceptor]), and takes the mark off before googlevideo sees it.
 */
internal object Ipv4Links {
    const val PARAM = "vinz_v4"

    fun mark(url: String): String = url.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(PARAM, "1")?.build()?.toString() ?: url
    fun isMarked(url: HttpUrl): Boolean = url.queryParameter(PARAM) != null
    fun isMarked(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isMarked) == true
    fun strip(url: HttpUrl): HttpUrl = if (isMarked(url)) url.newBuilder().removeAllQueryParameters(PARAM).build() else url
}

/** Told about each googlevideo response the stream interceptor gets: its link, its code and the family it came over. */
fun interface StreamWatch {
    fun fetched(url: HttpUrl, code: Int, family: String?)
}

/** A way of getting a song's link ([StreamResolver]'s ladder) and whether it was asked over IPv4 only. */
internal data class StreamWay(val name: String, val ipv4: Boolean) {
    override fun toString() = if (ipv4) "$name over IPv4 only" else name

    companion object {
        const val DIRECT = "visionOS"
        const val NEWPIPE = "NewPipe"
        const val ACCOUNT = "account:"
    }
}

/**
 * Which way last worked on each network after the usual one was refused (#408), tried first there next time and
 * forgotten when it's refused. Keyed by the resolver's opaque network id; at most [cap] networks, the least recently
 * used going first. Kept in [prefs] (never in a backup: Backup's PREFS_PARTS maps [PREFS] to null).
 */
internal class WayMemory(private val prefs: SharedPreferences?, private val cap: Int = CAP) {
    private val ways = LinkedHashMap<String, StreamWay>(16, 0.75f, true)

    init {
        // Oldest first, by when each was written: the access order starts as it was.
        prefs?.all?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
            ?.mapNotNull { (k, v) -> decode(v)?.let { Triple(k, it.first, it.second) } }
            ?.sortedBy { it.third }
            ?.forEach { (k, way, _) -> ways[k] = way }
        trim()
    }

    @Synchronized fun get(network: String): StreamWay? = ways[network]

    @Synchronized fun worked(network: String, way: StreamWay) {
        ways[network] = way
        prefs?.edit()?.putString(network, encode(way, System.currentTimeMillis()))?.apply()
        trim()
    }

    /** [way] was refused on [network]: forgotten there if it's the one remembered (whether or not over IPv4 only). */
    @Synchronized fun refused(network: String, way: StreamWay): Boolean {
        if (ways[network]?.name != way.name) return false
        ways.remove(network)
        prefs?.edit()?.remove(network)?.apply()
        return true
    }

    @Synchronized fun size() = ways.size

    private fun trim() {
        while (ways.size > cap) {
            val oldest = ways.keys.first()
            ways.remove(oldest)
            prefs?.edit()?.remove(oldest)?.apply()
        }
    }

    companion object {
        const val PREFS = "stream_ways"
        const val CAP = 20

        private fun encode(way: StreamWay, at: Long) = "${way.name}|${if (way.ipv4) 4 else 0}|$at"

        private fun decode(text: String): Pair<StreamWay, Long>? {
            val parts = text.split('|')
            if (parts.size != 3 || parts[0].isBlank()) return null
            return StreamWay(parts[0], parts[1] == "4") to (parts[2].toLongOrNull() ?: 0L)
        }
    }
}

/**
 * The links handed out lately ([StreamResolver.resolve]): which song and way each came from, which family it was asked
 * over, and whether its bytes have been fetched yet. At most [cap] links; the oldest go first.
 */
internal class LinkBook(private val cap: Int = 64) {
    /** [formats]: the lookup the link came from, so a link given up on forgets that lookup only (#514). */
    class Entry(val songId: String, val way: StreamWay, val asked: String?, val remember: Boolean, val formats: Any? = null) {
        @Volatile var fetched = false
    }

    private val links = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > cap
    }

    /** Notes [url]'s link; false when it was noted already (its first note, and whether it's been fetched, stay). */
    @Synchronized fun note(url: String, entry: Entry): Boolean {
        val key = url.toHttpUrlOrNull()?.let(::keyOf) ?: return false
        if (links.containsKey(key)) return false
        links[key] = entry
        return true
    }
    @Synchronized fun find(url: HttpUrl): Entry? = links[keyOf(url)]

    companion object {
        /** A link's identity, whatever range and request number a fetch adds to it: its answer, format and expiry. */
        fun keyOf(url: HttpUrl): String =
            "${url.host}|${url.queryParameter("ei") ?: url.queryParameter("id")}|${url.queryParameter("itag")}|${url.queryParameter("expire")}"
    }
}
