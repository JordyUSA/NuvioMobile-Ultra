package com.nuvio.app.features.downloads

import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.probeCastMedia

/**
 * Session-scoped, in-memory only. A completed download's original format never changes without a
 * new download replacing it, so there is nothing to invalidate — the cache is just here to stop
 * scrolling the list from re-probing the same file over and over.
 *
 * Deliberately not persisted: a probe is cheap (a `MediaMetadataRetriever`/`AVAsset` read of the
 * header, not a full-file scan) and this is advisory UI, not state anything else depends on.
 */
internal object DownloadFormatProbeCache {
    private val cache = mutableStateMapOf<String, CastMediaProbe?>()

    /** Null when unprobed, or when the probe was attempted and failed — both render as "no badge". */
    fun get(downloadId: String): CastMediaProbe? = cache[downloadId]

    fun isProbed(downloadId: String): Boolean = cache.containsKey(downloadId)

    suspend fun probe(downloadId: String, localFileUri: String) {
        if (cache.containsKey(downloadId)) return
        val result = probeCastMedia(localFileUri).getOrNull()
        cache[downloadId] = result
    }

    fun clear() {
        cache.clear()
    }
}

/** "1080p • MKV • 1.2 GB", built from a probe plus the size already known from the download. */
internal fun CastMediaProbe.originalFormatLabel(totalBytes: Long?): String {
    val parts = buildList {
        video?.height?.takeIf { it > 0 }?.let { add("${it}p") }
        add(container.shortLabel())
        totalBytes?.takeIf { it > 0L }?.let { add(formatDownloadBytes(it)) }
    }
    return parts.joinToString(" • ")
}

private fun CastContainer.shortLabel(): String = when (this) {
    CastContainer.MP4 -> "MP4"
    CastContainer.QUICKTIME -> "MOV"
    CastContainer.WEBM -> "WebM"
    CastContainer.MATROSKA -> "MKV"
    CastContainer.MPEG_TS -> "TS"
    CastContainer.AVI -> "AVI"
    CastContainer.HLS -> "HLS"
    CastContainer.DASH -> "DASH"
    CastContainer.UNKNOWN -> "Video"
}
