package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.storage.AppCacheDirectories
import com.nuvio.app.core.storage.StorageUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.Path.Companion.toPath

/**
 * Owns the app's single Coil [ImageLoader] and the disk cache behind it.
 *
 * Coil's disk cache was previously left at its defaults, which put artwork in the platform cache
 * directory with a max size derived from free space. Both are wrong for this app: the directory
 * is reclaimable by the OS, so the library would go blank exactly when the user was offline and
 * could not refetch, and a size that floats with free space cannot be reported or reasoned about
 * in settings. Here the directory is explicit (see [AppCacheDirectories]) and the ceiling is a
 * fixed number the user can see and clear.
 */
object NuvioImageCache {

    /**
     * Roughly a few thousand posters. Large enough that a normal library survives eviction,
     * small enough to stay a defensible share of a phone's storage.
     */
    private const val MaxDiskSizeBytes = 512L * 1024L * 1024L

    private const val MaxConcurrentPrefetches = 4

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefetchLimiter = Semaphore(MaxConcurrentPrefetches)

    /**
     * URLs already handed to Coil. Prefetch is called on every library emission, and the library
     * changes far more often than its artwork does; without this the same few hundred requests
     * would be rebuilt on every save, rename or progress update.
     */
    private val requestedUrls = mutableSetOf<String>()
    private val requestedUrlsLock = Mutex()

    private var imageLoader: ImageLoader? = null
    private var platformContext: PlatformContext? = null

    fun build(context: PlatformContext): ImageLoader {
        platformContext = context
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            // The memory cache is deliberately left at Coil's default, which sizes itself as a
            // share of what the platform will give the app. Naming a fixed number here would be
            // worse on both platforms, and MemoryCache's percentage builder is Android-only.
            .diskCache {
                DiskCache.Builder()
                    .directory(AppCacheDirectories.images().toPath())
                    .maxSizeBytes(MaxDiskSizeBytes)
                    .build()
            }
            .components {
                add(SvgDecoder.Factory())
            }
            .configurePlatformImageLoader()
            .build()
            .also { imageLoader = it }
    }

    fun sizeBytes(): Long = StorageUsage.directorySizeBytes(AppCacheDirectories.images())

    fun clear() {
        val loader = imageLoader
        loader?.memoryCache?.clear()
        loader?.diskCache?.clear()
        // Coil only removes the entries it knows about. Anything left behind — a partial write,
        // a journal from a previous install — still occupies the space the user just asked to
        // reclaim, and the size they see afterwards has to be zero.
        StorageUsage.deleteDirectoryContents(AppCacheDirectories.images())
        prefetchScope.launch {
            requestedUrlsLock.withLock { requestedUrls.clear() }
        }
    }

    /**
     * Warms the disk cache for artwork the user has not scrolled to yet.
     *
     * Deliberately does not touch the memory cache: these images are being fetched for a future
     * offline session, not for the screen in front of the user, and letting them evict what is
     * actually on screen would trade a visible stutter for an invisible benefit.
     */
    fun prefetch(imageUrls: List<String>) {
        val loader = imageLoader ?: return
        val context = platformContext ?: return

        prefetchScope.launch {
            // Callers are a mix of composition effects and repository coroutines, so the
            // seen-set is only touched under the mutex rather than assuming one thread.
            val pending = requestedUrlsLock.withLock {
                imageUrls.filter { it.isNotBlank() && requestedUrls.add(it) }
            }

            pending.forEach { url ->
                launch {
                    prefetchLimiter.withPermit {
                        runCatching {
                            loader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .diskCacheKey(url)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                            )
                        }
                    }
                }
            }
        }
    }
}
