package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.nuvio.app.core.storage.AppCacheDirectories
import com.nuvio.app.core.storage.StorageUsage
import java.io.File

internal actual object VideoStreamCache {

    private var applicationContext: Context? = null
    private var simpleCache: SimpleCache? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun directoryPath(): String = AppCacheDirectories.videoCache()

    actual fun sizeBytes(): Long = StorageUsage.directorySizeBytes(directoryPath())

    actual fun clear() {
        // SimpleCache holds an exclusive lock on its directory for the lifetime of the instance,
        // and a second instance over the same folder throws. Releasing before deleting is what
        // makes a clear-then-play-again cycle work rather than dying on the next open.
        runCatching { simpleCache?.release() }
        simpleCache = null
        StorageUsage.deleteDirectoryContents(directoryPath())
    }

    /**
     * The cache ExoPlayer should read and write through, or null when caching is off or
     * unavailable.
     *
     * Returning null rather than throwing is deliberate: a cache that cannot be opened — no
     * space, a locked directory, a corrupt index — must degrade to uncached playback. Losing the
     * cache costs the user a re-buffer; failing here would cost them the video.
     */
    fun acquire(maxSizeBytes: Long): Cache? {
        simpleCache?.let { return it }
        val context = applicationContext ?: return null

        return runCatching {
            SimpleCache(
                File(directoryPath()),
                LeastRecentlyUsedCacheEvictor(maxSizeBytes),
                StandaloneDatabaseProvider(context),
            )
        }.getOrNull()?.also { simpleCache = it }
    }
}
