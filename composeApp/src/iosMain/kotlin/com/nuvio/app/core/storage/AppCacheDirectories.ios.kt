package com.nuvio.app.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

@OptIn(ExperimentalForeignApi::class)
internal actual object AppCacheDirectories {
    actual fun images(): String {
        val path = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/NuvioImageCache"
        createDirectory(path)
        excludeFromBackup(path)
        return path
    }

    actual fun videoCache(): String {
        val path = "${NSHomeDirectory().trimEnd('/')}/Library/Caches/NuvioVideoCache"
        createDirectory(path)
        return path
    }

    private fun createDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    /**
     * Posters are refetchable, so iCloud should not carry them. Application Support is backed up
     * by default, and a few hundred megabytes of artwork in every device backup is not a cost the
     * user agreed to.
     */
    private fun excludeFromBackup(path: String) {
        runCatching {
            NSURL.fileURLWithPath(path).setResourceValue(
                value = true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            )
        }
    }
}
