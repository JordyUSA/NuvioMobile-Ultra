package com.nuvio.app.core.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.URI

internal actual object FileShareBridge {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun shareFile(localFileUri: String, displayName: String): Boolean {
        val context = appContext ?: return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        if (!file.exists()) return false

        // Downloads live in filesDir, which no other app can read. FileProvider is what turns
        // that into a URI the receiving app is actually granted access to.
        val contentUri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull() ?: return false

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, displayName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    private fun String.toLocalFileOrNull(): File? = runCatching {
        if (startsWith("file:")) File(URI(this)) else File(this)
    }.getOrNull()
}
