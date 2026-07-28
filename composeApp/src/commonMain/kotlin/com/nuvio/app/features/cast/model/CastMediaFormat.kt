package com.nuvio.app.features.cast.model

/**
 * Describes the media a stream actually contains, as reported by a platform probe.
 *
 * Everything here is what we discovered about the *source*. What a given Chromecast can
 * play is described by [CastReceiverCapabilities], and the two are reconciled by
 * `CastDeliveryPlanner`.
 */

enum class CastVideoCodec {
    H264,
    HEVC,
    VP8,
    VP9,
    AV1,
    MPEG2,
    MPEG4,
    VC1,
    UNKNOWN,
}

enum class CastAudioCodec {
    AAC,
    MP3,
    OPUS,
    VORBIS,
    FLAC,
    PCM,
    AC3,
    EAC3,
    DTS,
    DTS_HD,
    TRUEHD,
    UNKNOWN,
}

enum class CastContainer {
    MP4,
    WEBM,
    MATROSKA,
    HLS,
    DASH,
    MPEG_TS,
    AVI,
    QUICKTIME,
    UNKNOWN,
}

/** Dynamic-range transfer characteristics. Chromecasts differ sharply on these. */
enum class CastDynamicRange {
    SDR,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION,
}

data class CastVideoStream(
    val codec: CastVideoCodec,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val bitrateBitsPerSecond: Long?,
    val dynamicRange: CastDynamicRange = CastDynamicRange.SDR,
    val bitDepth: Int = 8,
    /** Codec profile as reported by the container, e.g. "High", "Main 10". Advisory only. */
    val profile: String? = null,
)

data class CastAudioStream(
    val codec: CastAudioCodec,
    val channelCount: Int,
    val sampleRateHz: Int?,
    val bitrateBitsPerSecond: Long?,
    val language: String? = null,
    val isDefault: Boolean = false,
)

data class CastSubtitleStream(
    val language: String?,
    val label: String?,
    /** True for PGS/VOBSUB and friends, which a Chromecast cannot render and we cannot remux. */
    val isBitmap: Boolean,
    val url: String? = null,
)

/**
 * The result of probing a source. [durationMs] is null for live streams.
 */
data class CastMediaProbe(
    val container: CastContainer,
    val video: CastVideoStream?,
    val audioTracks: List<CastAudioStream>,
    val subtitleTracks: List<CastSubtitleStream> = emptyList(),
    val durationMs: Long? = null,
    val isLive: Boolean = false,
) {
    val primaryAudio: CastAudioStream?
        get() = audioTracks.firstOrNull { it.isDefault } ?: audioTracks.firstOrNull()
}
