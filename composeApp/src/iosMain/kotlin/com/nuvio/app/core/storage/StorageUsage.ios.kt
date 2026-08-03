package com.nuvio.app.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
internal actual object StorageUsage {
    actual fun directorySizeBytes(path: String): Long {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return 0L

        val enumerator = manager.enumeratorAtPath(path) ?: return 0L
        var total = 0L
        while (true) {
            val relativePath = enumerator.nextObject() as? String ?: break
            val attributes = manager.attributesOfItemAtPath("$path/$relativePath", null) ?: continue
            total += attributes[NSFileSize].toFileSizeBytes()
        }
        return total
    }

    actual fun deleteDirectoryContents(path: String) {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return

        val children = manager.contentsOfDirectoryAtPath(path, null) ?: return
        children.forEach { child ->
            val name = child as? String ?: return@forEach
            manager.removeItemAtPath("$path/$name", null)
        }
    }

    /**
     * Kotlin/Native bridges NSNumber to a Kotlin number when it comes out of a collection, but
     * not in every case, so both shapes are accepted. Reading only one of them would leave the
     * cache size silently reporting zero rather than failing visibly.
     */
    private fun Any?.toFileSizeBytes(): Long = when (this) {
        is Number -> toLong()
        is NSNumber -> longLongValue
        else -> 0L
    }
}
