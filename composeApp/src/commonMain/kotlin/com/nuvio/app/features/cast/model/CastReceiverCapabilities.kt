package com.nuvio.app.features.cast.model

/**
 * What a particular Cast receiver can decode.
 *
 * The Default Media Receiver gives us no way to interrogate the device for its codec support,
 * so this is derived from the advertised model name. Detection is therefore best-effort:
 * [CastReceiverProfile.forModel] deliberately falls back to the most conservative video profile
 * rather than an optimistic one, because guessing too high produces a black screen on the TV
 * while guessing too low only costs us an unnecessary transcode.
 *
 * Figures follow Google's "Supported Media for Google Cast" matrix.
 */
data class CastReceiverCapabilities(
    val profile: CastReceiverProfile,
    val supportsVideo: Boolean,
    val videoCodecs: Set<CastVideoCodec>,
    val audioCodecs: Set<CastAudioCodec>,
    val containers: Set<CastContainer>,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val dynamicRanges: Set<CastDynamicRange>,
    /** Max H.264 level x10 (e.g. 41 == L4.1). Streams above this must be transcoded. */
    val maxH264LevelTimesTen: Int,
    val maxBitDepth: Int,
) {
    fun supports(codec: CastVideoCodec): Boolean = supportsVideo && codec in videoCodecs

    fun supports(codec: CastAudioCodec): Boolean = codec in audioCodecs

    fun supports(container: CastContainer): Boolean = container in containers

    fun supports(range: CastDynamicRange): Boolean = range in dynamicRanges
}

enum class CastReceiverProfile {
    /** Chromecast 1st and 2nd generation: H.264 up to 1080p30. */
    GEN_1_2,

    /** Chromecast 3rd generation: H.264 up to 1080p60. */
    GEN_3,

    /** Chromecast Ultra: adds HEVC, VP9 and 4K HDR. */
    ULTRA,

    /** Chromecast with Google TV (4K) and Google TV Streamer. */
    GOOGLE_TV_4K,

    /** Chromecast with Google TV (HD, 2022): adds AV1 but caps at 1080p. */
    GOOGLE_TV_HD,

    /** Generic Android TV / built-in Cast television. */
    ANDROID_TV,

    /** Speakers and displays without video output. */
    AUDIO_ONLY,

    /** Unrecognised model: treated as [GEN_1_2]. */
    UNKNOWN,
    ;

    companion object {
        /**
         * Maps a Cast model name to a profile.
         *
         * [hasVideoOutput] should come from the Cast SDK's device capability flags, which are
         * authoritative for the video/audio-only split even when the model name is unfamiliar.
         */
        fun forModel(modelName: String?, hasVideoOutput: Boolean = true): CastReceiverProfile {
            if (!hasVideoOutput) return AUDIO_ONLY
            val model = modelName?.trim()?.lowercase().orEmpty()
            if (model.isEmpty()) return UNKNOWN

            // Audio-only hardware, in case the capability flag was not supplied.
            if (
                model.contains("chromecast audio") ||
                model.contains("google home") ||
                model.contains("nest audio") ||
                model.contains("nest mini") ||
                model.contains("home mini") ||
                model.contains("home max")
            ) {
                return AUDIO_ONLY
            }

            return when {
                model.contains("google tv streamer") -> GOOGLE_TV_4K
                // Order matters: the HD model must be matched before the generic Google TV rule.
                model.contains("google tv") && model.contains("hd") -> GOOGLE_TV_HD
                model.contains("google tv") -> GOOGLE_TV_4K
                model.contains("ultra") -> ULTRA
                model.contains("chromecast") && model.contains("3") -> GEN_3
                model.contains("chromecast") -> GEN_1_2
                model.contains("android tv") || model.contains("bravia") || model.contains("shield") -> ANDROID_TV
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Containers every Cast receiver accepts. Note the absence of Matroska and AVI: those are the
 * common case for downloaded content and are precisely why the remux path exists.
 */
private val BASE_CONTAINERS = setOf(
    CastContainer.MP4,
    CastContainer.WEBM,
    CastContainer.HLS,
    CastContainer.DASH,
    CastContainer.MPEG_TS,
)

/**
 * Audio every Cast receiver decodes itself.
 *
 * AC-3 and E-AC-3 are deliberately excluded: Chromecast only passes them through to an external
 * receiver over HDMI, and we cannot detect from the sender whether the attached amplifier
 * supports it. Transcoding them to AAC always works, so that is what we do.
 */
private val BASE_AUDIO = setOf(
    CastAudioCodec.AAC,
    CastAudioCodec.MP3,
    CastAudioCodec.OPUS,
    CastAudioCodec.VORBIS,
    CastAudioCodec.FLAC,
    CastAudioCodec.PCM,
)

fun capabilitiesFor(profile: CastReceiverProfile): CastReceiverCapabilities = when (profile) {
    CastReceiverProfile.AUDIO_ONLY -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = false,
        videoCodecs = emptySet(),
        audioCodecs = BASE_AUDIO,
        containers = setOf(CastContainer.MP4, CastContainer.WEBM, CastContainer.HLS),
        maxWidth = 0,
        maxHeight = 0,
        maxFrameRate = 0,
        dynamicRanges = emptySet(),
        maxH264LevelTimesTen = 0,
        maxBitDepth = 8,
    )

    CastReceiverProfile.GEN_3 -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(CastVideoCodec.H264, CastVideoCodec.VP8),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 60,
        dynamicRanges = setOf(CastDynamicRange.SDR),
        maxH264LevelTimesTen = 42,
        maxBitDepth = 8,
    )

    CastReceiverProfile.ULTRA -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(
            CastVideoCodec.H264,
            CastVideoCodec.HEVC,
            CastVideoCodec.VP8,
            CastVideoCodec.VP9,
        ),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRate = 60,
        dynamicRanges = setOf(
            CastDynamicRange.SDR,
            CastDynamicRange.HDR10,
            CastDynamicRange.HLG,
            CastDynamicRange.DOLBY_VISION,
        ),
        maxH264LevelTimesTen = 42,
        maxBitDepth = 10,
    )

    CastReceiverProfile.GOOGLE_TV_4K -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(
            CastVideoCodec.H264,
            CastVideoCodec.HEVC,
            CastVideoCodec.VP8,
            CastVideoCodec.VP9,
        ),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRate = 60,
        dynamicRanges = setOf(
            CastDynamicRange.SDR,
            CastDynamicRange.HDR10,
            CastDynamicRange.HDR10_PLUS,
            CastDynamicRange.HLG,
            CastDynamicRange.DOLBY_VISION,
        ),
        maxH264LevelTimesTen = 51,
        maxBitDepth = 10,
    )

    CastReceiverProfile.GOOGLE_TV_HD -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(
            CastVideoCodec.H264,
            CastVideoCodec.HEVC,
            CastVideoCodec.VP8,
            CastVideoCodec.VP9,
            CastVideoCodec.AV1,
        ),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 60,
        dynamicRanges = setOf(
            CastDynamicRange.SDR,
            CastDynamicRange.HDR10,
            CastDynamicRange.HDR10_PLUS,
            CastDynamicRange.HLG,
        ),
        maxH264LevelTimesTen = 51,
        maxBitDepth = 10,
    )

    CastReceiverProfile.ANDROID_TV -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(
            CastVideoCodec.H264,
            CastVideoCodec.HEVC,
            CastVideoCodec.VP8,
            CastVideoCodec.VP9,
        ),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 3840,
        maxHeight = 2160,
        maxFrameRate = 60,
        dynamicRanges = setOf(
            CastDynamicRange.SDR,
            CastDynamicRange.HDR10,
            CastDynamicRange.HLG,
        ),
        maxH264LevelTimesTen = 51,
        maxBitDepth = 10,
    )

    // Both the 1st/2nd generation dongles and anything we failed to recognise. H.264 High
    // Profile Level 4.1 at 1080p30 is the floor that every Cast video device supports.
    CastReceiverProfile.GEN_1_2,
    CastReceiverProfile.UNKNOWN,
    -> CastReceiverCapabilities(
        profile = profile,
        supportsVideo = true,
        videoCodecs = setOf(CastVideoCodec.H264, CastVideoCodec.VP8),
        audioCodecs = BASE_AUDIO,
        containers = BASE_CONTAINERS,
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 30,
        dynamicRanges = setOf(CastDynamicRange.SDR),
        maxH264LevelTimesTen = 41,
        maxBitDepth = 8,
    )
}
