package com.vinz.music

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * The tab [name] in the tab bar (or the rail on a wide window), and only there: a page can show a clickable of the same
 * name (Settings → Recommendations & history has a "Replay" jump chip), and an unscoped matcher tapped that instead.
 */
fun navTab(name: String): SemanticsMatcher =
    (hasText(name) or hasContentDescription(name)) and hasClickAction() and hasAnyAncestor(hasTestTag(NAV_TABS_TAG))

/** Waits for the tab [name] (the app may still be starting) and taps it. */
fun ComposeTestRule.tapTab(name: String, timeoutMs: Long = 15_000) {
    waitUntil(timeoutMs) { onAllNodes(navTab(name)).fetchSemanticsNodes().isNotEmpty() }
    clickWhenFound(navTab(name))
}

/** Waits until the tab bar shows the tab [name]: the app is up. */
fun ComposeTestRule.waitForTab(name: String, timeoutMs: Long = 20_000) =
    waitUntil(timeoutMs) { onAllNodes(navTab(name)).fetchSemanticsNodes().isNotEmpty() }
