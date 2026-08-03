package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/**
 * Artwork loaded with an explicit, stable disk cache key.
 *
 * Passing a bare URL string to [AsyncImage] rebuilds an `ImageRequest` on every recomposition and
 * leaves the cache keys implicit. Both matter here: poster grids recompose constantly while
 * scrolling, and the disk key is what decides whether an image survives to the next launch. This
 * is the same shape as `CollectionCardRemoteImage` on Android, generalised so every poster gets it.
 */
@Composable
fun NuvioAsyncImage(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalPlatformContext.current
    val request = remember(context, imageUrl) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey(imageUrl)
            .diskCacheKey(imageUrl)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
