package com.vinz.music.data

/** Pure expiry rules shared by the encrypted Supabase session store and JVM regression tests. */
internal object CloudSessionPolicy {
    const val REFRESH_LEEWAY_SECONDS = 60L

    fun shouldRefresh(
        expiresAtEpochSeconds: Long,
        nowEpochSeconds: Long,
        leewaySeconds: Long = REFRESH_LEEWAY_SECONDS,
    ): Boolean = expiresAtEpochSeconds <= nowEpochSeconds + leewaySeconds

    fun expiryEpochSeconds(
        expiresAtEpochSeconds: Long,
        expiresInSeconds: Long,
        nowEpochSeconds: Long,
    ): Long = expiresAtEpochSeconds.takeIf { it > 0L }
        ?: nowEpochSeconds + expiresInSeconds.coerceAtLeast(1L)

    fun sameSessionVersion(
        expectedAccessToken: String,
        expectedRefreshToken: String,
        currentAccessToken: String,
        currentRefreshToken: String,
    ): Boolean = expectedAccessToken == currentAccessToken && expectedRefreshToken == currentRefreshToken
}
