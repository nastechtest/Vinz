package com.vinz.music.download

import androidx.media3.datasource.HttpDataSource
import com.vinz.music.data.friendlyMessage
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a download failed, in words (GitHub #98: "14 downloads failed" said nothing about which, or why). Media3 hands
 * the download manager's listener the exception a download ended on; this turns it into one short line for the list
 * of failed downloads.
 */
object DownloadFailures {
    fun describe(e: Throwable?): String {
        if (e == null) return "It stopped without saying why"
        val chain = generateSequence(e) { it.cause }.take(10).toList()
        val http = chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
        val messages = chain.mapNotNull { it.message?.lowercase() }
        return when {
            http != null -> when (http.responseCode) {
                403 -> "YouTube refused the download link (403). Trying again gets a fresh one"
                404, 410 -> "YouTube no longer has this song (${http.responseCode})"
                416 -> "The saved part didn't match the song any more (416). Trying again starts it over"
                429 -> "YouTube asked Vinz to slow down (429). Try again in a few minutes"
                in 500..599 -> "YouTube had trouble answering (${http.responseCode}). Try again in a bit"
                else -> "YouTube answered with an error (${http.responseCode})"
            }
            messages.any { "enospc" in it || "no space left" in it } -> "The phone ran out of space"
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } -> "The phone was offline"
            chain.any { it is SocketTimeoutException } -> "The connection timed out"
            chain.any { it is SSLException } -> "Couldn't connect securely (public Wi-Fi or a VPN?)"
            messages.any { "sign in" in it || "login_required" in it || "age" in it && "restrict" in it } ->
                "Needs a signed-in account (age-restricted or Premium-only)"
            messages.any { "unavailable" in it || "not available" in it } -> "This song isn't available to download"
            chain.any { it is HttpDataSource.HttpDataSourceException } -> "The connection dropped part-way"
            else -> e.friendlyMessage(if (chain.any { it is IOException }) "The connection dropped part-way" else "Something went wrong")
        }
    }
}
