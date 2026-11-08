package com.vinz.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cloud identity for Vinz. This is deliberately separate from YouTube Music's cookie-based login.
 * The Supabase publishable key is public client configuration; privileged keys never belong in this app.
 */
class VinzCloudAccount(context: Context, private val http: OkHttpClient) {
    data class Session(val accessToken: String, val refreshToken: String, val userId: String, val email: String)
    data class Profile(
        val id: String,
        val username: String,
        val displayName: String,
        val bio: String,
        val avatarPath: String?,
        val isPublic: Boolean,
        val discoverable: Boolean,
    )
    data class YouTubeStatus(val connected: Boolean, val channelTitle: String = "")\n    data class YouTubeImportResult(val importedCount: Int, val playlistCount: Int)\n\n    sealed interface AuthResult {
        data class SignedIn(val profile: Profile?) : AuthResult
        data object EmailConfirmationRequired : AuthResult
    }

    private val app = context.applicationContext
    private val store = EncryptedSessionStore(app)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val session: Session? get() = store.read()?.let(::decodeSession)

    suspend fun signUp(email: String, password: String, username: String, displayName: String): AuthResult = withContext(Dispatchers.IO) {
        require(email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { "Enter a valid email address." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        require(username.matches(Regex("^[a-z0-9_]{3,24}$"))) { "Username must be 3–24 characters: lowercase letters, numbers or underscore." }
        require(displayName.trim().length in 1..60) { "Display name must be 1–60 characters." }
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val result = request("/auth/v1/signup", "POST", body)
        val access = result.optString("access_token")
        if (access.isBlank()) return@withContext AuthResult.EmailConfirmationRequired
        val user = result.optJSONObject("user") ?: result
        val userId = user.optString("id")
        val refresh = result.optString("refresh_token")
        if (userId.isBlank() || refresh.isBlank()) error("Supabase did not return a complete account session.")
        store.write(JSONObject().put("access_token", access).put("refresh_token", refresh).put("user_id", userId).put("email", email.trim()).toString())
        val profile = saveProfile(username, displayName, "", true, true)
        AuthResult.SignedIn(profile)
    }

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        require(email.isNotBlank()) { "Enter your email address." }
        require(password.isNotEmpty()) { "Enter your password." }
        val result = request("/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email.trim()).put("password", password).toString())
        val user = result.optJSONObject("user") ?: error("Supabase did not return the account.")
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val userId = user.optString("id")
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) error("The account session was incomplete.")
        store.write(JSONObject().put("access_token", access).put("refresh_token", refresh).put("user_id", userId).put("email", user.optString("email", email)).toString())
        AuthResult.SignedIn(loadProfile())
    }

    suspend fun youtubeStatus(): YouTubeStatus = withContext(Dispatchers.IO) {
        val result = apiRequest("/v1/integrations/youtube/status", "GET")
        val channel = result.optJSONObject("channel")
        YouTubeStatus(result.optBoolean("connected"), channel?.optString("title").orEmpty())
    }

    /** Starts the official Google consent screen; no YouTube email/password is collected by Vinz. */
    suspend fun youtubeConnectUrl(): String = withContext(Dispatchers.IO) {
        val result = apiRequest("/v1/integrations/youtube/connect", "GET")
        val value = result.optString("authorization_url")
        val parsed = runCatching { java.net.URI(value) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host != "accounts.google.com") {
            error("Vinz returned an invalid Google authorization URL.")
        }
        value
    }

    suspend fun importYouTubeMusic(): YouTubeImportResult = withContext(Dispatchers.IO) {
        val result = apiRequest("/v1/integrations/youtube/import", "POST", "{}")
        YouTubeImportResult(result.optInt("imported_count"), result.optInt("playlist_count"))
    }

    suspend fun disconnectYouTubeMusic() = withContext(Dispatchers.IO) {
        apiRequest("/v1/integrations/youtube/connection", "DELETE")
    }

    private fun apiRequest(path: String, method: String, body: String? = null): JSONObject {
        val s = session ?: error("Sign in to your Vinz Account first.")
        val builder = Request.Builder().url(VINZ_API_URL + path)
            .header("Authorization", "Bearer ${s.accessToken}")
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", "application/json").method(method, body.toRequestBody(jsonType))
        else builder.method(method, null)
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                error(if (code.isNotBlank()) code else "Vinz YouTube service is unavailable (${response.code}).")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    suspend fun sendPasswordRecovery(email: String) = withContext(Dispatchers.IO) {
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { "Enter a valid email address." }
        request("/auth/v1/recover", "POST", JSONObject().put("email", email.trim()).toString(), authenticated = false)
    }

    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val saved = store.read()?.let(::decodeSession) ?: return@withContext false
        val result = request("/auth/v1/token?grant_type=refresh_token", "POST",
            JSONObject().put("refresh_token", saved.refreshToken).toString(), authenticated = false)
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val user = result.optJSONObject("user")
        if (access.isBlank() || refresh.isBlank()) return@withContext false
        store.write(JSONObject().put("access_token", access).put("refresh_token", refresh)
            .put("user_id", user?.optString("id")?.takeIf { it.isNotBlank() } ?: saved.userId)
            .put("email", user?.optString("email")?.takeIf { it.isNotBlank() } ?: saved.email).toString())
        true
    }

    /** Checks username availability through the authenticated Supabase RPC; the unique index still decides saves. */
    suspend fun checkUsernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        require(username.matches(Regex("^[a-z0-9_]{3,24}$"))) { "Invalid username." }
        val s = session ?: error("Sign in to your Vinz Account first.")
        val body = JSONObject().put("p_username", username).toString()
        val request = Request.Builder().url(SUPABASE_URL + "/rest/v1/rpc/vinz_username_available")
            .header("apikey", SUPABASE_PUBLISHABLE_KEY)
            .header("Authorization", "Bearer ${s.accessToken}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val value = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) error("Username availability service is unavailable.")
            value.toBooleanStrictOrNull() ?: error("Username availability service returned an invalid response.")
        }
    }

    suspend fun loadProfile(): Profile? = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext null
        val response = request("/rest/v1/vinz_profiles?select=*&id=eq.${s.userId}", "GET")
        val rows = response.optJSONArray("data") ?: return@withContext null
        if (rows.length() == 0) null else rows.getJSONObject(0).toProfile()
    }

    suspend fun saveProfile(username: String, displayName: String, bio: String, isPublic: Boolean, discoverable: Boolean): Profile =
        withContext(Dispatchers.IO) {
            require(username.matches(Regex("^[a-z0-9_]{3,24}$"))) { "Username must be 3–24 lowercase letters, numbers or underscores." }
            require(displayName.trim().length in 1..60) { "Display name must be 1–60 characters." }
            require(bio.length <= 160) { "Bio must be 160 characters or fewer." }
            val s = session ?: error("Sign in to your Vinz Account first.")
            val payload = JSONObject().put("username", username)
                .put("display_name", displayName.trim()).put("bio", bio)
                .put("is_public", isPublic).put("discoverable", discoverable)
            // The auth-user trigger creates the row; PATCH uses the existing owner-only UPDATE policy.
            val result = request("/rest/v1/vinz_profiles?id=eq.${s.userId}", "PATCH", payload.toString(),
                prefer = "return=representation")
            val rows = result.optJSONArray("data")
            rows?.takeIf { it.length() > 0 }?.getJSONObject(0)?.toProfile()
                ?: loadProfile() ?: error("Profile was saved but could not be reloaded.")
        }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val s = session
        if (s != null) runCatching { request("/auth/v1/logout", "POST", null) }
        store.clear()
    }

    private fun request(path: String, method: String, body: String? = null, authenticated: Boolean = true, prefer: String? = null): JSONObject {
        val s = if (authenticated) session else null
        val builder = Request.Builder().url(SUPABASE_URL + path)
            .header("apikey", SUPABASE_PUBLISHABLE_KEY)
            .header("Accept", "application/json")
        if (s != null) builder.header("Authorization", "Bearer ${s.accessToken}")
        else builder.header("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
        if (body != null) builder.header("Content-Type", "application/json").method(method, body.toRequestBody(jsonType))
        else builder.method(method, null)
        prefer?.let { builder.header("Prefer", it) }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message").ifBlank { JSONObject(text).optString("msg") } }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "Vinz Account request failed (${response.code}).")
            }
            return if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) JSONObject().put("data", org.json.JSONArray(text)) else JSONObject(text)
        }
    }

    private fun decodeSession(value: String): Session {
        val j = JSONObject(value)
        return Session(j.getString("access_token"), j.getString("refresh_token"), j.getString("user_id"), j.optString("email"))
    }

    private fun JSONObject.toProfile() = Profile(
        id = getString("id"), username = optString("username"), displayName = optString("display_name"),
        bio = optString("bio"), avatarPath = optString("avatar_path").takeIf { it.isNotBlank() && it != "null" },
        isPublic = optBoolean("is_public", true), discoverable = optBoolean("discoverable", true),
    )

    private class EncryptedSessionStore(context: Context) {
        private val prefs = context.getSharedPreferences("vinz_cloud_session", Context.MODE_PRIVATE)
        private val alias = "vinz_cloud_session_v1"
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            return generator.generateKey()
        }
        fun write(value: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            prefs.edit().putString("session", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        }
        fun read(): String? = runCatching {
            val packed = Base64.decode(prefs.getString("session", null), Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        }.getOrNull()
        fun clear() { prefs.edit().remove("session").apply() }
    }

    companion object {
        const val VINZ_API_URL = "https://api.vinzmusic.workers.dev"\n        const val SUPABASE_URL = "https://iszghebmetrbkfhnnoqq.supabase.co"
        // Publishable client key only. Never embed service-role or secret keys in the Android app.
        const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_9UXwKG0oVl1J9zIkJ-bZKg_W-fkotZe"
    }
}
