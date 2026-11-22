package com.vinz.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.LocalNavigator
import com.vinz.music.ui.Navigator
import com.vinz.music.ui.Routes
import com.vinz.music.ui.components.LocalFrosted
import com.vinz.music.ui.screens.StoredSettings
import com.vinz.music.ui.screens.settings.SettingsRowValue
import com.vinz.music.ui.screens.settings.SettingsTags
import com.vinz.music.ui.theme.Backdrop
import com.vinz.music.ui.theme.BackdropLayer
import com.vinz.music.ui.theme.LocalContainer
import com.vinz.music.ui.theme.VinzTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * What the Theme & colors and Looks page tests share (1.8.4): a page drawn with a real [AppContainer] and its stored
 * settings (the settings store is one per process: emptied after each test), and ways to find and work the kit's rows
 * by their titles.
 */
abstract class ThemeLooksPageHarness {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    protected val container by made
    protected val stored: AppSettings get() = container.settings.stored.value
    protected var nav: NavHostController? = null

    /** The settings store is one per JVM: whatever an earlier test (of any class) left in it goes before this one starts. */
    @org.junit.Before fun clean() {
        runBlocking { container.settings.editNow { it.clear() } }
        val fresh = AppSettings()
        val until = System.currentTimeMillis() + 5_000
        while (container.settings.stored.value.let { it.themeMode != fresh.themeMode || it.performanceMode || it.pureBlack } && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @After fun close() {
        runCatching { runBlocking { container.settings.editNow { it.clear() } } }
        made.closeAfterTest()
    }

    protected fun await(what: String = "the stored settings", check: () -> Boolean) {
        runCatching { compose.waitUntil(8_000) { check() } }.onFailure { throw AssertionError("Waited in vain for $what", it) }
        compose.waitForIdle()
    }

    /**
     * [page] as Settings shows it, on the settings as stored. [glass]: on a background, as the app's frosted pages are.
     * [routes]: inside a navigation graph, so a Link row to another screen has somewhere to go.
     */
    protected fun show(fontScale: Float = 1f, glass: Boolean = false, routes: Boolean = false, page: @Composable () -> Unit) {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            val controller = rememberNavController().also { nav = it }
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalContainer provides container, LocalNavigator provides Navigator(controller, openPlayer = {}),
                LocalDensity provides Density(d.density, fontScale),
            ) {
                VinzTheme(s, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (glass) BackdropLayer(Backdrop.SPECTRUM, null, 0.35f, motion = false, modifier = Modifier.fillMaxSize())
                        CompositionLocalProvider(LocalFrosted provides glass) {
                            if (routes) NavHost(controller, "page") {
                                composable("page") { StoredSettings(page) }
                                composable(Routes.BACKGROUNDS) { Box(Modifier.fillMaxSize()) }
                                composable(Routes.LOOK_SCAN) { Box(Modifier.fillMaxSize()) }
                            } else StoredSettings(page)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------------ Rows and groups

    private val isRow = SettingsTags.rowTypes.map { hasTestTag(it) }.reduce { a, b -> a or b }

    protected fun tagOf(n: SemanticsNode): String? = n.config.getOrNull(SemanticsProperties.TestTag)

    protected fun textsOf(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap { textsOf(it) }

    private fun below(n: SemanticsNode): List<SemanticsNode> = n.children.flatMap { listOf(it) + below(it) }

    /** Every row on the page, top to bottom. */
    protected fun rows(): List<SemanticsNode> =
        compose.onAllNodes(isRow, useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }

    /** The groups, top to bottom: each one's title ("" for none) and its rows. */
    protected fun groups(): List<Pair<String, List<SemanticsNode>>> =
        compose.onAllNodes(hasTestTag(SettingsTags.GROUP), useUnmergedTree = true).fetchSemanticsNodes().sortedBy { it.positionInRoot.y }.map { g ->
            val all = below(g)
            val title = all.firstOrNull { it.config.getOrNull(SemanticsProperties.Heading) != null }?.let { textsOf(it).firstOrNull() }.orEmpty()
            title to all.filter { tagOf(it) in SettingsTags.rowTypes }
        }

    /** A row's title: its first text. */
    protected fun titleOf(row: SemanticsNode): String = textsOf(row).first()

    protected fun rowMatcher(title: String): SemanticsMatcher = isRow and hasAnyDescendant(hasText(title))

    protected fun row(title: String): SemanticsNodeInteraction = compose.onNode(rowMatcher(title), useUnmergedTree = true)

    protected fun hasRow(title: String) = compose.onAllNodes(rowMatcher(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun valueOf(title: String): String = row(title).fetchSemanticsNode().config[SettingsRowValue]

    /** The row's own tap (a switch, a pick or a link). */
    protected fun tap(title: String) {
        row(title).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** A chip of a Choice row. */
    protected fun chip(title: String, label: String) {
        compose.onNode(hasText(label) and hasAnyAncestor(rowMatcher(title)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** A Slider row, moved to [value] and let go. */
    protected fun slide(title: String, value: Float) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(rowMatcher(title)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    /** Opens a Pick row's sheet and taps the option called [label]. */
    protected fun pick(title: String, label: String) {
        tap(title)
        compose.onNode(hasTestTag(SettingsTags.SHEET_OPTION) and hasText(label)).performClick()
        compose.waitForIdle()
    }

    protected fun sheetOptions(): List<String> =
        // An option's caption comes after its example (which may hold text of its own: a small Home).
        compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().map { textsOf(it).last() }

    protected fun sheetGone() = await("the sheet to close") { compose.onAllNodes(hasTestTag(SettingsTags.SHEET)).fetchSemanticsNodes().isEmpty() }

    // ------------------------------------------------------------------ Checks every page shares

    /** Budgets (plan §2.1.7) and the rows' values: at most 6 groups, at most 7 rows a group, a value on every row but switches and links. */
    protected fun assertBudgets() {
        val groups = groups()
        assertTrue("at most 6 groups: ${groups.map { it.first }}", groups.size <= 6)
        for ((title, rows) in groups) assertTrue("\"$title\" has ${rows.size} rows: ${rows.map { titleOf(it) }}", rows.size <= 7)
        assertTrue("no two groups share a name", groups.map { it.first }.filter { it.isNotBlank() }.let { it.size == it.toSet().size })
        for (r in rows()) {
            val tag = tagOf(r)
            if (tag != SettingsTags.ROW_SWITCH && tag != SettingsTags.ROW_LINK) assertTrue("\"${titleOf(r)}\" says its value", r.config[SettingsRowValue].isNotBlank())
        }
    }

    /** No text of a row or a group's heading is cut off (large text: rows grow and wrap). */
    protected fun assertNoRowTextIsCutOff() {
        val checked = ArrayList<String>()
        fun check(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            val layout = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (text != null && layout != null) {
                val out = ArrayList<TextLayoutResult>()
                layout(out)
                out.firstOrNull()?.let { l ->
                    // Not `hasVisualOverflow`: a short title that takes only its own width in a row reads as "narrower
                    // than its paragraph" there. What counts: no line is taller or wider than the room it was given.
                    val wide = (0 until l.lineCount).any { l.getLineRight(it) > l.size.width + 0.5f || l.getLineLeft(it) < -0.5f }
                    assertFalse("\"$text\" is cut off (${l.size} for ${l.lineCount} lines, ${l.multiParagraph.height} tall)", l.didOverflowHeight || wide)
                    assertFalse("\"$text\" ends in an ellipsis", l.isLineEllipsized(l.lineCount - 1))
                    checked += text
                }
            }
            n.children.forEach { check(it) }
        }
        rows().forEach { check(it) }
        assertTrue("texts were checked (${checked.size})", checked.size > 10)
    }

    protected fun save(folder: String, name: String): Bitmap {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/settings-pages/$folder").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot
    }
}
