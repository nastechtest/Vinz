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

enum class CloudAuthFailure { EMAIL_NOT_CONFIRMED, INVALID_OTP, OTP_EXPIRED, RATE_LIMITED, USERNAME_TAKEN }

class CloudAuthException(val failure: CloudAuthFailure) : IllegalStateException()

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
    sealed interface AuthResult {
        data class SignedIn(val profile: Profile?) : AuthResult
        data class VerificationRequired(val email: String) : AuthResult
        data object EmailConfirmationRequired : AuthResult
    }

    private val app = context.applicationContext
    private val store = EncryptedSessionStore(app)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val session: Session? get() = store.read()?.let(::decodeSession)
    fun pendingVerificationEmail(): String? = if (session == null) pendingVerification()?.email else null

    suspend fun signUp(email: String, password: String, username: String, displayName: String): AuthResult = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim()
        require(normalizedEmail.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) { "Enter a valid email address." }
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        require(username.matches(Regex("^[a-z0-9_]{3,24}$"))) { "Username must be 3–24 characters: lowercase letters, numbers or underscore." }
        require(displayName.trim().length in 1..60) { "Display name must be 1–60 characters." }

        // Never persist a signup session before the user has entered the email OTP.
        val result = request("/auth/v1/signup", "POST",
            JSONObject().put("email", normalizedEmail).put("password", password).toString(), authenticated = false)
        val hasImmediateSession = result.optString("access_token").isNotBlank()
        store.writePendingVerification(pendingVerificationJson(
            normalizedEmail, username, displayName.trim(), if (hasImmediateSession) "email" else "signup",
        ))

        // If hosted email confirmations are disabled, signup may return a session immediately.
        // Discard it and send a separate OTP so this app still requires explicit code verification.
        if (hasImmediateSession) sendEmailOtp(normalizedEmail)
        AuthResult.VerificationRequired(normalizedEmail)
    }

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim()
        require(normalizedEmail.isNotBlank()) { "Enter your email address." }
        require(password.isNotEmpty()) { "Enter your password." }
        val result = try {
            request("/auth/v1/token?grant_type=password", "POST",
                JSONObject().put("email", normalizedEmail).put("password", password).toString(), authenticated = false)
        } catch (failure: CloudAuthException) {
            if (failure.failure != CloudAuthFailure.EMAIL_NOT_CONFIRMED) throw failure
            store.writePendingVerification(pendingVerificationJson(normalizedEmail, "", "", "signup"))
            resendSignupOtp(normalizedEmail)
            return@withContext AuthResult.VerificationRequired(normalizedEmail)
        }
        val user = result.optJSONObject("user") ?: error("Supabase did not return the account.")
        if (!isEmailConfirmed(user)) {
            store.writePendingVerification(pendingVerificationJson(normalizedEmail, "", "", "signup"))
            resendSignupOtp(normalizedEmail)
            return@withContext AuthResult.VerificationRequired(normalizedEmail)
        }
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val userId = user.optString("id")
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) error("The account session was incomplete.")
        store.write(JSONObject().put("access_token", access).put("refresh_token", refresh)
            .put("user_id", userId).put("email", user.optString("email", normalizedEmail)).toString())
        store.clearPendingVerification()
        AuthResult.SignedIn(loadProfile())
    }

    /** Verifies the six-digit email OTP before creating a local session or enabling account features. */
    suspend fun verifyEmailOtp(email: String, token: String): AuthResult = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim()
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) { "Enter a valid email address." }
        require(token.matches(Regex("^[0-9]{6}$"))) { "Enter the six-digit code from your email." }
        val pending = pendingVerification()
        if (pending != null && !pending.email.equals(normalizedEmail, ignoreCase = true)) {
            error("The verification request does not match this email address.")
        }
        val result = request("/auth/v1/verify", "POST",
            JSONObject().put("type", "email").put("email", normalizedEmail).put("token", token).toString(),
            authenticated = false)
        val user = result.optJSONObject("user") ?: error("Supabase did not return the verified account.")
        val verifiedEmail = user.optString("email")
        if (verifiedEmail.isNotBlank() && !verifiedEmail.equals(normalizedEmail, ignoreCase = true)) {
            error("The verified account did not match this email address.")
        }
        if (!isEmailConfirmed(user)) throw CloudAuthException(CloudAuthFailure.EMAIL_NOT_CONFIRMED)
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val userId = user.optString("id")
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) error("The verified account session was incomplete.")
        store.write(JSONObject().put("access_token", access).put("refresh_token", refresh)
            .put("user_id", userId).put("email", verifiedEmail.ifBlank { normalizedEmail }).toString())

        // The auth-user trigger created a safe default row. Apply signup profile data only after verification.
        // A username collision leaves the verified session active so the user can choose another username.
        val profile = if (!pending?.username.isNullOrBlank()) {
            runCatching { saveProfile(pending!!.username, pending.displayName, "", true, true) }.getOrNull()
        } else runCatching { loadProfile() }.getOrNull()
        // Verification is complete even if the requested username collided; the signed-in editor can retry it.
        store.clearPendingVerification()
        AuthResult.SignedIn(profile)
    }

    /** Supabase owns expiry and attempt limits; this requests a fresh server-issued code. */
    suspend fun resendEmailOtp(email: String) = withContext(Dispatchers.IO) {
        val normalizedEmail = email.trim()
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) { "Enter a valid email address." }
        val pending = pendingVerification()
        if (pending != null && !pending.email.equals(normalizedEmail, ignoreCase = true)) {
            error("The verification request does not match this email address.")
        }
        if (pending?.resendType == "email") sendEmailOtp(normalizedEmail) else resendSignupOtp(normalizedEmail)
    }

    private fun sendEmailOtp(email: String) {
        request("/auth/v1/otp", "POST",
            JSONObject().put("email", email).put("create_user", false).toString(), authenticated = false)
    }

    private fun resendSignupOtp(email: String) {
        request("/auth/v1/resend", "POST",
            JSONObject().put("type", "signup").put("email", email).toString(), authenticated = false)
    }

    private fun pendingVerificationJson(email: String, username: String, displayName: String, resendType: String) =
        JSONObject().put("email", email).put("username", username).put("display_name", displayName)
            .put("resend_type", resendType).toString()

    private data class PendingVerification(val email: String, val username: String, val displayName: String, val resendType: String)

    private fun pendingVerification(): PendingVerification? = store.readPendingVerification()?.let { raw ->
        runCatching {
            val value = JSONObject(raw)
            PendingVerification(value.optString("email"), value.optString("username"),
                value.optString("display_name"), value.optString("resend_type", "signup"))
        }.getOrNull()
    }

    private fun isEmailConfirmed(user: JSONObject): Boolean =
        listOf("email_confirmed_at", "confirmed_at").any { key ->
            val value = user.opt(key)
            value is String && value.isNotBlank()
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

    /** Uses a public boolean-only endpoint; no email, user ID or private profile data is returned. */
    suspend fun checkUsernameAvailable(username: String): Boolean = withContext(Dispatchers.IO) {
        require(username.matches(Regex("^[a-z0-9_]{3,24}$"))) { "Invalid username." }
        val request = Request.Builder().url("$VINZ_API_URL/v1/usernames/$username/availability")
            .header("Accept", "application/json")
            .get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) error("Username availability is temporarily rate limited.")
            if (!response.isSuccessful) error("Username availability service is unavailable.")
            val payload = runCatching { JSONObject(body) }.getOrNull()
                ?: error("Username availability service returned an invalid response.")
            if (payload.optString("username") != username) error("Username availability service returned a mismatched response.")
            payload.opt("available") as? Boolean
                ?: error("Username availability service returned an invalid response.")
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
            val saved = result.optJSONArray("data")?.takeIf { it.length() > 0 }?.getJSONObject(0)?.toProfile()
                ?: loadProfile() ?: error("Profile was saved but could not be reloaded.")
            if (pendingVerification()?.email?.equals(s.email, ignoreCase = true) == true) store.clearPendingVerification()
            saved
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
                val payload = runCatching { JSONObject(text) }.getOrNull()
                val message = listOf("message", "msg", "error_description", "error")
                    .map { payload?.optString(it).orEmpty() }.joinToString(" ").lowercase()
                val code = payload?.optString("code").orEmpty()
                val failure = when {
                    response.code == 429 || message.contains("rate limit") || message.contains("too many") ->
                        CloudAuthFailure.RATE_LIMITED
                    path.contains("/verify") && (message.contains("expired") || code == "otp_expired") ->
                        CloudAuthFailure.OTP_EXPIRED
                    path.contains("/verify") && (message.contains("invalid") || message.contains("token")) ->
                        CloudAuthFailure.INVALID_OTP
                    message.contains("email not confirmed") || code == "email_not_confirmed" ->
                        CloudAuthFailure.EMAIL_NOT_CONFIRMED
                    path.startsWith("/rest/v1/vinz_profiles") && (response.code == 409 || code == "23505") ->
                        CloudAuthFailure.USERNAME_TAKEN
                    else -> null
                }
                if (failure != null) throw CloudAuthException(failure)
                error("Vinz Account request failed (${response.code}).")
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
        private fun encrypt(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        private fun decrypt(value: String?): String? = runCatching {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        }.getOrNull()
        fun write(value: String) { prefs.edit().putString("session", encrypt(value)).apply() }
        fun read(): String? = decrypt(prefs.getString("session", null))
        fun writePendingVerification(value: String) { prefs.edit().putString("pending_verification", encrypt(value)).apply() }
        fun readPendingVerification(): String? = decrypt(prefs.getString("pending_verification", null))
        fun clearPendingVerification() { prefs.edit().remove("pending_verification").apply() }
        fun clear() { prefs.edit().remove("session").remove("pending_verification").apply() }
    }

    companion object {
        const val VINZ_API_URL = "https://api.vinzmusic.workers.dev"
        const val SUPABASE_URL = "https://iszghebmetrbkfhnnoqq.supabase.co"
        // Publishable client key only. Never embed service-role or secret keys in the Android app.
        const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_9UXwKG0oVl1J9zIkJ-bZKg_W-fkotZe"
    }
}
