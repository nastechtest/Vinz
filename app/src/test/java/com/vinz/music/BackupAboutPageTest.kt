package com.vinz.music

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.BackupPart
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SOURCE_URL
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.BACKUP_ABOUT_FOOT
import com.vinz.music.ui.screens.settings.BACKUP_ABOUT_UPDATE
import com.vinz.music.ui.screens.settings.BackupAboutContent
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.UpdateCheck
import com.vinz.music.ui.screens.settings.diagnosticsShareIntent
import com.vinz.music.ui.screens.settings.oldGroups
import com.vinz.music.ui.screens.settings.updateCheckValue
import com.vinz.music.ui.screens.settings.value
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * 1.8.4, Settings → Backup & about rebuilt with the kit (plan §2.3, the ledger's Backup & about table): four groups of
 * the five rows within the budgets, Diagnostics a group of its own (no fold), the one stored setting the ledger gives
 * the page with its control (and the rows that follow it), back up and restore still opening their flows, the update
 * check's value line, the update itself under its group, the "unofficial" line at the page's foot, search, Reset this
 * page, and the page drawn in light, dark, Performance mode and at large text.
 *
 * Nothing here reaches a network: the store says GitHub was asked a moment ago (so opening the page asks nothing), and
 * the tests that tap "Check for updates" or a share row draw the page's content with their own stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class BackupAboutPageTest : ListeningPageHarness(SettingsPageId.BACKUP_ABOUT) {
    private fun t(id: Int) = tr(id)
    private val app get() = RuntimeEnvironment.getApplication()

    /** The tests that start with a newer Vinz already known of (as after Home's own check). */
    private val withUpdate = setOf("anUpdateIsOfferedUnderItsRow", "drawnWithEverything", "atLargeTextNothingIsCutOff")
    private val newer = "99.0.0"

    /** Before the container is made (it reads the update it knows of once): GitHub was asked just now, no crash is kept. */
    @get:Rule val offline = object : TestWatcher() {
        override fun starting(description: Description) {
            val prefs = app.getSharedPreferences("updates", 0).edit().clear().putLong("checked_at", System.currentTimeMillis())
            if (description.methodName in withUpdate) prefs.putString("latest_version", newer).putString("latest_url", "https://example.invalid/Vinz.apk")
            prefs.commit()
            crashes.deleteRecursively()
        }

        override fun finished(description: Description) {
            app.getSharedPreferences("updates", 0).edit().clear().commit()
            crashes.deleteRecursively()
        }
    }

    private val crashes get() = File(app.filesDir, "crashes")
    private val crashedAt = 1_762_000_000_000L
    private fun keepACrash() = File(crashes.apply { mkdirs() }, "crash-$crashedAt.txt").apply { writeText("Vinz test crash") }

    private val backupGroup = t(R.string.backup)
    private val updatesGroup = t(R.string.about_updates)
    private val aboutGroup = t(R.string.about_3)
    private val diagnosticsGroup = t(R.string.diagnostics)

    private val backUp = t(R.string.back_up)
    private val restore = t(R.string.restore_from_a_backup)
    private val check = t(R.string.check_for_updates)
    private val source = t(R.string.source_code)
    private val tour = t(R.string.take_the_tour)
    private val licenses = t(R.string.open_source_licenses)
    private val diagnostics = t(R.string.playback_diagnostics)
    private val lastCrash = t(R.string.last_crash)
    private val forgetCrashes = t(R.string.forget_crashes)
    private val shareLog = t(R.string.share_diagnostics)
    private val clearLog = t(R.string.clear_diagnostics)
    private val selfTest = t(R.string.selftest_title)
    private val unofficial = t(R.string.unofficial_not_affiliated_with_google_or)
    private val installed = tr(R.string.vinz, BuildConfig.VERSION_NAME)

    // 1.8.7.6: Support Vinz's three rows, first in About.
    private val support = t(R.string.support_vinz)
    private val supported = t(R.string.i_ve_supported_vinz)
    private val supporters = t(R.string.supporters)

    /** Debug builds (as the tests are) also have the design gallery's row; 1.8.7.6 adds Support Vinz's three. */
    private val aboutRows = if (BuildConfig.DEBUG) 7 else 6

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    // ------------------------------------------------------------------ The page's content, with stand-ins for its two ways out

    private val opened = ArrayList<String>()

    private fun showContent(check: suspend () -> String? = { null }, share: () -> Unit = {}, selfTest: (() -> List<com.vinz.music.data.SelfTestCheck>)? = null) {
        compose.setContent {
            val controller = rememberNavController().also { navController = it }
            val nav = Navigator(controller, openPlayer = {})
            val s by container.settings.stored.collectAsState()
            val links = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalUriHandler provides links) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            NavHost(controller, "page") {
                                composable("page") { BackupAboutContent(0.dp, null, true, {}, check, share, selfTest) }
                                composable("settings/{section}") { Box(Modifier.fillMaxSize()) }
                                composable(Routes.SUPPORTERS) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Layout

    @Test fun fourGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        assertEquals(listOf(backupGroup, updatesGroup, aboutGroup, diagnosticsGroup), groupTitles())
        assertEquals(listOf(2, 1, aboutRows, 2), groupSizes())
        assertEquals("nothing differs from how Vinz comes", 0, changedRows())
        // No group is named as the page, and none as one of its own rows.
        assertTrue(groupTitles().none { it == SettingsPageId.BACKUP_ABOUT.title })
        for (g in groupTitles()) assertFalse("a group and a row are both \"$g\"", hasRow(g))
        assertEquals("four groups: the jump chips are there", 1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Diagnostics is a group like the others: the "More options" fold is gone.
        assertEquals(0, shown(t(R.string.more_options)))
        // Actions and other screens are Link rows; the one setting is a Switch.
        for (title in listOf(backUp, restore, check, source, tour, licenses)) assertEquals(title, SettingsTags.ROW_LINK, typeOf(title))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(diagnostics))
        // What each says of itself.
        assertEquals("before any check: the Vinz that is installed", installed, valueOf(check))
        assertEquals(t(R.string.open_on_github), valueOf(source))
        assertEquals(t(R.string.gpl_3_0_or_later), valueOf(licenses))
        // No update is known of: nothing is drawn for one.
        assertEquals(0, compose.onAllNodes(hasTestTag(BACKUP_ABOUT_UPDATE)).fetchSemanticsNodes().size)
        // The root's line still says which Vinz this is.
        assertTrue(BuildConfig.VERSION_NAME in SettingsPageId.BACKUP_ABOUT.value(stored))
    }

    /** Vinz is unofficial: said on the page whatever else is on it, under the groups and not behind an ⓘ. */
    @Test fun theUnofficialLineIsAtThePagesFoot() {
        show()
        assertEquals("Unofficial · not affiliated with Google or YouTube", unofficial)
        val foot = compose.onNodeWithTag(BACKUP_ABOUT_FOOT).fetchSemanticsNode()
        assertEquals(unofficial, foot.config[SemanticsProperties.Text].first().text)
        assertEquals("said once", 1, shown(unofficial))
        assertEquals("outside the groups", 0, compose.onAllNodes(hasTestTag(BACKUP_ABOUT_FOOT) and hasAnyAncestor(hasTestTag(SettingsTags.GROUP))).fetchSemanticsNodes().size)
        val lastCard = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes().last()
        assertTrue("under the last group", foot.boundsInRoot.top >= lastCard.boundsInRoot.bottom)
        assertTrue("on the screen", foot.boundsInRoot.height > 0f)
    }

    /** §2.7: "Your data stays here" did nothing when tapped. It is no row now; its words are About's ⓘ. */
    @Test fun rowsThatOnlyExplainedAreBehindTheInfoMark() {
        show()
        assertFalse(hasRow(t(R.string.your_data_stays_here)))
        assertEquals(0, shown(t(R.string.about_data_info)))
        compose.onNodeWithContentDescription(tr(R.string.more_about, aboutGroup)).performClick()
        compose.waitForIdle()
        assertEquals(1, shown(t(R.string.about_data_info)))
        // The old second lines are no longer prose on the page.
        for (id in listOf(
            R.string.settings_library_replay_sign_in_you, R.string.replaces_what_the_backup_holds_then, R.string.vinz_also_checks_on_its_own,
            R.string.a_minute_s_walk_through_vinz, R.string.a_log_for_tracking_down_stutters, R.string.keeps_a_log_on_this_phone,
        )) assertEquals(t(id), 0, shown(t(id)))
        // One tap away: Back up's.
        compose.onNodeWithContentDescription(tr(R.string.more_about, backUp)).performClick()
        compose.waitForIdle()
        assertEquals(1, shown(t(R.string.settings_library_replay_sign_in_you)))
    }

    // ------------------------------------------------------------------ Nothing missing

    /** Principle 8: the stored setting the ledger gives the page (`audio_diagnostics`) has its control, and the store follows. */
    @Test fun everyStoredSettingHasItsControl() {
        show()
        assertFalse(stored.audioDiagnostics)
        assertFalse("Share and Clear only show while the log is kept", hasRow(shareLog) || hasRow(clearLog))
        tap(diagnostics)
        until("audio_diagnostics") { it.audioDiagnostics }
        assertTrue(hasRow(shareLog) && hasRow(clearLog))
        assertEquals(SettingsTags.ROW_LINK, typeOf(shareLog))
        assertEquals(SettingsTags.ROW_LINK, typeOf(clearLog))
        assertEquals(listOf(2, 1, aboutRows, 4), groupSizes())
        assertBudgets("diagnostics on")
        assertEquals(1, changedRows())

        // Reset this page lists it and turns it off again.
        val lines = resetLines(confirm = true)
        assertEquals(listOf("$diagnostics: ${t(R.string.on_2)} → ${t(R.string.off)}"), lines)
        until("the reset") { !it.audioDiagnostics }
        assertEquals(0, changedRows())
        assertEquals(listOf(2, 1, aboutRows, 2), groupSizes())
    }

    /** Clearing starts the log again and says so; nothing else on the page changes. */
    @Test fun clearDiagnosticsSaysItDid() {
        container.settings.setAudioDiagnostics(true)
        until("diagnostics on") { it.audioDiagnostics }
        show()
        tap(clearLog)
        assertEquals(t(R.string.diagnostics_cleared), ShadowToast.getTextOfLatestToast())
        assertTrue(stored.audioDiagnostics)
    }

    /** A crash kept on the phone has its two rows whether or not the log is on; sharing is only ever a tap's doing. */
    @Test fun aKeptCrashIsSharedOrForgotten() {
        val file = keepACrash()
        var shared = 0
        showContent(share = { shared++ })
        assertEquals("drawing the page shares nothing", 0, shared)
        assertEquals(listOf(2, 1, aboutRows, 4), groupSizes())
        val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crashedAt))
        assertEquals(tr(R.string.tap_to_share_its_details, at), valueOf(lastCrash))
        tap(lastCrash)
        assertEquals(1, shared)
        // With the log on as well: the group's fullest state, six rows.
        tap(diagnostics)
        until("audio_diagnostics") { it.audioDiagnostics }
        assertEquals(listOf(2, 1, aboutRows, 6), groupSizes())
        assertBudgets("a crash kept, diagnostics on")
        tap(shareLog)
        assertEquals(2, shared)
        // Forgetting deletes it and takes both rows away.
        tap(forgetCrashes)
        assertFalse(file.exists())
        assertFalse(hasRow(lastCrash) || hasRow(forgetCrashes))
        assertEquals(listOf(2, 1, aboutRows, 4), groupSizes())
        assertEquals(2, shared)
    }

    /** What the share sheet is handed: the text file, for the app the listener picks to read. */
    @Test fun diagnosticsGoToTheShareSheetOnly() {
        val uri = android.net.Uri.parse("content://com.vinz.music.share/share/vinz-playback-diagnostics.txt")
        val send = diagnosticsShareIntent(uri)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals(t(R.string.vinz_playback_diagnostics), send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertNull("no app is named: the listener picks", send.component)
        assertNull(send.`package`)
    }

    // ------------------------------------------------------------------ Is playback working? (1.8.7)

    /** The row: a Link row in Diagnostics, with its words behind ⓘ; drawing the page runs nothing. */
    @Test fun theSelfTestRowIsInDiagnostics() {
        var ran = 0
        showContent(selfTest = { ran++; emptyList() })
        assertEquals(SettingsTags.ROW_LINK, typeOf(selfTest))
        assertEquals("drawing the page runs no check", 0, ran)
        assertEquals(0, shown(t(R.string.selftest_info)))
        val line = SettingsIndex.entries.single { it.page == SettingsPageId.BACKUP_ABOUT && it.title == selfTest }
        assertEquals(diagnosticsGroup, line.group)
        assertTrue(SettingsIndex.search("not playing").any { it.title == selfTest })
        assertTrue(SettingsIndex.search("403").any { it.title == selfTest })
    }

    /**
     * The check comes first in Diagnostics, above the log's switch (1.8.7 release check's settings walk: someone whose
     * music won't play should meet "Is playback working?" before a switch about a log), in the group and in search.
     */
    @Test fun theCheckComesBeforeTheLog() {
        showContent(selfTest = { emptyList() })
        fun top(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue("\"$selfTest\" is above \"$diagnostics\"", top(selfTest) < top(diagnostics))
        val lines = SettingsIndex.entries.filter { it.page == SettingsPageId.BACKUP_ABOUT && it.group == diagnosticsGroup }.map { it.title }
        assertTrue(lines.indexOf(selfTest) < lines.indexOf(diagnostics))
    }

    /** The sheet runs the checks it is handed, shows each result, and Share adds the result to the shared diagnostics. */
    @Test fun theSelfTestSheetShowsAndSharesTheResult() {
        var shared = 0
        com.vinz.music.data.SelfTestText.last = null
        val checks = {
            listOf(
                com.vinz.music.data.SelfTestCheck("a", "First check", "Do the first thing.") { com.vinz.music.data.CheckResult.ok() },
                com.vinz.music.data.SelfTestCheck("b", "Second check", "Do the second thing.") { com.vinz.music.data.CheckResult.problem("broke") },
                com.vinz.music.data.SelfTestCheck("c", "Third check", "Do the third thing.", needs = listOf("b")) { com.vinz.music.data.CheckResult.ok() },
            )
        }
        showContent(share = { shared++ }, selfTest = checks)
        tap(selfTest)
        compose.waitUntil(5_000) { shown(t(R.string.selftest_ok)) == 1 && shown(t(R.string.selftest_problem) + "\nDo the second thing.") == 1 }
        assertEquals(1, shown(tr(R.string.selftest_skipped_after, "Second check")))
        compose.onNode(hasText(t(R.string.selftest_share)) and hasAnyAncestor(hasTestTag(SettingsTags.SHEET))).performClick()
        compose.waitForIdle()
        assertEquals(1, shared)
        val text = com.vinz.music.data.SelfTestText.last!!
        assertTrue(text, "1. First check: ${t(R.string.selftest_ok)}" in text && "2. Second check: ${t(R.string.selftest_problem)} (broke) Do the second thing." in text)
        com.vinz.music.data.SelfTestText.last = null
    }

    /** What "Share diagnostics" shares: the result too, once it has been shared from the sheet. */
    @Test fun theSharedDiagnosticsCarryTheResult() {
        assertEquals("about\ncanvas", com.vinz.music.ui.screens.settings.diagnosticsAbout("about", "canvas", null))
        val with = com.vinz.music.ui.screens.settings.diagnosticsAbout("about", "canvas", "1. Online: Works")
        assertTrue(with, with.startsWith("about\ncanvas\n") && with.endsWith("===== $selfTest =====\n1. Online: Works"))
    }

    // ------------------------------------------------------------------ Backup

    /** Back up still asks what goes in (every part offered, as before); nothing is written until a place is chosen. */
    @Test fun backUpAsksWhatGoesIn() {
        show()
        tap(backUp)
        assertEquals(1, shown(t(R.string.back_up_vinz)))
        for (part in BackupPart.entries) assertTrue(part.label, shown(part.label) >= 1)
        assertEquals(1, shown(t(R.string.choose_where)))
        compose.onNodeWithText(t(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(0, shown(t(R.string.back_up_vinz)))
        assertNull("nothing was opened", shadowOf(app).nextStartedActivity)
    }

    /** Restore still opens Android's file picker first (the confirm comes once a file is picked). */
    @Test fun restoreOpensTheFilePicker() {
        show()
        tap(restore)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started?.action)
        assertEquals(0, shown(t(R.string.restore_this_backup)))
    }

    // ------------------------------------------------------------------ Updates

    @Test fun theUpdateRowsValueLine() {
        assertEquals(installed, updateCheckValue(null, null))
        assertEquals(tr(R.string.vinz_is_available, "2.0"), updateCheckValue(null, "2.0"))
        assertEquals(t(R.string.asking_github), updateCheckValue(UpdateCheck.Asking, "2.0"))
        assertEquals(t(R.string.you_re_up_to_date), updateCheckValue(UpdateCheck.Current, null))
        assertEquals(tr(R.string.vinz_is_out_tap_below_to, "2.0"), updateCheckValue(UpdateCheck.Newer("2.0"), "2.0"))
        assertEquals(t(R.string.couldn_t_reach_github_try_again), updateCheckValue(UpdateCheck.Unreachable, null))
    }

    /** A tap asks once, says it is asking, then says what it found; nothing is asked until the tap. */
    @Test fun checkForUpdatesSaysWhatItFound() {
        var asked = 0
        val first = CompletableDeferred<Unit>()
        var answer: () -> String? = { null }
        showContent(check = { asked++; if (asked == 1) first.await(); answer() })
        assertEquals("drawing the page asks nothing", 0, asked)
        assertEquals(installed, valueOf(check))

        tap(check)
        assertEquals(1, asked)
        assertEquals(t(R.string.asking_github), valueOf(check))
        assertTrue("no second ask while the first is out", SemanticsProperties.Disabled in row(check).fetchSemanticsNode().config)
        first.complete(Unit)
        compose.waitUntil(5_000) { valueOf(check) == t(R.string.you_re_up_to_date) }

        answer = { "9.9.9" }
        tap(check)
        compose.waitUntil(5_000) { valueOf(check) == tr(R.string.vinz_is_out_tap_below_to, "9.9.9") }

        answer = { throw java.io.IOException("offline") }
        tap(check)
        compose.waitUntil(5_000) { valueOf(check) == t(R.string.couldn_t_reach_github_try_again) }
        assertEquals(3, asked)
        assertBudgets("after checking")
    }

    /** The update itself (Home's card, #201) sits under the Updates group, and the row names it before any tap. */
    @Test fun anUpdateIsOfferedUnderItsRow() {
        show()
        assertEquals(newer, container.updates.available.value?.version)
        val offer = tr(R.string.vinz_is_available, newer)
        assertEquals(offer, valueOf(check))
        val card = compose.onNodeWithTag(BACKUP_ABOUT_UPDATE).fetchSemanticsNode()
        assertEquals("the card's own title too", 2, shown(offer))
        assertEquals(1, shown(t(R.string.tap_to_update)))
        // Between Updates and About, in no group's card, as wide as the cards.
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes()
        assertTrue(card.boundsInRoot.top >= cards[1].boundsInRoot.bottom && card.boundsInRoot.bottom <= cards[2].boundsInRoot.top)
        assertEquals(0, compose.onAllNodes(hasTestTag(BACKUP_ABOUT_UPDATE) and hasAnyAncestor(hasTestTag(SettingsTags.GROUP))).fetchSemanticsNodes().size)
        val drawn = compose.onNode(hasText(t(R.string.tap_to_update)) and hasAnyAncestor(hasTestTag(BACKUP_ABOUT_UPDATE))).fetchSemanticsNode()
        assertTrue("inside the page's gutters", drawn.boundsInRoot.left >= cards[1].boundsInRoot.left && drawn.boundsInRoot.right <= cards[1].boundsInRoot.right)
        assertBudgets("an update offered")
        assertEquals(listOf(2, 1, aboutRows, 2), groupSizes())
    }

    // ------------------------------------------------------------------ About

    @Test fun aboutsLinksGoWhereTheyDid() {
        val tours = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { container.tourRequests.collect { tours.incrementAndGet() } }
        try {
            showContent()
            tap(source)
            assertEquals(listOf(SOURCE_URL), opened)
            tap(tour)
            assertEquals(1, tours.get())
            tap(licenses)
            compose.waitForIdle()
            assertEquals("settings/{section}", navController!!.currentDestination?.route)
            assertEquals("licenses", navController!!.currentBackStackEntry?.arguments?.getString("section"))
            assertTrue(SettingsMap.resolve("licenses")!!.licenses)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 1.8.7.6 (plan-1.8.7.6 §4.1 S1–S3): Support Vinz opens Ko-fi in the browser (nothing else leaves the phone), "I've
     * supported Vinz" is off as Vinz comes and shows a heart when on, and Supporters opens its own screen. All three
     * sit first in About, above "Source code", with their words behind the ⓘ.
     */
    @Test fun supportVinzsThreeRows() {
        showContent()
        for (title in listOf(support, supported, supporters)) assertTrue(title, hasRow(title))
        assertEquals(SettingsTags.ROW_LINK, typeOf(support))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(supported))
        assertEquals(SettingsTags.ROW_LINK, typeOf(supporters))
        assertEquals("Ko-fi", valueOf(support))
        // Above Source code, in this order.
        fun top(title: String) = compose.onAllNodes(hasText(title)).fetchSemanticsNodes().first().boundsInRoot.top
        assertTrue(top(support) < top(supported) && top(supported) < top(supporters) && top(supporters) < top(source))
        // Explanations only behind the ⓘ.
        assertEquals(0, shown(t(R.string.support_vinz_info)))
        assertEquals(0, shown(t(R.string.i_ve_supported_vinz_info)))
        tap(support)
        assertEquals(listOf("https://ko-fi.com/yigder"), opened)
        // The switch: off as Vinz comes, the listener's word when on.
        assertFalse(stored.behavior.supported)
        assertFalse(AppSettings().behavior.supported)
        tap(supported)
        until("supported") { it.behavior.supported }
        tap(supported)
        until("supported off") { !it.behavior.supported }
        tap(supporters)
        compose.waitForIdle()
        assertEquals(Routes.SUPPORTERS, navController!!.currentDestination?.route)
    }

    /** An empty Supporters list says so in one friendly line, with the way to Ko-fi; a list shows members first. */
    @Test fun theSupportersList() {
        val taps = ArrayList<String>()
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            CompositionLocalProvider(LocalContainer provides container) {
                VinzTheme(s, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides s) {
                        var list by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.vinz.music.ui.support.Supporters()) }
                        listState = { list = it }
                        com.vinz.music.ui.screens.settings.SupportersList(list) { taps += "kofi" }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, shown(t(R.string.supporters_empty)))
        compose.onNodeWithText(support).performClick()
        assertEquals(listOf("kofi"), taps)
        listState!!(com.vinz.music.ui.support.Supporters(members = listOf("Ana"), supporters = listOf("Bo", "Cy")))
        compose.waitForIdle()
        assertEquals(0, shown(t(R.string.supporters_empty)))
        // (A group's title is drawn as an eyebrow, in capitals.)
        fun top(text: String) = compose.onNodeWithText(text, ignoreCase = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top(t(R.string.supporters_members)) < top("Ana") && top("Ana") < top(t(R.string.supporters_thank_you)) && top(t(R.string.supporters_thank_you)) < top("Bo"))
        assertEquals(3, compose.onAllNodes(hasTestTag(com.vinz.music.ui.screens.settings.SupportersTags.NAME)).fetchSemanticsNodes().size)
    }

    private var listState: ((com.vinz.music.ui.support.Supporters) -> Unit)? = null

    // ------------------------------------------------------------------ Search and old links

    @Test fun searchFindsEveryRowInItsGroup() {
        show()
        val drawn = groupTitles().toSet()
        val lines = SettingsIndex.entries.filter { it.page == SettingsPageId.BACKUP_ABOUT && !it.isPage }
        val lost = lines.filter { it.group !in drawn }
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        fun line(title: String) = lines.single { it.title == title }
        // Every row of the page with default settings has its line, in the group it is drawn in.
        for ((group, titles) in listOf(backupGroup to listOf(backUp, restore), updatesGroup to listOf(check), aboutGroup to listOf(support, supported, supporters, source, tour, licenses), diagnosticsGroup to listOf(diagnostics, selfTest))) {
            for (title in titles) assertEquals(title, group, line(title).group)
        }
        // The rows that follow the log say what to turn on, and stop saying it once that's on.
        for (title in listOf(shareLog, clearLog)) {
            val needs = line(title).needs
            assertTrue(title, needs != null && !needs.on(AppSettings()) && needs.on(AppSettings(audioDiagnostics = true)))
            assertEquals(tr(R.string.turn_on_first, diagnostics), needs!!.hint)
        }
        for (title in listOf(lastCrash, forgetCrashes)) assertEquals(diagnosticsGroup, line(title).group)
        // Found by what people type.
        assertTrue(SettingsIndex.search("privacy").any { it.page == SettingsPageId.BACKUP_ABOUT && it.title == source })
        assertTrue(SettingsIndex.search("version").any { it.page == SettingsPageId.BACKUP_ABOUT })
        assertTrue(SettingsIndex.search("github").any { it.title == source })
        assertTrue(SettingsIndex.search("stutter").any { it.title == diagnostics })
        assertTrue(SettingsIndex.search("ko-fi").any { it.title == support })
        assertTrue(SettingsIndex.search("donate").any { it.title == support })
        assertEquals(check, SettingsIndex.search("check for updates").first().title)
        // Links written before the rework still land: the old page's key, and its groups by their names.
        assertTrue(SettingsPageId.BACKUP_ABOUT.oldGroups.values.all { it in drawn })
        assertEquals(SettingsTarget(SettingsPageId.BACKUP_ABOUT), SettingsMap.resolve("about"))
        for (g in listOf(backupGroup, aboutGroup, diagnosticsGroup)) assertEquals(SettingsTarget(SettingsPageId.BACKUP_ABOUT, g), SettingsMap.resolve("about", g))
    }

    // ------------------------------------------------------------------ Drawn

    private val everything: (AppSettings) -> AppSettings = { it.copy(audioDiagnostics = true) }

    /** The page's longest state: an update offered, a crash kept, the log on. */
    @Test fun atLargeTextNothingIsCutOff() {
        keepACrash()
        show(fontScale = 1.5f, over = everything)
        assertEquals(listOf(2, 1, aboutRows, 6), groupSizes())
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("backup-about", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h1100dp-xxhdpi") fun drawnLight() = drawn("backup-about-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h1100dp-xxhdpi") fun drawnDark() = drawn("backup-about-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h1100dp-xxhdpi") fun drawnPerformance() = drawn("backup-about-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun drawnWithEverything() {
        keepACrash()
        drawn("backup-about-dark-everything", true) { everything(it).copy(themeMode = ThemeMode.DARK) }
    }
}
