package com.vinz.music

import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudAccountState
import com.vinz.music.ui.screens.account.CloudAccountValidation
import com.vinz.music.ui.screens.account.CloudProfileDraft
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountValidationTest {
    @Test fun `onboarding continuation requires a loaded signed in cloud identity`() {
        val identity = com.vinz.music.ui.screens.account.CloudAccountIdentity("user", "user@example.test")
        val signedIn = CloudAccountState(identity = identity, profileReady = true)
        assertTrue(signedIn.canContinue)
        assertFalse(CloudAccountState().canContinue)
        assertFalse(signedIn.copy(busy = true).canContinue)
        assertFalse(signedIn.copy(profileReady = false).canContinue)
        assertFalse(signedIn.copy(profileLoadFailed = true).canContinue)
    }

    @Test fun `all bundled locales have matching account resource keys and format arguments`() {
        val res = java.io.File("src/main/res")
        fun entries(file: java.io.File): Map<String, String> {
            val root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = root.getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as org.w3c.dom.Element
                element.getAttribute("name") to element.textContent
            }
        }
        val base = entries(java.io.File(res, "values/cloud_account_strings.xml"))
        assertTrue(base.keys.containsAll(listOf("cloud_account_email_hint", "cloud_account_password_sign_in_hint")))
        val tables = res.listFiles().orEmpty().filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) &&
                java.io.File(it, "strings.xml").isFile
        }.map { java.io.File(it, "cloud_account_strings.xml") }
        assertTrue(tables.isNotEmpty())
        val argument = Regex("%[0-9]+\\$[a-zA-Z]")
        tables.forEach { table ->
            assertTrue("Missing account table: ${table.path}", table.isFile)
            val translated = entries(table)
            val otherKeys = table.parentFile.listFiles().orEmpty()
                .filter { it != table && it.extension == "xml" }.flatMap { entries(it).keys }.toSet()
            assertTrue("Duplicate account resources: ${table.path}", translated.keys.none { it in otherKeys })
            // These new strings are not translated everywhere yet; Android falls back to values/.
            val fallbackAllowed = setOf(
                "cloud_account_brand_name", "cloud_account_brand_section", "cloud_account_brand_tagline",
                "cloud_account_confirm_password", "cloud_account_passwords_do_not_match",
                "cloud_account_sign_in_cta", "cloud_account_sign_up_failed",
                "cloud_account_youtube_connect", "cloud_account_youtube_connected",
                "cloud_account_youtube_description", "cloud_account_youtube_disconnect",
                "cloud_account_youtube_failed", "cloud_account_youtube_import",
                "cloud_account_youtube_imported", "cloud_account_youtube_not_connected",
                "cloud_account_youtube_refresh", "cloud_account_youtube_title",
                "connect_youtube_for_playback", "optional_playback_session_not_vinz_identity",
                "youtube_playback_session",
            )
            assertTrue("Unknown account resources: ${table.path}", translated.keys.all { it in base.keys })
            assertTrue("Missing required account resources: ${table.path}",
                translated.keys.containsAll(base.keys - fallbackAllowed))
            translated.forEach { (key, value) ->
                // Indexed arguments can be reordered to suit the translated language.
                assertEquals("${table.path}:$key", argument.findAll(base.getValue(key)).map { it.value }.sorted().toList(),
                    argument.findAll(value).map { it.value }.sorted().toList())
            }
        }
    }

    @Test fun `username is ASCII lowercase and removes unsupported characters`() {
        assertEquals("artist_42", CloudAccountValidation.username("ARTIST_42"))
        assertEquals("strae_name", CloudAccountValidation.username("Straße_Name!"))
        assertEquals("", CloudAccountValidation.username("東京-😊"))
    }

    @Test fun `username lowercasing is stable under Turkish locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("istanbul_9", CloudAccountValidation.username("ISTANBUL_9"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `username is capped at twenty four allowed characters`() {
        assertEquals("a".repeat(24), CloudAccountValidation.username("A".repeat(30)))
    }

    @Test fun `email validation trims outer whitespace but rejects internal whitespace`() {
        assertTrue(CloudAccountValidation.validEmail("  user@example.test  "))
        assertFalse(CloudAccountValidation.validEmail("user @example.test"))
        assertFalse(CloudAccountValidation.validEmail("user@example .test"))
    }

    @Test fun `email validation requires one non terminal at sign and maximum length`() {
        assertFalse(CloudAccountValidation.validEmail("example.test"))
        assertFalse(CloudAccountValidation.validEmail("@example.test"))
        assertFalse(CloudAccountValidation.validEmail("user@"))
        assertFalse(CloudAccountValidation.validEmail("one@two@example.test"))
        assertFalse(CloudAccountValidation.validEmail("a".repeat(245) + "@example.test"))
    }

    @Test fun `profile requires username and trimmed display name within limits`() {
        assertFalse(CloudAccountValidation.validProfile(CloudProfileDraft(username = "ab", displayName = "Artist")))
        assertFalse(CloudAccountValidation.validProfile(CloudProfileDraft(username = "artist_7", displayName = "   ")))
        assertTrue(CloudAccountValidation.validProfile(CloudProfileDraft(username = "artist_7", displayName = " Artist ")))
        assertFalse(CloudAccountValidation.validProfile(CloudProfileDraft(username = "artist-7", displayName = "Artist")))
    }

    @Test fun `profile accepts exact bio and display name limits only`() {
        assertTrue(CloudAccountValidation.validProfile(CloudProfileDraft("artist_7", "d".repeat(60), "b".repeat(160))))
        assertFalse(CloudAccountValidation.validProfile(CloudProfileDraft("artist_7", "d".repeat(61), "")))
        assertFalse(CloudAccountValidation.validProfile(CloudProfileDraft("artist_7", "Artist", "b".repeat(161))))
    }

    @Test fun `submit requirements vary by mode and require fields`() {
        val base = CloudAccountState(email = "user@example.test")
        assertFalse(CloudAccountValidation.canSubmit(base))
        assertTrue(CloudAccountValidation.canSubmit(base.copy(mode = CloudAccountMode.RECOVERY)))
        assertTrue(CloudAccountValidation.canSubmit(base.copy(password = "one")))
        assertFalse(CloudAccountValidation.canSubmit(base.copy(mode = CloudAccountMode.SIGN_UP, password = "short")))
        assertFalse(CloudAccountValidation.canSubmit(base.copy(mode = CloudAccountMode.SIGN_UP, password = "password8")))
        assertTrue(CloudAccountValidation.canSubmit(base.copy(
            mode = CloudAccountMode.SIGN_UP,
            password = "password8",
            draft = CloudProfileDraft("artist_7", "Artist"),
            usernameAvailability = com.vinz.music.ui.screens.account.UsernameAvailability.AVAILABLE,
        )))
    }
}
