package com.vinz.music.data

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.vinz.music.AppContainer
import com.vinz.music.data.innertube.YouTubeSeen
import com.vinz.music.data.innertube.str
import com.vinz.music.data.model.AlbumRef
import com.vinz.music.data.model.ArtistRef
import com.vinz.music.data.model.Song
import com.vinz.music.data.prefs.LyricsSource
import com.vinz.music.data.stream.PlayerJsSolver
import com.vinz.music.data.stream.StreamResolver
import com.vinz.music.playback.AudioDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import java.io.IOException

/**
 * The self-test's real [SelfTestChecks.Probe]: the pieces playback itself uses, asked once. It goes only where playing
 * a song goes (YouTube Music, youtube.com's player script, googlevideo for the song's first second, LRCLIB for its
 * lyrics) and sends nothing of the listener's but what those requests always carry (the sign-in, for the signed-in ones).
 */
class LiveProbe(private val c: AppContainer, private val context: Context = c.app) : SelfTestChecks.Probe {
    override fun online() = c.network.isOnline()

    override suspend fun youtube() {
        // What Explore asks, without the account.
        c.innerTube.postAnonymous("browse", buildJsonObject { put("browseId", "FEmusic_explore") })
    }

    override fun signedIn() = c.settings.current.isLoggedIn

    override fun signedInAt(): Long? = c.settings.current.behavior.accountSavedAt.takeIf { it > 0 }

    override suspend fun accountSeen(): YouTubeSeen {
        // What Home asks, as the account: YouTube's answer says whether it took it as the account.
        val answer = c.innerTube.post("browse", buildJsonObject { put("browseId", "FEmusic_home") })
        return YouTubeSeen.of(answer)
    }

    override suspend fun streamLink(): SelfTestChecks.StreamLink = withContext(Dispatchers.IO) {
        // Asked afresh, as a song not played for a while would be.
        c.streams.invalidate(SelfTestChecks.SONG_ID)
        val r = c.streams.resolve(SelfTestChecks.SONG_ID)
        SelfTestChecks.StreamLink(r.audioUrl, r.itag, r.bitrate, r.client)
    }

    override suspend fun readStart(link: SelfTestChecks.StreamLink): SelfTestChecks.Read = withContext(Dispatchers.IO) {
        // About a second of sound (at least 16 kB), read through the player's own client.
        val bytes = maxOf(16_384, link.kbps * 125)
        val req = Request.Builder().url(link.url).header("Range", "bytes=0-${bytes - 1}").build()
        c.streamHttp.newCall(req).execute().use { r ->
            val got = if (r.isSuccessful) r.body.byteStream().use { s -> countUpTo(s, bytes) } else 0
            SelfTestChecks.Read(r.code, got)
        }
    }

    private fun countUpTo(s: java.io.InputStream, max: Int): Int {
        val buf = ByteArray(8192)
        var n = 0
        while (n < max) {
            val read = s.read(buf, 0, minOf(buf.size, max - n))
            if (read < 0) break
            n += read
        }
        return n
    }

    override suspend fun solver() = withContext(Dispatchers.IO) {
        // A solver of its own, let go at once: the player's is left as it is.
        val solver = PlayerJsSolver(context, c.http)
        try {
            val (id, _) = solver.currentPlayer()
            val sig = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".repeat(2)
            val (solved, _) = solver.solve(id, sig, "AbCdEfGhIjKlMnOp")
            if (solved.isNullOrEmpty() || solved == sig) throw IOException("player $id gave no signature")
        } finally {
            solver.close()
        }
    }

    override suspend fun botAsked(): Boolean = withContext(Dispatchers.IO) {
        val res = c.innerTube.visionOsPlayer(SelfTestChecks.SONG_ID)
        res.str("playabilityStatus", "status") == "LOGIN_REQUIRED" &&
            res.str("playabilityStatus", "reason")?.contains("bot", ignoreCase = true) == true
    }

    override fun premium(): SelfTestChecks.Premium {
        val s = c.settings.current
        if (!s.isLoggedIn || !s.premiumStream) return SelfTestChecks.Premium.NOT_ASKED
        if (!c.streams.premiumStateIsCurrent()) return SelfTestChecks.Premium.NOT_KNOWN
        return when (c.streams.premiumState.value) {
            StreamResolver.PremiumState.GETTING -> SelfTestChecks.Premium.GETTING
            StreamResolver.PremiumState.MISSING -> SelfTestChecks.Premium.MISSING
            StreamResolver.PremiumState.NOT_KNOWN -> SelfTestChecks.Premium.NOT_KNOWN
        }
    }

    override suspend fun lyrics(): Boolean {
        val song = Song(
            SelfTestChecks.SONG_ID, SelfTestChecks.SONG_TITLE, listOf(ArtistRef(SelfTestChecks.SONG_ARTIST)),
            AlbumRef(SelfTestChecks.SONG_ALBUM), SelfTestChecks.SONG_SECONDS,
        )
        return c.lyrics.fetch(song, LyricsSource.LRCLIB, force = true)?.lines?.any { !it.isGap } == true
    }

    override fun output(): SelfTestChecks.Output {
        val am = context.getSystemService(AudioManager::class.java) ?: return SelfTestChecks.Output("?", false)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val name = if (Build.VERSION.SDK_INT >= 33) {
            am.getAudioDevicesForAttributes(media).joinToString(", ") { AudioDiagnostics.describe(it) }.ifEmpty { AudioDiagnostics.devices(context) }
        } else AudioDiagnostics.devices(context)
        return SelfTestChecks.Output(name, am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
    }

    override fun backgroundAllowed(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false

    override fun notificationsAllowed() = Notifications.allowed(context)

    companion object {
        /**
         * Android's page for battery restrictions: its list of apps (asking for Vinz alone needs a permission Vinz
         * doesn't have), else Vinz's own app info.
         */
        fun openBatteryPage(context: Context) {
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(list) }.recoverCatching { context.startActivity(app) }
        }
    }
}
