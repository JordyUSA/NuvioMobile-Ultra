package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec

/**
 * What the encoder actually available on this build and this handset can do.
 *
 * This exists because the two Android backends differ sharply and neither is knowable at compile
 * time. Media3 Transformer encodes through whatever MediaCodec the device exposes and muxes only
 * MP4 and WebM. FFmpeg is present only when the build was made with `-Pnuvio.android.ffmpeg=true`,
 * and the AAR that ships is FFmpeg 6.0 built without `--enable-gpl`, which means no libx264 and no
 * certainty that the MediaCodec *encoders* exist at all — upstream added those after 6.0. Probing
 * rather than assuming is what keeps a user from picking an option that fails ten minutes into a
 * job.
 *
 * The result drives two things: [ConversionPlanner] silently downgrades a request it cannot serve
 * (recording a reason), and the advanced sheet disables the options that are not here.
 */
data class ConverterCapabilities(
    val videoEncoders: Set<CastVideoCodec>,
    val audioEncoders: Set<CastAudioCodec>,
    val containers: Set<CastContainer>,
    /** False would mean every job must re-encode; no current backend is that limited. */
    val canCopyStreams: Boolean,
    /** Shown in the advanced sheet footer so a failure can be reported against a known backend. */
    val backendLabel: String,
    /** Tallest output the device's encoder will accept. 0 means "no known ceiling". */
    val maxEncodeHeight: Int = 0,
    /**
     * What FFmpeg *specifically* can encode, which is not the same as [videoEncoders].
     *
     * This is the field that decides engine order. The Android AAR is FFmpeg 6.0, and MediaCodec
     * encoders landed upstream in 6.1 — so on that build this set comes back empty for video and
     * the engine must not hand an encode job to FFmpeg, or every job burns a doomed session before
     * falling back. Populated by an actual `-encoders` probe, never assumed, so a future 6.1+ AAR
     * silently upgrades the behaviour.
     */
    val ffmpegVideoEncoders: Set<CastVideoCodec> = emptySet(),
    /** True when FFmpeg is present at all, regardless of what it can encode. */
    val hasFfmpeg: Boolean = false,
) {
    fun supportsVideo(codec: CastVideoCodec): Boolean = codec in videoEncoders

    fun supportsAudio(codec: CastAudioCodec): Boolean = codec in audioEncoders

    fun supportsContainer(container: CastContainer): Boolean = container in containers

    /**
     * The codec to actually encode with when [wanted] is unavailable.
     *
     * H.264 is the fallback of last resort because it is the one encoder every handset with a
     * hardware video encoder has, and it is also the codec that makes the output play everywhere —
     * which is the point of the feature.
     */
    fun resolveVideoCodec(wanted: CastVideoCodec): CastVideoCodec? = when {
        supportsVideo(wanted) -> wanted
        supportsVideo(CastVideoCodec.H264) -> CastVideoCodec.H264
        else -> videoEncoders.firstOrNull()
    }

    fun resolveAudioCodec(wanted: CastAudioCodec): CastAudioCodec? = when {
        supportsAudio(wanted) -> wanted
        supportsAudio(CastAudioCodec.AAC) -> CastAudioCodec.AAC
        else -> audioEncoders.firstOrNull()
    }

    fun resolveContainer(wanted: CastContainer): CastContainer = when {
        supportsContainer(wanted) -> wanted
        supportsContainer(CastContainer.MP4) -> CastContainer.MP4
        else -> containers.firstOrNull() ?: CastContainer.MP4
    }

    companion object {
        /**
         * What every backend can be assumed to manage. Used when a probe fails outright, so the
         * sheet still opens with the presets that matter rather than showing nothing.
         */
        val Minimal = ConverterCapabilities(
            videoEncoders = setOf(CastVideoCodec.H264),
            audioEncoders = setOf(CastAudioCodec.AAC),
            containers = setOf(CastContainer.MP4),
            canCopyStreams = true,
            backendLabel = "Media3",
            maxEncodeHeight = 1080,
        )
    }
}

/**
 * Probed once and cached by the implementation — enumerating codecs is not free, and the answer
 * cannot change while the process is alive.
 */
internal expect suspend fun converterCapabilities(): ConverterCapabilities
