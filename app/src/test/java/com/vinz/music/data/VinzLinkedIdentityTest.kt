package com.vinz.music.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VinzLinkedIdentityTest {
    private val owner = "11111111-1111-4111-8111-111111111111"

    @Test fun `identity projection is owner scoped and contains no provider metadata`() {
        val user = JSONObject().put("id", owner).put("identities", JSONArray()
            .put(JSONObject().put("identity_id", "22222222-2222-4222-8222-222222222222")
                .put("user_id", owner).put("provider", "google").put("email", "google@example.test")
                .put("identity_data", JSONObject().put("sub", "private-provider-subject")))
            .put(JSONObject().put("identity_id", "33333333-3333-4333-8333-333333333333")
                .put("user_id", "44444444-4444-4444-8444-444444444444").put("provider", "github")))
        assertEquals(listOf(VinzLinkedIdentity(
            "22222222-2222-4222-8222-222222222222", "google", "google@example.test",
        )), VinzLinkedIdentityParser.parse(user, owner))
    }

    @Test fun `identity response for another user is rejected`() {
        val user = JSONObject().put("id", "44444444-4444-4444-8444-444444444444").put("identities", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { VinzLinkedIdentityParser.parse(user, owner) }
    }

    @Test fun `missing identity array fails closed`() {
        assertThrows(IllegalStateException::class.java) { VinzLinkedIdentityParser.parse(JSONObject().put("id", owner), owner) }
    }

    @Test fun `manual link request uses S256 and never includes the verifier`() {
        val pending = VinzOAuthPkce.create(1_000L)
        val path = VinzOAuthPkce.identityLinkAuthorizationPath("vinz://auth/callback", pending)
        assert(path.startsWith("/auth/v1/user/identities/authorize?"))
        assert(path.contains("provider=google"))
        assert(path.contains("code_challenge_method=s256"))
        assert(path.contains("skip_http_redirect=true"))
        assert(path.contains(VinzOAuthPkce.challenge(pending.verifier)))
        assertFalse(path.contains(pending.verifier))
    }
}
