package com.vinz.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vinz.music.data.CloudAvatarImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CloudAvatarImageTest {
    private fun jpeg(width: Int = 800, height: Int = 400): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test fun jpegIsCenterCroppedAndReencodedAsSquareWebp() {
        val encoded = CloudAvatarImage.encode(jpeg(), "image/jpeg")
        assertEquals("image/webp", encoded.contentType)
        assertTrue(encoded.bytes.size in 1..CloudAvatarImage.MAX_UPLOAD_BYTES)
        assertEquals("RIFF", String(encoded.bytes.copyOfRange(0, 4)))
        assertEquals("WEBP", String(encoded.bytes.copyOfRange(8, 12)))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, bounds)
        assertEquals(512, bounds.outWidth)
        assertEquals(512, bounds.outHeight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDeclaredMimeTypeIsRejected() {
        CloudAvatarImage.encode(jpeg(), "image/png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptImageIsRejected() {
        CloudAvatarImage.encode(byteArrayOf(1, 2, 3), "image/jpeg")
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceAboveBoundedReadLimitIsRejected() {
        CloudAvatarImage.encode(ByteArray(CloudAvatarImage.MAX_SOURCE_BYTES + 1), null)
    }
}
