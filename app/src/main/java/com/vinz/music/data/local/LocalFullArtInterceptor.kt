package com.vinz.music.data.local

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.size.Dimension

/**
 * #520: a local album's cover on a large view (the player, the album page) comes from the album's own picture
 * ([LocalMusic.fullArt]), decoded at the view's size like any other picture; lists keep Android's small album art.
 */
class LocalFullArtInterceptor(private val local: () -> LocalMusic?) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val album = LocalMusic.fullArtAlbum(
            url, (chain.size.width as? Dimension.Pixels)?.px, (chain.size.height as? Dimension.Pixels)?.px,
        ) ?: return chain.proceed()
        val file = local()?.fullArt(album) ?: return chain.proceed()
        val full = chain.withRequest(chain.request.newBuilder().data(file).build()).proceed()
        return if (full is ErrorResult) chain.proceed() else full
    }
}
