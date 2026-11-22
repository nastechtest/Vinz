package com.vinz.music.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 1.8.7 (plan 4.7): counts for the whole sitting, since the playback service started, where the per-song figures
 * (Stats for nerds' underruns) start again at every song. Written to every diagnostics tick line ([line]) and to
 * `diagnostics/diagnostics-summary.json` under `session` ([json]), which the soak reads.
 */
class SessionCounters(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Serializable
    data class Snapshot(
        /** How long the service has run, ms. */
        val sinceStartMs: Long = 0,
        val underruns: Int = 0,
        /** Playback waiting for data while it should play (the first load of a song isn't counted). */
        val bufferingEvents: Int = 0,
        val bufferingTotalMs: Long = 0,
        val bufferingLongestMs: Long = 0,
        /** Player errors by Media3's code name (ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT…). */
        val errorsByCode: Map<String, Int> = emptyMap(),
        /** Streams asked for afresh after an error. */
        val reResolves: Int = 0,
        /** Errors whose answer was HTTP 403. */
        val http403: Int = 0,
        /** Songs given up on after an error (moved on from). */
        val skipsByError: Int = 0,
    )

    @Serializable
    private data class Summary(val session: Snapshot)

    private var startedAt = now()
    private var s = Snapshot()
    private var bufferingSince = -1L

    @Synchronized fun reset() { startedAt = now(); s = Snapshot(); bufferingSince = -1 }

    @Synchronized fun underrun() { s = s.copy(underruns = s.underruns + 1) }

    /** Playback started waiting for data; a second call while waiting changes nothing. */
    @Synchronized fun bufferingStarted() { if (bufferingSince < 0) bufferingSince = now() }

    /** It's playing again (or stopped): the wait counts, with its length. Nothing if no wait was going on. */
    @Synchronized fun bufferingEnded() {
        if (bufferingSince < 0) return
        val took = (now() - bufferingSince).coerceAtLeast(0)
        bufferingSince = -1
        s = s.copy(bufferingEvents = s.bufferingEvents + 1, bufferingTotalMs = s.bufferingTotalMs + took, bufferingLongestMs = maxOf(s.bufferingLongestMs, took))
    }

    @Synchronized fun error(codeName: String, http: Int?) {
        s = s.copy(errorsByCode = s.errorsByCode + (codeName to (s.errorsByCode[codeName] ?: 0) + 1), http403 = s.http403 + if (http == 403) 1 else 0)
    }

    @Synchronized fun reResolve() { s = s.copy(reResolves = s.reResolves + 1) }

    @Synchronized fun skippedByError() { s = s.copy(skipsByError = s.skipsByError + 1) }

    @Synchronized fun snapshot(): Snapshot = s.copy(sinceStartMs = now() - startedAt)

    /** For the tick line: "session 12 min: underruns 1, buffering 2 (3.4 s, longest 2.1 s), errors 1, re-resolves 1, 403s 0, skips 0". */
    fun line(): String = snapshot().run {
        "session ${sinceStartMs / 60_000} min: underruns $underruns, buffering $bufferingEvents (%.1f s, longest %.1f s), errors ${errorsByCode.values.sum()}, re-resolves $reResolves, 403s $http403, skips $skipsByError"
            .format(java.util.Locale.US, bufferingTotalMs / 1000.0, bufferingLongestMs / 1000.0)
    }

    /** `{"session": {...}}`. */
    fun json(): String = JSON.encodeToString(Summary.serializer(), Summary(snapshot()))

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
