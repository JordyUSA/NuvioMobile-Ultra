package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe

/**
 * Inspects a source to discover its container and codecs.
 *
 * Failure is expected and survivable: a probe can fail on a stream that has not buffered
 * enough, or on an exotic container. Callers should fall back to a direct play attempt rather
 * than blocking playback, since the receiver may well cope.
 */
expect suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String> = emptyMap(),
): Result<CastMediaProbe>

/**
 * Best-effort container guess from a URL, used when a probe cannot identify the container
 * itself and to short-circuit obvious adaptive-streaming cases.
 */
fun containerFromUrl(url: String): CastContainer {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") -> CastContainer.HLS
        path.endsWith(".mpd") -> CastContainer.DASH
        path.endsWith(".mp4") || path.endsWith(".m4v") -> CastContainer.MP4
        path.endsWith(".webm") -> CastContainer.WEBM
        path.endsWith(".mkv") -> CastContainer.MATROSKA
        path.endsWith(".ts") || path.endsWith(".m2ts") -> CastContainer.MPEG_TS
        path.endsWith(".avi") -> CastContainer.AVI
        path.endsWith(".mov") -> CastContainer.QUICKTIME
        else -> CastContainer.UNKNOWN
    }
}

/** MIME type to advertise to the receiver for a given container. */
fun contentTypeFor(container: CastContainer): String = when (container) {
    CastContainer.MP4, CastContainer.QUICKTIME -> "video/mp4"
    CastContainer.WEBM -> "video/webm"
    CastContainer.HLS -> "application/x-mpegURL"
    CastContainer.DASH -> "application/dash+xml"
    CastContainer.MPEG_TS -> "video/mp2t"
    // Neither is playable by a receiver; the planner will have scheduled a repackage, so this
    // is only ever a placeholder for logging.
    CastContainer.MATROSKA -> "video/x-matroska"
    CastContainer.AVI -> "video/x-msvideo"
    CastContainer.UNKNOWN -> "video/mp4"
}
