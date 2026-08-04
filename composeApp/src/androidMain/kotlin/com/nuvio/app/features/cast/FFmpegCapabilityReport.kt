package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec

/**
 * What the bundled FFmpeg binary reported about itself.
 *
 * Lives in androidMain rather than in either FFmpeg source set so both the real implementation
 * (src/androidFfmpeg) and the no-op (src/androidNoFfmpeg) can name the type, and so the converter's
 * capability probe can consume it without caring which build it got.
 *
 * [encoderNames] carries the raw FFmpeg encoder identifiers so the command builder can pick the
 * specific one that exists — `h264_mediacodec` when the build has it, `libx264` when it does not —
 * rather than inferring from build flags that have already proved to be wrong.
 */
internal data class FFmpegCapabilityReport(
    val videoEncoders: Set<CastVideoCodec>,
    val audioEncoders: Set<CastAudioCodec>,
    val containers: Set<CastContainer>,
    val hasSoftwareVideoEncoder: Boolean,
    val encoderNames: Set<String>,
)
