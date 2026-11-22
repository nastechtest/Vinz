package com.vinz.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.compose.ComposeNavigator
import com.vinz.music.data.HistoryPause
import com.vinz.music.data.PlayCount
import com.prism.music.data.db.ImportedPlayEntity
import com.vinz.music.data.imports.ImportSource
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.Routes
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.oldGroups
import com.vinz.music.ui.screens.settings.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import kotlin.math.abs

/**
 * 1.8.4, Settings → Recommendations & history rebuilt with the kit (plan §2.3, the ledger's Recommendations & history
 * table): four groups of the five rows within the budgets, a control for every stored setting the ledger gives the
 * page (worked here, and the store follows), Your artists, Exclusions and Bring your history as Link rows to their own
 * screens, "Count history from" as one row with its sources in a sheet, Reset this page, and the page drawn in light,
 * dark, Performance mode and at large text. The window is tall so that the page's lazy list holds every group at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class RecommendationsHistoryPageTest : ListeningPageHarness(SettingsPageId.RECOMMENDATIONS_HISTORY) {
    private fun t(id: Int, vararg args: Any?) = if (args.isEmpty()) tr(id) else tr(id, *args)

    private val artists = t(R.string.your_artists)
    private val releases = t(R.string.new_release_notifications)
    private val mixesAgain = t(R.string.make_my_mixes_again)
    private val exclusions = t(R.string.exclusions)
    private val forget = t(R.string.forget_skipped_suggestions)
    private val newness = t(R.string.tune_familiar_new)
    private val depth = t(R.string.tune_hits_deep)
    private val energy = t(R.string.tune_calm_energetic)
    private val pause = t(R.string.hist_pause)
    private val countAfter = t(R.string.hist_count_after)
    private val account = t(R.string.hist_count_account)
    private val bring = t(R.string.imp_title)
    private val shapeMixes = t(R.string.imp_shape_mixes)
    private val hours = t(R.string.show_time_in_hours)
    private val themeColors = t(R.string.use_theme_colors)
    private val storyType = t(R.string.replay_story_font)
    private val rewind = t(R.string.rewind_notification)
    private val notices = t(R.string.replay_notices)
    private val recap = t(R.string.recap_notification)
    private val milestone = t(R.string.milestone_notification)
    private val finishLyric = t(R.string.finish_lyric_title)
    private val countFrom = t(R.string.imp_count_history_from)

    private val gMade = t(R.string.made_for_you)
    private val gRadios = t(R.string.tune_radios)
    private val gHistory = t(R.string.hist_title)
    private val gReplay = t(R.string.replay_2)

    private val signedIn: (AppSettings) -> AppSettings = { it.copy(cookie = "SAPISID=test") }

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    /** Somewhere for a Link row's route to go (the harness's graph holds the page and the equalizer). */
    private fun addRoutes(vararg routes: String) {
        val nc = navController!!
        compose.runOnUiThread {
            val navigator = nc.navigatorProvider.getNavigator(ComposeNavigator::class.java)
            routes.forEach { r -> nc.graph.addDestination(ComposeNavigator.Destination(navigator) { Box(Modifier.fillMaxSize()) }.apply { route = r }) }
        }
    }

    private fun opens(title: String, route: String) {
        assertEquals("page", navController!!.currentDestination?.route)
        tap(title)
        compose.waitUntil(5_000) { navController!!.currentDestination?.route == route }
        compose.runOnUiThread { navController!!.popBackStack() }
        compose.waitUntil(5_000) { navController!!.currentDestination?.route == "page" }
        compose.waitForIdle()
    }

    /** A row inside the "Count history from" sheet, by its source's name. */
    private fun inSheet(title: String) = compose.onNode(rowIs(title) and hasAnyAncestor(hasTestTag(SettingsTags.SHEET)))

    @Test fun fourGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        // The ledger's layout with a fresh install's settings ("Bring your history" is a row of History: its group was named as its row).
        assertEquals(listOf(gMade, gRadios, gHistory, gReplay), groupTitles())
        assertEquals(listOf(5, 3, 3, 5), groupSizes())
        assertEquals("nothing differs from how Vinz comes", 0, changedRows())
        assertTrue(groupTitles().none { it == SettingsPageId.RECOMMENDATIONS_HISTORY.title })
        for (g in groupTitles()) assertFalse("a group and a row are both \"$g\"", hasRow(g))
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
        // Each row is the type the ledger gives it, and says its state.
        for (link in listOf(artists, mixesAgain, exclusions, forget)) assertEquals(link, SettingsTags.ROW_LINK, typeOf(link))
        assertFalse("Bring your history is on Accounts & sharing since 1.8.7.6", hasRow(bring))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(releases))
        for (slider in listOf(newness, depth, energy)) {
            assertEquals(SettingsTags.ROW_SLIDER, typeOf(slider))
            assertEquals(t(R.string.tune_as_it_comes), valueOf(slider))
        }
        assertEquals(SettingsTags.ROW_PICK, typeOf(pause))
        assertEquals(t(R.string.off), valueOf(pause))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(countAfter))
        assertEquals(PlayCount.label(10), valueOf(countAfter))
        assertFalse("YouTube Music's history only waits when there is an account", hasRow(account))
        assertFalse("nothing brought in yet", hasRow(shapeMixes))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(hours))
        assertEquals(t(R.string.like_20h_4m), valueOf(hours))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(themeColors))
        assertEquals(SettingsTags.ROW_PICK, typeOf(storyType))
        assertEquals(t(R.string.replay_story_font_poster), valueOf(storyType))
        // 1.8.5: Replay's three notices are one row; Rewind's is on as Vinz comes, the two new ones off.
        assertEquals(SettingsTags.ROW_PICK, typeOf(notices))
        // W45 (1.8.7.6): the ones on, by name ("1 of 3 on" before).
        assertEquals("Rewind", valueOf(notices))
        assertFalse(hasRow(rewind))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(finishLyric))
        assertEquals(SettingsTags.ROW_PICK, typeOf(countFrom))
        assertEquals(t(R.string.imp_vinz_plays), valueOf(countFrom))
        // "A song and its video as one" is Home & Library's; "Plays in Vinz · Always counted" only explained (§2.7).
        assertFalse(hasRow(t(R.string.a_song_and_its_video_as)))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(t(R.string.imp_always_counted))).fetchSemanticsNodes().isEmpty())
        assertTrue(SettingsIndex.entries.none { it.page == SettingsPageId.RECOMMENDATIONS_HISTORY && it.title == t(R.string.a_song_and_its_video_as) })
    }

    /** Principle 8: every stored setting the ledger gives Recommendations & history has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        show(over = signedIn)
        val d = AppSettings()
        assertEquals("signed in, History has its third setting", listOf(5, 3, 4, 5), groupSizes())

        // release_notifications
        tap(releases)
        until("release_notifications") { !it.releaseNotifications }
        // radio_tune_newness, radio_tune_depth, radio_tune_energy: each slider moves its own value and leaves the others.
        slide(newness, 0.5f)
        until("radio_tune_newness") { abs(it.behavior.radioTune.newness - 0.5f) < 0.01f }
        assertEquals("${t(R.string.tune_new)} · 50%", valueOf(newness))
        slide(depth, -0.5f)
        until("radio_tune_depth") { abs(it.behavior.radioTune.depth + 0.5f) < 0.01f && abs(it.behavior.radioTune.newness - 0.5f) < 0.01f }
        assertEquals("${t(R.string.tune_hits)} · 50%", valueOf(depth))
        slide(energy, 1f)
        until("radio_tune_energy") { it.behavior.radioTune.energy == 1f && abs(it.behavior.radioTune.depth + 0.5f) < 0.01f }
        // history_paused_until: a list in a sheet; the row then says until when.
        val before = System.currentTimeMillis()
        pick(pause, HistoryPause.Choice.HOUR.label)
        until("history_paused_until") { it.behavior.historyPausedUntil in (before + 3_500_000L)..(System.currentTimeMillis() + 3_600_000L) }
        assertTrue(valueOf(pause), valueOf(pause).startsWith(t(R.string.hist_paused_line, "").trim()))
        assertTrue("the root's line says so", t(R.string.hist_paused_title) in SettingsPageId.RECOMMENDATIONS_HISTORY.value(stored))
        pick(pause, HistoryPause.Choice.UNTIL_ON.label)
        until("history_paused_until") { it.behavior.historyPausedUntil == HistoryPause.FOREVER }
        assertEquals(t(R.string.hist_paused_line, t(R.string.hist_paused_until_on)), valueOf(pause))
        // play_count_seconds, play_count_account
        choose(countAfter, PlayCount.label(30))
        until("play_count_seconds") { it.behavior.playCountSeconds == 30 }
        tap(account)
        until("play_count_account") { it.behavior.playCountOnAccount }
        // replay_hours, replay_theme_colors, rewind_notification
        tap(hours)
        until("replay_hours") { !it.replayHours }
        assertEquals(t(R.string.like_1_204_min), valueOf(hours))
        tap(themeColors)
        until("replay_theme_colors") { it.replayThemeColors }
        tap(notices)
        assertEquals(1, sheets())
        inSheet(rewind).performSemanticsAction(SemanticsActions.OnClick)
        until("rewind_notification") { !it.rewindNotification }
        assertEquals(t(R.string.off), valueOf(notices))
        // recap_notify, milestone_notify (1.8.5, off as Vinz comes), in the same sheet.
        inSheet(recap).performSemanticsAction(SemanticsActions.OnClick)
        until("recap_notify") { it.behavior.recapNotify }
        inSheet(milestone).performSemanticsAction(SemanticsActions.OnClick)
        until("milestone_notify") { it.behavior.milestoneNotify }
        swipeSheetAway()
        assertEquals("Monthly recap · Milestones", valueOf(notices))
        // finish_lyric
        tap(finishLyric)
        until("finish_lyric") { it.behavior.finishLyric }
        // replay_story_font: in its pick sheet, one card a type.
        tap(storyType)
        assertEquals(2, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        assertFalse("a sheet swiped away changes nothing", stored.view.replayStoryFont)
        pick(storyType, t(R.string.replay_story_font_own))
        until("replay_story_font") { it.view.replayStoryFont }
        assertEquals(t(R.string.replay_story_font_own), valueOf(storyType))

        // Every row with a setting now differs from how Vinz comes, says so, and the page still fits.
        assertBudgets("everything changed")
        assertEquals(listOf(5, 3, 4, 5), groupSizes())
        assertEquals(12, changedRows())

        // Reset this page lists each of them and puts them all back (history is recorded again).
        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 14, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("$pause: ") && it.endsWith("→ " + t(R.string.off)) })
        assertTrue(lines.toString(), lines.any { it == "$countAfter: ${PlayCount.label(30)} → ${PlayCount.label(10)}" })
        until("the reset") {
            it.behavior.radioTune == d.behavior.radioTune && it.behavior.historyPausedUntil == 0L && it.behavior.playCountSeconds == d.behavior.playCountSeconds &&
                it.behavior.playCountOnAccount == d.behavior.playCountOnAccount && it.releaseNotifications == d.releaseNotifications && it.replayHours == d.replayHours &&
                it.replayThemeColors == d.replayThemeColors && it.rewindNotification == d.rewindNotification && it.view.replayStoryFont == d.view.replayStoryFont &&
                !it.behavior.recapNotify && !it.behavior.milestoneNotify && !it.behavior.finishLyric
        }
        assertEquals(0, changedRows())
    }

    /**
     * A Replay notice turned on asks for notifications when Vinz may not post them (Android 13+ without the permission):
     * before, the switch showed on and nothing ever came. Turning one off doesn't ask.
     */
    @Test fun turningOnAReplayNoticeAsksForNotifications() {
        var asked = 0
        container.notificationPrompt = { asked++ }
        show()
        tap(notices)
        inSheet(rewind).performSemanticsAction(SemanticsActions.OnClick)
        until("rewind_notification off") { !it.rewindNotification }
        assertEquals("turning one off doesn't ask", 0, asked)
        inSheet(recap).performSemanticsAction(SemanticsActions.OnClick)
        until("recap_notify") { it.behavior.recapNotify }
        compose.waitForIdle()
        assertEquals("turning one on asks", 1, asked)
    }

    /** Your artists and Exclusions stay their own screens, behind Link rows (Bring your history is on Accounts & sharing). */
    @Test fun theLinkRowsOpenTheirScreens() {
        show()
        addRoutes(Routes.PICK_ARTISTS, Routes.EXCLUSIONS, Routes.BRING_HISTORY)
        opens(artists, Routes.PICK_ARTISTS)
        opens(exclusions, Routes.EXCLUSIONS)
        // "Forget skipped suggestions" does it at once and says so, as before (it never asked first).
        tap(forget)
        assertEquals(t(R.string.suggestions_start_fresh), ShadowToast.getTextOfLatestToast())
    }

    /** "Count history from": one row saying what Replay counts; its sheet has every source, a switch for one with plays. */
    @Test fun countHistoryFromIsOneRowWithItsSourcesInASheet() {
        runBlocking {
            container.db.imports().insertPlays(
                listOf(ImportedPlayEntity(origin = ImportSource.LASTFM.key, timestamp = 1_700_000_000_000L, playedMs = 200_000L, title = "Song", artist = "Artist", songKey = "artist|song")),
            )
        }
        show()
        addRoutes(Routes.BRING_HISTORY)
        compose.waitUntil(5_000) { hasRow(shapeMixes) }
        assertEquals("with a history brought in, its switch shows", listOf(5, 3, 4, 5), groupSizes())
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(shapeMixes))
        assertEquals(t(R.string.imp_vinz_plays), valueOf(countFrom))

        tap(countFrom)
        assertEquals(1, sheets())
        // Every source is listed: the one with plays as a switch, the others as the way to bring them in.
        for (source in ImportSource.entries) {
            val node = inSheet(source.label).fetchSemanticsNode()
            val type = node.config[SemanticsProperties.TestTag]
            if (source == ImportSource.LASTFM) {
                assertEquals(SettingsTags.ROW_SWITCH, type)
                assertEquals(t(R.string.imp_n_plays, "1"), node.config[SettingsRowValue])
            } else {
                assertEquals(source.label, SettingsTags.ROW_LINK, type)
                assertEquals(if (source.isLive) t(R.string.off) else t(R.string.imp_not_brought_in), node.config[SettingsRowValue])
            }
        }
        inSheet(ImportSource.LASTFM.label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { ImportSource.LASTFM in container.imports.counted.value }
        compose.waitForIdle()
        swipeSheetAway()
        assertEquals(t(R.string.imp_vinz_plays) + " · " + ImportSource.LASTFM.label, valueOf(countFrom))
        assertTrue(row(countFrom).fetchSemanticsNode().config[SettingsRowChanged])
        // Reset this page: Replay counts Vinz's own plays alone again.
        val lines = resetLines(confirm = true)
        assertEquals(lines.toString(), 1, lines.size)
        compose.waitUntil(5_000) { container.imports.counted.value.isEmpty() }
        compose.waitForIdle()
        assertEquals(t(R.string.imp_vinz_plays), valueOf(countFrom))
        // A source that isn't here yet leads to where it is brought in.
        tap(countFrom)
        inSheet(ImportSource.SPOTIFY.label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { navController!!.currentDestination?.route == Routes.BRING_HISTORY }
    }

    /** Links written before the rework still land, and every line of the page's search names a group the page draws. */
    @Test fun oldGroupNamesOpenTheGroupThatHoldsThemNow() {
        assertNull(SettingsPageId.RECOMMENDATIONS_HISTORY.oldGroups[bring])
        fun to(group: String) = SettingsMap.resolve("library", group)
        // Bring your history moved to Accounts & sharing (1.8.7.6): the old link opens it there, not here.
        assertEquals(SettingsTarget(SettingsPageId.ACCOUNTS_SHARING, t(R.string.accounts)), to(bring))
        assertEquals(SettingsTarget(SettingsPageId.RECOMMENDATIONS_HISTORY, gHistory), to(gHistory))
        assertEquals(SettingsTarget(SettingsPageId.RECOMMENDATIONS_HISTORY, gRadios), to(gRadios))
        assertEquals(SettingsTarget(SettingsPageId.RECOMMENDATIONS_HISTORY, gMade), to(gMade))
        assertEquals(SettingsTarget(SettingsPageId.RECOMMENDATIONS_HISTORY, gReplay), to(gReplay))
        show(over = signedIn)
        val drawn = groupTitles().toSet()
        val mine = SettingsIndex.entries.filter { it.page == SettingsPageId.RECOMMENDATIONS_HISTORY && it.group.isNotBlank() }
        val lost = mine.filter { it.group !in drawn }
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        for (title in listOf(artists, releases, mixesAgain, exclusions, forget, newness, depth, energy, pause, countAfter, account, shapeMixes, hours, themeColors, storyType, notices, finishLyric, countFrom)) {
            assertEquals(title, 1, mine.count { it.title == title })
        }
        // The three notices are in one row's sheet: each one's name finds that row.
        for (word in listOf(rewind, recap, milestone)) assertTrue(word, SettingsIndex.search(word).any { it.title == notices })
        // Every row drawn is one of them.
        for (r in rows()) {
            val title = r.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: continue
            assertTrue("\"$title\" has no line in search", mine.any { it.title == title })
        }
        // The account's row says what it needs, and search still finds the page by its old name.
        assertFalse(mine.single { it.title == account }.needs!!.on(AppSettings()))
        assertTrue(SettingsIndex.search("private session").any { it.title == pause })
        // Bring your history is no line of this page (it is Accounts & sharing's), and search still finds it.
        assertTrue(mine.none { it.title == bring })
        assertTrue(SettingsIndex.search("spotify history").any { it.title == bring && it.page == SettingsPageId.ACCOUNTS_SHARING })
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.settings.setHistoryPausedUntil(HistoryPause.FOREVER)
        container.settings.setRadioTune(AppSettings().behavior.radioTune.copy(newness = -0.75f, energy = 0.5f))
        until("paused and tuned") { it.behavior.historyPausedUntil == HistoryPause.FOREVER && it.behavior.radioTune.energy == 0.5f }
        show(fontScale = 1.5f, over = signedIn)
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("recommendations-history", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnLight() = drawn("recommendations-history-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnDark() = drawn("recommendations-history-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnPerformance() = drawn("recommendations-history-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** Signed in, history paused and the radios tuned (the page's longest state). */
    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi") fun drawnSignedInAndPaused() = drawn("recommendations-history-dark-signed-in-paused", true) {
        it.copy(
            themeMode = ThemeMode.DARK, cookie = "SAPISID=test",
            behavior = it.behavior.copy(historyPausedUntil = HistoryPause.FOREVER, radioTune = it.behavior.radioTune.copy(newness = 0.5f, depth = -0.25f)),
        )
    }
}
