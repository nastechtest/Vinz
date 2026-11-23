package com.vinz.music.data

import com.vinz.music.R
import com.vinz.music.data.innertube.YouTubeSeen
import com.vinz.music.tr
import java.text.DateFormat
import java.util.Date

/**
 * The twelve checks of "Is playback working?" (1.8.7, plan §9), in order, built on a [Probe]: what each asks of the phone
 * or of YouTube. [LiveProbe] is the real one (the pieces playback itself uses); tests hand in fakes. A check that throws
 * is a problem; a check that needs another is skipped when that one didn't work (offline skips everything that goes out).
 */
object SelfTestChecks {
    /**
     * The song the stream, bot and lyrics checks ask about: "Do I Wanna Know?" by Arctic Monkeys, a song every service
     * knows and has for years (WordLyricsLiveTest asks about it too).
     */
    const val SONG_ID = "bpOSxM0rNPM"
    const val SONG_TITLE = "Do I Wanna Know?"
    const val SONG_ARTIST = "Arctic Monkeys"
    const val SONG_ALBUM = "AM"
    const val SONG_SECONDS = 272

    // The checks' keys, for [SelfTestCheck.needs] and the sheet (the battery row's button).
    const val ONLINE = "online"
    const val YOUTUBE = "youtube"
    const val SIGN_IN = "signin"
    const val STREAM = "stream"
    const val FIRST_SECOND = "first_second"
    const val SOLVER = "solver"
    const val BOT = "bot"
    const val PREMIUM = "premium"
    const val LYRICS = "lyrics"
    const val OUTPUT = "output"
    const val BATTERY = "battery"
    const val NOTIFICATIONS = "notifications"

    /** A song's stream link as the resolver gave it: enough to read its start and say which way worked. */
    data class StreamLink(val url: String, val itag: Int, val kbps: Int, val client: String?)

    /** What reading the start of a link got: the HTTP code and how many bytes came. */
    data class Read(val code: Int, val bytes: Int)

    /** Premium's 256 kbps as far as Vinz knows: not asked (signed out, or Premium quality off), coming, missing, not known yet. */
    enum class Premium { NOT_ASKED, GETTING, MISSING, NOT_KNOWN }

    /** The sound output in use, in words, and whether the music volume is at zero. */
    data class Output(val name: String, val silent: Boolean)

    /** What the checks ask. Each may block (they run off the main thread) or throw (a problem). */
    interface Probe {
        fun online(): Boolean
        /** Asks YouTube Music something small; throws when it can't be reached. */
        suspend fun youtube()
        fun signedIn(): Boolean
        /** When the sign-in was saved (ms), or null if not known (signed in before 1.8.2.1). */
        fun signedInAt(): Long?
        /** A signed-in request, and YouTube's own word on whether it took it as the account. */
        suspend fun accountSeen(): YouTubeSeen
        suspend fun streamLink(): StreamLink
        suspend fun readStart(link: StreamLink): Read
        /** Solves a made-up signature with YouTube's player script; throws when it can't. */
        suspend fun solver()
        /** Whether YouTube answered a player request with "Sign in to confirm you're not a bot". */
        suspend fun botAsked(): Boolean
        fun premium(): Premium
        /** Whether the lyrics service answered with lyrics for the song. */
        suspend fun lyrics(): Boolean
        fun output(): Output
        fun backgroundAllowed(): Boolean
        fun notificationsAllowed(): Boolean
    }

    /** Premium's formats (AAC 141, Opus 774), as the resolver knows them. */
    private val PREMIUM_ITAGS = setOf(141, 774)

    fun checks(p: Probe): List<SelfTestCheck> {
        // What one check found that a later one reads: the link the 403 check reads the start of.
        var link: StreamLink? = null
        return listOf(
            SelfTestCheck(ONLINE, tr(R.string.selftest_online), tr(R.string.selftest_fix_online), timeoutMs = 5_000) {
                if (p.online()) CheckResult.ok() else CheckResult.problem()
            },
            SelfTestCheck(YOUTUBE, tr(R.string.selftest_youtube), tr(R.string.selftest_fix_youtube), listOf(ONLINE)) {
                p.youtube()
                CheckResult.ok()
            },
            SelfTestCheck(SIGN_IN, tr(R.string.selftest_signin), tr(R.string.selftest_fix_signin), listOf(YOUTUBE)) {
                if (!p.signedIn()) return@SelfTestCheck CheckResult.skipped(tr(R.string.selftest_not_signed_in))
                val age = p.signedInAt()?.let { tr(R.string.selftest_signed_in_on, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                val seen = p.accountSeen()
                val detail = listOfNotNull(age, seen.text).joinToString(", ")
                if (seen.signedIn == false) CheckResult.problem(detail) else CheckResult.ok(detail)
            },
            SelfTestCheck(STREAM, tr(R.string.selftest_stream), tr(R.string.selftest_fix_stream), listOf(YOUTUBE), timeoutMs = 30_000) {
                val l = p.streamLink().also { link = it }
                CheckResult.ok("itag ${l.itag}, ${l.kbps} kbps, client ${l.client ?: "?"}")
            },
            SelfTestCheck(FIRST_SECOND, tr(R.string.selftest_first_second), tr(R.string.selftest_fix_first_second), listOf(STREAM)) {
                val l = link ?: return@SelfTestCheck CheckResult.problem("no link")
                val r = p.readStart(l)
                val detail = "HTTP ${r.code}, ${r.bytes} bytes, client ${l.client ?: "?"}"
                if (r.code in 200..299 && r.bytes > 0) CheckResult.ok(detail) else CheckResult.problem(detail)
            },
            // Loading YouTube's player script into its sandbox can take a while on a slow phone.
            SelfTestCheck(SOLVER, tr(R.string.selftest_solver), tr(R.string.selftest_fix_solver), listOf(YOUTUBE), timeoutMs = 90_000) {
                p.solver()
                CheckResult.ok()
            },
            SelfTestCheck(BOT, tr(R.string.selftest_bot), tr(R.string.selftest_fix_bot), listOf(YOUTUBE)) {
                if (p.botAsked()) CheckResult.problem("asked") else CheckResult.ok()
            },
            SelfTestCheck(PREMIUM, tr(R.string.selftest_premium), tr(R.string.selftest_fix_premium), listOf(YOUTUBE)) {
                val l = link
                when {
                    !p.signedIn() -> CheckResult.skipped(tr(R.string.selftest_not_signed_in))
                    l != null && l.itag in PREMIUM_ITAGS -> CheckResult.ok("itag ${l.itag}")
                    else -> when (p.premium()) {
                        Premium.NOT_ASKED -> CheckResult.skipped(tr(R.string.selftest_premium_off))
                        Premium.GETTING -> CheckResult.ok()
                        Premium.MISSING -> CheckResult.problem()
                        Premium.NOT_KNOWN -> CheckResult.skipped(tr(R.string.selftest_premium_not_known))
                    }
                }
            },
            SelfTestCheck(LYRICS, tr(R.string.selftest_lyrics), tr(R.string.selftest_fix_lyrics), listOf(ONLINE)) {
                if (p.lyrics()) CheckResult.ok("LRCLIB") else CheckResult.problem("LRCLIB")
            },
            SelfTestCheck(OUTPUT, tr(R.string.selftest_output), tr(R.string.selftest_fix_output), timeoutMs = 5_000) {
                val o = p.output()
                if (o.silent) CheckResult.problem(o.name) else CheckResult.ok(o.name)
            },
            SelfTestCheck(BATTERY, tr(R.string.selftest_battery), tr(R.string.selftest_fix_battery), timeoutMs = 5_000) {
                if (p.backgroundAllowed()) CheckResult.ok() else CheckResult.problem()
            },
            SelfTestCheck(NOTIFICATIONS, tr(R.string.selftest_notifications), tr(R.string.selftest_fix_notifications), timeoutMs = 5_000) {
                if (p.notificationsAllowed()) CheckResult.ok() else CheckResult.problem()
            },
        )
    }
}

/** The self-test's words: each row's value line, and the text "Share the result" adds to the diagnostics. */
object SelfTestText {
    /** The last result shared, added to what "Share diagnostics" shares while Vinz runs (kept in memory only). */
    @Volatile var last: String? = null

    fun status(r: CheckResult): String = when (r.status) {
        CheckStatus.WAITING -> tr(R.string.selftest_waiting)
        CheckStatus.RUNNING -> tr(R.string.selftest_checking)
        CheckStatus.OK -> tr(R.string.selftest_ok)
        CheckStatus.PROBLEM -> tr(R.string.selftest_problem)
        CheckStatus.SKIPPED -> r.after?.let { tr(R.string.selftest_skipped_after, it) } ?: tr(R.string.selftest_skipped)
    }

    /**
     * A row's value line in the sheet: what it found and, on a problem, what to do. The technical details (the client,
     * the HTTP code) stay in the shared text, apart from a skipped check's reason.
     */
    fun value(r: CheckResult): String = when (r.status) {
        CheckStatus.PROBLEM -> listOfNotNull(status(r), r.fix).joinToString("\n")
        CheckStatus.SKIPPED -> if (r.after == null && r.detail != null) "${status(r)}: ${r.detail}" else status(r)
        else -> status(r)
    }

    /** All of it as text: one line a check, with its details and, on a problem, what to do. */
    fun report(rows: List<SelfTestRow>, at: Long = System.currentTimeMillis()): String = buildString {
        append(tr(R.string.selftest_title)).append(" (")
            .append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))).append(")\n")
        rows.forEachIndexed { i, row ->
            val r = row.result
            append(i + 1).append(". ").append(row.check.title).append(": ").append(status(r))
            r.detail?.let { append(" (").append(it).append(')') }
            if (r.status == CheckStatus.PROBLEM && r.fix != null) append(" ").append(r.fix)
            append('\n')
        }
    }.trimEnd()
}
