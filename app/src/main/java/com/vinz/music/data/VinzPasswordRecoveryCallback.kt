package com.vinz.music.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Validated Supabase recovery callback payload. Tokens are consumed immediately and never logged. */
internal data class PasswordRecoveryCallback(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenHash: String? = null,
    val expiresInSeconds: Long = 3_600L,
)

/**
 * Accept only the app-owned recovery URI and the recovery event. Supabase may return an implicit-flow
 * session in the fragment, or a TokenHash when the email template uses a direct verification link.
 */
internal object VinzPasswordRecoveryCallback {
    fun parse(raw: String): PasswordRecoveryCallback? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "vinz" || uri.host != "auth" || uri.rawPath != "/reset-password") return null
        val values = parameters(uri.rawFragment) + parameters(uri.rawQuery)
        if (values["type"] != "recovery") return null

        val tokenHash = values["token_hash"]?.takeIf(String::isNotBlank)
        val accessToken = values["access_token"]?.takeIf(String::isNotBlank)
        val refreshToken = values["refresh_token"]?.takeIf(String::isNotBlank)
        val hasSession = accessToken != null && refreshToken != null
        if (!hasSession && tokenHash == null) return null
        if ((accessToken == null) != (refreshToken == null)) return null

        val expires = values["expires_in"]?.toLongOrNull() ?: 3_600L
        if (expires !in 1L..86_400L) return null
        return PasswordRecoveryCallback(accessToken, refreshToken, tokenHash, expires)
    }

    private fun parameters(source: String?): Map<String, String> =
        source.orEmpty().split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val separator = pair.indexOf('=')
            val key = decode(if (separator < 0) pair else pair.substring(0, separator))
            if (key.isBlank()) return@mapNotNull null
            val value = decode(if (separator < 0) "" else pair.substring(separator + 1))
            key to value
        }.toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault("")
}
