package com.nuvio.app.features.converter

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil
import com.nuvio.app.features.cast.ffmpegCapabilityReport
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Volatile
private var cached: ConverterCapabilities? = null

/**
 * Discovers what this handset and this build can encode.
 *
 * Two independent sources are merged. Media3's [EncoderUtil] reports the device's real MediaCodec
 * encoders, which is the authority for the Transformer path and is available in every build.
 * FFmpeg, when bundled, reports itself via an `-encoders` probe — see the note on
 * `ffmpegCapabilityReport`, but the short version is that the vendored AAR very likely has no
 * video encoder at all and the converter must know that rather than assume otherwise.
 *
 * Enumerating codecs walks the platform codec list, so the answer is cached; it cannot change
 * while the process is alive.
 */
@OptIn(UnstableApi::class)
internal actual suspend fun converterCapabilities(): ConverterCapabilities {
    cached?.let { return it }

    return withContext(Dispatchers.Default) {
        cached?.let { return@withContext it }

        val ffmpeg = ffmpegCapabilityReport()

        val media3Video = buildSet {
            if (EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264).isNotEmpty()) {
                add(CastVideoCodec.H264)
            }
            if (EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty()) {
                add(CastVideoCodec.HEVC)
            }
        }

        val media3Audio = buildSet {
            if (hasPlatformEncoder(MimeTypes.AUDIO_AAC)) add(CastAudioCodec.AAC)
        }

        val containers = buildSet {
            // Media3's muxer writes MP4. WebM support varies by API level and is not worth
            // offering when the codecs it needs (VP8/VP9) are rarely encodable anyway.
            add(CastContainer.MP4)
            ffmpeg?.containers?.let(::addAll)
        }

        val capabilities = ConverterCapabilities(
            videoEncoders = media3Video + ffmpeg?.videoEncoders.orEmpty(),
            audioEncoders = media3Audio + ffmpeg?.audioEncoders.orEmpty(),
            containers = containers,
            canCopyStreams = true,
            backendLabel = when {
                ffmpeg == null -> "Media3"
                ffmpeg.videoEncoders.isEmpty() -> "Media3 + FFmpeg (remux)"
                else -> "Media3 + FFmpeg"
            },
            maxEncodeHeight = maxEncodeHeight(),
            ffmpegVideoEncoders = ffmpeg?.videoEncoders.orEmpty(),
            hasFfmpeg = ffmpeg != null,
        )

        cached = capabilities
        capabilities
    }
}

/**
 * The tallest H.264 output the device's best encoder accepts.
 *
 * Falls back to 1080p rather than to "unlimited": claiming a ceiling we did not measure would let
 * the planner target a resolution the encoder then rejects, which fails minutes into a job instead
 * of at the point the user chose it.
 */
@OptIn(UnstableApi::class)
private fun maxEncodeHeight(): Int =
    runCatching {
        EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H264)
            .mapNotNull { encoder ->
                runCatching {
                    EncoderUtil.getSupportedResolutionRanges(encoder, MimeTypes.VIDEO_H264).second.upper
                }.getOrNull()
            }
            .maxOrNull()
    }.getOrNull() ?: DEFAULT_MAX_ENCODE_HEIGHT

private fun hasPlatformEncoder(mimeType: String): Boolean =
    runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }.getOrDefault(false)

private const val DEFAULT_MAX_ENCODE_HEIGHT = 1080
