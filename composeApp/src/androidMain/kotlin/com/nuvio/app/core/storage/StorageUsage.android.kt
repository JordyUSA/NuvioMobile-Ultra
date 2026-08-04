package com.nuvio.app.core.storage

import java.io.File

internal actual object StorageUsage {
    actual fun directorySizeBytes(path: String): Long {
        val root = File(path)
        if (!root.exists()) return 0L
        return runCatching {
            root.walkBottomUp()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    actual fun deleteDirectoryContents(path: String) {
        val root = File(path)
        if (!root.exists()) return
        runCatching {
            root.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }
}
