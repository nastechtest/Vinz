package com.vinz.music.data.spotify

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The codes Spotify's web player names its library reads by (persisted-query hashes). Spotify changes one every few
 * months, and a changed one stops that read. So: the codes compiled in ([BUILT_IN], newest first), and, only after a
 * code was refused, a small file in Vinz's own repository ([URL]) that can name a newer one without a Vinz release
 * (owner, 2026-11-15). The file is data: operation name → 64 hex characters, and only for the reads in
 * [SpotifyWeb.READS]; anything else in it is dropped.
 *
 * Built-in values as published by Meld (GPL-3.0, github.com/FrancescoGrazioso/Meld), checked on the A16 2026-11-15.
 */
class SpotifyCodeBook(
    private val prefs: SharedPreferences,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The codes to try for [op], in order: one from the fetched file first, then the built-in ones. */
    fun codes(op: String): List<String> =
        (listOfNotNull(fetched()[op]) + BUILT_IN[op].orEmpty()).distinct()

    private fun fetched(): Map<String, String> = prefs.getString(KEY, null)?.let(::parse).orEmpty()

    /** Reads the file again, at most once in [EVERY_MS]. True when it named a code that wasn't known for some read. */
    suspend fun refresh(): Boolean {
        val last = prefs.getLong(KEY_AT, -1)
        if (last >= 0 && now() - last < EVERY_MS) return false
        prefs.edit().putLong(KEY_AT, now()).apply()
        val text = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(URL).build()).execute().use { r -> if (r.isSuccessful) r.body.string().take(MAX_CHARS) else null }
            }.getOrNull()
        } ?: return false
        val fresh = parse(text)
        if (fresh.isEmpty()) return false
        val new = fresh.any { (op, code) -> code !in codes(op) }
        prefs.edit().putString(KEY, JsonObject(fresh.mapValues { JsonPrimitive(it.value) }).toString()).apply()
        return new
    }

    companion object {
        const val URL = "https://raw.githubusercontent.com/nastechtest/Vinz/main/spotify/codes.json"
        const val EVERY_MS = 6 * 60 * 60_000L
        private const val MAX_CHARS = 8_000
        private const val KEY = "web_codes"
        private const val KEY_AT = "web_codes_at"
        private val HEX = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        val BUILT_IN: Map<String, List<String>> = mapOf(
            "profileAttributes" to listOf(
                "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
                "b197b5adb4b761690f76ad9d9fb278c14c14e7331f357c04a56e7001af7106e0",
            ),
            "libraryV3" to listOf(
                "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
                "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
            ),
            "fetchPlaylist" to listOf(
                "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
                "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a",
            ),
            "fetchLibraryTracks" to listOf("087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"),
            // 1.8.7.6, "Spotify's canvas first" ([SpotifyCanvas]): both answered 30 of 30 on the A16 trial (2026-11-16)
            // with the session's token alone.
            "searchDesktop" to listOf("db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345"),
            "canvas" to listOf("575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"),
        )

        /** `{"profileAttributes": "<64 hex>", ...}`: reads only, well-formed codes only. Empty when it isn't that. */
        fun parse(text: String): Map<String, String> {
            val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
            return o.mapNotNull { (op, v) ->
                val code = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
                if (op in SpotifyWeb.READS && code != null && HEX.matches(code)) op to code else null
            }.toMap()
        }
    }
}

/**
 * The signed-in listener's library, read the way Spotify's web player reads it (its GraphQL at [URL]) with the
 * session's token. **Read only, enforced here:** [read] sends nothing but the reads in [READS]; no change to
 * anything on Spotify can be asked through this class, whatever the caller. Requests are spaced [SpotifyApi.GAP_MS]
 * apart and a 429's wait is kept by everyone.
 */
class SpotifyWeb(
    private val http: OkHttpClient,
    private val tokens: SessionTokens,
    private val book: SpotifyCodeBook,
    private val userAgent: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val paceLock = Mutex()
    @Volatile private var lastAt = 0L
    @Volatile var quietUntil = 0L
        private set

    /** The answer's "data" for one of [READS]. */
    suspend fun read(op: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        if (op !in READS) throw SpotifyError.Forbidden("not a read: $op")
        var tried = emptyList<String>()
        repeat(2) { round ->
            val codes = book.codes(op) - tried.toSet()
            for (code in codes) {
                when (val a = post(op, code, variables)) {
                    is Answer.Data -> return a.data
                    Answer.UnknownCode -> Unit
                }
            }
            tried = tried + codes
            // Every known code was refused: Spotify changed it. Look for a newer one once, then give up until a refresh.
            if (round == 0 && !book.refresh()) throw SpotifyError.Unavailable(412, CODES_CHANGED)
        }
        throw SpotifyError.Unavailable(412, CODES_CHANGED)
    }

    private sealed class Answer {
        class Data(val data: JsonObject) : Answer()
        object UnknownCode : Answer()
    }

    private suspend fun post(op: String, code: String, variables: JsonObject, retried: Boolean = false): Answer {
        pace()
        val token = tokens.token(force = retried)
        val req = Request.Builder().url(URL)
            .header("Authorization", "Bearer $token")
            .header("app-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent())
            .post(body(op, code, variables).toRequestBody(JSON))
            .build()
        val (status, text, retryAfter) = withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r -> Triple(r.code, r.body.string(), r.header("Retry-After")) }
        }
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val error = ((root?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message")
            ?.let { (it as? JsonPrimitive)?.content }
        return when {
            status == 412 || error?.contains("PersistedQueryNotFound", ignoreCase = true) == true -> Answer.UnknownCode
            status == 401 && !retried -> post(op, code, variables, retried = true)
            status == 401 -> throw SpotifyError.Revoked("401 after a new token")
            status == 429 -> {
                val ms = (retryAfter?.trim()?.toLongOrNull() ?: 60L).coerceIn(1, 86_400) * 1000
                quietUntil = maxOf(quietUntil, now() + ms)
                throw SpotifyError.RateLimited(ms, false)
            }
            status == 403 -> throw SpotifyError.Forbidden(error ?: "403")
            status !in 200..299 -> throw SpotifyError.Unavailable(status, error.orEmpty().take(80))
            error != null -> throw SpotifyError.Unavailable(status, error.take(80))
            else -> Answer.Data(root?.get("data") as? JsonObject ?: throw SpotifyError.Unavailable(status, "no data"))
        }
    }

    private suspend fun pace() = paceLock.withLock {
        val wait = quietUntil - now()
        if (wait > 0) throw SpotifyError.RateLimited(wait, false)
        val gap = lastAt + SpotifyApi.GAP_MS - now()
        if (gap > 0) pause(gap)
        lastAt = now()
    }

    companion object {
        const val URL = "https://api-partner.spotify.com/pathfinder/v2/query"
        /** What [SpotifyError.Unavailable] says when no known code works: the card reads "This needs a Vinz update". */
        const val CODES_CHANGED = "Spotify changed its codes"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Everything this class can ask. All of them only read: the account's name, its library, a playlist, and (1.8.7.6,
         * [SpotifyCanvas]) a search of Spotify's catalogue and a track's canvas.
         */
        val READS = setOf("profileAttributes", "libraryV3", "fetchLibraryTracks", "fetchPlaylist", "searchDesktop", "canvas")

        fun body(op: String, code: String, variables: JsonObject): String = JsonObject(mapOf(
            "variables" to variables,
            "operationName" to JsonPrimitive(op),
            "extensions" to JsonObject(mapOf("persistedQuery" to JsonObject(mapOf(
                "version" to JsonPrimitive(1), "sha256Hash" to JsonPrimitive(code),
            )))),
        )).toString()
    }
}
