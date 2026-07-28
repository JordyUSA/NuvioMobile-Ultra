package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastDynamicRange
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastReceiverCapabilities
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Decides how a stream has to be delivered to a particular Cast receiver.
 *
 * Three outcomes, cheapest first:
 *  - [CastDeliveryMode.DIRECT]: hand the receiver the original URL and let it pull the bytes.
 *  - [CastDeliveryMode.REMUX]: codecs are fine but the container is not (the Matroska case).
 *    Repackage without re-encoding, which is close to free.
 *  - [CastDeliveryMode.TRANSCODE]: at least the video has to be re-encoded.
 *
 * This is a pure function of the probe and the capabilities so it can be unit tested without a
 * device in the room.
 */
object CastDeliveryPlanner {

    /** Bitrate ceilings we target when re-encoding, by output height. */
    private const val BITRATE_1080P = 8_000_000L
    private const val BITRATE_720P = 5_000_000L
    private const val BITRATE_480P = 2_500_000L

    private const val AAC_STEREO_BITRATE = 192_000L
    private const val AAC_SURROUND_BITRATE = 384_000L

    fun plan(
        probe: CastMediaProbe,
        capabilities: CastReceiverCapabilities,
        /**
         * True when the receiver can fetch the source itself. Local files, torrent-backed
         * streams and anything behind a loopback address force the local server on, because a
         * Chromecast resolves URLs from its own network position, not the phone's.
         */
        sourceReachableByReceiver: Boolean,
    ): CastDeliveryPlan {
        val reasons = mutableListOf<CastIncompatibility>()
        val video = probe.video

        if (video != null && !capabilities.supportsVideo) {
            return CastDeliveryPlan(
                mode = CastDeliveryMode.UNSUPPORTED,
                reasons = listOf(CastIncompatibility.ReceiverHasNoVideoOutput),
                videoTarget = null,
                audioTarget = null,
                targetContainer = probe.container,
                requiresLocalServer = false,
            )
        }

        // --- Video ---------------------------------------------------------------------
        var videoNeedsEncode = false
        if (video != null) {
            if (!capabilities.supports(video.codec)) {
                reasons += CastIncompatibility.VideoCodecUnsupported(video.codec)
                videoNeedsEncode = true
            }
            if (video.width > capabilities.maxWidth || video.height > capabilities.maxHeight) {
                reasons += CastIncompatibility.ResolutionTooHigh(video.width, video.height)
                videoNeedsEncode = true
            }
            val fps = video.frameRate
            if (fps != null && fps > capabilities.maxFrameRate + 0.5f) {
                reasons += CastIncompatibility.FrameRateTooHigh(fps)
                videoNeedsEncode = true
            }
            if (video.bitDepth > capabilities.maxBitDepth) {
                reasons += CastIncompatibility.BitDepthUnsupported(video.bitDepth)
                videoNeedsEncode = true
            }
            if (!capabilities.supports(video.dynamicRange)) {
                reasons += CastIncompatibility.DynamicRangeUnsupported(video.dynamicRange)
                videoNeedsEncode = true
            }
            // H.264 above the receiver's level decodes to a black screen rather than failing
            // cleanly, so treat an over-level stream as needing a re-encode.
            if (
                video.codec == CastVideoCodec.H264 &&
                levelTimesTen(video.profile) > capabilities.maxH264LevelTimesTen
            ) {
                reasons += CastIncompatibility.VideoLevelTooHigh(video.profile)
                videoNeedsEncode = true
            }
        }

        // --- Audio ---------------------------------------------------------------------
        val audio = probe.primaryAudio
        var audioNeedsEncode = false
        if (audio != null && !capabilities.supports(audio.codec)) {
            reasons += CastIncompatibility.AudioCodecUnsupported(audio.codec)
            audioNeedsEncode = true
        }

        // --- Container -----------------------------------------------------------------
        val containerSupported = capabilities.supports(probe.container)
        if (!containerSupported) {
            reasons += CastIncompatibility.ContainerUnsupported(probe.container)
        }

        val mode = when {
            videoNeedsEncode -> CastDeliveryMode.TRANSCODE
            audioNeedsEncode || !containerSupported -> CastDeliveryMode.REMUX
            !sourceReachableByReceiver -> CastDeliveryMode.DIRECT
            else -> CastDeliveryMode.DIRECT
        }

        val videoTarget = if (videoNeedsEncode && video != null) {
            buildVideoTarget(video.width, video.height, video.frameRate, video.bitrateBitsPerSecond, capabilities)
        } else {
            null
        }

        val audioTarget = if (audioNeedsEncode && audio != null) {
            val channels = min(audio.channelCount.coerceAtLeast(1), 6)
            CastAudioTarget(
                codec = CastAudioCodec.AAC,
                channelCount = channels,
                bitrateBitsPerSecond = if (channels > 2) AAC_SURROUND_BITRATE else AAC_STEREO_BITRATE,
            )
        } else {
            null
        }

        // Anything we repackage or re-encode is produced on the phone, so it has to be served
        // from the phone regardless of whether the original URL was publicly reachable.
        val requiresLocalServer = mode != CastDeliveryMode.DIRECT || !sourceReachableByReceiver

        return CastDeliveryPlan(
            mode = mode,
            reasons = reasons.toList(),
            videoTarget = videoTarget,
            audioTarget = audioTarget,
            targetContainer = if (mode == CastDeliveryMode.DIRECT) probe.container else CastContainer.MP4,
            requiresLocalServer = requiresLocalServer,
        )
    }

    private fun buildVideoTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFrameRate: Float?,
        sourceBitrate: Long?,
        capabilities: CastReceiverCapabilities,
    ): CastVideoTarget {
        // Scale down preserving aspect ratio, and only ever downwards.
        var width = sourceWidth
        var height = sourceHeight
        if (width > capabilities.maxWidth || height > capabilities.maxHeight) {
            val scale = min(
                capabilities.maxWidth.toDouble() / width.toDouble(),
                capabilities.maxHeight.toDouble() / height.toDouble(),
            )
            width = (width * scale).roundToLong().toInt()
            height = (height * scale).roundToLong().toInt()
        }
        // H.264 encoders require even dimensions.
        width = width and 1.inv()
        height = height and 1.inv()

        val ceiling = when {
            height > 720 -> BITRATE_1080P
            height > 480 -> BITRATE_720P
            else -> BITRATE_480P
        }
        // Never spend more bits than the source actually had.
        val bitrate = sourceBitrate?.takeIf { it in 1..ceiling } ?: ceiling

        val frameRate = sourceFrameRate
            ?.takeIf { it > 0f }
            ?.let { min(it, capabilities.maxFrameRate.toFloat()) }

        return CastVideoTarget(
            codec = CastVideoCodec.H264,
            width = width,
            height = height,
            bitrateBitsPerSecond = bitrate,
            frameRate = frameRate,
        )
    }

    /**
     * Extracts an H.264 level from a container-reported profile string such as
     * "High Profile Level 5.1". Returns 0 when nothing usable is present, which is treated as
     * "no evidence of a problem" rather than as a failure.
     */
    private fun levelTimesTen(profile: String?): Int {
        val text = profile?.lowercase() ?: return 0
        val marker = text.indexOf("level")
        if (marker < 0) return 0
        val number = text.substring(marker + "level".length)
            .trimStart(' ', ':', '=')
            .takeWhile { it.isDigit() || it == '.' }
        if (number.isEmpty()) return 0
        val value = number.toDoubleOrNull() ?: return 0
        return (value * 10).roundToLong().toInt()
    }
}

enum class CastDeliveryMode {
    /** Give the receiver the original URL. */
    DIRECT,

    /** Repackage the container, copying at least the video track. */
    REMUX,

    /** Re-encode the video. */
    TRANSCODE,

    /** Nothing we can do — currently only a video stream sent to a speaker. */
    UNSUPPORTED,
}

data class CastVideoTarget(
    val codec: CastVideoCodec,
    val width: Int,
    val height: Int,
    val bitrateBitsPerSecond: Long,
    val frameRate: Float?,
)

data class CastAudioTarget(
    val codec: CastAudioCodec,
    val channelCount: Int,
    val bitrateBitsPerSecond: Long,
)

data class CastDeliveryPlan(
    val mode: CastDeliveryMode,
    val reasons: List<CastIncompatibility>,
    /** Null means "copy the video track untouched". */
    val videoTarget: CastVideoTarget?,
    /** Null means "copy the audio track untouched". */
    val audioTarget: CastAudioTarget?,
    val targetContainer: CastContainer,
    val requiresLocalServer: Boolean,
)

/** Why a stream could not be handed straight to the receiver. Surfaced in the UI. */
sealed interface CastIncompatibility {
    data class VideoCodecUnsupported(val codec: CastVideoCodec) : CastIncompatibility
    data class AudioCodecUnsupported(val codec: CastAudioCodec) : CastIncompatibility
    data class ContainerUnsupported(val container: CastContainer) : CastIncompatibility
    data class ResolutionTooHigh(val width: Int, val height: Int) : CastIncompatibility
    data class FrameRateTooHigh(val frameRate: Float) : CastIncompatibility
    data class BitDepthUnsupported(val bitDepth: Int) : CastIncompatibility
    data class DynamicRangeUnsupported(val range: CastDynamicRange) : CastIncompatibility
    data class VideoLevelTooHigh(val profile: String?) : CastIncompatibility
    data object ReceiverHasNoVideoOutput : CastIncompatibility
}
