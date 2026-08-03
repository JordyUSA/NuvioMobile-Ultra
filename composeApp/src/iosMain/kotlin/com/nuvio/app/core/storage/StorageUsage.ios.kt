package com.nuvio.app.core.storage

import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

internal actual object StorageUsage {
    actual fun directorySizeBytes(path: String): Long {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return 0L

        val enumerator = manager.enumeratorAtPath(path) ?: return 0L
        var total = 0L
        while (true) {
            val relativePath = enumerator.nextObject() as? String ?: break
            val attributes = manager.attributesOfItemAtPath("$path/$relativePath", null) ?: continue
            val size = attributes[NSFileSize] as? NSNumber ?: continue
            total += size.longLongValue
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
}
