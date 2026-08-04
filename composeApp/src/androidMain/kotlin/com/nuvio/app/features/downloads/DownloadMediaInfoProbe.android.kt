package com.nuvio.app.features.downloads

import com.nuvio.app.features.cast.probeCastMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

internal actual suspend fun probeDownloadMediaInfo(
    localFileUri: String,
): DownloadMediaInfo? = withContext(Dispatchers.IO) {
    fromMediaInfo(localFileUri) ?: fromCastProber(localFileUri)
}

/**
 * MediaInfoLib needs a real filesystem path — it opens the file itself rather than going through
 * a ContentResolver — and completed downloads are stored as `file:` URIs, so this is only ever a
 * scheme strip. Anything else (a content:// URI from an imported file) returns null and the
 * MediaExtractor path takes over, which is exactly the right split.
 */
private fun fromMediaInfo(localFileUri: String): DownloadMediaInfo? {
    if (!MediaInfoNative.isAvailable) return null
    val path = localFileUri.toLocalFilePathOrNull() ?: return null
    val json = MediaInfoNative.informJson(path) ?: return null
    return parseMediaInfoJson(json)
}

private suspend fun fromCastProber(localFileUri: String): DownloadMediaInfo? =
    probeCastMedia(localFileUri).getOrNull()?.toDownloadMediaInfo()

private fun String.toLocalFilePathOrNull(): String? = runCatching {
    val file = if (startsWith("file:")) File(URI(this)) else File(this)
    file.takeIf { it.isFile && it.canRead() }?.absolutePath
}.getOrNull()
