package com.vinz.music

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.PlayerBackground
import com.vinz.music.data.prefs.PlayerLayout
import com.vinz.music.data.prefs.PlayerPart
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.data.prefs.VisualizerStyle
import com.vinz.music.data.prefs.shows
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.PlayerSetting
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.playerLookKeys
import com.vinz.music.ui.screens.settings.playerSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Player rebuilt with the kit: its budgets, that every stored setting the ledger gives the page has
 * its row (nothing went missing), its merged controls and its new rows, on a window tall enough to hold the whole page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class PlayerPageTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private val prefs get() = container.settings
    private val stored get() = container.settings.stored.value
    /** The settings' store is one for the whole test run: each test starts from Vinz as it comes, and leaves it so. */
    private fun asVinzComes() = kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }

    @org.junit.Before fun fresh() { asVinzComes(); spotifySession(false) }
    @org.junit.After fun close() { asVinzComes(); made.closeAfterTest() }

    private fun showPage(theme: AppSettings = AppSettings(), fontScale: Float = 1f) {
        compose.setContent {
            val nav = Navigator(rememberNavController(), openPlayer = {})
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale)) {
                VinzTheme(theme, artworkColor = null) { StoredSettings { SettingsPageContent(SettingsPageId.PLAYER, 0.dp, onSearch = {}) } }
            }
        }
        compose.waitForIdle()
    }

    private fun until(what: String, cond: (AppSettings) -> Boolean) {
        compose.waitUntil(10_000) { cond(stored) }
        compose.waitForIdle()
        assertTrue(what, cond(stored))
    }

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun rowsIn(node: SemanticsNode): List<SemanticsNode> =
        (if (node.config.getOrNull(SemanticsProperties.TestTag) in SettingsTags.rowTypes) listOf(node) else emptyList()) + node.children.flatMap { rowsIn(it) }

    private fun firstText(node: SemanticsNode): String? =
        node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: node.children.firstNotNullOfOrNull { firstText(it) }

    /** Every kit row drawn now, on the page and in an open sheet, by its title. */
    private fun rows(): Map<String, SemanticsNode> = SettingsTags.rowTypes.flatMap { tagged(it) }.mapNotNull { n -> firstText(n)?.let { it to n } }.toMap()

    private fun row(title: String) = compose.onNode(SettingsTags.rowTypes.map { hasTestTag(it) }.reduce { a, b -> a or b } and hasAnyDescendant(hasText(title)), useUnmergedTree = true)

    private fun tap(title: String) {
        row(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Taps the chip [text] of the row [title] (other rows may show the same word). */
    private fun chip(title: String, text: String) {
        val inRow = androidx.compose.ui.test.hasAnyAncestor(SettingsTags.rowTypes.map { hasTestTag(it) }.reduce { a, b -> a or b } and hasAnyDescendant(hasText(title)))
        compose.onAllNodes(hasText(text) and inRow, useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
    }

    private fun closeSheet() {
        compose.onNodeWithTag(SettingsTags.SHEET).performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Budgets

    /** 1.8.7.6: "Under the title" names only the parts the player shows (a fresh install hides Volume). */
    @Test fun underTheTitleLeavesOutHiddenParts() {
        val volume = com.vinz.music.data.prefs.DeckPart.VOLUME.label
        val fresh = com.vinz.music.ui.screens.settings.deckWords(AppSettings())
        assertFalse("a fresh install's row leaves Volume out: $fresh", volume in fresh)
        val shown = com.vinz.music.ui.screens.settings.deckWords(AppSettings(hideVolumeBar = false))
        assertTrue("with the volume bar shown the row names it: $shown", volume in shown)
    }

    @Test fun budgetsHoldWithDefaultSettings() {
        showPage()
        val groups = tagged(SettingsTags.GROUP)
        assertTrue("1 to 6 groups, found ${groups.size}", groups.size in 1..6)
        val counts = groups.map { rowsIn(it).size }
        assertTrue("at most 7 rows a group, and no group without kit rows: $counts", counts.all { it in 1..7 })
        assertEquals(listOf("Layout and controls", "Progress bar and times", "Title", "Cover", "On the player", "Modes"), groups.map { firstText(it) })
        // 1.8.6: Animated covers and Canvas are two rows of Cover; Cover animation moved to Modes to make room.
        assertEquals("the ledger's fit: 7 · 7 · 5 · 7 · 7 · 5", listOf(7, 7, 5, 7, 7, 5), counts)
        assertTrue("no group is named after the page", groups.none { firstText(it) == SettingsPageId.PLAYER.title })
        // Only the five row types, each saying its value (a switch or a link may do without a value line).
        val all = groups.flatMap { rowsIn(it) }
        for (r in all) {
            val tag = r.config[SemanticsProperties.TestTag]
            val value = r.config[SettingsRowValue]
            if (tag != SettingsTags.ROW_SWITCH && tag != SettingsTags.ROW_LINK) assertTrue("${firstText(r)} ($tag) says no value", value.isNotBlank())
        }
        // With Vinz as it comes nothing is marked as changed, and there is nothing to reset.
        assertTrue(all.none { it.config[SettingsRowChanged] })
        assertTrue(playerSettings.none { it.changed(stored) })
        // The pinned preview and the jump chips are there.
        assertEquals(1, tagged(SettingsTags.PREVIEW).size)
        assertEquals(1, tagged(SettingsTags.JUMP_CHIPS).size)
        // "Edit the player's ⋯ menu" (W31, 1.8.7.6; "Edit this menu" before) stays a Link row; the progress bar's color
        // is named apart from the mini player's.
        assertEquals(SettingsTags.ROW_LINK, rows().getValue("Edit the player's ⋯ menu").config[SemanticsProperties.TestTag])
        assertTrue("Progress bar color" in rows())
        assertEquals("Time left", rows().getValue("Right-hand time").config[SettingsRowValue])
    }

    private fun drawsWith(theme: AppSettings, fontScale: Float = 1f, before: () -> Unit = {}) {
        before()
        showPage(theme, fontScale)
        // No text of a row is cut off: none is taller than its room or ends in an ellipsis (rows grow at large text).
        // (A one-line text narrower than the room it was offered reports a width overflow, so that isn't asked.)
        val seen = LinkedHashSet<String>()
        val cut = ArrayList<String>()
        fun check(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.Text) != null && n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) {
                val out = ArrayList<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
                val l = out.firstOrNull()
                if (l != null && (l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))) cut += n.config[SemanticsProperties.Text].first().text
            }
            n.children.forEach { check(it) }
        }
        fun pass() = tagged(SettingsTags.GROUP).forEach { g -> firstText(g)?.let { seen += it }; check(g) }
        pass()
        // At large text the page is taller than this window: the rest of it, scrolled to.
        compose.onNodeWithTag(SettingsTags.PAGE_LIST).performTouchInput { repeat(3) { swipeUp() } }
        compose.waitForIdle()
        pass()
        assertEquals(listOf("Layout and controls", "Progress bar and times", "Title", "Cover", "On the player", "Modes"), seen.toList())
        assertTrue("cut off: $cut", cut.isEmpty())
    }

    @Test fun drawsInLight() = drawsWith(AppSettings(themeMode = ThemeMode.LIGHT))
    @Test fun drawsInDark() = drawsWith(AppSettings(themeMode = ThemeMode.DARK))
    @Test fun drawsAtLargeText() = drawsWith(AppSettings(themeMode = ThemeMode.DARK), fontScale = 1.5f)

    @Test fun drawsInPerformanceMode() {
        drawsWith(AppSettings(themeMode = ThemeMode.DARK, performanceMode = true)) {
            prefs.setPerformanceMode(true)
            until("Performance mode is stored") { it.performanceMode }
        }
        // It says that its effects are paused, as the page did before.
        compose.onNodeWithText(tr(R.string.performance_mode_is_on), useUnmergedTree = true).assertExists()
    }

    // ------------------------------------------------------------------ Nothing missing

    /** Every stored key the ledger gives Player (release-1.8.4/ledger.md, "### Player"), in its order. */
    private val ledgerKeys = listOf(
        "player_layout", "vinyl_tonearm", "player_background", "player_backdrop_show", "player_gradient_depth", "mesh_speed", "mesh_intensity", "player_blur",
        "transport_style", "play_ring", "player_buttons_pill", "video_controls_hide", "video_buttons_hide",
        "scrubber_style", "scrubber_color", "scrubber_thickness", "scrubber_thumb", "crossfade_shape", "volume_matches_progress", "player_times", "show_track_length",
        "player_title_align", "player_title_size", "player_title_weight", "player_title_scroll", "player_artist_links",
        "cover_size", "player_art_corners", "cover_shape_player", "full_bleed_artwork", "video_full_bleed", "bleed_fit", "bleed_roomy", "art_shrink_on_pause",
        "cover_flight", "cover_flight_back", "canvas_enabled", "canvas_fills_background",
        "player_hidden_parts", "player_handle", "show_playing_from", "lyrics_on_player", "show_quality", "best_part_button", "player_shuffle_repeat", "hide_volume_bar",
        "show_output_device", "player_nav_band", "crossfade_mark", "blend_covers", "player_deck", "player_buttons", "show_video_switch", "volume_control",
        "player_menu_hidden", "player_menu_tiles", "panel_player", "scroll_shows_controls", "player_remembers_page", "lyrics_steady",
        "queue_headings", "queue_dj_chip", "queue_lengths", "queue_played_moves", "queue_tidy",
        "visualizer", "visualizer_color", "immersive_player", "immersive_art", "immersive_lyrics", "immersive_controls", "immersive_video", "immersive_background",
        "lock_screen_player", "lock_screen_layout", "lock_screen_volume", "lock_screen_like", "lock_screen_output", "lock_screen_repeat", "lock_screen_up_next",
        "lock_screen_paused", "lock_screen_hide_song", "desk_mode",
        // 1.8.4 (D53): new with this release, on the lock screen's sheet.
        "lock_screen_player_look",
        // 1.8.6: Canvas's sheet.
        "canvas_use_videos", "canvas_videos_cellular", "canvas_video_first", "canvas_keep_controls", "canvas_lyrics", "canvas_lighter",
        // Owner, 2026-11-13: animated covers on their own.
        "canvas_covers",
        // 1.8.7.6: Spotify's canvas first, in Canvas's sheet (set by hand, its one-time offer counts as answered).
        "canvas_spotify_first", "canvas_spotify_offered",
    )

    /** Signed in to Spotify with the session sign-in (its mark in the connection's own file), or not. */
    private fun spotifySession(on: Boolean) {
        RuntimeEnvironment.getApplication().getSharedPreferences(com.vinz.music.data.spotify.SpotifyStore.AUTH_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().clear().putBoolean("session_on", on).commit()
        com.vinz.music.data.spotify.SpotifySessionNow.watch(
            RuntimeEnvironment.getApplication().getSharedPreferences(com.vinz.music.data.spotify.SpotifyStore.AUTH_PREFS, android.content.Context.MODE_PRIVATE),
        )
    }

    @Test fun everyLedgerKeyHasItsSetting() {
        val held = playerSettings.flatMap { it.keys }
        assertEquals("a key is held by one setting", held.size, held.toSet().size)
        assertEquals("the ledger's keys for Player, and nothing else", ledgerKeys.toSet(), held.toSet())
        // `queue_locked` is state, allow-listed by the plan (§2.11): not a row.
        assertFalse("queue_locked" in held)
        // Every key is a real stored one, and Reset's keys are the page's look settings.
        assertTrue(held.all { it in prefs.allKeyNames })
        val look = held.filter { it in prefs.lookKinds.keys }.toSet()
        assertEquals(look, playerLookKeys.toSet())
        assertTrue("immersive_background" in playerLookKeys)
    }

    /** Another value of the same kind, to see a setter store it. */
    private fun other(v: Any?): Any? = when (v) {
        is Boolean -> !v
        is Float -> v + 0.25f
        is Int -> v + 4
        is Enum<*> -> v.javaClass.enumConstants!!.let { all -> all[(v.ordinal + 1) % all.size] }
        is Pair<*, *> -> other(v.first) to other(v.second)
        is Set<*> -> if (PlayerPart.HANDLE in v) v - PlayerPart.HANDLE else v + PlayerPart.HANDLE
        is List<*> -> if (v.all { it is Boolean }) v.map { other(it) } else v.reversed()
        else -> error("no other value for $v")
    }

    @Test fun everySettingsRowIsOnThePage() {
        // Everything that shows rows only under a condition, on: Crossfade, the lock screen, Immersive, the visualizer,
        // an edge-to-edge cover, the volume bar, Vinyl.
        prefs.setMix(true); prefs.setLockScreen(true); prefs.setImmersivePlayer(true); prefs.setVisualizer(VisualizerStyle.BARS)
        prefs.setFullBleedArtwork(true); prefs.setPartShown(PlayerPart.VOLUME, true); prefs.setPlayerLayout(PlayerLayout.VINYL)
        // Canvas (its loops) on and Full screen, with Animated covers: every row of its sheet. Signed in to Spotify with
        // the session sign-in: "Spotify's canvas first" too.
        prefs.setCanvasFillsBackground(true); prefs.setCanvasKinds(covers = true, videos = true)
        spotifySession(true)
        until("the conditions are stored") {
            it.mix.on && it.behavior.lockScreen && it.immersivePlayer && it.fullBleedArtwork && it.shows(PlayerPart.VOLUME) && it.player.layout == PlayerLayout.VINYL &&
                it.behavior.canvas.fillsBackground && it.behavior.canvas.useVideos
        }
        showPage()
        val onPage = HashSet<String>()
        // What the picked background is tuned by takes one place: each background in turn.
        for (bg in listOf(PlayerBackground.MESH, PlayerBackground.GRADIENT, PlayerBackground.BLUR, PlayerBackground.APPEARANCE)) {
            prefs.setPlayerBackground(bg)
            until("$bg") { it.player.background == bg }
            onPage += rows().keys
        }
        assertTrue("Which background" in onPage)
        val inSheet = HashMap<String, Set<String>>()
        for (sheet in playerSettings.mapNotNull { it.sheet }.distinct()) {
            tap(sheet)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isNotEmpty() }
            inSheet[sheet] = rowsIn(compose.onNodeWithTag(SettingsTags.SHEET, useUnmergedTree = true).fetchSemanticsNode()).mapNotNull { firstText(it) }.toSet()
            closeSheet()
        }
        assertEquals(setOf("Immersive options", "Lock screen options", "Canvas"), inSheet.keys)
        val missing = playerSettings.filter { p -> if (p.sheet == null) p.row !in onPage else p.row !in inSheet.getValue(p.sheet!!) }
        assertTrue("no row for: ${missing.map { it.keys to it.row }}", missing.isEmpty())
        assertTrue("the sheet's rows open from a row of the page", inSheet.keys.all { it in onPage })
    }

    /**
     * Each setting's setter stores what it's given, and it then counts as changed (the ⋯ menu's setter is its own
     * editor's: only its reset is the page's). No page is drawn here: the store is read as it is.
     */
    @Suppress("UNCHECKED_CAST")
    @Test fun everySettingsSetterStores() {
        fun wait(what: String, cond: (AppSettings) -> Boolean) {
            val end = System.currentTimeMillis() + 10_000
            while (!cond(stored) && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue(what, cond(stored))
        }
        var checked = 0
        for (p in playerSettings as List<PlayerSetting<Any?>>) {
            if ("player_menu_hidden" in p.keys) continue
            assertFalse("${p.keys} starts as Vinz comes", p.changed(stored))
            val want = other(p.get(stored))
            p.set(prefs, want)
            wait("${p.keys} stores $want (has ${p.get(stored)})") { p.get(it) == want }
            assertTrue("${p.keys} counts as changed", p.changed(stored))
            checked++
        }
        assertEquals(playerSettings.size - 1, checked)
        // And every one of them goes back with the page's reset.
        com.vinz.music.ui.screens.settings.resetPlayerPage(prefs, stored)
        wait("all back: ${playerSettings.filter { it.changed(stored) }.map { it.keys }}") { s -> playerSettings.none { it.changed(s) } }
    }

    // ------------------------------------------------------------------ Merged controls, on the page

    @Test fun theMergedChipsStoreBothValues() {
        showPage()
        chip("Cover animation", "Opening and closing")
        until("Cover animation: both") { it.view.coverFlight && it.view.coverFlightBack }
        chip("Cover animation", "Opening")
        until("Cover animation: opening only") { it.view.coverFlight && !it.view.coverFlightBack }
        assertEquals("Opening", rows().getValue("Cover animation").config[SettingsRowValue])

        // (Canvas's three choices are in its sheet now: canvasIsOneRowWithItsChoicesInASheet.)

        // Edge to edge ("Reaches the screen's edges" since 1.8.7.6, W32): Cover and video shows the fit and "More room
        // for the picture" under it; Video keeps only the latter.
        assertFalse("How the cover fits" in rows())
        chip("Reaches the screen's edges", "Cover and video")
        until("Edge to edge: both") { it.fullBleedArtwork && it.videoFullBleed }
        assertTrue("How the cover fits" in rows() && "More room for the picture" in rows())
        chip("Reaches the screen's edges", "Video")
        until("Edge to edge: video") { !it.fullBleedArtwork && it.videoFullBleed }
        assertTrue("How the cover fits" !in rows() && "More room for the picture" in rows())

        // In the queue: a chip is one stored switch.
        chip("In the queue", "Song lengths")
        until("queue lengths") { it.view.queueLengths && it.view.queueHeadings && it.view.queueDjChip && !it.view.queueTidy }
        chip("Lyrics and the queue", "Reopen on the last page")
        until("reopen") { it.behavior.playerRemembersPage && !it.behavior.lyricsSteady }
        assertTrue(rows().getValue("In the queue").config[SettingsRowChanged])
    }

    @Test fun controlsOverVideosKeepsAStoredFourthCombinationUntilChanged() {
        prefs.setVideoButtonsHide(true)
        until("buttons wait, controls stay") { it.view.videoButtonsHide && !it.view.videoControlsHide }
        showPage()
        assertEquals(tr(R.string.settings_player_buttons_wait_only), rows().getValue("Controls over videos").config[SettingsRowValue])
        tap("Controls over videos")
        assertEquals("the fourth is offered while it's stored", 4, tagged(SettingsTags.SHEET_OPTION).size)
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Hide controls and buttons")).performClick()
        until("hide both") { it.view.videoControlsHide && it.view.videoButtonsHide }
        compose.waitUntil(10_000) { tagged(SettingsTags.SHEET).isEmpty() }
        tap("Controls over videos")
        assertEquals(3, tagged(SettingsTags.SHEET_OPTION).size)
    }

    // ------------------------------------------------------------------ New rows

    @Test fun rightHandTimeIsARowNow() {
        showPage()
        chip("Right-hand time", "Song length")
        until("song length") { it.showTrackLength }
        assertEquals("Song length", rows().getValue("Right-hand time").config[SettingsRowValue])
        assertTrue(rows().getValue("Right-hand time").config[SettingsRowChanged])
    }

    @Test fun theLockScreensOptionsOpenInASheetWithSameLookAsThePlayer() {
        showPage()
        assertFalse("off: no options row", "Lock screen options" in rows())
        prefs.setLockScreen(true)
        until("lock screen on") { it.behavior.lockScreen }
        assertEquals("Modes stays within its budget", true, rowsIn(tagged(SettingsTags.GROUP).last()).size <= 7)
        tap("Lock screen options")
        compose.waitUntil(10_000) { tagged(SettingsTags.SHEET).isNotEmpty() }
        val same = rows().getValue("Same look as the player")
        assertEquals(SettingsTags.ROW_SWITCH, same.config[SemanticsProperties.TestTag])
        assertFalse("off by default", stored.view.lockScreenPlayerLook)
        tap("Same look as the player")
        until("same look") { it.view.lockScreenPlayerLook }
        assertTrue("lock_screen_player_look" in playerLookKeys)
        for (title in listOf("Layout", "Show", "Stay while paused", "Hide the song's name and cover while locked")) assertTrue(title, title in rows())
    }

    @Test fun thePlayerCoversShapeIsPickedHere() {
        showPage()
        tap("Cover shape")
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText("Circle")).performClick()
        until("circle") { it.shapes.player == com.vinz.music.data.prefs.CoverShape.CIRCLE }
    }

    // ------------------------------------------------------------------ Canvas (1.8.6)

    private fun sheetOpen() = compose.waitUntil(10_000) { tagged(SettingsTags.SHEET).isNotEmpty() }

    /** The R6 question's buttons (a button is not a row). */
    private fun button(text: String) = compose.onNode(
        hasText(text) and androidx.compose.ui.test.hasClickAction() and SettingsTags.rowTypes.map { !hasTestTag(it) }.reduce { a, b -> a and b },
    )


    /** The rows drawn in the open sheet, by title (the page behind it has a "Canvas" row too). */
    private fun sheetRows(): Map<String, SemanticsNode> =
        rowsIn(compose.onNodeWithTag(SettingsTags.SHEET, useUnmergedTree = true).fetchSemanticsNode()).mapNotNull { n -> firstText(n)?.let { it to n } }.toMap()

    /** Taps the row [title] in the open sheet. */
    private fun tapInSheet(title: String) {
        compose.onNode(
            SettingsTags.rowTypes.map { hasTestTag(it) }.reduce { a, b -> a or b } and hasAnyDescendant(hasText(title)) and
                androidx.compose.ui.test.hasAnyAncestor(hasTestTag(SettingsTags.SHEET)),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** The Cover group's rows, by title, in order. */
    private fun coverRows(): List<String> =
        tagged(SettingsTags.GROUP).first { firstText(it) == "Cover" }.let { g -> rowsIn(g).mapNotNull { firstText(it) } }

    /** Owner, 2026-11-13 ("Can you just keep these options separate?"): Animated covers and Canvas, each its own switch. */
    @Test fun animatedCoversAndCanvasAreSeparate() {
        showPage()
        // Two rows of Cover, last: Animated covers (a switch on the page, on) and Canvas (a Link row, Off as Vinz comes).
        assertEquals(listOf("Animated covers", "Canvas"), coverRows().takeLast(2))
        assertEquals(SettingsTags.ROW_SWITCH, rows().getValue("Animated covers").config[SemanticsProperties.TestTag])
        assertEquals(com.vinz.music.data.prefs.CanvasKinds(covers = true, videos = false), com.vinz.music.data.prefs.CanvasKinds.shown(stored))
        val row = rows().getValue("Canvas")
        assertEquals(SettingsTags.ROW_LINK, row.config[SemanticsProperties.TestTag])
        assertEquals("Off", row.config[SettingsRowValue])
        assertFalse("Cover animation is in Modes now", "Cover animation" in coverRows())
        assertTrue("Cover animation" in rows())

        // Canvas's sheet with only Animated covers on: its switch (off) and nothing else (an animated cover is not placed here).
        tap("Canvas")
        sheetOpen()
        assertEquals(listOf("Canvas"), sheetRows().keys.toList())
        assertEquals(SettingsTags.ROW_SWITCH, sheetRows().getValue("Canvas").config[SemanticsProperties.TestTag])

        // Canvas on: the loops' rows come, and "When a song has both" (both are on).
        tapInSheet("Canvas")
        until("canvas on") { it.behavior.canvas.enabled && it.behavior.canvas.covers && it.behavior.canvas.useVideos }
        assertEquals(listOf("Canvas", "Use mobile data", "When a song has both", "Lighter loops", "Where it shows"), sheetRows().keys.toList())
        assertEquals("On the cover", sheetRows().getValue("Where it shows").config[SettingsRowValue])
        // Two choices, no Off: the two switches are what turn things off.
        val inWhere = androidx.compose.ui.test.hasAnyAncestor(SettingsTags.rowTypes.map { hasTestTag(it) }.reduce { a, b -> a or b } and hasAnyDescendant(hasText("Where it shows")))
        assertTrue(compose.onAllNodes(hasText("Full screen") and inWhere, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("Off") and inWhere, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        tapInSheet("Use mobile data")
        until("videos_cellular") { it.behavior.canvas.videosOnCellular }
        chip("When a song has both", "Music video")
        until("video_first") { it.behavior.canvas.videoFirst }
        tapInSheet("Lighter loops")
        until("lighter") { it.behavior.canvas.lighter }
        // Full screen brings "Keep controls showing".
        chip("Where it shows", "Full screen")
        until("full screen") { it.behavior.canvas.fillsBackground }
        tapInSheet("Keep controls showing")
        until("keep_controls") { it.behavior.canvas.keepControls }
        tapInSheet("Keep lyrics showing")
        until("lyrics") { it.behavior.canvas.lyrics }
        assertEquals(CanvasPrefsAll, stored.behavior.canvas)
        closeSheet()
        assertEquals("Full screen", rows().getValue("Canvas").config[SettingsRowValue])

        // Animated covers off: Canvas's loops stay, "When a song has both" goes (there is no both).
        tap("Animated covers")
        until("covers off") { it.behavior.canvas.enabled && !it.behavior.canvas.covers && it.behavior.canvas.useVideos }
        tap("Canvas")
        sheetOpen()
        assertFalse("When a song has both" in sheetRows())
        assertTrue("Lighter loops" in sheetRows() && "Where it shows" in sheetRows() && "Keep controls showing" in sheetRows() && "Keep lyrics showing" in sheetRows())

        // Canvas off too, the last one: Canvas as a whole is off, and what the rows hold is kept.
        tapInSheet("Canvas")
        until("all off") { !it.behavior.canvas.enabled }
        assertEquals(listOf("Canvas"), sheetRows().keys.toList())
        assertTrue(stored.behavior.canvas.useVideos && stored.behavior.canvas.lighter && stored.behavior.canvas.fillsBackground)
        closeSheet()
        assertEquals("Off", rows().getValue("Canvas").config[SettingsRowValue])
        assertTrue("it differs from how Vinz comes", rows().getValue("Animated covers").config[SettingsRowChanged])

        // Animated covers back on alone (Canvas was off before, its flag on): the loops stay off.
        tap("Animated covers")
        until("covers on alone") { it.behavior.canvas.enabled && it.behavior.canvas.covers && !it.behavior.canvas.useVideos }
        assertEquals("Off", rows().getValue("Canvas").config[SettingsRowValue])
    }

    /**
     * 1.8.7.6, "Spotify's canvas first" (owner, 2026-11-16): a switch in Canvas's sheet, off by default, shown only while
     * signed in to Spotify with the session sign-in and Canvas is on; turned on, the mobile-data switch its loops follow
     * shows with it, and its one-time offer counts as answered.
     */
    @Test fun spotifysCanvasFirstShowsOnlyWithTheSessionSignIn() {
        val title = tr(R.string.spot_canvas_first)
        spotifySession(false)
        showPage()
        tap("Canvas")
        sheetOpen()
        assertFalse("hidden without the session sign-in", title in sheetRows())
        closeSheet()

        spotifySession(true)
        compose.waitForIdle()
        tap("Canvas")
        sheetOpen()
        // Only Animated covers on (Vinz as it comes): Canvas's switch, then Spotify's (off); no mobile-data row yet.
        assertEquals(listOf("Canvas", title), sheetRows().keys.toList())
        assertEquals(SettingsTags.ROW_SWITCH, sheetRows().getValue(title).config[SemanticsProperties.TestTag])
        assertFalse(stored.behavior.canvas.spotifyFirst)
        tapInSheet(title)
        until("spotify first on, the offer answered") { it.behavior.canvas.spotifyFirst && it.behavior.canvas.spotifyOffered }
        assertEquals(listOf("Canvas", title, "Use mobile data"), sheetRows().keys.toList())
        assertTrue(sheetRows().getValue(title).config[SettingsRowChanged])
        closeSheet()
        assertTrue("the Canvas row says something changed in its sheet", rows().getValue("Canvas").config[SettingsRowChanged])
        assertFalse("not part of a Look", "canvas_spotify_first" in prefs.lookKinds.keys)

        // 1.8.7.6 (the A16 check): Vinz's two switches have no say over it. Each of the four ways they can be set, the
        // row is there, on, with its mobile-data switch, and it can be switched off and on again.
        for ((covers, videos) in listOf(true to true, true to false, false to true, false to false)) {
            prefs.setCanvasKinds(covers = covers, videos = videos)
            until("covers $covers, videos $videos") { com.vinz.music.data.prefs.CanvasKinds.shown(it) == com.vinz.music.data.prefs.CanvasKinds(covers, videos) }
            tap("Canvas")
            sheetOpen()
            val keys = sheetRows().keys.toList()
            assertEquals("covers $covers, videos $videos: $keys", listOf("Canvas", title, "Use mobile data"), keys.take(3))
            assertTrue(stored.behavior.canvas.spotifyFirst)
            tapInSheet(title)
            until("off") { !it.behavior.canvas.spotifyFirst }
            tapInSheet(title)
            until("on again") { it.behavior.canvas.spotifyFirst }
            closeSheet()
        }
        spotifySession(false)
    }

    @Test fun spotifysCanvasFirstIsFoundBySearch() {
        spotifySession(false)
        val line = com.vinz.music.ui.screens.SettingsIndex.search("spotify canvas").first { it.title == tr(R.string.spot_canvas_first) }
        assertEquals(SettingsPageId.PLAYER, line.page)
        assertFalse("says to sign in first", line.needs!!.on(AppSettings()))
        spotifySession(true)
        assertTrue(line.needs!!.on(AppSettings()))
        spotifySession(false)
    }

    private val CanvasPrefsAll = com.vinz.music.data.prefs.CanvasPrefs(useVideos = true, videoFirst = true, keepControls = true, lyrics = true, lighter = true, videosOnCellular = true, fillsBackground = true)

    @Test fun theCanvasRowSaysWhereItShows() {
        prefs.setCanvasFillsBackground(true); prefs.setCanvasUseVideos(true)
        until("full screen with videos") { it.behavior.canvas.fillsBackground && it.behavior.canvas.useVideos }
        showPage()
        // Existing users see no change in what is selected: the old "Fill the player" is Full screen.
        assertEquals("Full screen", rows().getValue("Canvas").config[SettingsRowValue])
        // Switched off as a whole before (the old "Off"): both switches show off, Full screen kept for later.
        prefs.setCanvasEnabled(false)
        until("off") { !it.behavior.canvas.enabled }
        compose.waitForIdle()
        assertEquals("Off", rows().getValue("Canvas").config[SettingsRowValue])
        assertEquals(com.vinz.music.data.prefs.CanvasKinds(false, false), com.vinz.music.data.prefs.CanvasKinds.shown(stored))
        assertTrue(rows().getValue("Animated covers").config[SettingsRowChanged])
    }

    @Test fun canvasSearchLines() {
        val mine = com.vinz.music.ui.screens.SettingsIndex.entries.filter { it.page == SettingsPageId.PLAYER && it.group == "Cover" }
        fun line(t: String) = mine.single { it.title == t }
        // Animated covers and Canvas each have their line, always there; the old names still find them.
        assertEquals(null, line("Animated covers").needs)
        assertEquals(null, line("Canvas").needs)
        assertTrue(com.vinz.music.ui.screens.SettingsIndex.search("animated covers").any { it.title == "Animated covers" && it.page == SettingsPageId.PLAYER })
        assertTrue(com.vinz.music.ui.screens.SettingsIndex.search("use music videos").any { it.title == "Canvas" && it.page == SettingsPageId.PLAYER })
        assertTrue(com.vinz.music.ui.screens.SettingsIndex.search("fill the player").any { it.title == "Where it shows" && it.page == SettingsPageId.PLAYER })
        assertFalse("no line of its own any more", mine.any { it.title == "Use music videos" })
        val d = AppSettings()
        val videos = d.copy(behavior = d.behavior.copy(canvas = d.behavior.canvas.copy(useVideos = true)))
        val noCovers = d.copy(behavior = d.behavior.copy(canvas = d.behavior.canvas.copy(covers = false)))
        // "Where it shows" is for Canvas's loops only (an animated cover always plays in the cover).
        assertFalse(line("Where it shows").needs!!.on(d))
        assertTrue(line("Where it shows").needs!!.on(videos))
        assertTrue(line("Where it shows").needs!!.on(videos.copy(behavior = videos.behavior.copy(canvas = videos.behavior.canvas.copy(covers = false)))))
        assertFalse(line("Where it shows").needs!!.on(noCovers))
        assertFalse(line("Where it shows").needs!!.on(videos.copy(behavior = videos.behavior.copy(canvas = videos.behavior.canvas.copy(enabled = false)))))
        // "When a song has both" needs both.
        assertFalse(line("When a song has both").needs!!.on(videos.copy(behavior = videos.behavior.copy(canvas = videos.behavior.canvas.copy(covers = false)))))
        for (t in listOf("When a song has both", "Lighter loops", "Use mobile data")) {
            assertFalse(t, line(t).needs!!.on(d))
            assertTrue(t, line(t).needs!!.on(videos))
            assertFalse(t, line(t).needs!!.on(videos.copy(behavior = videos.behavior.copy(canvas = videos.behavior.canvas.copy(enabled = false)))))
        }
        assertFalse(line("Keep controls showing").needs!!.on(d))
        assertFalse("animated covers alone are never full screen", line("Keep controls showing").needs!!.on(d.copy(behavior = d.behavior.copy(canvas = d.behavior.canvas.copy(fillsBackground = true)))))
        assertTrue(line("Keep controls showing").needs!!.on(videos.copy(behavior = videos.behavior.copy(canvas = videos.behavior.canvas.copy(fillsBackground = true)))))
        assertFalse(line("Keep controls showing").needs!!.on(noCovers.copy(behavior = noCovers.behavior.copy(canvas = noCovers.behavior.canvas.copy(fillsBackground = true)))))
        // Cover animation's line is in Modes now, where its row is.
        assertTrue(com.vinz.music.ui.screens.SettingsIndex.entries.any { it.page == SettingsPageId.PLAYER && it.group == "Modes" && it.title == "Cover animation" })
    }

    // ------------------------------------------------------------------ Reset this page

    @Test fun resetThisPageListsAndPutsBackLookAndOtherSettings() {
        // A look setting, one that isn't part of a Look, one of a merge, and the one another page's list still holds.
        prefs.setScrubberThickness(1.5f); prefs.setDeskMode(true); prefs.setCanvasEnabled(false); prefs.setCoverShape("player", com.vinz.music.data.prefs.CoverShape.CIRCLE)
        prefs.setShowTrackLength(true)
        until("changed") { it.scrubberThickness == 1.5f && it.tv.deskMode && !it.behavior.canvas.enabled && it.showTrackLength && it.shapes.player == com.vinz.music.data.prefs.CoverShape.CIRCLE }
        showPage()
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        val listed = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        assertEquals(5, listed.size)
        assertTrue("$listed", "Desk mode: On → Off" in listed && "Animated covers: Off → On" in listed && "Right-hand time: Song length → Time left" in listed)
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        until("all back") { s -> playerSettings.none { it.changed(s) } }
    }
}
