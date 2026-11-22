package com.vinz.music.playback

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1.8.7 (plan 4.4): whether a song failed because the connection did (a timeout, a dropped or refused connection, no
 * route to YouTube), not because of the song. Such a song is tried again where it stopped ([NetworkRetry]); before, a
 * timeout at 0:40 skipped it. HTTP statuses (403, 410…) and a stream YouTube won't give have their own handling.
 */
internal fun networkFailure(errorCode: Int, error: Throwable): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) return true
    if (errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return false
    return generateSequence(error) { it.cause }.any { e ->
        e is java.net.SocketException || e is java.net.UnknownHostException || e is javax.net.ssl.SSLException ||
            // OkHttp's timeouts ("timeout") and SocketTimeoutException are InterruptedIOExceptions.
            e is java.io.InterruptedIOException || e.javaClass.simpleName == "StreamResetException"
    }
}

/**
 * What happens to a song whose connection failed ([networkFailure]): tried again in place after a short back-off
 * ([BACKOFF_MS]), waited on while the phone is offline (that wait doesn't use up a try), skipped only once the tries
 * are used up. Every path is bounded: tries and waits only start over for another song, or once the song has played
 * on [CLEAR_AFTER_MS] past where it last failed (a retry resumes at that place, so it can never clear its own count).
 */
internal class NetworkRetry(
    private val backoffMs: List<Long> = BACKOFF_MS,
    private val maxWaits: Int = MAX_WAITS,
) {
    sealed interface Step {
        /** Play on from [positionMs] after [afterMs]; [attempt] counts from 1, [last] is the final try. */
        data class Retry(val afterMs: Long, val positionMs: Long, val attempt: Int, val last: Boolean) : Step
        /** Offline: wait for the network, then play on from [positionMs]. */
        data class WaitForNetwork(val positionMs: Long, val wait: Int) : Step
        /** Give up on the song, for [reason]. */
        data class Skip(val reason: String) : Step
    }

    private var songId: String? = null
    private var tries = 0
    private var waits = 0
    private var failedAtMs = 0L

    /** [id] failed at [positionMs] for [what] (e.g. the error code's name); [online]: a fresh check of the network. */
    @Synchronized fun failed(id: String, positionMs: Long, online: Boolean, what: String): Step {
        if (id != songId) { songId = id; tries = 0; waits = 0 }
        failedAtMs = positionMs
        val at = "at ${positionMs / 1000} s"
        if (!online) {
            if (waits < maxWaits) return Step.WaitForNetwork(positionMs, ++waits)
            return Step.Skip("$what $at, still failing after waiting for the network $maxWaits times")
        }
        if (tries < backoffMs.size) {
            val after = backoffMs[tries++]
            return Step.Retry(after, positionMs, tries, last = tries == backoffMs.size)
        }
        return Step.Skip("$what $at, ${backoffMs.size} tries in place used up")
    }

    /** Called while [id] plays at [positionMs]: once it got well past where it failed, a later failure starts afresh. */
    @Synchronized fun playing(id: String, positionMs: Long) {
        if (id == songId && positionMs - failedAtMs >= CLEAR_AFTER_MS) { songId = null; tries = 0; waits = 0 }
    }

    companion object {
        val BACKOFF_MS = listOf(500L, 2_000L, 5_000L)
        const val MAX_WAITS = 10
        const val CLEAR_AFTER_MS = 30_000L
        /** After the network is back, a moment for it to settle before the song is asked for again. */
        const val BACK_ONLINE_MS = 1_000L
        /** How often the network is checked afresh while waiting, as the callback can lag behind ([awaitOnline]). */
        const val RECHECK_MS = 5_000L
    }
}

/**
 * Suspends until [check] says the phone is online. Each round waits for [online] to change to true or [recheckMs],
 * whichever comes first, so a stale [online] never makes it spin.
 */
internal suspend fun awaitOnline(online: StateFlow<Boolean>, check: () -> Boolean, recheckMs: Long = NetworkRetry.RECHECK_MS) {
    while (!check()) withTimeoutOrNull(recheckMs) { online.drop(1).first { it } }
}

/**
 * Songs that failed on the network Vinz was on: when the phone moves to another network (or comes back online), their
 * 5-minute block in the stream resolver ([com.vinz.music.data.stream.RefusedSongs]) is lifted, as the new network may
 * well get them. [take] hands them over (with the song coming up, whose lookup may have failed ahead of time) and
 * starts afresh.
 */
internal class FailedOnThisNetwork(private val max: Int = 200) {
    private val ids = LinkedHashSet<String>()

    @Synchronized fun failed(id: String) {
        ids.remove(id); ids += id
        while (ids.size > max) ids.remove(ids.first())
    }

    @Synchronized fun take(also: Collection<String?> = emptyList()): Set<String> {
        val out = LinkedHashSet(ids).apply { also.filterNotNullTo(this) }
        ids.clear()
        return out
    }
}
