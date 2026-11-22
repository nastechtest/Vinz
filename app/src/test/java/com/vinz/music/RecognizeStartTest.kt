package com.vinz.music

import com.vinz.music.data.recognize.Recognizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** #215: a recognized song plays from the moment that was heard, as the music has gone on since. */
class RecognizeStartTest {
    @Test fun noOffsetStartsAtTheTop() {
        assertEquals(0L, Recognizer.startMs(null, heardAt = 1_000, now = 11_000, durationMs = 200_000))
    }

    @Test fun theOffsetPlusTheTimeSinceListeningBegan() {
        assertEquals(70_000L, Recognizer.startMs(60_000, heardAt = 1_000, now = 11_000, durationMs = 200_000))
    }

    @Test fun pastTheEndStopsAFewSecondsBeforeIt() {
        assertEquals(195_000L, Recognizer.startMs(190_000, heardAt = 0, now = 20_000, durationMs = 200_000))
        // Long after: still only the last seconds.
        assertEquals(195_000L, Recognizer.startMs(60_000, heardAt = 0, now = 3_600_000, durationMs = 200_000))
    }

    @Test fun anImplausibleOffsetStartsAtTheTop() {
        assertEquals(0L, Recognizer.startMs(-5_000, heardAt = 0, now = 10_000, durationMs = 200_000))
        assertEquals(0L, Recognizer.startMs(300_000, heardAt = 0, now = 10_000, durationMs = 200_000))
    }

    @Test fun aClockGoingBackAddsNothing() {
        assertEquals(60_000L, Recognizer.startMs(60_000, heardAt = 50_000, now = 10_000, durationMs = 200_000))
    }

    @Test fun anUnknownLengthIsNotClamped() {
        assertEquals(70_000L, Recognizer.startMs(60_000, heardAt = 0, now = 10_000, durationMs = 0))
    }
}
