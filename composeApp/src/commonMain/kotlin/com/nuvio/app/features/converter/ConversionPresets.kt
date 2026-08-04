package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec

/**
 * The presets offered as chips in the convert sheet.
 *
 * Bitrate figures reuse the ladder `CastDeliveryPlanner` already encodes rather than introducing a
 * second set of magic numbers; where a preset wants a different ceiling it says so explicitly and
 * says why.
 *
 * A preset is only a starting point: [ConversionPlanner] still reconciles it against what the
 * source actually contains and what this device can encode, so "cap 1080p" never upscales a 720p
 * file and a codec the handset lacks is downgraded rather than attempted.
 */
object ConversionPresets {

    /** Matches `CastDeliveryPlanner.BITRATE_1080P`. */
    const val BITRATE_1080P = 8_000_000L
    const val BITRATE_720P = 2_500_000L

    /**
     * Above the 1080p ceiling on purpose: this preset exists for people who would rather spend
     * storage than quality, and 8 Mbps is visibly soft on high-motion content.
     */
    const val BITRATE_1080P_HIGH = 12_000_000L

    const val AAC_LOW = 128_000L
    const val AAC_STANDARD = 192_000L
    const val AAC_HIGH = 256_000L

    /** 0 means "keep whatever the source has". */
    const val CHANNELS_KEEP = 0
    const val CHANNELS_STEREO = 2

    /** The chips shown in the sheet, in the order they appear. [ConversionPreset.Custom] is not offered. */
    val selectable: List<ConversionPreset> = listOf(
        ConversionPreset.RemuxOnly,
        ConversionPreset.UniversalMp4,
        ConversionPreset.PhoneSpaceSaver,
        ConversionPreset.HighQuality1080p,
        ConversionPreset.AudioOnly,
    )

    fun specFor(preset: ConversionPreset): ConversionSpec = when (preset) {
        // Container-only fix, which is what most downloads actually need: the codecs are already
        // fine, the MKV wrapper is what a TV or car unit rejects. Copies both tracks, so it runs
        // at roughly disk speed and loses nothing.
        ConversionPreset.RemuxOnly -> ConversionSpec(
            container = CastContainer.MP4,
            videoCodec = null,
            maxHeight = null,
            videoBitrateBitsPerSecond = null,
            maxFrameRate = null,
            audioCodec = null,
            audioChannelCount = CHANNELS_KEEP,
        )

        // The "make it play anywhere" default. H.264 Main/High + AAC in MP4 is the one combination
        // essentially no player rejects.
        ConversionPreset.UniversalMp4 -> ConversionSpec(
            container = CastContainer.MP4,
            videoCodec = CastVideoCodec.H264,
            maxHeight = 1080,
            videoBitrateBitsPerSecond = BITRATE_1080P,
            audioCodec = CastAudioCodec.AAC,
            audioBitrateBitsPerSecond = AAC_STANDARD,
            audioChannelCount = CHANNELS_STEREO,
        )

        // Storage, not compatibility. The frame-rate cap matters more than it looks: a 60 fps
        // source held to 2.5 Mbps falls apart, where the same bitrate at 30 fps holds up.
        ConversionPreset.PhoneSpaceSaver -> ConversionSpec(
            container = CastContainer.MP4,
            videoCodec = CastVideoCodec.H264,
            maxHeight = 720,
            videoBitrateBitsPerSecond = BITRATE_720P,
            maxFrameRate = 30,
            audioCodec = CastAudioCodec.AAC,
            audioBitrateBitsPerSecond = AAC_LOW,
            audioChannelCount = CHANNELS_STEREO,
        )

        // Still fixes container and codec compatibility, but spends enough bits that the result
        // is worth keeping on a television.
        ConversionPreset.HighQuality1080p -> ConversionSpec(
            container = CastContainer.MP4,
            videoCodec = CastVideoCodec.H264,
            maxHeight = 1080,
            videoBitrateBitsPerSecond = BITRATE_1080P_HIGH,
            audioCodec = CastAudioCodec.AAC,
            audioBitrateBitsPerSecond = AAC_HIGH,
            audioChannelCount = CHANNELS_KEEP,
        )

        // Standup, concerts, anything worth listening to without the video. Also by far the
        // cheapest job the pipeline can run, which makes it a good first thing to try.
        ConversionPreset.AudioOnly -> ConversionSpec(
            container = CastContainer.MP4,
            videoCodec = null,
            dropVideo = true,
            maxHeight = null,
            audioCodec = CastAudioCodec.AAC,
            audioBitrateBitsPerSecond = AAC_STANDARD,
            audioChannelCount = CHANNELS_STEREO,
        )

        // Never actually asked for — the sheet switches to this the moment a control is edited.
        ConversionPreset.Custom -> specFor(ConversionPreset.UniversalMp4)
    }

    /**
     * Whether this device can run [preset] at all, used to disable a chip rather than let it fail
     * later. Deliberately permissive: a preset whose *codec* is unavailable is downgraded by the
     * planner rather than blocked, so only a genuinely impossible combination returns false.
     */
    fun isSupported(preset: ConversionPreset, capabilities: ConverterCapabilities): Boolean {
        val spec = specFor(preset)
        if (spec.videoCodec == null && !spec.dropVideo && !capabilities.canCopyStreams) return false
        if (spec.audioCodec != null && capabilities.audioEncoders.isEmpty()) return false
        if (spec.videoCodec != null && capabilities.videoEncoders.isEmpty()) return false
        return true
    }

    /** Which preset a spec corresponds to, or [ConversionPreset.Custom] when it matches none. */
    fun presetFor(spec: ConversionSpec): ConversionPreset =
        selectable.firstOrNull { specFor(it) == spec } ?: ConversionPreset.Custom
}
