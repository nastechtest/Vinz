package com.vinz.music

import com.vinz.music.playback.SessionCounters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.7 (plan 4.7): counts for the whole sitting. Underruns used to start again at every song, and the soak read only
 * underruns and stalls from the log.
 */
class SessionCountersTest {
    private var now = 1_000L
    private val counters = SessionCounters { now }

    @Test fun countsAddUpOverTheSitting() {
        repeat(3) { counters.underrun() }
        counters.bufferingStarted(); now += 400; counters.bufferingEnded()
        counters.bufferingStarted(); now += 100; counters.bufferingStarted(); now += 1_100; counters.bufferingEnded()
        counters.bufferingEnded() // a second end with no wait going on counts nothing
        counters.error("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", null)
        counters.error("ERROR_CODE_IO_BAD_HTTP_STATUS", 403)
        counters.error("ERROR_CODE_IO_BAD_HTTP_STATUS", 403)
        counters.reResolve(); counters.reResolve()
        counters.skippedByError()
        val s = counters.snapshot()
        assertEquals(3, s.underruns)
        assertEquals(2, s.bufferingEvents)
        assertEquals(1_600L, s.bufferingTotalMs)
        assertEquals("the longest wait, from its first start", 1_200L, s.bufferingLongestMs)
        assertEquals(mapOf("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" to 1, "ERROR_CODE_IO_BAD_HTTP_STATUS" to 2), s.errorsByCode)
        assertEquals(2, s.http403)
        assertEquals(2, s.reResolves)
        assertEquals(1, s.skipsByError)
        assertEquals(1_600L, s.sinceStartMs)
    }

    @Test fun theSummaryHoldsThemUnderSession() {
        counters.underrun()
        counters.error("ERROR_CODE_IO_UNSPECIFIED", null)
        now += 61_000
        val session = Json.parseToJsonElement(counters.json()).jsonObject["session"]!!.jsonObject
        assertEquals(1, session["underruns"]!!.jsonPrimitive.int)
        assertEquals(0, session["bufferingEvents"]!!.jsonPrimitive.int)
        assertEquals(61_000L, session["sinceStartMs"]!!.jsonPrimitive.long)
        assertEquals(1, session["errorsByCode"]!!.jsonObject["ERROR_CODE_IO_UNSPECIFIED"]!!.jsonPrimitive.int)
        for (k in listOf("bufferingTotalMs", "bufferingLongestMs", "reResolves", "http403", "skipsByError")) assertTrue("$k missing", k in session)
    }

    @Test fun theTickLineReadsThem() {
        counters.underrun()
        counters.bufferingStarted(); now += 2_500; counters.bufferingEnded()
        val line = counters.line()
        assertTrue(line, "underruns 1" in line && "buffering 1 (2.5 s, longest 2.5 s)" in line && "skips 0" in line)
    }

    @Test fun aNewServiceStartsAfresh() {
        counters.underrun(); counters.bufferingStarted()
        now += 5_000
        counters.reset()
        counters.bufferingEnded()
        val s = counters.snapshot()
        assertEquals(0, s.underruns)
        assertEquals("a wait from before the restart isn't counted", 0, s.bufferingEvents)
        assertEquals(0L, s.sinceStartMs)
    }
}
