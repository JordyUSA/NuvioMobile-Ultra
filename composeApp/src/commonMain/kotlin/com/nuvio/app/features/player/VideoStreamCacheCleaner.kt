package com.nuvio.app.features.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fire-and-forget wrapper around [VideoStreamCache.clear].
 *
 * Clearing deletes files, and the callers that need it most — leaving the player, backgrounding
 * the app, launching — are all on the main thread at a moment the user is watching an animation.
 * A few gigabytes of deletes there is a visible stall.
 *
 * The mutex matters as much as the dispatcher: exiting the player straight into the background
 * fires two clears within a frame of each other, and on Android the first one releases the
 * SimpleCache the second would otherwise be reading.
 */
internal object VideoStreamCacheCleaner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    fun clearAsync() {
        scope.launch {
            lock.withLock { VideoStreamCache.clear() }
        }
    }
}
