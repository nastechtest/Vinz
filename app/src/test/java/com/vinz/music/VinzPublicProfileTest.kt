package com.vinz.music

import com.vinz.music.data.VinzPublicProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class VinzPublicProfileTest {
    @Test fun normalizesOnlyValidNonReservedUsernames() {
        assertEquals("mix_listener_7", VinzPublicProfile.normalizeUsername("  MIX_Listener_7 "))
        assertNull(VinzPublicProfile.normalizeUsername("two words"))
        assertNull(VinzPublicProfile.normalizeUsername("admin"))
        assertNull(VinzPublicProfile.normalizeUsername("ab"))
        assertNull(VinzPublicProfile.normalizeUsername("name.with.dot"))
    }

    @Test fun privateRowsAreNeverRendered() {
        val row = JSONObject().apply {
            put("username", "private_listener")
            put("display_name", "Private listener")
            put("bio", "Not public")
            put("is_public", false)
        }
        assertNull(VinzPublicProfile.fromJson(row))
    }

    @Test fun publicProjectionContainsOnlyPublicProfileFields() {
        val row = JSONObject().apply {
            put("username", "vinz_listener")
            put("display_name", "Vinz Listener")
            put("bio", "Music fan")
            put("avatar_path", "123e4567-e89b-12d3-a456-426614174000/123e4567-e89b-12d3-a456-426614174001.webp")
            put("is_public", true)
            put("full_name", "Must never be copied")
            put("email", "private@example.invalid")
        }
        val profile = VinzPublicProfile.fromJson(row)
        assertNotNull(profile)
        assertEquals("vinz_listener", profile!!.username)
        assertEquals("Vinz Listener", profile.displayName)
        assertEquals("Music fan", profile.bio)
        assertNotNull(profile.avatarPath)
    }

    @Test fun unsafeAvatarPathsAreDiscarded() {
        val row = JSONObject().apply {
            put("username", "safe_listener")
            put("display_name", "Safe listener")
            put("bio", "")
            put("avatar_path", "https://attacker.invalid/avatar.webp")
            put("is_public", true)
        }
        assertNull(VinzPublicProfile.fromJson(row)?.avatarPath)
    }

    @Test fun appUriUsesOnlyNormalizedUsername() {
        assertEquals("vinz://profile/music_fan", VinzPublicProfile.appUri("Music_Fan"))
        assertNull(VinzPublicProfile.appUri("not/a/username"))
    }
}
