package com.vinz.music

import android.content.Context
import com.vinz.music.data.spotify.SessionFetch
import com.vinz.music.data.spotify.SessionFetcher
import com.vinz.music.data.spotify.SessionRules
import com.vinz.music.data.spotify.SessionToken
import com.vinz.music.data.spotify.SessionTokens
import com.vinz.music.data.spotify.SessionWeb
import com.vinz.music.data.spotify.SpotifyCodeBook
import com.vinz.music.data.spotify.SpotifyError
import com.vinz.music.data.spotify.SpotifyStore
import com.vinz.music.data.spotify.SpotifyWeb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Sign in to Spotify" (1.8.7.5): the kept session token, what Spotify's token answer means, which requests are
 * Spotify's, the reader that can only read, and the codes that can be refreshed from Vinz's own repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SpotifySessionTest {
    private val app = RuntimeEnvironment.getApplication()
    private val auth = app.getSharedPreferences(SpotifyStore.AUTH_PREFS, Context.MODE_PRIVATE)
    private val prefs = app.getSharedPreferences(SpotifyStore.PREFS, Context.MODE_PRIVATE)
    private val store = SpotifyStore(auth, prefs, SpotifyFixtures.ReverseBox)
    private var clock = 1_000_000L
    private val net = SpotifyFixtures.FakeSpotify()

    private var fetches = 0
    private var next: () -> SessionFetch = { SessionFetch.Got(SessionToken("tok-$fetches", clock + 3_600_000)) }
    private val fetcher = SessionFetcher { fetches++; next() }
    private val tokens = SessionTokens(store, fetcher, now = { clock })
    private val book = SpotifyCodeBook(prefs, net.client, now = { clock })
    private val web = SpotifyWeb(net.client, tokens, book, userAgent = { "UA" }, now = { clock }, pause = { clock += it })

    @Before fun clean() {
        auth.edit().clear().commit()
        prefs.edit().clear().commit()
    }

    private fun bodyOf(i: Int) = Buffer().also { net.requests[i].body!!.writeTo(it) }.readUtf8()

    // ---- what the token answer means ----

    @Test fun aSignedInAnswerGivesTheTokenAndItsEnd() {
        val f = SessionRules.answer(200, """{"clientId":"c","accessToken":"abc","accessTokenExpirationTimestampMs":5000000,"isAnonymous":false}""", now = 1_000_000)
        f as SessionFetch.Got
        assertEquals("abc", f.token.access)
        assertEquals(5_000_000L, f.token.expiresAt)
        assertFalse("a token never prints itself", f.token.toString().contains("abc"))
    }

    @Test fun anAnswerForNobodyMeansSignedOut() {
        assertTrue(SessionRules.answer(200, """{"accessToken":"anon","isAnonymous":true}""", 0) is SessionFetch.SignedOut)
        assertTrue(SessionRules.answer(401, "", 0) is SessionFetch.SignedOut)
    }

    @Test fun aBrokenAnswerIsAFailureNotASignOut() {
        assertTrue(SessionRules.answer(200, "<html>", 0) is SessionFetch.Failed)
        assertTrue(SessionRules.answer(200, """{"isAnonymous":false}""", 0) is SessionFetch.Failed)
        assertTrue(SessionRules.answer(503, "", 0) is SessionFetch.Failed)
        // No end time in the answer: half an hour, not for ever.
        val f = SessionRules.answer(200, """{"accessToken":"abc"}""", now = 1000) as SessionFetch.Got
        assertEquals(1000 + 30 * 60_000L, f.token.expiresAt)
    }

    // ---- which requests are Spotify's ----

    @Test fun onlySpotifysOwnHosts() {
        for (h in listOf("open.spotify.com", "spotify.com", "api-partner.spotify.com", "i.scdn.co", "open.spotifycdn.com")) assertTrue(h, SessionRules.isSpotify(h))
        for (h in listOf("evilspotify.com", "open.spotify.com.evil.example", "www.google.com", "", null)) assertFalse("$h", SessionRules.isSpotify(h))
        assertTrue(SessionRules.isSpotify(java.net.URI(SpotifyWeb.URL).host))
        assertTrue(SessionRules.isSpotify(java.net.URI(SessionWeb.PLAYER).host))
    }

    @Test fun onlyThePagesOwnTokenRequestIsCarried() {
        assertTrue(SessionRules.isTokenRequest("https://open.spotify.com/api/token?reason=init&productType=web-player&totp=1&totpVer=61"))
        assertFalse(SessionRules.isTokenRequest("http://open.spotify.com/api/token"))
        assertFalse(SessionRules.isTokenRequest("https://open.spotify.com/api/server-time"))
        assertFalse(SessionRules.isTokenRequest("https://open.spotify.com.evil.example/api/token"))
        assertFalse(SessionRules.isTokenRequest("https://evil.example/api/token?x=open.spotify.com"))
        assertFalse(SessionRules.isTokenRequest("https://accounts.spotify.com/api/token"))
    }

    @Test fun theCarriedRequestKeepsThePagesHeadersAndAddsTheCookies() {
        val out = SessionRules.carried(mapOf("User-Agent" to "UA", "Referer" to "https://open.spotify.com/", "accept-encoding" to "gzip", "cookie" to "old=1"), "sp_dc=v")
        assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://open.spotify.com/", "Cookie" to "sp_dc=v"), out)
        assertFalse(SessionRules.carried(mapOf("A" to "b"), null).containsKey("Cookie"))
    }

    @Test fun theUserAgentLosesItsWebViewMarks() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-A166U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36",
            SessionWeb.userAgent("Mozilla/5.0 (Linux; Android 16; SM-A166U; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.0.0 Mobile Safari/537.36"),
        )
    }

    // ---- the kept token ----

    @Test fun notSignedInMeansNoFetchAtAll() = runBlocking {
        try { tokens.token(); fail() } catch (e: SpotifyError.NotConnected) { }
        assertEquals(0, fetches)
    }

    @Test fun aKeptTokenIsReusedUntilItRunsOutAndIsSealedOnThePhone() = runBlocking {
        assertTrue(tokens.begin())
        assertTrue(store.sessionOn)
        assertEquals("tok-1", tokens.token())
        assertEquals("tok-1", tokens.token())
        assertEquals(1, fetches)
        assertFalse("never stored as it is", auth.all.values.any { it == "tok-1" })
        clock += 3_600_000 - SessionTokens.EARLY_MS + 1
        assertEquals("tok-2", tokens.token())
        assertEquals(2, fetches)
    }

    @Test fun twoReadersAtOnceMakeOneFetch() = runBlocking {
        tokens.begin()
        clock += 4_000_000
        val got = listOf(async { tokens.token() }, async { tokens.token() }).awaitAll()
        assertEquals(listOf("tok-2", "tok-2"), got)
        assertEquals(2, fetches)
    }

    @Test fun aFailedFetchIsNotTriedAgainForTenMinutes() = runBlocking {
        tokens.begin()
        clock += 4_000_000
        next = { SessionFetch.Failed("the page asked for no token") }
        repeat(3) { try { tokens.token(); fail() } catch (e: SpotifyError.Unavailable) { } }
        assertEquals("one fetch, no loop", 2, fetches)
        assertFalse("a failure is not a sign-out", store.sessionEnded)
        clock += SessionTokens.RETRY_MS + 1
        next = { SessionFetch.Got(SessionToken("tok-late", clock + 3_600_000)) }
        assertEquals("tok-late", tokens.token())
    }

    @Test fun anAnswerForNobodyEndsTheSessionAndStopsFetching() = runBlocking {
        tokens.begin()
        clock += 4_000_000
        next = { SessionFetch.SignedOut }
        try { tokens.token(); fail() } catch (e: SpotifyError.Revoked) { }
        assertTrue(store.sessionEnded)
        try { tokens.token(); fail() } catch (e: SpotifyError.Revoked) { }
        assertEquals(2, fetches)
    }

    @Test fun clearingTheSessionLeavesTheOwnKeyTokens() {
        store.saveTokens(com.vinz.music.data.spotify.SpotifyTokens("own", "ref", clock + 1000, ""))
        store.saveSession(SessionToken("sess", clock + 1000))
        store.clearSession()
        assertFalse(store.sessionOn)
        assertNull(store.sessionToken())
        assertEquals("own", store.tokens()?.access)
        assertTrue(store.connected)
    }

    @Test fun theSessionIsInNoBackup() {
        store.saveSession(SessionToken("sess", clock + 1000))
        assertTrue("kept in the file no backup carries", auth.contains("session_access"))
        assertNull(com.vinz.music.data.Backup.prefsPart(SpotifyStore.AUTH_PREFS))
        assertFalse(prefs.all.values.any { it.toString().contains("sess") })
    }

    // ---- the reader ----

    private val liked = """{"data":{"me":{"library":{"tracks":{"totalCount":2,"items":[]}}}}}"""

    @Test fun aReadSendsThePersistedCodeWithTheToken() = runBlocking {
        tokens.begin()
        net.routes[SpotifyWeb.URL] = { 200 to liked }
        val data = web.read("fetchLibraryTracks", JsonObject(mapOf("offset" to JsonPrimitive(0), "limit" to JsonPrimitive(50))))
        assertTrue(data.containsKey("me"))
        val r = net.requests.single()
        assertEquals("POST", r.method)
        assertEquals("Bearer tok-1", r.header("Authorization"))
        assertEquals("WebPlayer", r.header("app-platform"))
        val sent = bodyOf(0)
        assertTrue(sent, sent.contains("\"operationName\":\"fetchLibraryTracks\""))
        assertTrue(sent, sent.contains(SpotifyCodeBook.BUILT_IN.getValue("fetchLibraryTracks").first()))
    }

    @Test fun nothingButTheReadsCanBeAsked() = runBlocking {
        tokens.begin()
        net.routes[SpotifyWeb.URL] = { 200 to liked }
        for (op in listOf("addToLibrary", "removeFromLibrary", "addToPlaylist", "removeFromPlaylist", "moveItemsInPlaylist", "editPlaylistAttributes", "searchTracks", "")) {
            try { web.read(op); fail(op) } catch (e: SpotifyError.Forbidden) { }
        }
        assertEquals("nothing was sent", 0, net.requests.size)
        // 1.8.7.6: the catalogue's search and a track's canvas ("Spotify's canvas first"), both reads, join the four.
        assertEquals(setOf("profileAttributes", "libraryV3", "fetchLibraryTracks", "fetchPlaylist", "searchDesktop", "canvas"), SpotifyWeb.READS)
        assertEquals(SpotifyWeb.READS, SpotifyCodeBook.BUILT_IN.keys)
    }

    @Test fun aRefusedCodeFallsBackToTheOlderOne() = runBlocking {
        tokens.begin()
        var n = 0
        net.routes[SpotifyWeb.URL] = { if (n++ == 0) 200 to """{"errors":[{"message":"PersistedQueryNotFound"}]}""" else 200 to """{"data":{"me":{}}}""" }
        web.read("libraryV3")
        assertEquals(2, net.requests.size)
        assertTrue(bodyOf(1).contains(SpotifyCodeBook.BUILT_IN.getValue("libraryV3")[1]))
    }

    @Test fun whenEveryCodeIsRefusedTheFileInVinzsRepositoryIsReadOnce() = runBlocking {
        tokens.begin()
        val newer = "a".repeat(64)
        net.routes[SpotifyCodeBook.URL] = { 200 to """{"fetchLibraryTracks":"$newer","addToLibrary":"${"b".repeat(64)}","libraryV3":"not a code"}""" }
        net.routes[SpotifyWeb.URL] = { 412 to "" }
        try { web.read("fetchLibraryTracks"); fail() } catch (e: SpotifyError.Unavailable) { assertEquals(SpotifyWeb.CODES_CHANGED, e.message?.substringAfter(": ")) }
        assertEquals(1, net.count(SpotifyCodeBook.URL))
        assertEquals("the built-in one, then the fetched one", 2, net.count(SpotifyWeb.URL))
        assertTrue(bodyOf(net.requests.size - 1).contains(newer))
        assertEquals(newer, book.codes("fetchLibraryTracks").first())
        assertFalse("a write is never taken from the file", book.codes("addToLibrary").isNotEmpty())
        assertEquals("a malformed code is dropped", SpotifyCodeBook.BUILT_IN.getValue("libraryV3"), book.codes("libraryV3"))
        // Asked again straight away: the file is not fetched a second time.
        try { web.read("fetchLibraryTracks"); fail() } catch (e: SpotifyError.Unavailable) { }
        assertEquals(1, net.count(SpotifyCodeBook.URL))
    }

    @Test fun theCodesFileInTheRepositoryIsWhatTheAppWouldTake() {
        val file = listOf(java.io.File("../spotify/codes.json"), java.io.File("spotify/codes.json")).first { it.exists() }
        val codes = SpotifyCodeBook.parse(file.readText())
        assertEquals("every read, and nothing else", SpotifyWeb.READS, codes.keys)
        // The file is never behind the app: each of its codes is one the app knows, or newer than all of them.
        assertEquals(SpotifyCodeBook.BUILT_IN.mapValues { it.value.first() }, codes)
        assertTrue(SpotifyCodeBook.URL.endsWith("/nastechtest/Vinz/main/spotify/codes.json"))
    }

    @Test fun aTooManyRequestsAnswerQuietsEveryRead() = runBlocking {
        tokens.begin()
        net.headers["Retry-After"] = "120"
        net.routes[SpotifyWeb.URL] = { 429 to "" }
        try { web.read("profileAttributes"); fail() } catch (e: SpotifyError.RateLimited) { assertEquals(120_000L, e.retryAfterMs) }
        try { web.read("libraryV3"); fail() } catch (e: SpotifyError.RateLimited) { }
        assertEquals("the second read never left", 1, net.requests.size)
    }

    @Test fun anExpiredTokenIsReplacedOnce() = runBlocking {
        tokens.begin()
        var n = 0
        net.routes[SpotifyWeb.URL] = { if (n++ == 0) 401 to "" else 200 to """{"data":{"me":{}}}""" }
        web.read("profileAttributes")
        assertEquals("Bearer tok-2", net.requests.last().header("Authorization"))
        assertEquals(2, fetches)
    }
}
