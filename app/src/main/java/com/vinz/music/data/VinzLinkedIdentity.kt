package com.vinz.music.data

import org.json.JSONObject
import java.util.UUID

/** Minimal, account-owned projection of a Supabase Auth identity. Never contains provider tokens. */
data class VinzLinkedIdentity(val id: String, val provider: String, val email: String)

internal object VinzLinkedIdentityParser {
    private val providerPattern = Regex("^[a-z0-9_-]{1,32}$")

    fun parse(user: JSONObject, expectedUserId: String): List<VinzLinkedIdentity> {
        require(expectedUserId.isNotBlank() && user.optString("id") == expectedUserId) {
            "The connected identities belong to a different account."
        }
        val identities = user.optJSONArray("identities")
            ?: error("The connected identity response was incomplete.")
        val seen = HashSet<String>()
        return buildList {
            for (index in 0 until identities.length()) {
                val item = identities.optJSONObject(index) ?: continue
                val identityId = runCatching { UUID.fromString(item.optString("identity_id")).toString() }.getOrNull() ?: continue
                val ownerId = item.optString("user_id")
                val provider = item.optString("provider").lowercase()
                if (ownerId != expectedUserId || !providerPattern.matches(provider) || !seen.add(identityId)) continue
                val identityData = item.optJSONObject("identity_data")
                val email = item.optString("email").takeIf { it.isNotBlank() && it != "null" }
                    ?: identityData?.optString("email").orEmpty().takeUnless { it == "null" }.orEmpty()
                add(VinzLinkedIdentity(identityId, provider, email))
            }
        }.sortedWith(compareBy<VinzLinkedIdentity> { it.provider }.thenBy { it.email.lowercase() })
    }
}
