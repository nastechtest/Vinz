package com.vinz.music

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vinz.music.connect.TvInstall
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.data.prefs.ThemeMode
import com.vinz.music.ui.screens.SettingsIndex
import com.vinz.music.ui.screens.settings.SettingsMap
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.screens.settings.SettingsTarget
import com.vinz.music.ui.screens.settings.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.8.4, Settings → Speakers, TVs & browser rebuilt with the kit (plan §2.3, §2.7, the ledger's table): three groups
 * within the budgets (Vinz on a TV, Browser remote, Handoff); a control for both stored settings the ledger gives the
 * page; the remote's address, pairing code and paired browsers, and handoff's paired devices, as content of a sheet
 * behind one row each (so a group never grows with what's paired); "Play on a speaker or TV", the row that did nothing,
 * gone; Reset this page; the old links; and the page drawn in light, dark, Performance mode and at large text.
 *
 * Nothing here reaches a service: the browser remote and "Share Vinz with a TV" only listen on this machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class SpeakersTvsBrowserPageTest : ListeningPageHarness(SettingsPageId.SPEAKERS_TVS_BROWSER) {
    private fun t(id: Int, vararg args: Any) = tr(id, *args)
    private val me = SettingsPageId.SPEAKERS_TVS_BROWSER

    private val tvGroup = t(R.string.vinz_on_a_tv)
    private val browserGroup = t(R.string.cn_browser_remote)
    private val handoffGroup = t(R.string.handoff)
    private val share = t(R.string.share_vinz_with_a_tv)
    private val downloader = t(R.string.downloader_code)
    private val remote = t(R.string.control_from_a_browser)
    private val addressAndCode = t(R.string.cn_address_and_code)
    private val handoff = t(R.string.available_for_handoff)
    private val pairedDevices = t(R.string.cn_paired_devices)

    /** A line of content in one of the page's sheets (`ConnectTags.LINE`): not a row, counted by no budget. */
    private val line = "settings:connect:line"

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    private fun lines() = compose.onAllNodes(hasTestTag(line)).fetchSemanticsNodes().size

    private fun lineWith(text: String) = compose.onNode(hasTestTag(line) and (hasText(text) or hasAnyDescendant(hasText(text))))

    private fun saveNode(tag: String, folder: String, name: String): File {
        val shot = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/$folder").apply { mkdirs() }
        return File(dir, "$name.png").also { f -> f.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun remoteOff() {
        container.settings.setWebRemote(false)
        until("the browser remote off") { !it.webRemote }
        runCatching { compose.waitUntil(5_000) { !container.webRemote.running.value } }
    }

    @Test fun threeGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        assertEquals(listOf(tvGroup, browserGroup, handoffGroup), groupTitles())
        // Share Vinz with a TV and the Downloader code; the remote's switch; handoff's switch.
        assertEquals(listOf(if (TvInstall.DOWNLOADER_CODE.isBlank()) 1 else 2, 1, 1), groupSizes())
        assertEquals(0, changedRows())
        assertTrue("no group repeats the page's name", groupTitles().none { it == me.title })
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(share))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(remote))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(handoff))
        assertEquals(SettingsTags.ROW_LINK, typeOf(downloader))
        assertTrue(valueOf(downloader), TvInstall.DOWNLOADER_CODE in valueOf(downloader))
        // §2.7: the row that did nothing is gone, with the group that held only it and its line in search.
        val cast = t(R.string.play_on_a_speaker_or_tv)
        assertFalse(hasRow(cast))
        assertFalse(t(R.string.speakers_tvs) in groupTitles())
        assertTrue(SettingsIndex.entries.none { it.title == cast })
        // ...and so are the sentences under the old switches (they're words for search, and behind the ⓘ).
        for (id in listOf(R.string.from_a_computer_or_tablet_on, R.string.move_music_between_your_vinz_devices, R.string.from_this_phone_over_your_wi_fi, R.string.pick_it_from_the_device_line)) {
            assertEquals(t(id), 0, compose.onAllNodes(hasText(t(id))).fetchSemanticsNodes().size)
        }
        // Rows that follow a switch aren't there while it's off.
        assertFalse(hasRow(addressAndCode) || hasRow(pairedDevices) || hasRow(t(R.string.address)))
    }

    /** Principle 8: both stored settings the ledger gives the page have their control here, and it stores them. */
    @Test fun everyStoredSettingHasItsControl() {
        show()
        val d = AppSettings()
        assertFalse(d.webRemote || d.handoffAvailable)
        try {
            // web_remote
            tap(remote)
            until("web_remote") { it.webRemote }
            assertTrue("its row follows", hasRow(addressAndCode))
            assertEquals(SettingsTags.ROW_PICK, typeOf(addressAndCode))
            assertTrue("the row says the address, or why there is none", valueOf(addressAndCode).isNotBlank())
            assertBudgets("the browser remote on")
            assertEquals(2, groupSizes()[1])
            // handoff_available
            tap(handoff)
            until("handoff_available") { it.handoffAvailable }
            assertEquals(2, changedRows())
            // The root's line is live.
            val root = me.value(stored)
            assertTrue(root, t(R.string.browser_remote_on) in root && t(R.string.root_handoff_on) in root)
            // Short, and no group that's gone ("Speakers & TVs").
            assertFalse(root, t(R.string.speakers_tvs) in root)
            assertEquals(t(R.string.root_connect_short), me.value(AppSettings()))

            // Reset this page lists both and puts them back.
            val lines = resetLines(confirm = true)
            assertEquals(lines.joinToString("\n"), 2, lines.size)
            assertTrue(lines.toString(), lines.any { it.startsWith("$remote: ${t(R.string.on_2)} → ${t(R.string.off)}") })
            until("the reset") { !it.webRemote && !it.handoffAvailable }
            assertEquals(0, changedRows())
            assertFalse(hasRow(addressAndCode))
        } finally {
            remoteOff()
        }
    }

    /**
     * The address, the pairing code and the paired browsers are content of the row's sheet: however many browsers are
     * paired, the group stays two rows. Unpairing works from the sheet, one and all.
     */
    @Test fun theRemotesAddressCodeAndBrowsersAreInOneRowsSheet() {
        container.settings.setWebRemote(true)
        until("the browser remote on") { it.webRemote }
        try {
            val auth = container.webRemote.auth
            for (name in listOf("Firefox on the laptop", "Safari on the tablet", "Chrome on the desk")) auth.pair(auth.code, name, "192.168.1.20")
            assertEquals(3, auth.browsers().size)
            show()
            assertBudgets("three browsers paired")
            assertEquals("the group doesn't grow with the browsers", 2, groupSizes()[1])
            assertEquals(0, lines())

            tap(addressAndCode)
            assertEquals(1, sheets())
            // The pairing code and three browsers are lines; the address (a tap copies it) and "Unpair all browsers"
            // are the kit's action rows, in the sheet.
            assertEquals(4, lines())
            val inSheet = hasAnyAncestor(hasTestTag(SettingsTags.SHEET))
            for (action in listOf(t(R.string.address), t(R.string.unpair_all_browsers))) {
                assertEquals(action, SettingsTags.ROW_LINK, compose.onNode(rowIs(action) and inSheet).fetchSemanticsNode().config[SemanticsProperties.TestTag])
            }
            val code = auth.code
            assertEquals(1, compose.onAllNodes(hasText(t(R.string.one_browser_per_code, code.take(3), code.drop(3)))).fetchSemanticsNodes().size)
            // What the sheet holds are lines, not rows: the page's rows are the same as before it opened.
            assertBudgets("with the sheet open")
            // A new code.
            lineWith(t(R.string.pairing_code)).performClick()
            compose.waitForIdle()
            assertTrue("a tap gives a new code", auth.code != code)
            // Unpair one.
            compose.onNode(hasContentDescription(t(R.string.unpair, "Safari on the tablet"))).performClick()
            compose.waitForIdle()
            assertEquals(listOf("Chrome on the desk", "Firefox on the laptop"), auth.browsers().map { it.name }.sorted())
            assertEquals(3, lines())
            // Unpair all.
            compose.onNode(rowIs(t(R.string.unpair_all_browsers)) and inSheet).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(0, auth.browsers().size)
            assertEquals(1, lines())
            assertFalse("it goes with the browsers", hasRow(t(R.string.unpair_all_browsers)))
            assertEquals(1, compose.onAllNodes(hasText(t(R.string.cn_no_browsers))).fetchSemanticsNodes().size)
            swipeSheetAway()
            assertTrue(stored.webRemote)
        } finally {
            remoteOff()
        }
    }

    /** Handoff's paired devices: one row, only while there are any, and a sheet to forget them in. */
    @Test fun handoffsPairedDevicesAreBehindOneRow() {
        // (The third value is the pairing's key: any text, shown nowhere.)
        container.handoff.savePairing("device-1", "Pixel Tablet", "k1")
        container.handoff.savePairing("device-2", "Living room TV", "k2")
        show()
        assertBudgets("two devices paired")
        assertEquals(2, groupSizes()[2])
        assertEquals(SettingsTags.ROW_PICK, typeOf(pairedDevices))
        assertEquals("Living room TV, Pixel Tablet", valueOf(pairedDevices))
        assertEquals(0, compose.onAllNodes(hasText("k1", substring = true)).fetchSemanticsNodes().size)

        tap(pairedDevices)
        assertEquals(1, sheets())
        assertEquals(2, lines())
        compose.onNode(hasContentDescription(t(R.string.forget, "Pixel Tablet"))).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Living room TV"), container.handoff.pairedDevices.value.map { it.second })
        assertEquals(1, lines())
        compose.onNode(hasContentDescription(t(R.string.forget, "Living room TV"))).performClick()
        compose.waitUntil(5_000) { !hasRow(pairedDevices) }
        assertEquals("the row goes with the last device", 1, groupSizes()[2])
        assertEquals(0, sheets())
    }

    /** "Share Vinz with a TV" isn't kept: it says how it went, and the Downloader code is copied by a tap. */
    @Test fun sharingWithATvAndTheDownloaderCode() {
        show()
        val address = t(R.string.address)
        val before = stored
        tap(share)
        // A copy of Vinz that can't be handed over (a test's has no APK) says so and stays off; else the address shows.
        if (container.tvInstall.running) {
            assertTrue(hasRow(address))
            assertEquals(SettingsTags.ROW_LINK, typeOf(address))
            assertBudgets("sharing")
            tap(share)
            assertFalse("off again", container.tvInstall.running)
        } else {
            assertEquals(t(R.string.this_copy_can_t_be_shared), valueOf(share))
        }
        assertFalse(hasRow(address))
        assertEquals("nothing is stored for it", before, stored)

        tap(downloader)
        val clip = (RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertEquals(TvInstall.DOWNLOADER_CODE, clip?.getItemAt(0)?.text?.toString())
    }

    /** The old links land, and every line of the page's search names a group the page draws. */
    @Test fun theOldRoutesAndSearch() {
        assertEquals(SettingsTarget(me), SettingsMap.resolve("connect"))
        // "Control from a browser" was the group's title; it is the group's switch now.
        assertEquals(SettingsTarget(me, browserGroup), SettingsMap.resolve("connect", remote))
        assertEquals(SettingsTarget(me, handoffGroup), SettingsMap.resolve("connect", handoffGroup))
        assertEquals(SettingsTarget(me, tvGroup), SettingsMap.resolve(me.key, tvGroup))

        show()
        val mine = SettingsIndex.entries.filter { it.page == me && !it.isPage }
        assertEquals(mine.joinToString { it.group }, setOf(tvGroup, browserGroup, handoffGroup), mine.map { it.group }.toSet())
        assertTrue(mine.map { it.group }.toSet() == groupTitles().toSet())
        // What only shows with the remote on says so.
        val off = AppSettings(webRemote = false)
        for (title in listOf(addressAndCode, t(R.string.address), t(R.string.cn_paired_browsers), t(R.string.unpair_all_browsers))) {
            val needs = mine.single { it.title == title }.needs
            assertTrue(title, needs != null && !needs.on(off) && needs.on(off.copy(webRemote = true)))
        }
        for (title in listOf(share, downloader, remote, handoff)) assertEquals(title, null, mine.single { it.title == title }.needs)
        // Found by the words of before, and by what people call it.
        assertTrue(SettingsIndex.search("web remote").any { it.title == remote })
        assertTrue(SettingsIndex.search("browser pairing code").any { it.page == me && it.group == browserGroup })
        assertTrue(SettingsIndex.search("handoff").any { it.title == handoff })
        assertTrue(SettingsIndex.search("downloader").any { it.title == downloader })
        // The page is its own code now.
        val source = File("src/main/java/com/vinz/music/ui/screens/settings/SpeakersTvsBrowserPage.kt").readText()
        assertTrue("ConnectSettings(" !in source && "InterimPage(" !in source)
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.settings.setWebRemote(true)
        container.settings.setHandoffAvailable(true)
        until("both on") { it.webRemote && it.handoffAvailable }
        try {
            container.handoff.savePairing("device-1", "Pixel Tablet", "k1")
            show(fontScale = 1.5f)
            assertBudgets("large text")
            assertNoTextCutOff("font scale 1.5")
        } finally {
            remoteOff()
        }
    }

    /** [texts]: also that no text is cut off (the harness's check wants more text than the page has with everything off). */
    private fun drawn(name: String, glass: Boolean, texts: Boolean = false, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("speakers-tvs-browser", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        if (texts) assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnLight() = drawn("speakers-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnDark() = drawn("speakers-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnPerformance() = drawn("speakers-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** The page's longest state: the remote and handoff on, a device paired. */
    @Test @Config(qualifiers = "w360dp-h900dp-xxhdpi") fun drawnWithEverythingOn() {
        container.settings.setWebRemote(true)
        container.settings.setHandoffAvailable(true)
        until("both on") { it.webRemote && it.handoffAvailable }
        try {
            container.handoff.savePairing("device-1", "Pixel Tablet", "k1")
            drawn("speakers-dark-everything-on", true, texts = true) { it.copy(themeMode = ThemeMode.DARK) }
            // And the remote's sheet over it.
            val auth = container.webRemote.auth
            auth.pair(auth.code, "Firefox on the laptop", "192.168.1.20")
            tap(addressAndCode)
            assertEquals(1, sheets())
            val shot = runCatching { saveNode(SettingsTags.SHEET, "speakers-tvs-browser", "speakers-dark-remote-sheet") }
            assertTrue("the sheet's picture: ${shot.exceptionOrNull()}", shot.isSuccess)
        } finally {
            remoteOff()
        }
    }
}
