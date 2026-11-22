package com.vinz.music.ui.support

import android.content.Context
import androidx.compose.ui.platform.UriHandler
import com.vinz.music.BuildConfig
import java.time.Instant
import java.time.ZoneId

/**
 * Support Vinz (1.8.7.6, plan-1.8.7.6 §4): when one of the few asks to support Vinz on Ko-fi may show.
 *
 * Every ask shows once, has a clear no, and never comes back. Vinz never contacts Ko-fi: a tap opens [KOFI_URL] in the
 * browser ([openKofi]). Nothing is recorded but each ask's "shown" mark and the day of the last ask ([SupportMarks],
 * on the phone, carried by a backup with the settings) and the switch "I've supported Vinz"
 * ([com.vinz.music.data.prefs.BehaviorPrefs.supported]).
 *
 * The rules (§4.2), all in [due], a pure function tested without a screen:
 * - never with the switch on, never on a TV (Android Auto, widgets and notifications never ask at all);
 * - never before the first run is finished, never in the first [QUIET_DAYS] days after install;
 * - never two different asks on one calendar day;
 * - each ask once ([Ask.key] is its mark);
 * - the 100-hour card only once the phone's own listening reaches [HOURS], and not while another Home offer shows.
 */
object SupportAsks {
    const val KOFI_URL = "https://ko-fi.com/yigder"
    const val QUIET_DAYS = 7L
    const val HOURS = 100L
    val HOURS_MS = HOURS * 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** The kinds of ask. [WHATS_NEW] is one per release: its mark carries the release's versionCode. */
    enum class Kind { HOME_HOURS, REPLAY, REWIND, WHATS_NEW }

    /** One ask; [key] is its "shown" mark. */
    data class Ask(val kind: Kind, val version: Int = 0) {
        val key: String get() = when (kind) {
            Kind.HOME_HOURS -> "home_hours"
            Kind.REPLAY -> "replay_month"
            Kind.REWIND -> "rewind_year"
            Kind.WHATS_NEW -> "whats_new_$version"
        }
    }

    val HOME = Ask(Kind.HOME_HOURS)
    val REPLAY = Ask(Kind.REPLAY)
    val REWIND = Ask(Kind.REWIND)
    fun whatsNew(version: Int) = Ask(Kind.WHATS_NEW, version)

    /** Everything [due] looks at. Times are epoch milliseconds; [lastAskAt] 0 is "never". */
    data class State(
        val now: Long,
        val installedAt: Long,
        val onboarded: Boolean,
        val supported: Boolean,
        val shown: Set<String> = emptySet(),
        val lastAskKey: String? = null,
        val lastAskAt: Long = 0L,
        /** The phone's own all-time listening (play_events; brought-in histories are not in it). */
        val listenedMs: Long = 0L,
        /** Another offer card (Performance mode, Smart Downloads) is on Home. */
        val otherHomeOffer: Boolean = false,
        val tv: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    /** Whether [ask] may show now. */
    fun due(ask: Ask, s: State): Boolean {
        if (s.supported || s.tv || !s.onboarded) return false
        if (s.now - s.installedAt < QUIET_DAYS * DAY_MS) return false
        if (ask.key in s.shown) return false
        // One ask a day: another one shown today makes this one wait for tomorrow. (The same ask seen earlier today,
        // the Home card not yet answered, may stay.)
        if (s.lastAskAt > 0 && s.lastAskKey != ask.key && sameDay(s.lastAskAt, s.now, s.zone)) return false
        return when (ask.kind) {
            Kind.HOME_HOURS -> s.listenedMs >= HOURS_MS && !s.otherHomeOffer
            else -> true
        }
    }

    fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** Opens Ko-fi in the browser (Android's ACTION_VIEW, through Compose's [UriHandler]). Vinz itself never contacts it. */
    fun openKofi(uri: UriHandler) {
        runCatching { uri.openUri(KOFI_URL) }
    }
}

/**
 * The asks' marks, in their own small preferences file ([PREFS], like the tour's), not in the settings: which asks were
 * shown, and which ask was last shown and when. A backup carries the file with Settings, so a restored phone does not
 * ask again.
 */
class SupportMarks(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun now(): Long = (if (BuildConfig.DEBUG) testNow else null) ?: System.currentTimeMillis()

    /** When Vinz was installed on this phone (a debug build's test may set it). */
    fun installedAt(): Long = (if (BuildConfig.DEBUG) testInstalledAt else null)
        ?: runCatching { app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime }.getOrDefault(now())

    fun shown(): Set<String> = prefs.getStringSet(SHOWN, emptySet()).orEmpty().toSet()

    /** [ask] was on screen today (the Home card, until it is answered): no other ask today. */
    fun stamp(ask: SupportAsks.Ask) {
        prefs.edit().putString(LAST_KEY, ask.key).putLong(LAST_AT, now()).apply()
    }

    /** [ask] is done for good. */
    fun end(ask: SupportAsks.Ask) {
        prefs.edit().putStringSet(SHOWN, shown() + ask.key).putString(LAST_KEY, ask.key).putLong(LAST_AT, now()).apply()
    }

    /** The state for [SupportAsks.due], with this phone's marks, clock and install date. */
    fun state(
        onboarded: Boolean, supported: Boolean, listenedMs: Long = 0L, otherHomeOffer: Boolean = false,
        tv: Boolean = com.vinz.music.ui.tv.TvDevice.isTv(app),
    ) = SupportAsks.State(
        now = now(), installedAt = installedAt(), onboarded = onboarded, supported = supported, shown = shown(),
        lastAskKey = prefs.getString(LAST_KEY, null), lastAskAt = prefs.getLong(LAST_AT, 0L),
        listenedMs = listenedMs, otherHomeOffer = otherHomeOffer, tv = tv,
    )

    companion object {
        const val PREFS = "support"
        private const val SHOWN = "shown"
        private const val LAST_KEY = "last_key"
        private const val LAST_AT = "last_at"

        /** Debug builds only (in-app tests): the clock and the install date. Read nowhere in a release build. */
        @Volatile var testNow: Long? = null
        @Volatile var testInstalledAt: Long? = null
    }
}
