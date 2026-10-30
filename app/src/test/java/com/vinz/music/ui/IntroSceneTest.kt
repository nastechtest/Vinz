package com.vinz.music.ui.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSceneTest {
    @Test fun theBeatsRunInOrder() {
        val beats = listOf(
            Intro.SPARK, Intro.PLANET, Intro.PULL_FROM, Intro.ORBITS, Intro.PULL_TO,
            Intro.WORD, Intro.SWEEP, Intro.POWERED, Intro.LINE_ONE, Intro.LINE_TWO,
            Intro.LINE_THREE, Intro.DOCK, Intro.STILL_AT,
        )
        beats.zipWithNext().forEach { (a, b) -> assertTrue("$a should come before $b", a < b) }
    }

    @Test fun theCameraPullsThePlanetOffTheFrame() {
        val min = 1080f
        assertTrue(introPlanetRadius(Intro.PULL_FROM, min) > min * 1.05f)
        assertTrue(introPlanetRadius(Intro.PULL_TO, min) < min * 0.05f)
        var last = Float.MAX_VALUE
        var t = Intro.PULL_FROM
        while (t <= Intro.PULL_TO) {
            val r = introPlanetRadius(t, min)
            assertTrue("the pull must not grow the planet at $t", r <= last + 0.001f)
            last = r
            t += 0.05f
        }
    }

    @Test fun theSkyIsTheSameOnEveryInstall() {
        val a = introStars()
        val b = introStars()
        assertEquals(924, a.size)
        a.zip(b).forEach { (x, y) ->
            assertEquals(x.angle, y.angle, 0f)
            assertEquals(x.start, y.start, 0f)
            assertEquals(x.collectAt, y.collectAt, 0f)
        }
        assertNotEquals(a.first().angle, a.last().angle, 0f)
    }

    @Test fun everyStarHasItsOwnTurnInTheHold() {
        introStars().forEach { star -> assertTrue("a star waits past the ring", star.collectAt in 0f..0.80f) }
        val stars = introStars()
        assertTrue(stars.count { it.collectAt < 0.2f } > 50)
        assertTrue(stars.count { it.collectAt > 0.6f } > 50)
    }

    @Test fun theHoldEmptiesTheSky() {
        val stars = introStars()
        assertTrue(stars.all { it.collected(0f) == 0f })
        assertTrue(stars.count { it.collected(0.5f) > 0f } > stars.size / 3)
        assertTrue(stars.all { it.collected(1f) >= 1f })
    }

    @Test fun starsFadeInAndOutOfTheirOwnTravel() {
        // A star is invisible at the middle of the sky and again at its edge, so the wrap-around is never seen.
        fun skyAt(start: Float) = Star(0f, start, 0f, 0f, 1f, 1f, 0f, 0f, 1, 0f)
        assertEquals(0f, skyAt(0f).life(0f), 0.001f)
        assertTrue(skyAt(0.5f).life(0f) > 0.95f)
        assertTrue(skyAt(0.95f).life(0f) < 0.2f)
    }

    @Test fun theDockSitsAtTheBottomOfTheFrame() {
        val centre = dockCentre(Size(1080f, 2160f), dockRadius = 240f, inset = 260f)
        assertEquals(Offset(540f, 2160f - 260f - 240f), centre)
    }
}
