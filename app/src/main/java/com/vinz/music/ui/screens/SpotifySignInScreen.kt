package com.vinz.music.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vinz.music.R
import com.vinz.music.data.spotify.SessionWeb
import com.vinz.music.data.spotify.Spotify
import com.vinz.music.tr
import com.vinz.music.ui.components.VinzButton
import com.vinz.music.ui.components.VinzTextButton
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The decidable parts of "Sign in to Spotify" (1.8.7.5), kept apart from the WebView so a JVM test can reach them. */
object SpotifySignIn {
    const val PLAYER_HOST = "open.spotify.com"

    /**
     * True when the page on screen is Spotify's web player (exactly `https://open.spotify.com`, no lookalike host, not
     * plain http) and the session cookie exists: the sign-in has finished and Vinz takes over.
     */
    fun landedSignedIn(url: String?, signedIn: Boolean): Boolean {
        if (!signedIn || url.isNullOrBlank()) return false
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!u.scheme.equals("https", ignoreCase = true)) return false
        if (u.rawUserInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        return u.host?.lowercase() == PLAYER_HOST
    }

    /** Lets the finish run once per try: the page can report landing several times (start, finish, redirects). */
    class Once {
        private var started = false
        /** True the first time only, until [reset]. */
        fun start(): Boolean = if (started) false else { started = true; true }
        fun reset() { started = false }
    }
}

/**
 * "Before you sign in": what signing in to Spotify through its web page means, in plain words, before the page opens.
 * Not now leaves without anything stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyBeforeScreen(onSignIn: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("spot_signin_before")) {
        TopAppBar(
            title = { Text(tr(R.string.spot_signin_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(VinzIcons.ArrowBack, tr(R.string.back)) } },
        )
        Column(
            Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 520.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(tr(R.string.spot_signin_before_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            for (line in listOf(
                R.string.spot_signin_terms, R.string.spot_signin_risk, R.string.spot_signin_reads_only,
                R.string.spot_signin_on_phone, R.string.spot_signin_other_buttons,
            )) {
                Text(tr(line), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 14.dp))
            }
        }
        Column(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 520.dp).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            VinzButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("spot_signin_go")) {
                Text(tr(R.string.sign_in))
            }
            VinzTextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("spot_signin_not_now")) {
                Text(tr(R.string.not_now))
            }
        }
    }
}

/**
 * Spotify's own sign-in page. Vinz reads nothing out of it and puts nothing into it: once the page lands on the web
 * player signed in ([SessionWeb.signedIn]), the page stops and [Spotify.sessionSignedIn] fetches the session's token
 * itself. The page's own token requests get an empty answer ([SessionWeb.signInPageAnswer]), so it never becomes a
 * player. Spotify's cookies are left as they are on the way out: the session lives in them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val c = LocalContainer.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0f) }
    /** Signed in on the page; Vinz is getting the session ready. */
    var connecting by remember { mutableStateOf(false) }
    /** The finish didn't work: the reason to show, when there is one. */
    var notFinished by remember { mutableStateOf<String?>(null) }
    /** Why Spotify's page didn't load, when it didn't. */
    var failed by remember { mutableStateOf<String?>(null) }
    /** True once the session is ready: the screen goes. */
    var finished by remember { mutableStateOf(false) }
    val once = remember { SpotifySignIn.Once() }

    fun maybeFinish(view: WebView, url: String?) {
        if (!SpotifySignIn.landedSignedIn(url, SessionWeb.signedIn())) return
        if (!once.start()) return
        view.stopLoading()
        connecting = true
        CookieManager.getInstance().flush()
        // In the app's scope: leaving mid-way must not cut the connection off half made.
        c.scope.launch(Dispatchers.Main) {
            val ok = c.spotify.sessionSignedIn()
            if (ok) finished = true
            else {
                connecting = false
                notFinished = (c.spotify.connection.value as? Spotify.Connection.Failed)?.message.orEmpty()
            }
        }
    }

    fun again() {
        failed = null
        notFinished = null
        progress = 0f
        once.reset()
        web?.loadUrl(SessionWeb.SIGN_IN)
    }

    LaunchedEffect(finished) { if (finished) onDone() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("spot_signin_page")) {
        TopAppBar(
            title = { Text(tr(R.string.spot_signin_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(VinzIcons.ArrowBack, tr(R.string.back)) } },
        )
        val browsing = !connecting && notFinished == null && failed == null
        if (progress < 1f && browsing) com.vinz.music.ui.components.VinzProgressBar({ progress }, Modifier.fillMaxWidth(), height = 2.dp)
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopLoading(); it.destroy() },
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Fills the box (see DiscordLoginScreen: a WRAP_CONTENT WebView measures viewport units as 0).
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.userAgentString = SessionWeb.userAgent(settings.userAgentString)
                        CookieManager.getInstance().setAcceptCookie(true)
                        // The sign-in page's check that a person is signing in runs in a frame from another site.
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                SessionWeb.signInPageAnswer(request)?.let { return it }
                                return super.shouldInterceptRequest(view, request)
                            }

                            // Spotify's links to its app (spotify:, intent:) would dead-end here.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme != "http" && request.url.scheme != "https"

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (!connecting) failed = null
                                maybeFinish(view, url)
                            }

                            override fun onPageFinished(view: WebView, url: String) { maybeFinish(view, url) }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                // Stopping the page once signed in can end its load with an error: not one to show.
                                if (request.isForMainFrame && !connecting && !finished) failed = error.description?.toString().orEmpty()
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode >= 400 && !connecting && !finished) {
                                    failed = tr(R.string.spot_signin_http, response.statusCode)
                                }
                            }
                        }
                        loadUrl(SessionWeb.SIGN_IN)
                        web = this
                    }
                },
            )
            when {
                connecting || finished -> Cover {
                    com.vinz.music.ui.components.VinzSpinner(Modifier.size(30.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(tr(R.string.spot_signin_connecting), style = MaterialTheme.typography.titleMedium)
                }
                notFinished != null -> Cover {
                    Text(tr(R.string.spot_signin_failed), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        notFinished?.takeIf { it.isNotBlank() } ?: tr(R.string.spot_signin_failed_why),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    VinzButton(onClick = { again() }) { Text(tr(R.string.try_again)) }
                    VinzTextButton(onClick = onBack) { Text(tr(R.string.back)) }
                }
                failed != null -> Cover {
                    Text(tr(R.string.spot_signin_unreachable), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr(R.string.spot_signin_unreachable_why),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val why = failed.orEmpty()
                    if (why.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(20.dp))
                    VinzButton(onClick = { again() }) { Text(tr(R.string.try_again)) }
                    VinzTextButton(onClick = onBack) { Text(tr(R.string.back)) }
                }
            }
        }
    }
}

/** Covers the page with a message in the middle. */
@Composable
private fun Cover(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
