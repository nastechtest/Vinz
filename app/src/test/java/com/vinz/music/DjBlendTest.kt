package com.vinz.music

import com.vinz.music.playback.DjBlend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #502: with DJ mode on, the crossfade length was always worked out (Automatic), so a listener who set a fixed length
 * had it ignored. A fixed length is now the DJ's blends' length too; Automatic, and Crossfade off, stay automatic.
 */
class DjBlendTest {
    @Test fun aFixedLengthIsKeptInDjMode() {
        assertFalse("Crossfade on at a set length: the DJ blends at it", DjBlend.automatic(mixOn = true, mixAuto = false, dj = true))
        assertFalse("and the keys don't stretch or cut it", DjBlend.keysSetLength(dj = true, automatic = false))
    }

    @Test fun automaticAndOffStayAutomaticInDjMode() {
        assertTrue(DjBlend.automatic(mixOn = true, mixAuto = true, dj = true))
        // Crossfade off: DJ mode turns blending on, at the automatic length, whatever length was stored before.
        assertTrue(DjBlend.automatic(mixOn = false, mixAuto = false, dj = true))
        assertTrue(DjBlend.automatic(mixOn = false, mixAuto = true, dj = true))
        assertTrue("the keys set an automatic length", DjBlend.keysSetLength(dj = true, automatic = true))
    }

    @Test fun withoutDjModeItIsTheListenersOwn() {
        assertTrue(DjBlend.automatic(mixOn = true, mixAuto = true, dj = false))
        assertFalse(DjBlend.automatic(mixOn = true, mixAuto = false, dj = false))
        assertFalse("keys only count in DJ mode", DjBlend.keysSetLength(dj = false, automatic = true))
        assertFalse(DjBlend.keysSetLength(dj = false, automatic = false))
    }
}
