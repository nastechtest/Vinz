package com.vinz.music

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.vinz.music.data.prefs.AppSettings
import com.vinz.music.ui.components.DragGrip
import com.vinz.music.ui.components.LocalReorderAnnouncer
import com.vinz.music.ui.components.ReorderList
import com.vinz.music.ui.components.reorderPosition
import com.vinz.music.ui.theme.VinzTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the TalkBack move [action] ("Move down", ...) on the reorder row whose grip says [grip] ("Drag Shuffle"), as
 * TalkBack's actions menu does (the pages' tests use it where they used to tap the row's arrows).
 */
internal fun ComposeTestRule.reorderAction(grip: String, action: String) {
    val node = onNode(hasContentDescription(grip), useUnmergedTree = true).fetchSemanticsNode()
    val move = node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.firstOrNull { it.label == action }
    assertTrue("\"$grip\" has \"$action\"", move != null)
    runOnIdle { assertTrue("\"$action\" moved \"$grip\"", move!!.action()) }
    waitForIdle()
}

/**
 * 1.8.4 §2.8: a reorder list moves without a finger, so the up / down arrows rows used to show can go.
 * - TalkBack: each row's grip has "Move up", "Move down", "Move to top", "Move to bottom" (the ones that apply where it
 *   is); each moves the row, the store follows, and the new place is said ("3 of 8").
 * - D-pad / keyboard: the focused grip picks the row up on Enter, Up / Down move it, Enter / Back / Esc put it down and
 *   the order is stored then.
 * - No list draws arrow buttons any more, and every list's rows show the grip that carries all this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class ReorderListTest {
    @get:Rule val compose = createComposeRule()

    /** The "store": what the list's onReorder last wrote, and how many times. */
    private var stored by mutableStateOf(listOf("A", "B", "C", "D", "E"))
    private var writes = 0
    private val heard = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            VinzTheme(AppSettings(), artworkColor = null) {
                CompositionLocalProvider(LocalReorderAnnouncer provides { heard += it }) {
                    Column {
                        ReorderList(stored, { it }, { stored = it; writes++ }) { s, _, _, mod ->
                            Row(mod.fillMaxWidth().height(56.dp)) {
                                Text(s, Modifier.weight(1f))
                                DragGrip(Modifier.dragHandle(), tr(R.string.drag, s))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun grip(s: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(tr(R.string.drag, s))

    private fun actionsOf(s: String): List<String> =
        grip(s).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    /** Runs [s]'s TalkBack action [label], as TalkBack's actions menu does. */
    private fun act(s: String, label: String) {
        val action = grip(s).fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }.firstOrNull { it.label == label }
        assertTrue("$s has \"$label\" (it has ${actionsOf(s)})", action != null)
        compose.runOnIdle { assertTrue("\"$label\" on $s moves it", action!!.action()) }
        compose.waitForIdle()
    }

    private val up get() = tr(R.string.move_up)
    private val down get() = tr(R.string.move_down)
    private val top get() = tr(R.string.move_to_top)
    private val bottom get() = tr(R.string.move_to_bottom)

    @Test fun eachRowHasTheMovesThatApplyWhereItIs() {
        show()
        assertEquals("the first row", listOf(down, bottom), actionsOf("A"))
        assertEquals("a middle row", listOf(up, down, top, bottom), actionsOf("C"))
        assertEquals("the last row", listOf(up, top), actionsOf("E"))
    }

    @Test fun theActionsMoveTheRowTheStoreFollowsAndThePlaceIsSaid() {
        show()
        act("C", up)
        assertEquals(listOf("A", "C", "B", "D", "E"), stored)
        assertEquals(reorderPosition(1, 5), heard.last())
        assertEquals("2 of 5", heard.last())
        act("C", bottom)
        assertEquals(listOf("A", "B", "D", "E", "C"), stored)
        assertEquals("5 of 5", heard.last())
        // At the bottom now: its moves follow its place.
        assertEquals(listOf(up, top), actionsOf("C"))
        act("C", top)
        assertEquals(listOf("C", "A", "B", "D", "E"), stored)
        assertEquals("1 of 5", heard.last())
        act("A", down)
        assertEquals(listOf("C", "B", "A", "D", "E"), stored)
        assertEquals("3 of 5", heard.last())
        act("E", up)
        assertEquals(listOf("C", "B", "A", "E", "D"), stored)
        assertEquals("4 of 5", heard.last())
        assertEquals("one store write per move", 5, writes)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theFocusedGripPicksTheRowUpAndTheArrowKeysMoveIt() {
        show()
        grip("B").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        grip("B").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(tr(R.string.reorder_picked_up, 2, 5), heard.last())
        grip("B").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals("3 of 5", heard.last())
        grip("B").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals("4 of 5", heard.last())
        assertEquals("carried: stored only when put down, as a drag is", 0, writes)
        grip("B").performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(listOf("A", "C", "D", "B", "E"), stored)
        assertEquals(1, writes)
        assertEquals(tr(R.string.reorder_put_down, 4, 5), heard.last())
        // Put down, the arrows are focus's again: a Down doesn't move it.
        grip("B").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(listOf("A", "C", "D", "B", "E"), stored)

        // Up to the top and past it (it stays first), then Back puts it down.
        grip("D").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        grip("D").performKeyInput { pressKey(Key.DirectionCenter) }
        repeat(4) { grip("D").performKeyInput { pressKey(Key.DirectionUp) }; compose.waitForIdle() }
        grip("D").performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertEquals(listOf("D", "A", "C", "B", "E"), stored)
        assertEquals(2, writes)

        // Esc puts it down too; the row keeps its focus through the moves.
        grip("A").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        grip("A").performKeyInput { pressKey(Key.Spacebar) }
        grip("A").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertTrue("the moved grip keeps the focus", grip("A").fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false })
        grip("A").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        assertEquals(listOf("D", "C", "A", "B", "E"), stored)
        assertEquals(3, writes)
    }

    /**
     * No list draws its own up / down arrow buttons any more (their descriptions were "Move %1$s up" / "Move %1$s down"),
     * and every file that builds a [ReorderList] gives its rows the grip ([com.vinz.music.ui.components.ReorderScope.dragHandle])
     * that carries the TalkBack actions and the keys: no list loses its only way to move a row (principle 8).
     */
    /** From just inside a `ReorderList(` call: its row lambda (the braces after the closing parenthesis), as text. */
    private fun rowLambda(call: String): String {
        var depth = 1
        var i = 0
        while (i < call.length && depth > 0) { if (call[i] == '(') depth++ else if (call[i] == ')') depth--; i++ }
        val open = call.indexOf('{', i)
        if (open < 0) return ""
        depth = 0
        var j = open
        while (j < call.length) {
            if (call[j] == '{') depth++ else if (call[j] == '}') { depth--; if (depth == 0) break }
            j++
        }
        return call.substring(open, minOf(j + 1, call.length))
    }

    @Test fun noListDrawsArrowsAndEveryListShowsTheGrip() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("found the sources", sources.size > 100)
        val arrows = sources.filter { f -> f.readText().let { "R.string.move_up_2" in it || "R.string.move_down_2" in it } }
        assertEquals("files still drawing up / down arrows", emptyList<String>(), arrows.map { it.name })
        val lists = sources.filter { f -> f.name != "Reorder.kt" && Regex("""\bReorderList\(""").containsMatchIn(f.readText()) }
        // The lists §2.8 names, so a rename doesn't leave this checking nothing.
        val names = lists.map { it.name }.toSet()
        for (expected in listOf("CatalogueScreen.kt", "LibraryScreen.kt", "LocalMusicScreen.kt", "PlayerYourWay.kt", "TabsMiniPlayerPage.kt", "GesturesButtonsPage.kt", "LyricsSingParts.kt", "PlayerPage.kt", "SearchLanding.kt")) {
            assertTrue("$expected builds a ReorderList", expected in names)
        }
        val gripless = lists.filter { f -> f.readText().split(Regex("""\bReorderList\(""")).drop(1).any { call -> "dragHandle()" !in rowLambda(call) } }
        assertEquals("ReorderLists whose rows show no grip", emptyList<String>(), gripless.map { it.name })
        assertFalse("OrderRow takes no arrows", File("src/main/java/com/vinz/music/ui/screens/PlayerYourWay.kt").readText().contains("onUp"))
    }
}
