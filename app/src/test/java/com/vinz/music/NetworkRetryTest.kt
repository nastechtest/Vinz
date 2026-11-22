package com.vinz.music

import androidx.media3.common.PlaybackException
import com.vinz.music.data.NetworkSwitch
import com.vinz.music.data.stream.RefusedSongs
import com.vinz.music.playback.FailedOnThisNetwork
import com.vinz.music.playback.NetworkRetry
import com.vinz.music.playback.NetworkRetry.Step
import com.vinz.music.playback.awaitOnline
import com.vinz.music.playback.networkFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 1.8.7 (plan 4.4): a timeout mid-song skipped the song. A song whose connection failed is now tried again where it
 * stopped, waited on while offline, and skipped only once its tries are used up, with the reason.
 */
class NetworkRetryTest {
    /** A stand-in for the player and the service's handling of each step: where the song plays on from, or a skip. */
    private class FakePlayer(val retry: NetworkRetry = NetworkRetry()) {
        var positionMs = 0L
        var playing = true
        var online = true
        val waitedMs = ArrayList<Long>()
        var skippedFor: String? = null
        var waitingForNetwork = false

        /** The player stops at [positionMs] with a timeout; the service decides. */
        fun timeout(id: String = "song") {
            playing = false
            when (val step = retry.failed(id, positionMs, online, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")) {
                is Step.Retry -> { waitedMs += step.afterMs; positionMs = step.positionMs; playing = true }
                is Step.WaitForNetwork -> { waitingForNetwork = true; positionMs = step.positionMs }
                is Step.Skip -> skippedFor = step.reason
            }
        }

        fun networkBack() { online = true; if (waitingForNetwork) { waitingForNetwork = false; playing = true } }
    }

    @Test fun aTimeoutAt40SecondsPlaysOnFrom40Seconds() {
        val p = FakePlayer()
        p.positionMs = 40_000
        p.timeout()
        assertTrue("it plays again", p.playing)
        assertEquals("from where it stopped", 40_000L, p.positionMs)
        assertEquals("after a short wait", listOf(500L), p.waitedMs)
        assertNull("not skipped", p.skippedFor)
    }

    @Test fun threeInARowAtThePlaceSkipWithTheReason() {
        val p = FakePlayer()
        p.positionMs = 40_000
        repeat(3) { p.timeout() }
        assertEquals("backed off longer each time", listOf(500L, 2_000L, 5_000L), p.waitedMs)
        assertNull("three tries before giving up", p.skippedFor)
        p.timeout()
        val reason = p.skippedFor
        assertTrue("skipped, saying why: $reason", reason != null && "TIMEOUT" in reason && "40 s" in reason && "3 tries" in reason)
    }

    @Test fun theLastTryIsMarkedSoItsStreamIsAskedForAfresh() {
        val r = NetworkRetry()
        val steps = (1..3).map { r.failed("a", 1_000, online = true, what = "x") as Step.Retry }
        assertEquals(listOf(false, false, true), steps.map { it.last })
    }

    @Test fun offlineItWaitsWithoutUsingATryThenResumes() {
        val p = FakePlayer()
        p.positionMs = 40_000
        p.online = false
        p.timeout()
        assertTrue("waiting for the network", p.waitingForNetwork)
        assertFalse("not playing meanwhile", p.playing)
        assertNull(p.skippedFor)
        p.networkBack()
        assertTrue(p.playing)
        assertEquals(40_000L, p.positionMs)
        // The wait used no try: three are still there.
        repeat(3) { p.timeout() }
        assertNull("the wait didn't count as a try", p.skippedFor)
        assertEquals(3, p.waitedMs.size)
    }

    @Test fun waitingForTheNetworkIsBoundedToo() {
        val r = NetworkRetry()
        repeat(NetworkRetry.MAX_WAITS) { assertTrue(r.failed("a", 5_000, online = false, what = "x") is Step.WaitForNetwork) }
        val last = r.failed("a", 5_000, online = false, what = "x")
        assertTrue("$last", last is Step.Skip && "waiting for the network" in last.reason)
    }

    @Test fun aRetryAtTheSamePlaceNeverStartsTheCountAfresh() {
        val r = NetworkRetry()
        repeat(3) { r.failed("a", 40_000, online = true, what = "x"); r.playing("a", 40_000 + 1_000) }
        assertTrue(r.failed("a", 41_000, online = true, what = "x") is Step.Skip)
    }

    @Test fun playingWellPastThePlaceStartsAfresh() {
        val r = NetworkRetry()
        repeat(3) { r.failed("a", 40_000, online = true, what = "x") }
        r.playing("a", 40_000 + NetworkRetry.CLEAR_AFTER_MS)
        assertTrue("a timeout later in the song gets its own tries", r.failed("a", 75_000, online = true, what = "x") is Step.Retry)
    }

    @Test fun anotherSongHasItsOwnTries() {
        val r = NetworkRetry()
        repeat(3) { r.failed("a", 0, online = true, what = "x") }
        assertTrue(r.failed("b", 0, online = true, what = "x") is Step.Retry)
    }

    @Test fun whatCountsAsTheConnectionFailing() {
        assertTrue(networkFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, IOException()))
        assertTrue(networkFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException()))
        assertTrue("a read that timed out", networkFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("x", SocketTimeoutException("timeout"))))
        assertTrue(networkFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException(java.net.UnknownHostException("rr1.googlevideo.com"))))
        assertFalse("a stream YouTube won't give isn't the network", networkFailure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("Video unavailable")))
        assertFalse("an HTTP status has its own handling", networkFailure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, IOException(SocketTimeoutException())))
        assertFalse(networkFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, IOException(SocketTimeoutException())))
    }

    @Test fun awaitOnlineReturnsWhenTheNetworkComesBack() = runBlocking {
        val online = MutableStateFlow(false)
        var really = false
        val waiting = async { awaitOnline(online, { really }, recheckMs = 50) }
        delay(200)
        assertFalse("still offline: still waiting", waiting.isCompleted)
        really = true
        online.value = true
        withTimeout(2_000) { waiting.await() }
    }

    @Test fun aStaleOnlineFlagDoesNotMakeItSpin() = runBlocking {
        val online = MutableStateFlow(true) // says online, but a fresh check says not
        var checks = 0
        val waiting = async { awaitOnline(online, { checks++; false }, recheckMs = 100) }
        delay(450)
        waiting.cancel()
        assertTrue("checked about every 100 ms, not in a tight loop: $checks", checks in 2..8)
    }

    @Test fun aNetworkChangeLiftsTheBlockOnSongsThatFailedHere() {
        val refused = RefusedSongs { 1_000L }
        refused.note("a", "Video unavailable", "me")
        refused.note("next", "Video unavailable", "me")
        val failed = FailedOnThisNetwork()
        failed.failed("a")
        // The phone moves to another network: the service hands every id over to the resolver to forget.
        failed.take(listOf("next")).forEach { refused.forget(it) }
        assertNull("asked again on the new network", refused.reasonFor("a", "me"))
        assertNull("the song coming up too", refused.reasonFor("next", "me"))
        assertTrue("and the list starts afresh", failed.take().isEmpty())
    }

    @Test fun whatCountsAsAnotherNetwork() {
        val s = NetworkSwitch()
        assertFalse("the first network seen", s.seen("wifi", true))
        assertFalse("the same network's capabilities changing", s.seen("wifi", true))
        assertTrue("on to mobile data", s.seen("cell", true))
        assertFalse("lost", s.seen(null, false))
        assertTrue("back online", s.seen("cell", true))
    }
}
