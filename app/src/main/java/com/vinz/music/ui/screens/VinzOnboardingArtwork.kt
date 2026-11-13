package com.vinz.music.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the first-install artwork bundled as base64 text assets. This keeps onboarding imagery available
 * on a clean install even when the listener is offline; decoding happens off the main thread.
 */
@Composable
internal fun rememberVinzOnboardingArtwork(assetName: String): ImageBitmap? {
    val context = LocalContext.current
    val artwork = produceState<ImageBitmap?>(initialValue = null, assetName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val encoded = context.assets.open(assetName).bufferedReader().use { it.readText() }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return artwork.value
}
