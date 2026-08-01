package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec

private var cached: ConverterCapabilities? = null

/**
 * iOS always has FFmpegKit — the min-GPL frameworks are vendored in `iosApp/Frameworks/FFmpegKit`,
 * not opt-in the way the Android AAR is — so this is close to static. It is still an actual probe
 * rather than a hardcoded set, because "the build vendors FFmpeg" and "this FFmpeg has the encoder
 * we want" have already turned out to be different questions on the Android side, and there is no
 * reason to assume iOS is immune to the same drift.
 */
internal actual suspend fun converterCapabilities(): ConverterCapabilities {
    cached?.let { return it }

    val names = ConverterHost.requireBridge()
        ?.let { bridge -> runCatching { bridge.probeEncoderNames() }.getOrNull() }
        ?.split(' ', '\n', '\t')
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()

    val videoEncoders = buildSet {
        if (names.any { it == "h264_videotoolbox" || it == "libx264" }) add(CastVideoCodec.H264)
        if (names.any { it == "hevc_videotoolbox" || it == "libx265" }) add(CastVideoCodec.HEVC)
    }.ifEmpty {
        // The bridge not being installed yet is the common reason to land here, and refusing every
        // preset in that window would be worse than assuming the baseline the vendored build is
        // known to carry.
        setOf(CastVideoCodec.H264)
    }

    val audioEncoders = buildSet {
        add(CastAudioCodec.AAC)
        if ("libmp3lame" in names) add(CastAudioCodec.MP3)
    }

    val capabilities = ConverterCapabilities(
        videoEncoders = videoEncoders,
        audioEncoders = audioEncoders,
        containers = setOf(CastContainer.MP4, CastContainer.MATROSKA),
        canCopyStreams = true,
        backendLabel = "FFmpeg",
        maxEncodeHeight = MAX_ENCODE_HEIGHT,
        ffmpegVideoEncoders = videoEncoders,
        hasFfmpeg = true,
    )

    // Only cached once the bridge has actually answered; caching the fallback would freeze the
    // baseline guess in place for the rest of the process.
    if (names.isNotEmpty()) cached = capabilities
    return capabilities
}

private const val MAX_ENCODE_HEIGHT = 2160
