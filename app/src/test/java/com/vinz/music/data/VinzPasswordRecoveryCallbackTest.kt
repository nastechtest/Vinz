package com.vinz.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VinzPasswordRecoveryCallbackTest {
    @Test fun androidManifestRegistersTheExactRecoveryCallbackRoute() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals(true, manifest.contains(
            """android:scheme="vinz" android:host="auth" android:path="/reset-password"""",
        ))
    }

    @Test fun acceptsSupabaseImplicitSessionInFragment() {
        val callback = VinzPasswordRecoveryCallback.parse(
            "vinz://auth/reset-password#access_token=access.jwt.value&refresh_token=refresh-value&type=recovery&expires_in=1800",
        )
        assertNotNull(callback)
        assertEquals("access.jwt.value", callback?.accessToken)
        assertEquals("refresh-value", callback?.refreshToken)
        assertEquals(1_800L, callback?.expiresInSeconds)
    }

    @Test fun acceptsTokenHashFromCustomSupabaseEmailTemplate() {
        val callback = VinzPasswordRecoveryCallback.parse(
            "vinz://auth/reset-password?token_hash=opaque-hash&type=recovery",
        )
        assertNotNull(callback)
        assertEquals("opaque-hash", callback?.tokenHash)
    }

    @Test fun rejectsWrongRouteAndNonRecoveryAuthEvents() {
        assertNull(VinzPasswordRecoveryCallback.parse("vinz://auth/callback#access_token=a&refresh_token=b&type=recovery"))
        assertNull(VinzPasswordRecoveryCallback.parse("vinz://auth/reset-password#access_token=a&refresh_token=b&type=signup"))
        assertNull(VinzPasswordRecoveryCallback.parse("https://example.test/auth/reset-password#access_token=a&refresh_token=b&type=recovery"))
    }

    @Test fun rejectsIncompleteOrExpiredSessionPayloads() {
        assertNull(VinzPasswordRecoveryCallback.parse("vinz://auth/reset-password#access_token=a&type=recovery"))
        assertNull(VinzPasswordRecoveryCallback.parse("vinz://auth/reset-password#access_token=a&refresh_token=b&type=recovery&expires_in=0"))
        assertNull(VinzPasswordRecoveryCallback.parse("vinz://auth/reset-password?type=recovery"))
    }
}
