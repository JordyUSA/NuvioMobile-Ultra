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

/**
 * Whether an HLS playlist describes a live stream rather than a finished recording.
 *
 * A media playlist ending in `#EXT-X-ENDLIST` is complete, and `#EXT-X-PLAYLIST-TYPE:VOD` says
 * the same thing up front; anything else is still being written to. Getting this wrong in the
 * live direction is expensive — a Cast receiver given `STREAM_TYPE_LIVE` hides its scrubber
 * entirely, so a VOD stream misreported as live cannot be seeked at all.
 */
fun hlsPlaylistIsLive(playlist: String): Boolean {
    val text = playlist.uppercase()
    if (text.contains("#EXT-X-ENDLIST")) return false
    if (text.contains("#EXT-X-PLAYLIST-TYPE:VOD")) return false
    return true
}

/** True when this is a master playlist, whose variants have to be followed to learn anything. */
fun hlsIsMasterPlaylist(playlist: String): Boolean =
    playlist.uppercase().contains("#EXT-X-STREAM-INF")

/** The first variant URL in a master playlist, absolute against [baseUrl]. */
fun hlsFirstVariantUrl(playlist: String, baseUrl: String): String? {
    val lines = playlist.lines()
    val marker = lines.indexOfFirst { it.trimStart().uppercase().startsWith("#EXT-X-STREAM-INF") }
    if (marker < 0) return null
    val variant = lines.drop(marker + 1)
        .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
        ?.trim()
        ?: return null
    return resolveStreamUrl(baseUrl, variant)
}

/**
 * Whether a DASH manifest is live. `type="dynamic"` is the segment-timeline-still-growing case;
 * `static`, and the absence of the attribute, both mean a complete presentation.
 */
fun dashManifestIsLive(manifest: String): Boolean =
    Regex("""type\s*=\s*["']dynamic["']""", RegexOption.IGNORE_CASE).containsMatchIn(manifest)

/**
 * Resolves a possibly-relative playlist reference against the manifest it came from.
 *
 * Deliberately separate from the DLNA package's equivalent: the two describe different
 * protocols and coupling them would make an HLS change reach into UPnP parsing.
 */
internal fun resolveStreamUrl(baseUrl: String, reference: String): String {
    if (reference.startsWith("http://", ignoreCase = true) || reference.startsWith("https://", ignoreCase = true)) {
        return reference
    }
    // Stripped up front: a token on the master's URL is not part of the path its variants sit
    // under, and leaving it in would otherwise leak into the origin for a hostname-only base.
    val withoutQuery = baseUrl.substringBefore('?').substringBefore('#')
    val schemeEnd = withoutQuery.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val authorityEnd = withoutQuery.indexOf('/', schemeEnd).let { if (it < 0) withoutQuery.length else it }
    val origin = withoutQuery.substring(0, authorityEnd)
    if (reference.startsWith("/")) return origin + reference
    val basePath = withoutQuery.substring(authorityEnd).substringBeforeLast('/', "")
    return "$origin$basePath/$reference"
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
