package com.vinz.music.data

import org.json.JSONObject
import java.util.Locale

/**
 * The deliberately small public projection of a Vinz profile.
 *
 * Never add email, auth IDs, private full name, account settings or provider tokens here.
 * The database RLS policy and column grant expose only these public fields to anonymous readers.
 */
data class VinzPublicProfile(
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarPath: String?,
) {
    companion object {
        private val USERNAME = Regex("^[a-z0-9_]{3,24}$")
        private val AVATAR_PATH = Regex("^[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\\.webp$")
        private val RESERVED = setOf(
            "admin", "administrator", "api", "account", "accounts", "auth", "billing",
            "community", "discover", "help", "login", "logout", "moderator", "null",
            "official", "privacy", "profile", "root", "search", "security", "settings",
            "signup", "staff", "status", "support", "system", "terms", "undefined",
            "vinz", "webmaster", "www",
        )

        fun normalizeUsername(value: String): String? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return normalized.takeIf { USERNAME.matches(it) && it !in RESERVED }
        }

        /** Returns null for a private, malformed or incomplete row; never trusts a caller's URL. */
        fun fromJson(row: JSONObject): VinzPublicProfile? {
            if (!row.optBoolean("is_public", false)) return null
            val username = normalizeUsername(row.optString("username")) ?: return null
            val displayName = row.optString("display_name").trim()
                .takeIf { it.isNotEmpty() && it.length <= 60 } ?: return null
            val bio = row.optString("bio").takeIf { it.length <= 160 } ?: return null
            val avatarPath = row.optString("avatar_path")
                .takeIf { it.isNotBlank() && it != "null" && AVATAR_PATH.matches(it) }
            return VinzPublicProfile(username, displayName, bio, avatarPath)
        }

        /** Internal app URI. A verified HTTPS host is intentionally not invented before one is configured. */
        fun appUri(value: String): String? = normalizeUsername(value)?.let { "vinz://profile/$it" }
    }
}
