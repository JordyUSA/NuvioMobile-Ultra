package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastAudioStream
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastDynamicRange
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.CastVideoStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Probes with FFprobe (via [CastProberBridge], implemented in Swift over FFmpegKit) rather than
 * AVFoundation.
 *
 * A codec-level probe was attempted earlier directly on AVFoundation's
 * `load<Property>WithCompletionHandler:` API — the real replacement for the synchronous
 * `AVAssetTrack` properties, which are absent from this project's Kotlin/Native AVFoundation
 * bindings entirely. The individual calls resolved, but the same code compiled in one file
 * arrangement and failed the same way in another, which is a sign of genuine instability in that
 * specific interop surface rather than a wrong guess to iterate past. FFprobe sidesteps it
 * entirely: FFmpegKit is already vendored for [CastTranscoder]'s remux/re-encode path, its
 * Objective-C surface here is synchronous field access with no completion-handler generics for
 * the Kotlin/Native bridge to choke on, and it reads container/codec/HDR metadata FFprobe itself
 * — not AVFoundation — reports.
 *
 * Mirrors `CastMediaProber.android.kt`'s shape: extraction (here, [CastProberBridge] on the
 * Swift side reading raw FFprobe fields) is kept separate from classification (the private
 * `*For` functions below, turning those raw fields into the shared Cast enums).
 */
actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> {
    val container = containerFromUrl(url)

    // Adaptive manifests have no single set of codecs to read, and a receiver negotiates its
    // own variant, so probing them is both impossible here and unnecessary — same short-circuit
    // as the Android probe.
    if (container == CastContainer.HLS || container == CastContainer.DASH) {
        return Result.success(
            CastMediaProbe(
                container = container,
                video = null,
                audioTracks = emptyList(),
                isLive = true,
            ),
        )
    }

    return CastProberHost.probe(url, headers)
}

/**
 * Owns the Swift-side [CastProberBridge] and turns its completion callback back into the suspend
 * function above.
 *
 * A named object rather than top-level functions: this file's name contains a dot, which — as
 * `CastDeliveryBridgeRegistration.kt` notes for the same reason — would turn Swift-callable
 * top-level declarations into an awkward generated symbol. An object's members don't have that
 * problem; Kotlin/Native exposes a singleton `object` to Swift as `TypeName.shared` regardless of
 * which file it is declared in, so this stays in the same file as the classification logic it
 * feeds without the naming hazard.
 *
 * Kept separate from `CastDelivery`, which owns the analogous transcode continuation: probing is
 * not part of that object's `cast()` state machine, and there is no reason a probe and a
 * transcode could not legitimately overlap (e.g. a probe for the next queued cast while the
 * current one is still re-encoding).
 */
object CastProberHost {
    private var bridge: CastProberBridge? = null
    private var pending: ((Result<CastMediaProbe>) -> Unit)? = null

    fun attach(bridge: CastProberBridge) {
        this.bridge = bridge
    }

    suspend fun probe(url: String, headers: Map<String, String>): Result<CastMediaProbe> {
        val activeBridge = bridge
            ?: return Result.failure(UnsupportedOperationException("Media probing is not available"))
        val container = containerFromUrl(url)

        return suspendCancellableCoroutine { continuation ->
            pending = { result ->
                pending = null
                if (continuation.isActive) {
                    continuation.resume(result.map { it.copy(container = container) })
                }
            }
            continuation.invokeOnCancellation { pending = null }
            activeBridge.probe(url, headers.keys.toList(), headers.values.toList())
        }
    }

    /**
     * Called from Swift when a probe finishes. Ignored fields on failure carry placeholder
     * values.
     *
     * The per-track audio fields are `List<String>`, not `List<Int>`/`List<Long>`: Kotlin/Native
     * boxes primitives inside a `List`, so Swift would see `[KotlinInt]`/`[KotlinLong]` rather
     * than a plain `[Int32]`/`[Int64]` — an interop shape nothing else in this bridge uses.
     * `List<String>` is the one already proven here (`headerNames`/`headerValues` on
     * `CastTranscoderBridge.start`), so the numbers are stringified on the Swift side and parsed
     * back here instead of gambling on the boxed-array bridging.
     */
    fun onCompleted(
        success: Boolean,
        message: String?,
        durationMs: Long,
        hasVideo: Boolean,
        videoCodec: String,
        videoWidth: Int,
        videoHeight: Int,
        videoFrameRate: Float,
        videoBitrateBitsPerSecond: Long,
        videoProfile: String,
        videoPixelFormat: String,
        videoBitsPerRawSample: String,
        videoColorTransfer: String,
        audioCodecs: List<String>,
        audioChannelCounts: List<String>,
        audioSampleRates: List<String>,
        audioBitrates: List<String>,
        audioLanguages: List<String>,
    ) {
        val completion = pending ?: return
        pending = null

        if (!success) {
            completion(Result.failure(IllegalStateException(message ?: "Could not read this file")))
            return
        }

        val video = if (hasVideo) {
            CastVideoStream(
                codec = videoCodecFor(videoCodec),
                width = videoWidth,
                height = videoHeight,
                frameRate = videoFrameRate.takeIf { it > 0f },
                bitrateBitsPerSecond = videoBitrateBitsPerSecond.takeIf { it > 0 },
                dynamicRange = dynamicRangeFor(videoColorTransfer),
                bitDepth = bitDepthFor(videoPixelFormat, videoBitsPerRawSample),
                profile = videoProfile.takeIf { it.isNotBlank() },
            )
        } else {
            null
        }

        val audio = audioCodecs.indices.map { index ->
            CastAudioStream(
                codec = audioCodecFor(audioCodecs[index]),
                channelCount = audioChannelCounts.getOrNull(index)?.toIntOrNull()?.takeIf { it > 0 } ?: 2,
                sampleRateHz = audioSampleRates.getOrNull(index)?.toIntOrNull()?.takeIf { it > 0 },
                bitrateBitsPerSecond = audioBitrates.getOrNull(index)?.toLongOrNull()?.takeIf { it > 0 },
                language = audioLanguages.getOrNull(index)?.takeIf { it.isNotBlank() },
                isDefault = index == 0,
            )
        }

        // container is a placeholder here; probe() overwrites it from the URL once this result
        // reaches the pending continuation above.
        completion(
            Result.success(
                CastMediaProbe(
                    container = CastContainer.UNKNOWN,
                    video = video,
                    audioTracks = audio,
                    durationMs = durationMs.takeIf { it > 0 },
                    isLive = durationMs <= 0,
                ),
            ),
        )
    }
}

private fun videoCodecFor(codec: String): CastVideoCodec = when (codec.lowercase()) {
    "h264", "avc" -> CastVideoCodec.H264
    "hevc", "h265" -> CastVideoCodec.HEVC
    "vp8" -> CastVideoCodec.VP8
    "vp9" -> CastVideoCodec.VP9
    "av1" -> CastVideoCodec.AV1
    "mpeg2video" -> CastVideoCodec.MPEG2
    "mpeg4" -> CastVideoCodec.MPEG4
    "vc1" -> CastVideoCodec.VC1
    else -> CastVideoCodec.UNKNOWN
}

private fun audioCodecFor(codec: String): CastAudioCodec = when {
    codec.equals("aac", ignoreCase = true) -> CastAudioCodec.AAC
    codec.equals("mp3", ignoreCase = true) || codec.equals("libmp3lame", ignoreCase = true) -> CastAudioCodec.MP3
    codec.equals("opus", ignoreCase = true) -> CastAudioCodec.OPUS
    codec.equals("vorbis", ignoreCase = true) -> CastAudioCodec.VORBIS
    codec.equals("flac", ignoreCase = true) -> CastAudioCodec.FLAC
    codec.equals("ac3", ignoreCase = true) -> CastAudioCodec.AC3
    codec.equals("eac3", ignoreCase = true) -> CastAudioCodec.EAC3
    codec.equals("dts", ignoreCase = true) -> CastAudioCodec.DTS
    codec.equals("dts_hd", ignoreCase = true) || codec.equals("dtshd", ignoreCase = true) -> CastAudioCodec.DTS_HD
    codec.equals("truehd", ignoreCase = true) -> CastAudioCodec.TRUEHD
    codec.startsWith("pcm", ignoreCase = true) -> CastAudioCodec.PCM
    else -> CastAudioCodec.UNKNOWN
}

/**
 * FFprobe's `color_transfer` names (libavutil's `av_color_transfer_name`), not Android's
 * MediaFormat constants. Dolby Vision and HDR10+ are not detected here — the same gap
 * `CastMediaProber.android.kt` has, since both need side-data FFprobe does not surface through a
 * flat stream property.
 */
private fun dynamicRangeFor(colorTransfer: String): CastDynamicRange = when {
    colorTransfer.equals("smpte2084", ignoreCase = true) -> CastDynamicRange.HDR10
    colorTransfer.equals("arib-std-b67", ignoreCase = true) -> CastDynamicRange.HLG
    else -> CastDynamicRange.SDR
}

/**
 * `bits_per_raw_sample` is FFprobe's direct answer when present. `pix_fmt` (e.g. "yuv420p10le")
 * is the fallback for the streams that omit it, which is common for VP9/AV1.
 */
private fun bitDepthFor(pixFmt: String, bitsPerRawSample: String): Int {
    bitsPerRawSample.toIntOrNull()?.let { if (it > 0) return it }
    return if (pixFmt.contains("10") || pixFmt.contains("12")) 10 else 8
}
