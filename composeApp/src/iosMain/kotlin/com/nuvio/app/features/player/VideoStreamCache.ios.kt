package com.nuvio.app.features.player

import com.nuvio.app.core.storage.AppCacheDirectories
import com.nuvio.app.core.storage.StorageUsage

/**
 * iOS plays through mpv, which manages the on-disk cache itself once pointed at a directory
 * (`cache-on-disk` plus `cache-dir`). There is no handle to release here — mpv closes its own
 * files when playback ends — so clearing is just emptying the folder.
 */
internal actual object VideoStreamCache {

    actual fun directoryPath(): String = AppCacheDirectories.videoCache()

    actual fun sizeBytes(): Long = StorageUsage.directorySizeBytes(directoryPath())

    actual fun clear() {
        StorageUsage.deleteDirectoryContents(directoryPath())
    }
}
