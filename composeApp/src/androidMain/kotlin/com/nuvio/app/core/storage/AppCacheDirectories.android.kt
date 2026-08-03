package com.nuvio.app.core.storage

import android.content.Context
import java.io.File

internal actual object AppCacheDirectories {
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    actual fun images(): String = directory { context ->
        // filesDir, not cacheDir: Android clears cacheDir under storage pressure, which would
        // empty the library right when the user is offline and cannot refetch.
        File(context.filesDir, "image_cache")
    }

    actual fun videoCache(): String = directory { context ->
        File(context.cacheDir, "video_cache")
    }

    private fun directory(resolve: (Context) -> File): String {
        val context = applicationContext ?: return ""
        val directory = resolve(context)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory.absolutePath
    }
}
