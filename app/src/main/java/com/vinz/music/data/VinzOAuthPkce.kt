package com.vinz.music.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Small, platform-independent RFC 7636 helper for the native Supabase OAuth callback. */
object VinzOAuthPkce {
    private val random = SecureRandom()
    private const val CALLBACK_LIFETIME_MILLIS = 10 * 60 * 1_000L

    data class Pending(
        val verifier: String,
        val state: String,
        val createdAtEpochMillis: Long,
    )

    sealed interface Callback {
        data class Code(val value: String) : Callback
        data class Denied(val reason: String) : Callback
        data object InvalidState : Callback
        data object Expired : Callback
        data object Invalid : Callback
    }

    /** A 64-character unreserved verifier and independent 256-bit CSRF state. */
    fun create(nowMillis: Long = System.currentTimeMillis()): Pending =
        Pending(randomToken(48), randomToken(32), nowMillis)

    fun challenge(verifier: String): String {
        require(verifier.length in 43..128 && verifier.all { it.isLetterOrDigit() || it in "-._~" })
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** The state is embedded in redirect_to so it survives the provider round-trip without a client secret. */
    fun authorizationUrl(supabaseUrl: String, redirectUri: String, pending: Pending): String {
        val callback = URI(redirectUri)
        require(callback.scheme == "vinz" && callback.host == "auth" && callback.path == "/callback")
        val redirectWithState = redirectUri + "?state=" + encode(pending.state)
        return supabaseUrl.trimEnd('/') + "/auth/v1/authorize?" + listOf(
            "provider=google",
            "redirect_to=" + encode(redirectWithState),
            "code_challenge=" + encode(challenge(pending.verifier)),
            "code_challenge_method=s256",
        ).joinToString("&")
    }

    fun parseCallback(
        callback: String,
        pending: Pending,
        nowMillis: Long = System.currentTimeMillis(),
    ): Callback {
        val uri = runCatching { URI(callback) }.getOrNull() ?: return Callback.Invalid
        if (uri.scheme != "vinz" || uri.host != "auth" || uri.path != "/callback") return Callback.Invalid
        val params = parseParams(uri.rawQuery.orEmpty()) + parseParams(uri.rawFragment.orEmpty())
        val returnedState = params["state"] ?: return Callback.InvalidState
        if (!MessageDigest.isEqual(
                returnedState.toByteArray(StandardCharsets.UTF_8),
                pending.state.toByteArray(StandardCharsets.UTF_8),
            )) return Callback.InvalidState
        if (nowMillis < pending.createdAtEpochMillis - 60_000L ||
            nowMillis - pending.createdAtEpochMillis > CALLBACK_LIFETIME_MILLIS
        ) return Callback.Expired
        params["error"]?.let { return Callback.Denied(it.take(64)) }
        params["error_code"]?.let { return Callback.Denied(it.take(64)) }
        val code = params["code"]?.takeIf { it.isNotBlank() && it.length <= 2_048 }
            ?: return Callback.Invalid
        return Callback.Code(code)
    }

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun parseParams(raw: String): Map<String, String> = raw.split("&")
        .filter { it.isNotEmpty() }
        .associate { part ->
            val separator = part.indexOf('=')
            val key = if (separator < 0) part else part.substring(0, separator)
            val value = if (separator < 0) "" else part.substring(separator + 1)
            URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
}
