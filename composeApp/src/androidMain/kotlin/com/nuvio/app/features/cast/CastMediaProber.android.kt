package com.nuvio.app.features.cast

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastAudioStream
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastDynamicRange
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.CastVideoStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Probes with [MediaExtractor], which reads only enough of the container to parse its track
 * headers and therefore works against a remote URL without downloading the file.
 */
actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> = withContext(Dispatchers.IO) {
    val container = containerFromUrl(url)

    // Adaptive manifests have no single set of codecs to read, and a receiver negotiates its
    // own variant, so probing them for codecs is both impossible here and unnecessary. Liveness
    // still has to be established: it used to be hardcoded true, which handed every VOD stream
    // to the receiver as STREAM_TYPE_LIVE and left it with no scrubber at all.
    if (container == CastContainer.HLS || container == CastContainer.DASH) {
        return@withContext Result.success(
            CastMediaProbe(
                container = container,
                video = null,
                audioTracks = emptyList(),
                isLive = adaptiveStreamIsLive(url, container, headers),
            ),
        )
    }

    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(url, headers)

        var video: CastVideoStream? = null
        val audio = mutableListOf<CastAudioStream>()
        var durationUs = 0L

        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            format.optLong(MediaFormat.KEY_DURATION)?.let { durationUs = maxOf(durationUs, it) }

            when {
                mime.startsWith("video/") && video == null -> {
                    video = CastVideoStream(
                        codec = videoCodecFor(mime),
                        width = format.optInt(MediaFormat.KEY_WIDTH) ?: 0,
                        height = format.optInt(MediaFormat.KEY_HEIGHT) ?: 0,
                        frameRate = format.optInt(MediaFormat.KEY_FRAME_RATE)?.toFloat(),
                        bitrateBitsPerSecond = format.optInt(MediaFormat.KEY_BIT_RATE)?.toLong(),
                        dynamicRange = dynamicRangeFor(format),
                        bitDepth = bitDepthFor(format, mime),
                        profile = null,
                    )
                }

                mime.startsWith("audio/") -> {
                    audio += CastAudioStream(
                        codec = audioCodecFor(mime),
                        channelCount = format.optInt(MediaFormat.KEY_CHANNEL_COUNT) ?: 2,
                        sampleRateHz = format.optInt(MediaFormat.KEY_SAMPLE_RATE),
                        bitrateBitsPerSecond = format.optInt(MediaFormat.KEY_BIT_RATE)?.toLong(),
                        language = format.optString("language"),
                        isDefault = audio.isEmpty(),
                    )
                }
            }
        }

        Result.success(
            CastMediaProbe(
                container = container,
                video = video,
                audioTracks = audio,
                durationMs = (durationUs / 1000).takeIf { it > 0 },
                isLive = durationUs <= 0,
            ),
        )
    } catch (error: Throwable) {
        Result.failure(error)
    } finally {
        runCatching { extractor.release() }
    }
}

private fun videoCodecFor(mime: String): CastVideoCodec = when (mime.lowercase()) {
    MediaFormat.MIMETYPE_VIDEO_AVC -> CastVideoCodec.H264
    MediaFormat.MIMETYPE_VIDEO_HEVC -> CastVideoCodec.HEVC
    MediaFormat.MIMETYPE_VIDEO_VP8 -> CastVideoCodec.VP8
    MediaFormat.MIMETYPE_VIDEO_VP9 -> CastVideoCodec.VP9
    MediaFormat.MIMETYPE_VIDEO_AV1 -> CastVideoCodec.AV1
    MediaFormat.MIMETYPE_VIDEO_MPEG2 -> CastVideoCodec.MPEG2
    MediaFormat.MIMETYPE_VIDEO_MPEG4 -> CastVideoCodec.MPEG4
    "video/x-ms-wmv", "video/wvc1" -> CastVideoCodec.VC1
    else -> CastVideoCodec.UNKNOWN
}

private fun audioCodecFor(mime: String): CastAudioCodec = when (mime.lowercase()) {
    MediaFormat.MIMETYPE_AUDIO_AAC -> CastAudioCodec.AAC
    MediaFormat.MIMETYPE_AUDIO_MPEG -> CastAudioCodec.MP3
    MediaFormat.MIMETYPE_AUDIO_AC3 -> CastAudioCodec.AC3
    MediaFormat.MIMETYPE_AUDIO_EAC3 -> CastAudioCodec.EAC3
    MediaFormat.MIMETYPE_AUDIO_OPUS -> CastAudioCodec.OPUS
    MediaFormat.MIMETYPE_AUDIO_VORBIS -> CastAudioCodec.VORBIS
    MediaFormat.MIMETYPE_AUDIO_FLAC -> CastAudioCodec.FLAC
    MediaFormat.MIMETYPE_AUDIO_RAW -> CastAudioCodec.PCM
    "audio/vnd.dts" -> CastAudioCodec.DTS
    "audio/vnd.dts.hd" -> CastAudioCodec.DTS_HD
    "audio/true-hd" -> CastAudioCodec.TRUEHD
    else -> CastAudioCodec.UNKNOWN
}

/**
 * Reads the transfer function to classify dynamic range. Absent on most SDR content, and only
 * populated from Android 7.0, so SDR is the default rather than an assertion.
 */
private fun dynamicRangeFor(format: MediaFormat): CastDynamicRange {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return CastDynamicRange.SDR
    return when (format.optInt(MediaFormat.KEY_COLOR_TRANSFER)) {
        MediaFormat.COLOR_TRANSFER_ST2084 -> CastDynamicRange.HDR10
        MediaFormat.COLOR_TRANSFER_HLG -> CastDynamicRange.HLG
        else -> CastDynamicRange.SDR
    }
}

/**
 * HEVC Main 10 is the overwhelmingly common source of 10-bit content. There is no portable
 * MediaFormat key for bit depth, so the profile constant is the available signal.
 */
private fun bitDepthFor(format: MediaFormat, mime: String): Int {
    if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) return 8
    val profile = format.optInt(MediaFormat.KEY_PROFILE) ?: return 8
    // MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 == 0x2, Main10HDR10 == 0x1000.
    return if (profile == 0x2 || profile == 0x1000 || profile == 0x2000) 10 else 8
}

private fun MediaFormat.optInt(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.optLong(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.optString(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

/**
 * Decides whether an adaptive manifest is live by reading it.
 *
 * A master playlist carries no liveness information of its own, so its first variant is followed
 * once. Anything that fails — an unreachable manifest, a timeout — falls back to "not live",
 * which is the safer error: a live stream mislabelled VOD still plays and merely shows an
 * optimistic timeline, whereas VOD mislabelled live loses seeking altogether.
 */
private fun adaptiveStreamIsLive(
    url: String,
    container: CastContainer,
    headers: Map<String, String>,
): Boolean = runCatching {
    val manifest = fetchText(url, headers) ?: return@runCatching false
    if (container == CastContainer.DASH) return@runCatching dashManifestIsLive(manifest)

    if (!hlsIsMasterPlaylist(manifest)) return@runCatching hlsPlaylistIsLive(manifest)
    val variantUrl = hlsFirstVariantUrl(manifest, url) ?: return@runCatching false
    val variant = fetchText(variantUrl, headers) ?: return@runCatching false
    hlsPlaylistIsLive(variant)
}.getOrDefault(false)

private fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = MANIFEST_TIMEOUT_MS
        readTimeout = MANIFEST_TIMEOUT_MS
        instanceFollowRedirects = true
        headers.forEach { (key, value) -> setRequestProperty(key, value) }
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        // A manifest is a few kilobytes; a live playlist that never ends must not be read whole.
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(MANIFEST_MAX_CHARS)
            val read = reader.read(buffer)
            if (read <= 0) null else String(buffer, 0, read)
        }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private const val MANIFEST_TIMEOUT_MS = 5_000
private const val MANIFEST_MAX_CHARS = 64 * 1024
