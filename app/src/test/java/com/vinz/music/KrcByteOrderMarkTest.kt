package com.vinz.music

import com.vinz.music.data.lyrics.WordFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Regression for KuGou KRC files containing a byte-order mark (Prism #403). */
class KrcByteOrderMarkTest {
    private fun krc(text: String): String {
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val zipped = out.toByteArray()
        val xored = ByteArray(zipped.size) { i -> (zipped[i].toInt() xor key[i % 16]).toByte() }
        return java.util.Base64.getEncoder().encodeToString("krc1".toByteArray(Charsets.US_ASCII) + xored)
    }

    @Test
    fun aByteOrderMarkIsLeftOut() {
        val text = "\uFEFF[0,1000]<0,500,0>You <500,500,0>want"
        val decoded = WordFormat.decodeKrc(krc(text))
        assertFalse("no byte-order mark: $decoded", decoded!!.contains('\uFEFF'))
        assertEquals("[0,1000]<0,500,0>You <500,500,0>want", decoded)
    }
}
