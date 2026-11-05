package com.vinz.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextTrackPreviewTest {
    @Test fun usesFirstServiceProvidedPlayOrderIndex() {
        assertEquals("third", firstUpcoming(listOf("first", "second", "third"), listOf(2, 0)))
    }

    @Test fun returnsNullForNoUpcomingItem() {
        assertNull(firstUpcoming(listOf("only"), emptyList()))
    }

    @Test fun returnsNullForStaleQueueIndex() {
        assertNull(firstUpcoming(listOf("only"), listOf(1)))
    }
}
