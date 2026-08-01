package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamSubtitle

internal data class DownloadPlatformRequest(
    val sourceUrl: String,
    val sourceHeaders: Map<String, String>,
    val destinationFileName: String,
)

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
    ): DownloadsTaskHandle

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun cacheSubtitleFiles(
        subtitles: List<StreamSubtitle>,
        companionBaseFileName: String,
    ): List<StreamSubtitle>

    fun openDownloadsDirectory(): Boolean

    /**
     * Absolute path of the directory downloads live in, or null before initialization.
     *
     * Added for the converter, which writes its output alongside the source. Exposed here rather
     * than duplicated because both platform downloaders already hardcode this path privately and a
     * third copy is a third place for it to drift.
     */
    fun downloadsDirectoryPath(): String?

    /** Renames within the downloads directory. Returns the new `file://` URI, or null on failure. */
    fun renameFile(fromLocalFileUri: String, toFileName: String): String?

    fun fileSizeBytes(localFileUri: String): Long?
}
