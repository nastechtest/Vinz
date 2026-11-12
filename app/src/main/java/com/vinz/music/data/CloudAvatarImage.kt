package com.vinz.music.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads and sanitizes a selected profile image before it is sent to Supabase Storage. */
internal object CloudAvatarImage {
    const val MAX_SOURCE_BYTES = 12 * 1024 * 1024
    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
    private const val OUTPUT_EDGE = 512
    private const val MAX_DECODE_EDGE = 2048
    private const val MAX_SOURCE_DIMENSION = 12_000
    private const val MAX_SOURCE_PIXELS = 80_000_000L

    data class Encoded(val bytes: ByteArray, val contentType: String)

    suspend fun fromUri(resolver: ContentResolver, uri: Uri): Encoded = withContext(Dispatchers.IO) {
        val declaredType = resolver.getType(uri)?.lowercase()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_SOURCE_BYTES) { "Selected image is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Selected image could not be opened.")
        encode(bytes, declaredType)
    }

    fun encode(source: ByteArray, declaredType: String? = null): Encoded {
        require(source.isNotEmpty() && source.size <= MAX_SOURCE_BYTES) { "Selected image is too large or empty." }
        val detectedType = detectType(source)
        require(detectedType != null) { "Choose a JPEG, PNG or WebP image." }
        require(declaredType == null || declaredType == detectedType) { "Image content does not match its declared type." }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width > 0 && height > 0) { "Selected image is not a readable bitmap." }
        require(width <= MAX_SOURCE_DIMENSION && height <= MAX_SOURCE_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS) { "Selected image dimensions are too large." }

        var sample = 1
        while (maxOf(width, height) / sample > MAX_DECODE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            source, 0, source.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
        require(decoded != null) { "Selected image could not be decoded." }

        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        if (square !== decoded) decoded.recycle()
        val outputBitmap = Bitmap.createScaledBitmap(square, OUTPUT_EDGE, OUTPUT_EDGE, true)
        if (outputBitmap !== square) square.recycle()

        val output = ByteArrayOutputStream()
        val compressed = try {
            @Suppress("DEPRECATION")
            outputBitmap.compress(Bitmap.CompressFormat.WEBP, 86, output)
        } finally {
            outputBitmap.recycle()
        }
        require(compressed) { "Selected image could not be encoded." }
        val encoded = output.toByteArray()
        require(encoded.isNotEmpty() && encoded.size <= MAX_UPLOAD_BYTES) { "Sanitized avatar exceeds the storage limit." }
        return Encoded(encoded, "image/webp")
    }

    private fun detectType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
        else -> null
    }
}
