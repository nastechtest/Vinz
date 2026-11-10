package com.vinz.music

import com.vinz.music.data.OtpResendCooldown
import org.junit.Assert.assertEquals
import org.junit.Test

class OtpResendCooldownTest {
    @Test fun deadlineInFutureReturnsOnlyTheRemainingSeconds() {
        assertEquals(37, OtpResendCooldown.remainingSeconds(1_037L, 1_000L))
    }

    @Test fun deadlineAtOrBeforeNowAllowsResend() {
        assertEquals(0, OtpResendCooldown.remainingSeconds(1_000L, 1_000L))
        assertEquals(0, OtpResendCooldown.remainingSeconds(999L, 1_000L))
        assertEquals(0, OtpResendCooldown.remainingSeconds(0L, 1_000L))
    }

    @Test fun extremelyLongDeadlineIsClampedToIntRange() {
        assertEquals(Int.MAX_VALUE, OtpResendCooldown.remainingSeconds(Long.MAX_VALUE, 0L))
    }
}
