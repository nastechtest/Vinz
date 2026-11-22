package com.vinz.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.PipMode
import com.vinz.music.data.prefs.PlayerGesture
import com.vinz.music.data.prefs.SessionButton
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.oldGroups
import com.vinz.music.ui.screens.settings.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.8.4, Settings → Gestures & buttons rebuilt with the kit (plan §2.3, the ledger's Gestures & buttons table): five
 * groups within the budgets, a control for every stored setting the ledger gives the page (worked here, and the store
 * follows), the nine gesture menus as lists in sheets, "Gestures work" over its two switches in all four stored states,
 * the two new rows (Swipe song rows to queue, Lyrics in the notification), Swipe between tabs moved here, Reset this
 * page, and the page drawn in light, dark, Performance mode and at large text. The window is tall so that the page's
 * lazy list holds every group at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class GesturesButtonsPageTest : ListeningPageHarness(SettingsPageId.GESTURES_BUTTONS) {
    private fun t(id: Int) = tr(id)

    private val cover = t(R.string.gb_on_the_cover)
    private val mini = t(R.string.gb_on_the_mini_player)
    private val touch = t(R.string.gb_swipes_and_touch)
    private val outside = t(R.string.notification_and_car)
    private val screen = t(R.string.screen)

    private val swipeLeft = t(R.string.swipe_left)
    private val swipeRight = t(R.string.swipe_right)
    private val swipeUp = t(R.string.swipe_up)
    private val doubleTap = t(R.string.double_tap)
    private val seeks = t(R.string.gb_double_tap_seeks)
    private val longPress = t(R.string.long_press)
    private val work = t(R.string.gb_gestures_work)
    private val pullDown = t(R.string.pull_down)
    private val holdSeek = t(R.string.hold_previous_next_to_seek)
    private val haptics = t(R.string.haptics)
    private val queueSwipe = t(R.string.swipe_up_for_the_queue)
    private val tabSwipe = t(R.string.swipe_between_tabs)
    private val rowSwipes = t(R.string.gb_row_swipes)
    private val coverPlays = t(R.string.gb_lyrics_cover_plays)
    private val buttons = t(R.string.buttons)
    private val notificationLyrics = t(R.string.gb_lyrics_in_notification)
    private val portrait = t(R.string.lock_to_portrait)
    private val pip = t(R.string.picture_in_picture)

    private val onCover = t(R.string.gb_work_cover)
    private val anywhere = t(R.string.gb_work_anywhere)
    private val notLyrics = t(R.string.gb_work_not_lyrics)
    private val coverAndLyrics = t(R.string.gb_work_cover_and_lyrics)

    // ------------------------------------------------------------------ Finding rows

    /** The buttons editor isn't one of the five row types: a list in a group, counted as one row. */
    private val listTag = "gesturesButtons:buttons"

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    /** Rows a card: only the five row types and the one list. */
    private fun sizes(): List<Int> {
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes()
        for (card in cards) {
            val other = card.children.filter { it.config.getOrNull(SemanticsProperties.TestTag) !in SettingsTags.rowTypes + listTag }
            assertTrue("in a card but neither one of the five rows nor the buttons list: ${other.map { it.config }}", other.isEmpty())
        }
        return cards.map { it.children.size }
    }

    private fun budgets(what: String) {
        val sizes = sizes()
        assertTrue("$what: the page is rebuilt with the kit (its groups are cards of rows): $sizes", sizes.isNotEmpty() && sizes.all { it > 0 })
        assertTrue("$what: at most 6 groups: $sizes", sizes.size <= 6)
        assertTrue("$what: at most 7 rows a group: $sizes", sizes.all { it <= 7 })
        for (r in rows()) {
            val type = r.config[SemanticsProperties.TestTag]
            if (type == SettingsTags.ROW_SWITCH || type == SettingsTags.ROW_LINK) continue
            assertTrue("$what: a $type without a value", r.config[SettingsRowValue].isNotBlank())
        }
    }

    /** "Swipe left" is a row of the cover and of the mini player: a row by its group too. */
    private fun rowIn(group: String, title: String) =
        compose.onNode(rowIs(title) and hasAnyAncestor(hasTestTag(SettingsTags.GROUP) and hasAnyDescendant(hasText(group))))

    private fun valueIn(group: String, title: String): String = rowIn(group, title).fetchSemanticsNode().config[SettingsRowValue]

    private fun options() = compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes()

    private fun openIn(group: String, title: String) {
        rowIn(group, title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun pickIn(group: String, title: String, option: String) {
        openIn(group, title)
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(option)).performClick()
        compose.waitUntil(5_000) { sheets() == 0 }
        compose.waitForIdle()
    }

    private fun tick(button: SessionButton) {
        compose.onNode(hasContentDescription(button.label) and isToggleable() and hasAnyAncestor(hasTestTag(listTag))).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun listNode() = compose.onNodeWithTag(listTag).fetchSemanticsNode()

    // ------------------------------------------------------------------ The page

    @Test fun fiveGroupsWithinTheBudgets() {
        show()
        budgets("defaults")
        // The ledger's layout with a fresh install's settings: 7 · 4 · 6 · 2 · 2 (#464's switch in Swipes and touch, 1.8.5).
        assertEquals(listOf(cover, mini, touch, outside, screen), groupTitles())
        assertEquals(listOf(7, 4, 6, 2, 2), sizes())
        assertEquals("nothing differs from how Vinz comes", 0, changedRows())
        assertFalse(listNode().config[SettingsRowChanged])
        assertTrue(groupTitles().none { it == SettingsPageId.GESTURES_BUTTONS.title })
        // No group is named as one of the page's rows.
        val titles = SettingsIndex.entries.filter { it.page == SettingsPageId.GESTURES_BUTTONS }.map { it.title }
        for (g in groupTitles()) assertFalse("a group and a row are both \"$g\"", g in titles)
        assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)

        // The nine gesture menus: each a Pick row that says what the gesture does.
        val d = AppSettings()
        val menus = listOf(
            Triple(cover, swipeLeft, d.gestures.coverSwipeLeft), Triple(cover, swipeRight, d.gestures.coverSwipeRight), Triple(cover, swipeUp, d.gestures.coverSwipeUp),
            Triple(cover, doubleTap, d.gestures.coverDoubleTap), Triple(cover, longPress, d.gestures.coverLongPress),
            Triple(mini, swipeLeft, d.gestures.miniSwipeLeft), Triple(mini, swipeRight, d.gestures.miniSwipeRight), Triple(mini, swipeUp, d.gestures.miniSwipeUp), Triple(mini, pullDown, d.gestures.miniSwipeDown),
        )
        assertEquals(9, menus.size)
        for ((group, title, gesture) in menus) {
            val row = rowIn(group, title).fetchSemanticsNode()
            assertEquals("$group › $title", SettingsTags.ROW_PICK, row.config[SemanticsProperties.TestTag])
            assertEquals("$group › $title", gesture.label, row.config[SettingsRowValue])
        }
        // Each row is the type the ledger gives it.
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(seeks))
        assertEquals("10 s", valueOf(seeks))
        assertEquals(SettingsTags.ROW_PICK, typeOf(work))
        assertEquals(onCover, valueOf(work))
        assertEquals(SettingsTags.ROW_SLIDER, typeOf(holdSeek))
        assertEquals("10 s", valueOf(holdSeek))
        for (title in listOf(haptics, queueSwipe, tabSwipe, rowSwipes, coverPlays, notificationLyrics, portrait)) assertEquals(title, SettingsTags.ROW_SWITCH, typeOf(title))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(pip))
        assertEquals(PipMode.AUTO.label, valueOf(pip))
        // The buttons stay a list: Vinz's own six, in order.
        assertEquals(SessionButton.DEFAULT.joinToString(" · ") { it.label }, listNode().config[SettingsRowValue])
        // A menu's sheet lists what that gesture may do: seeking by side only for a double tap, Stop and Open only on the mini player.
        openIn(cover, doubleTap)
        assertEquals(PlayerGesture.choices(double = true).size, options().size)
        swipeSheetAway()
        openIn(mini, pullDown)
        assertEquals(PlayerGesture.choices(mini = true).size, options().size)
        swipeSheetAway()
        assertEquals("a sheet swiped away changes nothing", d.gestures.miniSwipeDown, stored.gestures.miniSwipeDown)
    }

    /** Principle 8: every stored setting the ledger gives Gestures & buttons has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        show()
        val d = AppSettings()

        // cover_swipe_left, cover_swipe_right, cover_swipe_up, cover_long_press
        pickIn(cover, swipeLeft, PlayerGesture.LIKE.label)
        until("cover_swipe_left") { it.gestures.coverSwipeLeft == PlayerGesture.LIKE }
        assertEquals(PlayerGesture.LIKE.label, valueIn(cover, swipeLeft))
        assertEquals("the mini player's is another setting", d.gestures.miniSwipeLeft, stored.gestures.miniSwipeLeft)
        pickIn(cover, swipeRight, PlayerGesture.SLEEP.label)
        until("cover_swipe_right") { it.gestures.coverSwipeRight == PlayerGesture.SLEEP }
        pickIn(cover, swipeUp, PlayerGesture.LYRICS.label)
        until("cover_swipe_up") { it.gestures.coverSwipeUp == PlayerGesture.LYRICS }
        pickIn(cover, longPress, PlayerGesture.MENU.label)
        until("cover_long_press") { it.gestures.coverLongPress == PlayerGesture.MENU }
        // double_tap_seek_seconds: under the double tap while it seeks by side; cover_double_tap.
        choose(seeks, "5 s")
        until("double_tap_seek_seconds") { it.doubleTapSeekSeconds == 5 }
        pickIn(cover, doubleTap, PlayerGesture.PLAY_PAUSE.label)
        until("cover_double_tap") { it.gestures.coverDoubleTap == PlayerGesture.PLAY_PAUSE }
        assertFalse("the step only shows while a double tap seeks by side", hasRow(seeks))
        // player_gestures_everywhere, player_gestures_under_lyrics: "Gestures work".
        pick(work, anywhere)
        until("player_gestures_everywhere, player_gestures_under_lyrics") { it.behavior.gesturesEverywhere && it.behavior.gesturesUnderLyrics }
        pick(work, notLyrics)
        until("player_gestures_under_lyrics") { it.behavior.gesturesEverywhere && !it.behavior.gesturesUnderLyrics }

        // mini_swipe_left, mini_swipe_right, mini_swipe_up, mini_swipe_down
        pickIn(mini, swipeLeft, PlayerGesture.SLEEP.label)
        until("mini_swipe_left") { it.gestures.miniSwipeLeft == PlayerGesture.SLEEP && it.gestures.coverSwipeLeft == PlayerGesture.LIKE }
        pickIn(mini, swipeRight, PlayerGesture.LIKE.label)
        until("mini_swipe_right") { it.gestures.miniSwipeRight == PlayerGesture.LIKE }
        pickIn(mini, swipeUp, PlayerGesture.QUEUE.label)
        until("mini_swipe_up") { it.gestures.miniSwipeUp == PlayerGesture.QUEUE }
        pickIn(mini, pullDown, PlayerGesture.NONE.label)
        until("mini_swipe_down") { it.gestures.miniSwipeDown == PlayerGesture.NONE }

        // hold_seek_seconds (Off at its start), haptics, panel_swipes, swipe_tabs, row_swipes, lyrics_cover_plays
        slide(holdSeek, 0f)
        until("hold_seek_seconds") { it.holdSeekSeconds == 0 }
        assertEquals(t(R.string.off), valueOf(holdSeek))
        tap(haptics)
        until("haptics") { !it.haptics }
        tap(queueSwipe)
        until("panel_swipes") { it.behavior.panelSwipes }
        tap(tabSwipe)
        until("swipe_tabs") { it.swipeTabs }
        tap(rowSwipes)
        until("row_swipes") { !it.behavior.rowSwipes }
        tap(coverPlays)
        until("lyrics_cover_plays") { it.behavior.lyricsCoverPlays }

        // session_buttons: untick one, tick another, move one.
        tick(SessionButton.LIKE)
        until("session_buttons") { it.sessionButtons == SessionButton.DEFAULT - SessionButton.LIKE }
        tick(SessionButton.BACK_10)
        until("session_buttons") { it.sessionButtons == SessionButton.DEFAULT - SessionButton.LIKE + SessionButton.BACK_10 }
        // 1.8.4 §2.8: the row's arrows went; its grip's TalkBack move does it.
        compose.reorderAction(tr(R.string.drag, SessionButton.SHUFFLE.label), tr(R.string.move_down))
        until("session_buttons") { it.sessionButtons.take(2) == listOf(SessionButton.REPEAT, SessionButton.SHUFFLE) }
        assertTrue(listNode().config[SettingsRowChanged])
        assertEquals(stored.sessionButtons.joinToString(" · ") { it.label }, listNode().config[SettingsRowValue])
        // car_lyrics_on_phone
        tap(notificationLyrics)
        until("car_lyrics_on_phone") { it.carLyricsOnPhone }

        // lock_portrait, pip_mode
        tap(portrait)
        until("lock_portrait") { it.behavior.lockPortrait }
        choose(pip, PipMode.MENU.label)
        until("pip_mode") { it.behavior.pip == PipMode.MENU }

        // Every row now differs from how Vinz comes, says so, and the page still fits.
        budgets("everything changed")
        assertEquals(listOf(6, 4, 6, 2, 2), sizes())
        assertEquals(19, changedRows())
        // The root's line is live.
        val line = SettingsPageId.GESTURES_BUTTONS.value(stored)
        assertTrue(line, PlayerGesture.PLAY_PAUSE.label in line && haptics !in line)

        // Reset this page lists each of them (the hidden double-tap step too) and puts them all back.
        val lines = resetLines(confirm = true)
        assertEquals(lines.joinToString("\n"), 21, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith("$mini · $swipeLeft: ${PlayerGesture.SLEEP.label} → ${d.gestures.miniSwipeLeft.label}") })
        assertTrue(lines.toString(), lines.any { it.startsWith("$work: $notLyrics → $onCover") })
        until("the reset") { it.behavior == d.behavior && it.sessionButtons == d.sessionButtons && it.haptics == d.haptics && it.swipeTabs == d.swipeTabs }
        until("the reset") {
            it.gestures.coverSwipeLeft == d.gestures.coverSwipeLeft && it.gestures.coverSwipeRight == d.gestures.coverSwipeRight && it.gestures.coverSwipeUp == d.gestures.coverSwipeUp && it.gestures.coverDoubleTap == d.gestures.coverDoubleTap &&
                it.gestures.coverLongPress == d.gestures.coverLongPress && it.gestures.miniSwipeLeft == d.gestures.miniSwipeLeft && it.gestures.miniSwipeRight == d.gestures.miniSwipeRight && it.gestures.miniSwipeUp == d.gestures.miniSwipeUp &&
                it.gestures.miniSwipeDown == d.gestures.miniSwipeDown && it.doubleTapSeekSeconds == d.doubleTapSeekSeconds && it.holdSeekSeconds == d.holdSeekSeconds &&
                it.carLyricsOnPhone == d.carLyricsOnPhone
        }
        assertEquals(0, changedRows())
        assertEquals(listOf(7, 4, 6, 2, 2), sizes())
    }

    /**
     * §2.5: the two switches as one row. All four stored states show as what they do and stay as stored when the sheet
     * is only looked at; the three choices store their pair; the fourth state is only listed for whoever has it.
     */
    @Test fun gesturesWorkShowsAllFourStoredStatesAndStoresThree() {
        show()
        fun store(everywhere: Boolean, underLyrics: Boolean) {
            container.settings.setGesturesEverywhere(everywhere)
            container.settings.setGesturesUnderLyrics(underLyrics)
            until("stored $everywhere, $underLyrics") { it.behavior.gesturesEverywhere == everywhere && it.behavior.gesturesUnderLyrics == underLyrics }
        }
        val faces = listOf(
            Triple(false, false, onCover), Triple(true, true, anywhere), Triple(true, false, notLyrics), Triple(false, true, coverAndLyrics),
        )
        for ((everywhere, underLyrics, face) in faces) {
            store(everywhere, underLyrics)
            assertEquals("$everywhere, $underLyrics", face, valueOf(work))
            tap(work)
            val listed = options()
            assertEquals("$face: what the sheet lists", if (face == coverAndLyrics) 4 else 3, listed.size)
            val ticked = listed.filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            assertEquals("$face is the ticked one", 1, ticked.size)
            assertEquals(1, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(face) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().size)
            // A tap on what's already picked, like swiping the sheet away, stores nothing.
            compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(face)).performClick()
            compose.waitUntil(5_000) { sheets() == 0 }
            compose.waitForIdle()
            assertEquals("$face: there and back", everywhere to underLyrics, stored.behavior.gesturesEverywhere to stored.behavior.gesturesUnderLyrics)
        }
        // From the fourth state, each of the three.
        pick(work, anywhere)
        until("Anywhere") { it.behavior.gesturesEverywhere && it.behavior.gesturesUnderLyrics }
        store(everywhere = false, underLyrics = true)
        pick(work, notLyrics)
        until("Anywhere but the lyrics") { it.behavior.gesturesEverywhere && !it.behavior.gesturesUnderLyrics }
        store(everywhere = false, underLyrics = true)
        pick(work, onCover)
        until("On the cover") { !it.behavior.gesturesEverywhere && !it.behavior.gesturesUnderLyrics }
        // Left behind, the fourth is no longer offered.
        tap(work)
        assertEquals(3, options().size)
        assertEquals(0, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(coverAndLyrics)).fetchSemanticsNodes().size)
        swipeSheetAway()
        // The two switches are no rows of their own any more, and are still found by their old names.
        assertFalse(hasRow(t(R.string.gestures_anywhere_on_the_player)) || hasRow(t(R.string.gestures_under_the_lyrics)))
        assertTrue(SettingsIndex.search("gestures below the lyrics").any { it.title == work })
        assertTrue(SettingsIndex.search("gestures anywhere on the player").any { it.title == work })
    }

    /** #406 and #392: the two new rows. */
    @Test fun theTwoNewRows() {
        show()
        // Swipe song rows to queue: on, as Vinz was.
        assertTrue(stored.behavior.rowSwipes)
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(rowSwipes))
        assertEquals("Swipe song rows to queue", rowSwipes)
        assertEquals("Swipe a song right to play it next, left to add it to the queue.", t(R.string.gb_row_swipes_info))
        tap(rowSwipes)
        until("row_swipes off") { !it.behavior.rowSwipes }
        assertTrue(row(rowSwipes).fetchSemanticsNode().config[SettingsRowChanged])
        tap(rowSwipes)
        until("row_swipes on") { it.behavior.rowSwipes }

        // Lyrics in the notification: off until asked for, and it stands on its own, whatever the car's switch says.
        assertFalse(stored.carLyricsOnPhone)
        assertEquals("Lyrics in the notification", notificationLyrics)
        assertEquals("Lock screen and notification", valueOf(notificationLyrics))
        assertEquals(
            "Shows the line being sung as the title on the lock screen, the notification and a watch, with or without a car.",
            t(R.string.gb_lyrics_in_notification_info),
        )
        container.settings.setCarLyrics(false)
        until("car lyrics off") { !it.carLyrics }
        assertTrue("there without Lyrics in the car", hasRow(notificationLyrics))
        tap(notificationLyrics)
        until("car_lyrics_on_phone") { it.carLyricsOnPhone && !it.carLyrics }
        tap(notificationLyrics)
        until("car_lyrics_on_phone off") { !it.carLyricsOnPhone }

        // Found by search, in their groups, on this page, with nothing to turn on first; the old name still finds it.
        for ((title, group) in listOf(rowSwipes to touch, notificationLyrics to outside)) {
            val e = SettingsIndex.entries.single { it.title == title }
            assertEquals(SettingsPageId.GESTURES_BUTTONS, e.page)
            assertEquals(group, e.group)
            assertEquals(null, e.needs)
        }
        assertTrue(SettingsIndex.search("on this phone too").any { it.title == notificationLyrics })
        // Swipe between tabs is here now (from Tabs & mini player), and found here.
        assertTrue(SettingsIndex.entries.any { it.title == tabSwipe && it.page == SettingsPageId.GESTURES_BUTTONS && it.group == touch })
    }

    /** #464 (1.8.5): "Tap the small cover to play or pause", a switch in Swipes and touch, off until switched on, its words behind the ⓘ. */
    @Test fun smallCoverTapIsASwitchOffByDefault() {
        show()
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(coverPlays))
        assertFalse(stored.behavior.lyricsCoverPlays)
        // W41 (1.8.7.6): it says which cover.
        assertEquals("Tap the lyrics' cover to pause", coverPlays)
        val e = SettingsIndex.entries.single { it.title == coverPlays }
        assertEquals(SettingsPageId.GESTURES_BUTTONS, e.page)
        assertEquals(touch, e.group)
        assertEquals(null, e.needs)
        assertTrue(SettingsIndex.search("small cover").any { it.title == coverPlays })
        assertTrue(SettingsIndex.search("thumbnail").any { it.title == coverPlays })
        val info = t(R.string.gb_lyrics_cover_plays_info)
        assertEquals("With the lyrics open, a tap on the small cover plays or pauses and a long press closes the lyrics. Off: a tap closes the lyrics.", info)
        // The explanation is behind the ⓘ only: never drawn on the page.
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText(info)).fetchSemanticsNodes().size)
        tap(coverPlays)
        until("lyrics_cover_plays") { it.behavior.lyricsCoverPlays }
        assertEquals(1, changedRows())
        tap(coverPlays)
        until("lyrics_cover_plays off") { !it.behavior.lyricsCoverPlays }
    }

    /** Links written before the rework still land: the old page's groups by their old names. */
    @Test fun oldGroupNamesOpenTheGroupThatHoldsThemNow() {
        val old = SettingsPageId.GESTURES_BUTTONS.oldGroups
        assertEquals(cover, old[t(R.string.gestures)])
        assertEquals(touch, old[t(R.string.buttons_and_touch)])
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, cover), SettingsMap.resolve("controls", t(R.string.gestures)))
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, touch), SettingsMap.resolve("controls", t(R.string.buttons_and_touch)))
        // What "Buttons in the car" and the tour link to.
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, outside), SettingsMap.resolve("controls", outside))
        assertEquals(SettingsTarget(SettingsPageId.GESTURES_BUTTONS, screen), SettingsMap.resolve("controls", screen))
        // Every line of the page's search names a group the page draws, and every row drawn is a line.
        show()
        val drawn = groupTitles().toSet()
        val mine = SettingsIndex.entries.filter { it.page == SettingsPageId.GESTURES_BUTTONS && !it.isPage }
        val lost = mine.filter { it.group !in drawn }
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        assertEquals("a line a row (7 + 4 + 6 + 2 + 2)", 21, mine.size)
    }

    @Test fun atLargeTextNothingIsCutOff() {
        show(fontScale = 1.5f)
        budgets("large text")
        assertNoTextCutOff("font scale 1.5")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("gestures-buttons", name)
        assertTrue(file.path, file.length() > 10_000)
        budgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun drawnLight() = drawn("gestures-buttons-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun drawnDark() = drawn("gestures-buttons-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun drawnPerformance() = drawn("gestures-buttons-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }
}
