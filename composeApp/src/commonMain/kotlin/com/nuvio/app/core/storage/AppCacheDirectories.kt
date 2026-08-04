package com.nuvio.app.core.storage

/**
 * Absolute paths for the two caches the user can see and clear from settings.
 *
 * The two live in deliberately different places. Posters back offline browsing, so they go
 * somewhere the OS will not reclaim on its own — reclaiming them is exactly the failure the
 * cache exists to prevent. Video is the opposite: it is scratch space that the app itself wipes
 * on player exit, app start and app close, so the platform cache directory is the right home and
 * an OS eviction is harmless.
 *
 * Both return a directory that has already been created.
 */
internal expect object AppCacheDirectories {
    fun images(): String

    fun videoCache(): String
}
