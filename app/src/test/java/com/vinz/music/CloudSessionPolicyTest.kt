package com.vinz.music

import com.vinz.music.data.CloudSessionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSessionPolicyTest {
    @Test fun serverAbsoluteExpiryTakesPrecedenceOverRelativeLifetime() {
        assertEquals(5_000L, CloudSessionPolicy.expiryEpochSeconds(5_000L, 3_600L, 1_000L))
    }

    @Test fun relativeLifetimeIsConvertedToAnAbsoluteTimestamp() {
        assertEquals(4_600L, CloudSessionPolicy.expiryEpochSeconds(0L, 3_600L, 1_000L))
    }

    @Test fun sessionRefreshStartsBeforeTheAccessTokenExpires() {
        assertTrue(CloudSessionPolicy.shouldRefresh(1_059L, 1_000L))
        assertTrue(CloudSessionPolicy.shouldRefresh(1_000L, 1_000L))
        assertFalse(CloudSessionPolicy.shouldRefresh(1_061L, 1_000L))
    }

    @Test fun legacySessionsWithoutAnExpiryAreRefreshedImmediately() {
        assertTrue(CloudSessionPolicy.shouldRefresh(0L, 1_000L))
    }

    @Test fun relativeExpiryNeverAddsANegativeLifetime() {
        assertEquals(1_001L, CloudSessionPolicy.expiryEpochSeconds(0L, -10L, 1_000L))
    }
}
