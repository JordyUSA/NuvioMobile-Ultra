package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.CastAudioTarget
import com.nuvio.app.features.cast.CastDeliveryMode
import com.nuvio.app.features.cast.CastVideoTarget
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlin.math.min
import kotlin.math.roundToLong

/** A change the planner had to make to the user's request, surfaced so the sheet can explain it. */
sealed interface ConversionAdjustment {
    data class VideoCodecDowngraded(val wanted: CastVideoCodec, val used: CastVideoCodec) : ConversionAdjustment
    data class AudioCodecDowngraded(val wanted: CastAudioCodec, val used: CastAudioCodec) : ConversionAdjustment
    data class ContainerDowngraded(val wanted: CastContainer, val used: CastContainer) : ConversionAdjustment
    data class ResolutionClamped(val width: Int, val height: Int) : ConversionAdjustment
    data class BitrateClamped(val bitsPerSecond: Long) : ConversionAdjustment
    data class TrackReencodedForContainer(val codec: String) : ConversionAdjustment
    data object FrameRateCapIgnored : ConversionAdjustment
    data object NothingToDo : ConversionAdjustment
}

data class ConversionPlanResult(
    val plan: MediaPlan,
    val adjustments: List<ConversionAdjustment>,
    val targetContainer: CastContainer,
    val dropVideo: Boolean,
    /** Null when neither track is re-encoded, since the output then tracks the source size. */
    val estimatedOutputBytes: Long?,
)

/**
 * Turns "what the user asked for" into "what will actually be run".
 *
 * Pure by construction — no platform types, no I/O — so the interesting decisions (never upscale,
 * never spend more bits than the source had, downgrade rather than fail) are unit-testable without
 * a device. Same reasoning, and much of the same arithmetic, as `CastDeliveryPlanner`; the
 * difference is that the constraints come from a user's spec and this handset's encoders rather
 * than from a television's capabilities.
 */
object ConversionPlanner {

    fun plan(
        probe: CastMediaProbe,
        spec: ConversionSpec,
        capabilities: ConverterCapabilities,
    ): ConversionPlanResult {
        val adjustments = mutableListOf<ConversionAdjustment>()

        val container = capabilities.resolveContainer(spec.container)
        if (container != spec.container) {
            adjustments += ConversionAdjustment.ContainerDowngraded(spec.container, container)
        }

        val video = probe.video

        // Only an explicit request strips the video track. A null [probe.video] is ambiguous — it
        // means either "this really is audio-only" or "the probe failed" — and inferring removal
        // from it would silently turn an unprobeable file into an audio-only one. When there is no
        // video description to plan an encode from, the track is copied instead.
        val dropVideo = spec.dropVideo

        val videoTarget = if (dropVideo || video == null) {
            null
        } else {
            resolveVideoTarget(video, spec, container, capabilities, adjustments)
        }

        val audioTarget = resolveAudioTarget(probe, spec, container, capabilities, adjustments)

        if (videoTarget == null && audioTarget == null && container == probe.container && !dropVideo) {
            adjustments += ConversionAdjustment.NothingToDo
        }

        // A conversion that removes the video track still has to re-encode or copy audio into the
        // target container; nothing else about the plan changes.

        val mode = when {
            videoTarget != null -> CastDeliveryMode.TRANSCODE
            // Even a pure copy still rewrites the container, so REMUX is the floor: the converter
            // always produces a new file, unlike cast which can hand over the original URL.
            else -> CastDeliveryMode.REMUX
        }

        val plan = MediaPlan(
            mode = mode,
            reasons = emptyList(),
            videoTarget = videoTarget,
            audioTarget = audioTarget,
            targetContainer = container,
            requiresLocalServer = false,
        )

        return ConversionPlanResult(
            plan = plan,
            adjustments = adjustments.toList(),
            targetContainer = container,
            dropVideo = dropVideo,
            estimatedOutputBytes = estimateOutputBytes(probe, videoTarget, audioTarget, dropVideo),
        )
    }

    private fun resolveVideoTarget(
        video: com.nuvio.app.features.cast.model.CastVideoStream,
        spec: ConversionSpec,
        container: CastContainer,
        capabilities: ConverterCapabilities,
        adjustments: MutableList<ConversionAdjustment>,
    ): CastVideoTarget? {
        val wanted = spec.videoCodec

        // "Copy" is only honourable when the target container can actually hold the source codec.
        // Copying VP9 into MP4 produces a file nothing will open, which is the exact failure this
        // feature exists to prevent.
        if (wanted == null) {
            if (containerAcceptsVideo(container, video.codec)) return null
            adjustments += ConversionAdjustment.TrackReencodedForContainer(video.codec.displayLabel())
        }

        val requested = wanted ?: CastVideoCodec.H264
        val codec = capabilities.resolveVideoCodec(requested)
            ?: return null // No encoder at all; the engine will attempt a straight copy.
        if (codec != requested) {
            adjustments += ConversionAdjustment.VideoCodecDowngraded(requested, codec)
        }

        val (width, height) = scaledDimensions(video.width, video.height, spec.maxHeight, capabilities)
        val clamped = width != video.width || height != video.height
        if (clamped) adjustments += ConversionAdjustment.ResolutionClamped(width, height)

        val ceiling = spec.videoBitrateBitsPerSecond ?: defaultBitrateFor(height)
        // Never spend more bits than the source actually had — re-encoding upward only wastes
        // storage and time, it cannot recover detail. Mirrors CastDeliveryPlanner's rule.
        val sourceBitrate = video.bitrateBitsPerSecond?.takeIf { it > 0L }
        val bitrate = if (sourceBitrate != null && sourceBitrate < ceiling && !clamped) {
            adjustments += ConversionAdjustment.BitrateClamped(sourceBitrate)
            sourceBitrate
        } else {
            ceiling
        }

        val sourceFps = video.frameRate?.takeIf { it > 0f }
        val cap = spec.maxFrameRate?.toFloat()
        val frameRate = when {
            cap == null -> sourceFps
            sourceFps == null -> null
            sourceFps <= cap + 0.5f -> {
                adjustments += ConversionAdjustment.FrameRateCapIgnored
                sourceFps
            }
            else -> cap
        }

        return CastVideoTarget(
            codec = codec,
            width = width,
            height = height,
            bitrateBitsPerSecond = bitrate,
            frameRate = frameRate,
        )
    }

    private fun resolveAudioTarget(
        probe: CastMediaProbe,
        spec: ConversionSpec,
        container: CastContainer,
        capabilities: ConverterCapabilities,
        adjustments: MutableList<ConversionAdjustment>,
    ): CastAudioTarget? {
        val audio = probe.primaryAudio ?: return null
        val wanted = spec.audioCodec

        if (wanted == null) {
            if (containerAcceptsAudio(container, audio.codec)) return null
            // This is the common DTS/TrueHD-in-MKV case: the codec cannot go into MP4, so a
            // "copy" request has to become an AAC encode or the output is unplayable.
            adjustments += ConversionAdjustment.TrackReencodedForContainer(audio.codec.displayLabel())
        }

        val requested = wanted ?: CastAudioCodec.AAC
        val codec = capabilities.resolveAudioCodec(requested) ?: return null
        if (codec != requested) {
            adjustments += ConversionAdjustment.AudioCodecDowngraded(requested, codec)
        }

        val channels = if (spec.audioChannelCount <= 0) {
            audio.channelCount.coerceIn(1, 6)
        } else {
            min(spec.audioChannelCount, audio.channelCount.coerceAtLeast(1))
        }

        return CastAudioTarget(
            codec = codec,
            channelCount = channels,
            bitrateBitsPerSecond = spec.audioBitrateBitsPerSecond,
        )
    }

    /**
     * Scale down preserving aspect ratio, and only ever downwards. Both dimensions are rounded to
     * even numbers because H.264 encoders reject odd ones.
     */
    internal fun scaledDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        maxHeight: Int?,
        capabilities: ConverterCapabilities,
    ): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return sourceWidth to sourceHeight

        val ceiling = listOfNotNull(maxHeight, capabilities.maxEncodeHeight.takeIf { it > 0 }).minOrNull()
            ?: return (sourceWidth and 1.inv()) to (sourceHeight and 1.inv())

        if (sourceHeight <= ceiling) {
            return (sourceWidth and 1.inv()) to (sourceHeight and 1.inv())
        }

        val scale = ceiling.toDouble() / sourceHeight.toDouble()
        val width = (sourceWidth * scale).roundToLong().toInt() and 1.inv()
        val height = (sourceHeight * scale).roundToLong().toInt() and 1.inv()
        return width.coerceAtLeast(2) to height.coerceAtLeast(2)
    }

    internal fun defaultBitrateFor(height: Int): Long = when {
        height > 720 -> ConversionPresets.BITRATE_1080P
        height > 480 -> 5_000_000L
        else -> ConversionPresets.BITRATE_720P
    }

    private fun estimateOutputBytes(
        probe: CastMediaProbe,
        videoTarget: CastVideoTarget?,
        audioTarget: CastAudioTarget?,
        dropVideo: Boolean,
    ): Long? {
        val durationMs = probe.durationMs?.takeIf { it > 0L } ?: return null
        // A copy tracks the source size, which the probe does not report, so decline to guess
        // rather than show a number that is wrong by an order of magnitude.
        if (videoTarget == null && !dropVideo) return null

        val videoBits = videoTarget?.bitrateBitsPerSecond ?: 0L
        val audioBits = audioTarget?.bitrateBitsPerSecond
            ?: probe.primaryAudio?.bitrateBitsPerSecond
            ?: 0L
        val totalBits = videoBits + audioBits
        if (totalBits <= 0L) return null

        return (durationMs / 1000.0 * totalBits / 8.0).roundToLong()
    }

    /**
     * Conservative container/codec compatibility.
     *
     * Deliberately narrower than what the specifications technically permit — MP4 can carry Opus
     * and FLAC on paper, but players in the wild frequently refuse them, and a file that only
     * Nuvio can open defeats the purpose of converting it.
     */
    internal fun containerAcceptsVideo(container: CastContainer, codec: CastVideoCodec): Boolean =
        when (container) {
            CastContainer.MP4, CastContainer.QUICKTIME -> codec in setOf(
                CastVideoCodec.H264,
                CastVideoCodec.HEVC,
                CastVideoCodec.MPEG4,
                CastVideoCodec.AV1,
            )
            CastContainer.WEBM -> codec in setOf(CastVideoCodec.VP8, CastVideoCodec.VP9, CastVideoCodec.AV1)
            CastContainer.MATROSKA -> codec != CastVideoCodec.UNKNOWN
            else -> false
        }

    internal fun containerAcceptsAudio(container: CastContainer, codec: CastAudioCodec): Boolean =
        when (container) {
            CastContainer.MP4, CastContainer.QUICKTIME -> codec in setOf(
                CastAudioCodec.AAC,
                CastAudioCodec.MP3,
                CastAudioCodec.AC3,
                CastAudioCodec.EAC3,
            )
            CastContainer.WEBM -> codec in setOf(CastAudioCodec.OPUS, CastAudioCodec.VORBIS)
            CastContainer.MATROSKA -> codec != CastAudioCodec.UNKNOWN
            else -> false
        }
}
