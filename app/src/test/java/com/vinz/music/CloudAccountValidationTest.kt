package com.vinz.music

import com.vinz.music.ui.screens.account.CloudAccountMode
import com.vinz.music.ui.screens.account.CloudAccountState
import com.vinz.music.ui.screens.account.UsernameAvailability
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

    @Test fun `locale account strings use translated entries or base fallback with matching format arguments`() {
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
        assertTrue(base.keys.containsAll(listOf(
            "cloud_account_email_hint", "cloud_account_password_sign_in_hint",
            "cloud_account_otp_title", "cloud_account_otp_intro", "cloud_account_otp_code",
            "cloud_account_otp_expiry_hint", "cloud_account_otp_verify", "cloud_account_otp_sent",
            "cloud_account_otp_resent", "cloud_account_otp_resend", "cloud_account_otp_resend_countdown",
            "cloud_account_otp_invalid_or_expired", "cloud_account_otp_rate_limited",
            "cloud_account_otp_verify_failed", "cloud_account_otp_resend_failed",
            "cloud_account_back_to_sign_in",
        )))
        val tables = res.listFiles().orEmpty().filter {
            it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) &&
                java.io.File(it, "strings.xml").isFile
        }.map { java.io.File(it, "cloud_account_strings.xml") }
        assertTrue(tables.isNotEmpty())
        val argument = Regex("%[0-9]+\\$[a-zA-Z]")
        tables.forEach { table ->
            assertTrue("Missing account table: ${table.path}", table.isFile)
            val translated = entries(table)
            val otherKeys = table.parentFile?.listFiles().orEmpty()
                .filter { it != table && it.extension == "xml" }.flatMap { entries(it).keys }.toSet()
            assertTrue("Duplicate account resources: ${table.path}", translated.keys.none { it in otherKeys })
            assertTrue(
                "${table.path}: unknown account resources ${translated.keys - base.keys}",
                base.keys.containsAll(translated.keys),
            )
            base.forEach { (key, value) ->
                // Android deliberately falls back to values/ for strings not translated in a locale pack.
                val resolved = translated[key] ?: value
                assertEquals("${table.path}:$key", argument.findAll(value).map { it.value }.toList(),
                    argument.findAll(resolved).map { it.value }.toList())
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
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
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
        val signup = base.copy(
            mode = CloudAccountMode.SIGN_UP,
            password = "password8",
            draft = CloudProfileDraft("artist_7", "Artist"),
        )
        assertFalse(CloudAccountValidation.canSubmit(signup))
        assertFalse(CloudAccountValidation.canSubmit(signup.copy(usernameAvailability = UsernameAvailability.CHECKING)))
        assertFalse(CloudAccountValidation.canSubmit(signup.copy(usernameAvailability = UsernameAvailability.UNAVAILABLE)))
        assertFalse(CloudAccountValidation.canSubmit(signup.copy(usernameAvailability = UsernameAvailability.ERROR)))
        assertTrue(CloudAccountValidation.canSubmit(signup.copy(usernameAvailability = UsernameAvailability.AVAILABLE)))
    }

    @Test fun `otp input keeps only six ASCII digits`() {
        assertEquals("123456", CloudAccountValidation.otp("12a3-45678"))
        assertEquals("", CloudAccountValidation.otp("１２３４５６"))
    }

    @Test fun `password reset requires a signed in recovery session and a strong enough password`() {
        val identity = com.vinz.music.ui.screens.account.CloudAccountIdentity("user", "user@example.test")
        val reset = CloudAccountState(mode = CloudAccountMode.RESET_PASSWORD, identity = identity, password = "short", confirmPassword = "short")
        assertFalse(CloudAccountValidation.canSubmit(reset))
        assertFalse(CloudAccountValidation.canSubmit(reset.copy(password = "new-password")))
        assertTrue(CloudAccountValidation.canSubmit(reset.copy(password = "new-password", confirmPassword = "new-password")))
        assertFalse(CloudAccountValidation.canSubmit(reset.copy(identity = null, password = "new-password", confirmPassword = "new-password")))
        assertFalse(CloudAccountValidation.canSubmit(reset.copy(password = "new-password", confirmPassword = "different-password")))
    }

    @Test fun `verification submit requires exactly six digits`() {
        val base = CloudAccountState(mode = CloudAccountMode.VERIFY_EMAIL, email = "user@example.test")
        assertFalse(CloudAccountValidation.canSubmit(base))
        assertFalse(CloudAccountValidation.canSubmit(base.copy(otp = "12345")))
        assertFalse(CloudAccountValidation.canSubmit(base.copy(otp = "1234567")))
        assertTrue(CloudAccountValidation.canSubmit(base.copy(otp = "123456")))
    }
}
