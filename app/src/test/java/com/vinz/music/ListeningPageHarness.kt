package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.settings.SettingsPageContent
import com.vinz.music.ui.screens.settings.SettingsPageId
import com.vinz.music.ui.screens.settings.SettingsRowChanged
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.LocalAppSettings
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * What PlaybackPageTest and SoundQualityPageTest share (1.8.4): one of the two pages drawn with a real [AppContainer]
 * on a store that starts and ends empty, the rows found by their titles and worked as a finger would (a switch, a
 * chip, a slider, a pick sheet), and the checks every rebuilt page has to pass (budgets, the five row types, nothing
 * cut off at large text, a PNG of it).
 */
abstract class ListeningPageHarness(private val page: SettingsPageId) {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    protected val container by made
    protected val stored: AppSettings get() = container.settings.stored.value

    /** What the page is shown on top of what's stored (the theme, Performance mode, signed in, Data saver). */
    protected var over by mutableStateOf<(AppSettings) -> AppSettings>({ it })
    protected var navController: NavHostController? = null

    @Before fun emptyStore() = runBlocking { container.settings.editNow { it.clear() } }

    @After fun close() {
        runBlocking { runCatching { container.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    protected fun show(fontScale: Float = 1f, glass: Boolean = false, over: (AppSettings) -> AppSettings = { it }) {
        this.over = over
        compose.setContent {
            val controller = rememberNavController().also { navController = it }
            val nav = Navigator(controller, openPlayer = {})
            val s by container.settings.stored.collectAsState()
            val shown = this.over(s)
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav, LocalDensity provides Density(d.density, fontScale)) {
                VinzTheme(shown, artworkColor = null) {
                    CompositionLocalProvider(LocalAppSettings provides shown, LocalFrosted provides glass) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            // A graph of two, so that a Link row's route has somewhere to go.
                            NavHost(controller, "page") {
                                composable("page") { SettingsPageContent(page, 0.dp, onSearch = {}) }
                                composable(Routes.EQ) { Box(Modifier.fillMaxSize()) }
                                // Spotify's "Before you sign in" (1.8.7.5), opened from Accounts & sharing.
                                composable("spotify_before") { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    protected fun until(what: String, check: (AppSettings) -> Boolean) {
        try { compose.waitUntil(5_000) { check(stored) } } catch (e: Throwable) { throw AssertionError("$what: the stored value didn't follow", e) }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Rows

    private val isRow = SemanticsMatcher("is one of the kit's five rows") { it.config.getOrNull(SemanticsProperties.TestTag) in SettingsTags.rowTypes }

    protected fun rowIs(title: String): SemanticsMatcher = isRow and (hasText(title) or hasAnyDescendant(hasText(title)))
    protected fun row(title: String): SemanticsNodeInteraction = compose.onNode(rowIs(title))
    protected fun hasRow(title: String) = compose.onAllNodes(rowIs(title)).fetchSemanticsNodes().isNotEmpty()
    protected fun valueOf(title: String): String = row(title).fetchSemanticsNode().config[SettingsRowValue]
    protected fun typeOf(title: String): String = row(title).fetchSemanticsNode().config[SemanticsProperties.TestTag]

    /** A switch or a link: the row's own tap (a click at its middle can land on the ⓘ after a short title). */
    protected fun tap(title: String) {
        row(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** A chip of a Choice row. */
    protected fun choose(title: String, option: String) {
        compose.onNode(hasText(option) and hasAnyAncestor(rowIs(title)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        compose.waitForIdle()
    }

    /** A Slider row, let go at [value]. */
    protected fun slide(title: String, value: Float) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(rowIs(title)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    /** A Pick row: open its sheet and tap [option]. */
    protected fun pick(title: String, option: String) {
        tap(title)
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(option)).performClick()
        compose.waitUntil(5_000) { sheets() == 0 }
        compose.waitForIdle()
    }

    protected fun sheets() = compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().size

    protected fun swipeSheetAway() {
        compose.onNodeWithTag(SettingsTags.SHEET).performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { sheets() == 0 }
        compose.waitForIdle()
    }

    protected fun rows(): List<SemanticsNode> = compose.onAllNodes(isRow).fetchSemanticsNodes()

    // ------------------------------------------------------------------ What every page has to pass

    /** The groups' titles top to bottom, each with its rows' titles... as counts: ≤ 6 groups, ≤ 7 rows, only the five rows. */
    protected fun groupSizes(): List<Int> {
        val cards = compose.onAllNodes(hasTestTag(SettingsTags.GROUP_CARD)).fetchSemanticsNodes()
        assertEquals("a card per group", compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes().size, cards.size)
        for (card in cards) {
            val other = card.children.filter { it.config.getOrNull(SemanticsProperties.TestTag) !in SettingsTags.rowTypes }
            assertTrue("in a card but not one of the five rows: ${other.map { it.config }}", other.isEmpty())
        }
        return cards.map { it.children.size }
    }

    protected fun assertBudgets(what: String) {
        val sizes = groupSizes()
        assertTrue("$what: the page is rebuilt with the kit (its groups are cards of rows): $sizes", sizes.isNotEmpty() && sizes.all { it > 0 })
        assertTrue("$what: at most 6 groups: $sizes", sizes.size <= 6)
        assertTrue("$what: at most 7 rows a group: $sizes", sizes.all { it <= 7 })
        // Every row says its state; only a switch or a link may do without a value line.
        for (r in rows()) {
            val type = r.config[SemanticsProperties.TestTag]
            if (type == SettingsTags.ROW_SWITCH || type == SettingsTags.ROW_LINK) continue
            assertTrue("$what: a $type without a value", r.config[SettingsRowValue].isNotBlank())
        }
    }

    protected fun changedRows(): Int = rows().count { it.config[SettingsRowChanged] }

    private fun layouts(node: SemanticsNode, into: MutableList<Pair<String, TextLayoutResult>>) {
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { get ->
            val out = ArrayList<TextLayoutResult>()
            get(out)
            out.firstOrNull()?.let { into += it.layoutInput.text.text to it }
        }
        node.children.forEach { layouts(it, into) }
    }

    /** No text on the page is cut off or ends in "…". */
    protected fun assertNoTextCutOff(what: String) {
        val all = ArrayList<Pair<String, TextLayoutResult>>()
        layouts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), all)
        assertTrue("$what: the page's text was read (${all.size})", all.size > 10)
        for ((text, l) in all) {
            // The bar's small copy of the page's name is one line by design (the scaffold's; it shows once scrolled).
            if (text == page.title && l.layoutInput.maxLines == 1) continue
            // (Not hasVisualOverflow: it is also true for a short text given more room than it needs.)
            assertFalse("$what: \"$text\" is cut off below", l.didOverflowHeight)
            for (line in 0 until l.lineCount) {
                assertFalse("$what: \"$text\" ends in an ellipsis", l.isLineEllipsized(line))
                assertTrue("$what: \"$text\" is wider than its room", l.getLineRight(line) - l.getLineLeft(line) <= l.size.width + 1f)
            }
        }
    }

    protected fun save(folder: String, name: String): File {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/$folder").apply { mkdirs() }
        return File(dir, "$name.png").also { f -> f.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** "Reset this page": what its confirm lists; [confirm] then resets. */
    protected fun resetLines(confirm: Boolean): List<String> {
        compose.onNodeWithTag(SettingsTags.PAGE_MORE).performClick()
        compose.onNodeWithTag(SettingsTags.RESET_ITEM).performClick()
        compose.waitForIdle()
        val lines = compose.onAllNodes(hasTestTag(SettingsTags.RESET_CHANGE)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString { t -> t.text } }
        if (confirm) compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        compose.waitForIdle()
        return lines
    }
}
