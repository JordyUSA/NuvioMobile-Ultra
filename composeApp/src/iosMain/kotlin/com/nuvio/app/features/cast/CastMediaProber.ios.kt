package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastAudioStream
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.CastVideoStream
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import kotlin.coroutines.resume

/**
 * Probes with `AVURLAsset`, which is the only demuxer available on iOS without adding a
 * dependency.
 *
 * Every value read here goes through the completion-handler loaders (`load<Property>With-
 * CompletionHandler:`) rather than the classic synchronous properties `AVAsynchronousKey-
 * ValueLoading` used to provide. The synchronous accessors this file originally used —
 * `tracks`, `mediaType`, `naturalSize`, `formatDescriptions`, `nominalFrameRate`,
 * `estimatedDataRate`, `languageCode` — are not present in this project's Kotlin/Native
 * AVFoundation bindings at all (confirmed by two rounds of `compileKotlinIosArm64` failures,
 * not a guess); the completion-handler siblings Apple ships alongside the `async`/`await`
 * versions for the same migration are.
 *
 * This still cannot open Matroska or AVI — AVFoundation never could — so on those a probe
 * fails and the caller falls back to reasoning from the container alone, exactly as the shared
 * documentation on [probeCastMedia] describes. That still covers the common re-encode cases
 * this exists for: HEVC or VP9 in an MP4 hitting a receiver that only decodes H.264, or a
 * resolution/bitrate above the receiver's ceiling.
 *
 * Dynamic range and bit depth are deliberately not read back: getting them from
 * `CMFormatDescription`'s extension dictionary means bridging its CFString keys, which is a lot
 * of fragile cinterop for a value where guessing SDR/8-bit only costs a picture that looks
 * washed out on an HDR-incapable receiver rather than a black screen. Codec and resolution are
 * what actually cause a hard failure, so those are what this reads precisely.
 */
@OptIn(ExperimentalForeignApi::class)
actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> {
    val container = containerFromUrl(url)

    // Adaptive manifests have no single set of codecs to read, and a receiver negotiates its
    // own variant, so probing them is both impossible here and unnecessary.
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

    val nsUrl = NSURL.URLWithString(url) ?: return Result.failure(IllegalArgumentException("Bad URL: $url"))
    val options: Map<Any?, Any?>? = if (headers.isNotEmpty()) {
        // The named platform constant for this key is not exposed by Kotlin/Native's
        // AVFoundation bindings; the literal is AVFoundation's own documented value for it.
        mapOf("AVURLAssetHTTPHeaderFieldsKey" to headers)
    } else {
        null
    }
    val asset = AVURLAsset(uRL = nsUrl, options = options)

    return suspendCancellableCoroutine { continuation ->
        asset.loadTracksWithCompletionHandler { tracksResult, error ->
            val tracks = tracksResult?.filterIsInstance<AVAssetTrack>().orEmpty()
            if (tracks.isEmpty()) {
                if (continuation.isActive) {
                    continuation.resume(
                        Result.failure(
                            IllegalStateException(
                                error?.localizedDescription ?: "AVFoundation could not open this source",
                            ),
                        ),
                    )
                }
                return@loadTracksWithCompletionHandler
            }

            describeTrack(tracks, index = 0, video = null, audio = mutableListOf()) { video, audio ->
                asset.loadDurationWithCompletionHandler { duration, _ ->
                    val durationSeconds = CMTimeGetSeconds(duration)
                    val durationMs = durationSeconds
                        .takeIf { it.isFinite() && it > 0 }
                        ?.let { (it * 1000).toLong() }

                    if (continuation.isActive) {
                        continuation.resume(
                            Result.success(
                                CastMediaProbe(
                                    container = container,
                                    video = video,
                                    audioTracks = audio,
                                    durationMs = durationMs,
                                    isLive = durationMs == null,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Walks [tracks] one at a time; each track's own properties load asynchronously in turn. */
@OptIn(ExperimentalForeignApi::class)
private fun describeTrack(
    tracks: List<AVAssetTrack>,
    index: Int,
    video: CastVideoStream?,
    audio: MutableList<CastAudioStream>,
    onDone: (CastVideoStream?, List<CastAudioStream>) -> Unit,
) {
    if (index >= tracks.size) {
        onDone(video, audio)
        return
    }
    val track = tracks[index]
    track.loadMediaTypeWithCompletionHandler { mediaType, _ ->
        when (mediaType) {
            // Only the first video track matters for delivery planning; a second angle or a
            // thumbnail track would only complicate the codec decision without changing it.
            AVMediaTypeVideo -> if (video != null) {
                describeTrack(tracks, index + 1, video, audio, onDone)
            } else {
                loadVideoStream(track) { stream -> describeTrack(tracks, index + 1, stream, audio, onDone) }
            }
            AVMediaTypeAudio -> loadAudioStream(track, isDefault = audio.isEmpty()) { stream ->
                audio += stream
                describeTrack(tracks, index + 1, video, audio, onDone)
            }
            else -> describeTrack(tracks, index + 1, video, audio, onDone)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun loadVideoStream(track: AVAssetTrack, onDone: (CastVideoStream) -> Unit) {
    track.loadNaturalSizeWithCompletionHandler { size, _ ->
        track.loadFormatDescriptionsWithCompletionHandler { descriptions, _ ->
            val codec = descriptions?.firstOrNull()
                ?.let { CMFormatDescriptionGetMediaSubType(it) }
                ?.let(::videoCodecForFourCc)
                ?: CastVideoCodec.UNKNOWN
            track.loadNominalFrameRateWithCompletionHandler { frameRate, _ ->
                track.loadEstimatedDataRateWithCompletionHandler { dataRate, _ ->
                    onDone(
                        CastVideoStream(
                            codec = codec,
                            width = size.useContents { width }.toInt(),
                            height = size.useContents { height }.toInt(),
                            frameRate = frameRate.takeIf { it > 0f },
                            bitrateBitsPerSecond = dataRate.takeIf { it > 0f }?.toLong(),
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun loadAudioStream(track: AVAssetTrack, isDefault: Boolean, onDone: (CastAudioStream) -> Unit) {
    track.loadFormatDescriptionsWithCompletionHandler { descriptions, _ ->
        val codec = descriptions?.firstOrNull()
            ?.let { CMFormatDescriptionGetMediaSubType(it) }
            ?.let(::audioCodecForFourCc)
            ?: CastAudioCodec.UNKNOWN
        track.loadEstimatedDataRateWithCompletionHandler { dataRate, _ ->
            track.loadLanguageCodeWithCompletionHandler { language, _ ->
                onDone(
                    CastAudioStream(
                        codec = codec,
                        // AVAssetTrack has no direct channel-count accessor without decoding the
                        // audio format description's ASBD; stereo is the overwhelmingly common
                        // case and, unlike the video checks above, an undercount here only
                        // affects the AAC downmix target, not whether the stream plays at all.
                        channelCount = 2,
                        sampleRateHz = null,
                        bitrateBitsPerSecond = dataRate.takeIf { it > 0f }?.toLong(),
                        language = language,
                        isDefault = isDefault,
                    ),
                )
            }
        }
    }
}

private fun fourCc(code: String): UInt {
    require(code.length == 4) { "FourCC must be exactly 4 characters: $code" }
    return code.fold(0u) { acc, char -> (acc shl 8) or char.code.toUInt() }
}

private val FOUR_CC_H264 = fourCc("avc1")
private val FOUR_CC_HEVC = fourCc("hvc1")
private val FOUR_CC_HEVC_ALT = fourCc("hev1")
private val FOUR_CC_VP8 = fourCc("vp08")
private val FOUR_CC_VP9 = fourCc("vp09")
private val FOUR_CC_AV1 = fourCc("av01")
private val FOUR_CC_MPEG2 = fourCc("mp2v")
private val FOUR_CC_MPEG4 = fourCc("mp4v")

private fun videoCodecForFourCc(fourCcValue: UInt): CastVideoCodec = when (fourCcValue) {
    FOUR_CC_H264 -> CastVideoCodec.H264
    FOUR_CC_HEVC, FOUR_CC_HEVC_ALT -> CastVideoCodec.HEVC
    FOUR_CC_VP8 -> CastVideoCodec.VP8
    FOUR_CC_VP9 -> CastVideoCodec.VP9
    FOUR_CC_AV1 -> CastVideoCodec.AV1
    FOUR_CC_MPEG2 -> CastVideoCodec.MPEG2
    FOUR_CC_MPEG4 -> CastVideoCodec.MPEG4
    else -> CastVideoCodec.UNKNOWN
}

private val FOUR_CC_AAC = fourCc("aac ")
private val FOUR_CC_MP3 = fourCc(".mp3")
private val FOUR_CC_AC3 = fourCc("ac-3")
private val FOUR_CC_EAC3 = fourCc("ec-3")
private val FOUR_CC_OPUS = fourCc("opus")
private val FOUR_CC_FLAC = fourCc("fLaC")
private val FOUR_CC_ALAC = fourCc("alac")

private fun audioCodecForFourCc(fourCcValue: UInt): CastAudioCodec = when (fourCcValue) {
    FOUR_CC_AAC -> CastAudioCodec.AAC
    FOUR_CC_MP3 -> CastAudioCodec.MP3
    FOUR_CC_AC3 -> CastAudioCodec.AC3
    FOUR_CC_EAC3 -> CastAudioCodec.EAC3
    FOUR_CC_OPUS -> CastAudioCodec.OPUS
    FOUR_CC_FLAC, FOUR_CC_ALAC -> CastAudioCodec.PCM
    else -> CastAudioCodec.UNKNOWN
}
