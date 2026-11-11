package com.vinz.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val email: String,
        val expiresAtEpochSeconds: Long = 0,
    )
    data class Profile(
        val id: String,
        val username: String,
        val displayName: String,
        val bio: String,
        val avatarPath: String?,
        val isPublic: Boolean,
        val discoverable: Boolean,
        val fullName: String = "",
    )
    sealed interface AuthResult {
        data class SignedIn(val profile: Profile?) : AuthResult
        data class VerificationRequired(val email: String) : AuthResult
        data object EmailConfirmationRequired : AuthResult
    }

    private val app = context.applicationContext
    private val store = EncryptedSessionStore(app)
    private val refreshLock = Any()
    private val mutableSessionChanges = MutableStateFlow(0L)
    /** Emits only after an external auth callback has established a new account session. */
    val sessionChanges: StateFlow<Long> = mutableSessionChanges.asStateFlow()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val session: Session? get() = store.read()?.let(::decodeSession)
    fun pendingVerificationEmail(): String? = if (session == null) pendingVerification()?.email else null

    /** Remaining resend cooldown is stored inside the encrypted pending-verification record. */
    fun otpResendSecondsRemaining(nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L): Int =
        OtpResendCooldown.remainingSeconds(pendingVerification()?.resendAvailableAtEpochSeconds ?: 0L, nowEpochSeconds)

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
        markOtpSent()
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
            markOtpSent()
            return@withContext AuthResult.VerificationRequired(normalizedEmail)
        }
        val user = result.optJSONObject("user") ?: error("Supabase did not return the account.")
        if (!isEmailConfirmed(user)) {
            store.writePendingVerification(pendingVerificationJson(normalizedEmail, "", "", "signup"))
            resendSignupOtp(normalizedEmail)
            markOtpSent()
            return@withContext AuthResult.VerificationRequired(normalizedEmail)
        }
        val access = result.optString("access_token")
        val refresh = result.optString("refresh_token")
        val userId = user.optString("id")
        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) error("The account session was incomplete.")
        persistSession(sessionFromAuthResponse(result, user, normalizedEmail))
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
        persistSession(sessionFromAuthResponse(result, user, verifiedEmail.ifBlank { normalizedEmail }))

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
        if (otpResendSecondsRemaining() > 0) throw CloudAuthException(CloudAuthFailure.RATE_LIMITED)
        if (pending?.resendType == "email") sendEmailOtp(normalizedEmail) else resendSignupOtp(normalizedEmail)
        markOtpSent()
    }

    private fun sendEmailOtp(email: String) {
        request("/auth/v1/otp", "POST",
            JSONObject().put("email", email).put("create_user", false).toString(), authenticated = false)
    }

    private fun resendSignupOtp(email: String) {
        request("/auth/v1/resend", "POST",
            JSONObject().put("type", "signup").put("email", email).toString(), authenticated = false)
    }

    private fun pendingVerificationJson(
        email: String, username: String, displayName: String, resendType: String,
        resendAvailableAtEpochSeconds: Long = 0L,
    ) = JSONObject().put("email", email).put("username", username).put("display_name", displayName)
        .put("resend_type", resendType).put("resend_available_at", resendAvailableAtEpochSeconds).toString()

    private data class PendingVerification(
        val email: String, val username: String, val displayName: String, val resendType: String,
        val resendAvailableAtEpochSeconds: Long,
    )

    private fun markOtpSent() {
        val pending = pendingVerification() ?: return
        store.writePendingVerification(pendingVerificationJson(
            pending.email, pending.username, pending.displayName, pending.resendType,
            System.currentTimeMillis() / 1_000L + OTP_RESEND_COOLDOWN_SECONDS,
        ))
    }

    private fun pendingVerification(): PendingVerification? = store.readPendingVerification()?.let { raw ->
        runCatching {
            val value = JSONObject(raw)
            PendingVerification(value.optString("email"), value.optString("username"),
                value.optString("display_name"), value.optString("resend_type", "signup"),
                value.optLong("resend_available_at", 0L))
        }.getOrNull()
    }

    private fun pendingGoogleOAuth(): VinzOAuthPkce.Pending? = store.readPendingOAuth()?.let { raw ->
        runCatching {
            val value = JSONObject(raw)
            VinzOAuthPkce.Pending(value.getString("verifier"), value.getString("state"), value.getLong("created_at"))
        }.getOrNull()
    }

    private fun isEmailConfirmed(user: JSONObject): Boolean =
        listOf("email_confirmed_at", "confirmed_at").any { key ->
            val value = user.opt(key)
            value is String && value.isNotBlank()
        }

    suspend fun sendPasswordRecovery(email: String) = withContext(Dispatchers.IO) {
        val normalized = email.trim()
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()) { "Enter a valid email address." }
        val redirect = java.net.URLEncoder.encode(VINZ_PASSWORD_RECOVERY_REDIRECT_URI, Charsets.UTF_8.name())
        request("/auth/v1/recover?redirect_to=$redirect", "POST", JSONObject().put("email", normalized).toString(), authenticated = false)
    }

    /** Consumes only a Supabase-issued recovery callback and verifies the resulting identity before storing it. */
    suspend fun completePasswordRecovery(callback: String) = withContext(Dispatchers.IO) {
        val parsed = VinzPasswordRecoveryCallback.parse(callback)
            ?: error("The password recovery link is invalid or has expired.")
        val recovered = if (parsed.tokenHash != null) {
            val payload = request(
                "/auth/v1/verify",
                "POST",
                JSONObject().put("type", "recovery").put("token_hash", parsed.tokenHash).toString(),
                authenticated = false,
            )
            val user = payload.optJSONObject("user") ?: error("The recovery link did not return an account.")
            if (!isEmailConfirmed(user)) throw CloudAuthException(CloudAuthFailure.EMAIL_NOT_CONFIRMED)
            sessionFromAuthResponse(payload, user, user.optString("email"))
        } else {
            val now = System.currentTimeMillis() / 1_000L
            val candidate = Session(
                accessToken = parsed.accessToken!!,
                refreshToken = parsed.refreshToken!!,
                userId = "",
                email = "",
                expiresAtEpochSeconds = now + parsed.expiresInSeconds,
            )
            val response = executeRequest("/auth/v1/user", "GET", null, candidate, null)
            val user = parseResponse("/auth/v1/user", response)
            if (!isEmailConfirmed(user)) throw CloudAuthException(CloudAuthFailure.EMAIL_NOT_CONFIRMED)
            val userId = user.optString("id")
            val email = user.optString("email")
            if (userId.isBlank() || email.isBlank()) error("The recovery link did not return a complete account.")
            candidate.copy(userId = userId, email = email)
        }
        if (recovered.userId.isBlank() || recovered.email.isBlank() || recovered.accessToken.isBlank() || recovered.refreshToken.isBlank()) {
            error("The recovery session was incomplete.")
        }
        persistSession(recovered, passwordRecovery = true)
        store.clearPendingVerification()
        store.clearPendingOAuth()
        store.writePendingPasswordRecovery()
        mutableSessionChanges.value = mutableSessionChanges.value + 1L
    }

    fun passwordRecoveryPending(): Boolean = store.readPendingPasswordRecovery() == "1"

    /** Updates the password only while a validated recovery session is pending. */
    suspend fun updateRecoveredPassword(password: String, expectedUserId: String) = withContext(Dispatchers.IO) {
        require(password.length >= 8) { "Use a password with at least 8 characters." }
        val current = session ?: error("Sign in to your Vinz Account first.")
        if (current.userId != expectedUserId || !passwordRecoveryPending()) {
            error("The password recovery session is no longer active.")
        }
        request("/auth/v1/user", "PUT", JSONObject().put("password", password).toString())
        store.clearPendingPasswordRecovery()
    }

    /** Starts Google sign-in with a one-use PKCE verifier kept only in encrypted Android Keystore storage. */
    suspend fun beginGoogleOAuth(): String = withContext(Dispatchers.IO) {
        val pending = VinzOAuthPkce.create()
        store.writePendingOAuth(
            JSONObject().put("verifier", pending.verifier).put("state", pending.state)
                .put("created_at", pending.createdAtEpochMillis).toString(),
        )
        VinzOAuthPkce.authorizationUrl(SUPABASE_URL, VINZ_OAUTH_REDIRECT_URI, pending)
    }

    /** Validates the app callback, exchanges the one-use code, and persists only a verified Supabase identity. */
    suspend fun completeGoogleOAuth(callback: String): AuthResult = withContext(Dispatchers.IO) {
        val pending = pendingGoogleOAuth() ?: error("No Google sign-in is waiting for a callback.")
        when (val result = VinzOAuthPkce.parseCallback(callback, pending)) {
            VinzOAuthPkce.Callback.InvalidState ->
                error("Google sign-in callback did not match the pending request.")
            VinzOAuthPkce.Callback.Expired -> {
                store.clearPendingOAuth()
                error("Google sign-in expired. Start again.")
            }
            is VinzOAuthPkce.Callback.Denied -> {
                store.clearPendingOAuth()
                error("Google sign-in was cancelled or declined.")
            }
            VinzOAuthPkce.Callback.Invalid -> {
                store.clearPendingOAuth()
                error("Google sign-in returned an invalid callback.")
            }
            is VinzOAuthPkce.Callback.Code -> {
                val response = try {
                    request(
                        "/auth/v1/token?grant_type=pkce",
                        "POST",
                        JSONObject().put("auth_code", result.value).put("code_verifier", pending.verifier).toString(),
                        authenticated = false,
                    )
                } catch (network: java.io.IOException) {
                    // Keep the encrypted verifier for a retry if the exchange never reached the server.
                    throw network
                } catch (failure: Exception) {
                    store.clearPendingOAuth()
                    throw failure
                }
                val user = response.optJSONObject("user") ?: run {
                    store.clearPendingOAuth()
                    error("Google sign-in did not return a Supabase account.")
                }
                if (!isEmailConfirmed(user)) {
                    store.clearPendingOAuth()
                    throw CloudAuthException(CloudAuthFailure.EMAIL_NOT_CONFIRMED)
                }
                val email = user.optString("email")
                val userId = user.optString("id")
                val access = response.optString("access_token")
                val refresh = response.optString("refresh_token")
                if (email.isBlank() || userId.isBlank() || access.isBlank() || refresh.isBlank()) {
                    store.clearPendingOAuth()
                    error("Google sign-in returned an incomplete account session.")
                }
                persistSession(sessionFromAuthResponse(response, user, email))
                store.clearPendingOAuth()
                mutableSessionChanges.value = mutableSessionChanges.value + 1L
                AuthResult.SignedIn(runCatching { loadProfile() }.getOrNull())
            }
        }
    }

    /** Explicit refresh entry point; normal authenticated requests refresh automatically as needed. */
    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val saved = store.read()?.let(::decodeSession) ?: return@withContext false
        refreshStoredSession(saved, force = true) != null
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
        val profile = rows.optJSONObject(0)?.toProfile() ?: return@withContext null
        profile.copy(fullName = loadPrivateFullName(s.userId))
    }

    private fun loadPrivateFullName(userId: String): String =
        request("/rest/v1/vinz_account_private?select=full_name&user_id=eq.$userId", "GET")
            .optJSONArray("data")?.optJSONObject(0)?.optString("full_name").orEmpty()

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

    /** Saves the private full name separately from the public music profile. */
    suspend fun savePrivateFullName(fullName: String): String = withContext(Dispatchers.IO) {
        val normalized = fullName.trim()
        require(normalized.length <= 120) { "Full name must be 120 characters or fewer." }
        val s = session ?: error("Sign in to your Vinz Account first.")
        val result = request(
            "/rest/v1/vinz_account_private?user_id=eq.${s.userId}",
            "PATCH",
            JSONObject().put("full_name", normalized).toString(),
            prefer = "return=representation",
        )
        val saved = result.optJSONArray("data")?.optJSONObject(0)?.optString("full_name")
        if (saved != null) saved else {
            val reloaded = loadPrivateFullName(s.userId)
            if (reloaded != normalized) error("Private account details could not be saved.")
            reloaded
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val s = session
        if (s != null) runCatching { request("/auth/v1/logout", "POST", null) }
        synchronized(refreshLock) { store.clear() }
    }

    private data class HttpResult(val code: Int, val body: String) {
        val successful: Boolean get() = code in 200..299
    }

    private fun executeRequest(
        path: String,
        method: String,
        body: String?,
        session: Session?,
        prefer: String?,
    ): HttpResult {
        val builder = Request.Builder().url(SUPABASE_URL + path)
            .header("apikey", SUPABASE_PUBLISHABLE_KEY)
            .header("Accept", "application/json")
        if (session != null) builder.header("Authorization", "Bearer ${session.accessToken}")
        else builder.header("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
        if (body != null) builder.header("Content-Type", "application/json").method(method, body.toRequestBody(jsonType))
        else builder.method(method, null)
        prefer?.let { builder.header("Prefer", it) }
        return http.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    /**
     * Authenticated REST calls refresh before expiry and retry one 401 after a rotated token.
     * The refresh lock prevents concurrent requests from consuming the same single-use refresh token.
     */
    private fun request(path: String, method: String, body: String? = null, authenticated: Boolean = true, prefer: String? = null): JSONObject {
        val originalSession = if (authenticated) sessionForRequest() else null
        var response = executeRequest(path, method, body, originalSession, prefer)
        if (response.code == 401 && originalSession != null) {
            val refreshed = refreshStoredSession(originalSession, force = true)
            if (refreshed != null && refreshed.userId == originalSession.userId) {
                response = executeRequest(path, method, body, refreshed, prefer)
                if (response.code == 401) clearSessionIfCurrent(refreshed)
            }
        }
        return parseResponse(path, response)
    }

    private fun sessionForRequest(): Session {
        val saved = store.read()?.let(::decodeSession) ?: error("Sign in to your Vinz Account first.")
        return refreshStoredSession(saved, force = false)
            ?: error("Your Vinz Account session expired. Sign in again.")
    }

    private fun refreshStoredSession(expected: Session, force: Boolean): Session? = synchronized(refreshLock) {
        val current = store.read()?.let(::decodeSession) ?: return@synchronized null
        if (current.userId != expected.userId) return@synchronized null
        // Another request may already have rotated the refresh token. Reuse its newer session.
        if (!CloudSessionPolicy.sameSessionVersion(
                expected.accessToken, expected.refreshToken, current.accessToken, current.refreshToken,
            )) {
            return@synchronized current
        }
        val now = System.currentTimeMillis() / 1_000L
        if (!force && !CloudSessionPolicy.shouldRefresh(current.expiresAtEpochSeconds, now)) {
            return@synchronized current
        }
        val refreshResponse = executeRequest(
            "/auth/v1/token?grant_type=refresh_token",
            "POST",
            JSONObject().put("refresh_token", current.refreshToken).toString(),
            session = null,
            prefer = null,
        )
        if (!refreshResponse.successful) {
            // Invalid/revoked refresh tokens are terminal; transient failures retain the session.
            if (refreshResponse.code == 400 || refreshResponse.code == 401) {
                store.clear()
                return@synchronized null
            }
            error("Vinz Account session refresh failed (${refreshResponse.code}).")
        }
        val payload = runCatching { JSONObject(refreshResponse.body) }.getOrNull()
            ?: error("Vinz Account session refresh returned an invalid response.")
        val user = payload.optJSONObject("user")
        val refreshedUserId = user?.optString("id")?.takeIf { it.isNotBlank() } ?: current.userId
        if (refreshedUserId != current.userId) {
            store.clear()
            return@synchronized null
        }
        val access = payload.optString("access_token")
        val refresh = payload.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) {
            store.clear()
            return@synchronized null
        }
        val expiresAt = CloudSessionPolicy.expiryEpochSeconds(
            payload.optLong("expires_at", 0L),
            payload.optLong("expires_in", 3_600L),
            now,
        )
        val rotated = Session(
            accessToken = access,
            refreshToken = refresh,
            userId = current.userId,
            email = user?.optString("email")?.takeIf { it.isNotBlank() } ?: current.email,
            expiresAtEpochSeconds = expiresAt,
        )
        store.write(encodeSession(rotated))
        rotated
    }

    private fun clearSessionIfCurrent(expected: Session) {
        synchronized(refreshLock) {
            val current = store.read()?.let(::decodeSession)
            if (current?.userId == expected.userId && current != null && CloudSessionPolicy.sameSessionVersion(
                    expected.accessToken, expected.refreshToken, current.accessToken, current.refreshToken,
                )) store.clear()
        }
    }

    private fun sessionFromAuthResponse(payload: JSONObject, user: JSONObject, fallbackEmail: String): Session {
        val now = System.currentTimeMillis() / 1_000L
        return Session(
            accessToken = payload.optString("access_token"),
            refreshToken = payload.optString("refresh_token"),
            userId = user.optString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() } ?: fallbackEmail,
            expiresAtEpochSeconds = CloudSessionPolicy.expiryEpochSeconds(
                payload.optLong("expires_at", 0L),
                payload.optLong("expires_in", 3_600L),
                now,
            ),
        )
    }

    private fun encodeSession(session: Session): String = JSONObject()
        .put("access_token", session.accessToken)
        .put("refresh_token", session.refreshToken)
        .put("user_id", session.userId)
        .put("email", session.email)
        .put("expires_at", session.expiresAtEpochSeconds)
        .toString()

    /** Serialize sign-in/OTP writes with refresh rotation so an older refresh cannot overwrite a new login. */
    private fun persistSession(session: Session, passwordRecovery: Boolean = false) {
        synchronized(refreshLock) {
            store.write(encodeSession(session))
            // A normal sign-in/account switch must never inherit an older recovery authorization.
            if (!passwordRecovery) store.clearPendingPasswordRecovery()
        }
    }

    private fun parseResponse(path: String, response: HttpResult): JSONObject {
        if (!response.successful) {
            val payload = runCatching { JSONObject(response.body) }.getOrNull()
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
        val text = response.body
        return if (text.isBlank()) JSONObject()
        else if (text.trimStart().startsWith("[")) JSONObject().put("data", org.json.JSONArray(text))
        else JSONObject(text)
    }

    private fun decodeSession(value: String): Session {
        val j = JSONObject(value)
        return Session(
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            userId = j.getString("user_id"),
            email = j.optString("email"),
            expiresAtEpochSeconds = j.optLong("expires_at", 0L),
        )
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
        fun writePendingOAuth(value: String) { prefs.edit().putString("pending_google_oauth", encrypt(value)).apply() }
        fun readPendingOAuth(): String? = decrypt(prefs.getString("pending_google_oauth", null))
        fun clearPendingOAuth() { prefs.edit().remove("pending_google_oauth").apply() }
        fun writePendingPasswordRecovery() { prefs.edit().putString("pending_password_recovery", encrypt("1")).apply() }
        fun readPendingPasswordRecovery(): String? = decrypt(prefs.getString("pending_password_recovery", null))
        fun clearPendingPasswordRecovery() { prefs.edit().remove("pending_password_recovery").apply() }
        fun clear() {
            prefs.edit().remove("session").remove("pending_verification").remove("pending_google_oauth")
                .remove("pending_password_recovery").apply()
        }
    }

    companion object {
        private const val OTP_RESEND_COOLDOWN_SECONDS = 60L
        const val VINZ_API_URL = "https://api.vinzmusic.workers.dev"
        const val VINZ_OAUTH_REDIRECT_URI = "vinz://auth/callback"
        const val VINZ_PASSWORD_RECOVERY_REDIRECT_URI = "vinz://auth/reset-password"
        const val SUPABASE_URL = "https://iszghebmetrbkfhnnoqq.supabase.co"
        // Publishable client key only. Never embed service-role or secret keys in the Android app.
        const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_9UXwKG0oVl1J9zIkJ-bZKg_W-fkotZe"
    }
}
