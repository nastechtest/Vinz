package com.vinz.music.data

import com.vinz.music.data.innertube.InnerTubeException
import com.vinz.music.data.stream.StreamException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What to tell the listener when something failed: plain words for connection trouble (instead of
 * `Unable to resolve host "music.youtube.com"…`), Vinz's and YouTube's own messages otherwise.
 */
fun Throwable.friendlyMessage(fallback: String = "Something went wrong"): String {
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    val code = chain.firstNotNullOfOrNull { (it as? InnerTubeException)?.code?.takeIf { c -> c > 0 } }
    return when {
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            "You're offline. Check your connection and try again."
        chain.any { it is SocketTimeoutException } -> "Vinz Music took too long to answer. Try again."
        chain.any { it is SSLException } -> "Couldn't connect securely. On public Wi-Fi or a VPN? Try another network."
        code == 429 -> "YouTube is limiting requests right now. Try again in a few minutes."
        code != null && code >= 500 -> "Vinz Music is having trouble right now. Try again in a bit."
        else -> chain.firstOrNull { it is StreamException || it is InnerTubeException }?.message?.takeIf { it.isNotBlank() }
            ?: chain.mapNotNull { it.message }.firstOrNull(::readable)
            ?: fallback
    }
}

/** A message a person can read: not a class name, stack detail or a wall of text. */
private fun readable(m: String): Boolean =
    m.isNotBlank() && m.length <= 160 && !m.contains("Exception") && !m.startsWith("java.") && m != "Source error"
