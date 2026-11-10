package com.vinz.music.data

/** Pure cooldown math shared by the encrypted auth client and deterministic JVM tests. */
internal object OtpResendCooldown {
    fun remainingSeconds(availableAtEpochSeconds: Long, nowEpochSeconds: Long): Int {
        if (availableAtEpochSeconds <= 0L || availableAtEpochSeconds <= nowEpochSeconds) return 0
        return (availableAtEpochSeconds - nowEpochSeconds)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}
