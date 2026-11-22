package com.vinz.music.data.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

/**
 * Gets the session's token the way Spotify's web player does, without copying any of Spotify's code: a WebView that is
 * never shown loads `open.spotify.com`; when the page asks for its own token, Vinz sends that one request on
 * unchanged (its address, its headers, the session's cookies) with OkHttp, keeps the token from the answer, and gives
 * the page an empty answer. No script is put into Spotify's page and nothing is read out of it. Because the page gets
 * no token it never becomes a player. Every request to a host that isn't Spotify's own is answered empty.
 *
 * Tried on the Galaxy A16, 2026-11-15 (`.claude/release-check/1.8.7.5/spotify-trial.md`): the page asks about 2 s
 * after loading; ten fetches of ten arrived, about 1.4 s each.
 */
class SessionWeb(
    private val context: Context,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionFetcher {

    override suspend fun fresh(): SessionFetch = withContext(Dispatchers.Main) {
        val out = CompletableDeferred<SessionFetch>()
        val web = runCatching { make(out) }.getOrElse { return@withContext SessionFetch.Failed("no WebView: ${it.javaClass.simpleName}") }
        try {
            web.loadUrl(PLAYER)
            withTimeoutOrNull(LIMIT_MS) { out.await() } ?: SessionFetch.Failed("the page asked for no token in ${LIMIT_MS / 1000} s")
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun make(out: CompletableDeferred<SessionFetch>): WebView {
        val web = WebView(context.applicationContext)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.settings.userAgentString = userAgent(web.settings.userAgentString)
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            // Called on the WebView's own network thread, so the carried request may block here.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!SessionRules.isSpotify(request.url.host)) return empty()
                if (!SessionRules.isTokenRequest(url)) return null
                if (!out.isCompleted) out.complete(carry(url, request.requestHeaders.orEmpty()))
                return empty()
            }
        }
        return web
    }

    private fun carry(url: String, pageHeaders: Map<String, String>): SessionFetch = runCatching {
        val headers = SessionRules.carried(pageHeaders, CookieManager.getInstance().getCookie(url))
        val req = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r -> SessionRules.answer(r.code, r.body.string(), now()) }
    }.getOrElse { SessionFetch.Failed(it.javaClass.simpleName) }

    private fun empty() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val PLAYER = "https://open.spotify.com/"
        const val SIGN_IN = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        const val LIMIT_MS = 30_000L

        /** A WebView's user agent without its "; wv" and "Version/x" marks (as the Google and Discord sign-ins do). */
        fun userAgent(webViewAgent: String): String =
            webViewAgent.replace(Regex(";\\s*wv\\)"), ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")

        fun userAgent(context: Context): String = userAgent(WebSettings.getDefaultUserAgent(context))

        /**
         * For the sign-in screen's own (visible) WebView, from its `shouldInterceptRequest`: once signed in, Spotify's
         * page asks for a token to become a player. It gets an empty answer there too, so the page on screen can never
         * show up among the listener's Spotify devices or play anything. Null for every other request (let it through:
         * the sign-in page needs its captcha's hosts).
         */
        fun signInPageAnswer(request: WebResourceRequest): WebResourceResponse? =
            if (SessionRules.isTokenRequest(request.url.toString())) {
                WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            } else null

        private val COOKIE_HOSTS = listOf(
            "https://open.spotify.com", "https://accounts.spotify.com", "https://www.spotify.com", "https://spotify.com",
            "https://challenge.spotify.com", "https://api-partner.spotify.com",
        )

        /** True while the WebView's store holds Spotify's session cookie (the sign-in page has finished). */
        fun signedIn(): Boolean = runCatching {
            CookieManager.getInstance().getCookie(PLAYER).orEmpty().split(';').any { it.trim().startsWith("sp_dc=") && it.trim().length > 6 }
        }.getOrDefault(false)

        /**
         * Signs out on this phone: Spotify's cookies (each by name, for its host and for the whole site: the store has
         * no "this site only" clear, and the Google sign-in's cookies live in the same store) and the pages' storage.
         */
        fun signOut() {
            runCatching {
                val cm = CookieManager.getInstance()
                for (host in COOKIE_HOSTS) {
                    val names = cm.getCookie(host).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
                    for (name in names) {
                        cm.setCookie(host, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(host, "$name=; Max-Age=0; Path=/; Domain=.spotify.com")
                    }
                }
                cm.flush()
                COOKIE_HOSTS.forEach { android.webkit.WebStorage.getInstance().deleteOrigin(it) }
            }
        }
    }
}
