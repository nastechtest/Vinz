package com.vinz.music.data.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * "Sign in to Spotify" (1.8.7.5, plan-spotify-session.md): the listener signs in on Spotify's own page inside Vinz,
 * and Vinz reads their library with that session, as Spotify's web player does. Unofficial and against Spotify's
 * terms; the app says so before the sign-in. Read only: see [SpotifyWeb.READS].
 *
 * This file is the part with no Android in it: what a token answer means, which requests are Spotify's, and the kept
 * token. The WebView that gets a token is [SessionWeb].
 */

/** A web-player access token: about an hour long, with nothing to renew it but asking again. */
class SessionToken(val access: String, val expiresAt: Long) {
    override fun toString() = "SessionToken(length=${access.length})"
}

/** One try at a new token. */
sealed class SessionFetch {
    class Got(val token: SessionToken) : SessionFetch()
    /** Spotify answered, but for nobody: the sign-in has ended. */
    object SignedOut : SessionFetch()
    /** No answer worth keeping (no network, the page changed, time ran out). [why] never holds a token or a cookie. */
    class Failed(val why: String) : SessionFetch()
}

fun interface SessionFetcher {
    suspend fun fresh(): SessionFetch
}

object SessionRules {
    private val ROOTS = listOf("spotify.com", "scdn.co", "spotifycdn.com")
    private val json = Json { ignoreUnknownKeys = true }

    /** Spotify's own hosts: the only ones the hidden page may reach. */
    fun isSpotify(host: String?): Boolean {
        val h = host.orEmpty().lowercase().trimEnd('.')
        return h.isNotEmpty() && ROOTS.any { h == it || h.endsWith(".$it") }
    }

    /** The web player's own request for its token (seen on the A16, 2026-11-15: `open.spotify.com/api/token`). */
    fun isTokenRequest(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.host == "open.spotify.com" && u.encodedPath == "/api/token"
    }

    /**
     * The page's request as Vinz sends it on: the page's own headers and the session's cookies. Accept-Encoding is
     * left to OkHttp (a copied one would hand back packed bytes).
     */
    fun carried(page: Map<String, String>, cookie: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        page.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true) && !k.equals("Cookie", true)) out[k] = v }
        if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        return out
    }

    /** What the token answer means. A token with no end time is given 30 minutes. */
    fun answer(code: Int, body: String, now: Long): SessionFetch {
        if (code == 401) return SessionFetch.SignedOut
        if (code !in 200..299) return SessionFetch.Failed("HTTP $code")
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return SessionFetch.Failed("not json")
        if ((o["isAnonymous"] as? JsonPrimitive)?.booleanOrNull == true) return SessionFetch.SignedOut
        val access = (o["accessToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return SessionFetch.Failed("no token in the answer")
        val until = (o["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it > now } ?: (now + 30 * 60_000L)
        return SessionFetch.Got(SessionToken(access, until))
    }
}

/**
 * The session's token for whoever reads: the kept one while it lasts, a new one (one fetch at a time) when it has run
 * out. A fetch that fails isn't tried again for [RETRY_MS], so nothing loops; an answer for nobody ends the session.
 */
class SessionTokens(
    private val store: SpotifyStore,
    private val fetcher: SessionFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var noFetchBefore = 0L

    suspend fun token(force: Boolean = false): String = lock.withLock {
        if (!store.sessionOn) throw SpotifyError.NotConnected()
        if (store.sessionEnded) throw SpotifyError.Revoked("signed out of Spotify")
        val kept = store.sessionToken()
        if (!force && kept != null && kept.expiresAt - EARLY_MS > now()) return kept.access
        if (now() < noFetchBefore) throw SpotifyError.Unavailable(0, "no token lately")
        take(fetcher.fresh())
    }

    /** The first token, right after the listener signed in: this is what turns the session on. */
    suspend fun begin(): Boolean = lock.withLock {
        noFetchBefore = 0
        when (val f = fetcher.fresh()) {
            is SessionFetch.Got -> { store.saveSession(f.token); true }
            else -> false
        }
    }

    private fun take(f: SessionFetch): String {
        return when (f) {
            is SessionFetch.Got -> { store.saveSession(f.token); noFetchBefore = 0; f.token.access }
            SessionFetch.SignedOut -> { store.sessionEnded = true; throw SpotifyError.Revoked("signed out of Spotify") }
            is SessionFetch.Failed -> { noFetchBefore = now() + RETRY_MS; throw SpotifyError.Unavailable(0, f.why) }
        }
    }

    companion object {
        /** A token this close to its end is replaced before use. */
        const val EARLY_MS = 60_000L
        const val RETRY_MS = 10 * 60_000L
    }
}
