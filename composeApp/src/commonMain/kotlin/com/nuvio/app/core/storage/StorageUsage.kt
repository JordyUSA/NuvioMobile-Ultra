package com.nuvio.app.core.storage

/**
 * Directory-level disk accounting, used by the cache controls in settings.
 *
 * Both operations are best effort: a directory that does not exist reports zero bytes rather
 * than failing, and a file that cannot be removed is skipped instead of aborting the sweep.
 * Cache clearing is a convenience, not a correctness requirement — leaving one locked file
 * behind is a far better outcome than surfacing an error the user cannot act on.
 */
internal expect object StorageUsage {
    fun directorySizeBytes(path: String): Long

    fun deleteDirectoryContents(path: String)
}
