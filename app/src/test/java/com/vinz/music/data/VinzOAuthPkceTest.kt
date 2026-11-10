package com.vinz.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinzOAuthPkceTest {
    @Test fun `challenge matches the RFC 7636 S256 example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            VinzOAuthPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun `authorization URL contains challenge but never the verifier`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val url = VinzOAuthPkce.authorizationUrl("https://project.supabase.co", "vinz://auth/callback", pending)
        assertTrue(url.contains("provider=google"))
        assertTrue(url.contains("code_challenge_method=s256"))
        assertTrue(url.contains(VinzOAuthPkce.challenge(pending.verifier)))
        assertFalse(url.contains(pending.verifier))
        assertTrue(url.contains("redirect_to="))
    }

    @Test fun `valid callback returns its one use code`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val callback = "vinz://auth/callback?state=${pending.state}&code=single-use-code"
        assertEquals(VinzOAuthPkce.Callback.Code("single-use-code"), VinzOAuthPkce.parseCallback(callback, pending, 2_000L))
    }

    @Test fun `callback with a different state is rejected`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val callback = "vinz://auth/callback?state=attacker&code=stolen"
        assertEquals(VinzOAuthPkce.Callback.InvalidState, VinzOAuthPkce.parseCallback(callback, pending, 2_000L))
    }

    @Test fun `callback outside the ten minute window is rejected`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val callback = "vinz://auth/callback?state=${pending.state}&code=old"
        assertEquals(VinzOAuthPkce.Callback.Expired, VinzOAuthPkce.parseCallback(callback, pending, 602_000L))
    }

    @Test fun `provider denial is surfaced only after state validation`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val callback = "vinz://auth/callback?state=${pending.state}&error=access_denied"
        assertEquals(VinzOAuthPkce.Callback.Denied("access_denied"), VinzOAuthPkce.parseCallback(callback, pending, 2_000L))
    }

    @Test fun `callback requires the exact app host and path`() {
        val pending = VinzOAuthPkce.create(1_000L)
        assertEquals(
            VinzOAuthPkce.Callback.Invalid,
            VinzOAuthPkce.parseCallback("vinz://other/callback?state=${pending.state}&code=x", pending, 2_000L),
        )
    }
}
