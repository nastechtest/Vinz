package com.vinz.music

import com.vinz.music.data.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract between the release pipeline and the in-app updater (data/Updates.kt).
 *
 * The workflows produce three shapes of version string — `1.7.7.1` (stable), `1.7.7.1-main.15` (a main
 * build) and `1.7.7.1-pr42.3` (a pull request build) — while GitHub writes the release tag as `v1.7.8`,
 * which the updater reads back after stripping the leading "v".
 *
 * If either side drifts, an installed Vinz either stops offering updates or offers a downgrade, and
 * neither failure is visible in CI: the app only learns about releases at runtime. These cases make the
 * consequences explicit.
 */
class ReleaseVersionTest {

    @Test
    fun `a stable release is newer than the main build it was cut from`() {
        assertTrue(UpdateChecker.isNewer("1.7.8", "1.7.7.1"))
        assertTrue(UpdateChecker.isNewer("1.7.8", "1.7.7.1-main.15"))
    }

    @Test
    fun `a pull request build is older than the release it precedes`() {
        assertTrue(UpdateChecker.isNewer("1.7.8", "1.7.7.1-pr42.3"))
    }

    @Test
    fun `the same version is never offered again`() {
        assertFalse(UpdateChecker.isNewer("1.7.8", "1.7.8"))
        assertFalse(UpdateChecker.isNewer("1.7.7.1", "1.7.7.1-pr42.3"))
        // Someone testing a main build of the version about to ship should not be told to "update" to it.
        assertFalse(UpdateChecker.isNewer("1.7.8", "1.7.8-main.3"))
    }

    @Test
    fun `versions compare numerically, not as text`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `a fourth component is honoured`() {
        assertTrue(UpdateChecker.isNewer("1.7.7.2", "1.7.7.1"))
        assertFalse(UpdateChecker.isNewer("1.7.7.1", "1.7.7.2"))
    }

    @Test
    fun `the release tag maps to a version the updater understands`() {
        // fetch() reads tag_name from /releases/latest and strips a leading "v".
        assertEquals("1.7.8", "v1.7.8".removePrefix("v").trim())
        assertTrue(UpdateChecker.isNewer("v1.7.8".removePrefix("v"), "1.7.7.1-main.15"))
    }

    @Test
    fun `a prerelease tag sorts below the release it precedes`() {
        // The rolling "Main — Latest" prerelease is never returned by /releases/latest, but keep the
        // comparison honest in case it ever is.
        assertFalse(UpdateChecker.isNewer("1.7.8-main.15", "1.7.8"))
    }
}
