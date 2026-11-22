package com.vinz.music

import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Why the TV's in-app tests failed with "multithreaded access to SnapshotStateObserver" and "Only the original thread
 * that created a view hierarchy can touch its views" (1.8.0): the test rules from androidx.compose.ui.test.junit4 run
 * the composition's coroutines on an UnconfinedTestDispatcher, so an effect that does some work in the background
 * (withContext(Dispatchers.Default), as Vinz's pages do) carries on on that background thread, and the composition's
 * next frame with it. The v2 rules run them on a StandardTestDispatcher, which the test clock runs on the main thread:
 * what the app itself does (its effects always come back to the main thread). The TV's in-app tests that wait only
 * through the rule (Ambient, Browse, Lyrics, Shell, Sing) use the v2 rules; the ones that also wait on the player by
 * sleeping (Link, Playback, Video, Home customize) keep the old ones: with v2, an effect runs only when the test waits
 * through the rule. So does Settings: there Home's first focus, a moment late under v2, took focus back from the side
 * navigation now and then on the slow TV. LockScreenTest uses them too (1.8.5): see the flow tests below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeTestThreadTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    @Test fun theOldRulesCarryOnOnTheBackgroundThread() {
        val main = Thread.currentThread()
        var after: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { withContext(Dispatchers.Default) { Thread.sleep(50) }; after = Thread.currentThread() } }
        compose.waitUntil(5_000) { after != null }
        assertTrue("carried on on ${after?.name}", after !== main)
    }

    /**
     * 1.8.5: what LockScreenTest ran into. Its page reads the settings with collectAsState, and a setting the test changes
     * reaches that flow on DataStore's background thread. Under the old rules the collector (as collectAsState's own: a
     * LaunchedEffect that collects into a state) carries on on that thread, so the state is written there, and the page's
     * next frame is now and then composed and applied there too, while the main thread draws it. On the emulator that
     * attached the Page's statusBarsPadding off the main thread (CalledFromWrongThreadException in its onAttach), and
     * closing the page later crashed with "Must run runDetachLifecycle() once after runAttachLifecycle()".
     */
    @Test fun theOldRulesTakeAFlowsValueOnTheThreadThatSetIt() {
        val main = Thread.currentThread()
        val flow = kotlinx.coroutines.flow.MutableStateFlow(0)
        var tookOn: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { flow.collect { if (it == 1) tookOn = Thread.currentThread() } } }
        compose.waitForIdle()
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.Default) { flow.value = 1 } }
        compose.waitUntil(5_000) { tookOn != null }
        assertTrue("took it on ${tookOn?.name}", tookOn !== main)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeTestThreadV2Test {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createComposeRule()

    @Test fun theV2RulesComeBackToTheMainThread() {
        val main = Thread.currentThread()
        var after: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { withContext(Dispatchers.Default) { Thread.sleep(50) }; after = Thread.currentThread() } }
        compose.waitUntil(5_000) { after != null }
        assertEquals(main, after)
    }

    /** The same under the v2 rules: the value is taken on the main thread, as in the app (LockScreenTest uses them). */
    @Test fun theV2RulesTakeAFlowsValueOnTheMainThread() {
        val main = Thread.currentThread()
        val flow = kotlinx.coroutines.flow.MutableStateFlow(0)
        var tookOn: Thread? = null
        compose.setContent { LaunchedEffect(Unit) { flow.collect { if (it == 1) tookOn = Thread.currentThread() } } }
        compose.waitForIdle()
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.Default) { flow.value = 1 } }
        compose.waitUntil(5_000) { tookOn != null }
        assertEquals(main, tookOn)
    }
}
