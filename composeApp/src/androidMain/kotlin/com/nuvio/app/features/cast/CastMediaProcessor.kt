package com.nuvio.app.features.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Turns a source the receiver cannot play into one it can.
 *
 * Deliberately an interface: the Media3 implementation below covers the common cases with
 * hardware encoders and no added binary weight, but it inherits MediaCodec's limits — it can
 * only decode what the handset itself decodes, so exotic audio such as DTS-HD or TrueHD will
 * fail here. Swapping in an FFmpeg-backed implementation is the way to close that gap without
 * touching the planner, the server or the UI.
 */
interface CastMediaProcessor {

    /**
     * Produces a receiver-playable file at [output].
     *
     * [onProgress] reports 0..100 and is advisory; Media3 cannot always estimate progress.
     */
    suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        /** Source duration, used to turn encoder progress into a percentage. */
        durationMs: Long?,
        headers: Map<String, String> = emptyMap(),
        /**
         * Processing choices a [CastDeliveryPlan] cannot express. Defaulted and declared before
         * [onProgress] so existing trailing-lambda call sites are untouched.
         */
        options: MediaProcessOptions = MediaProcessOptions(),
        onProgress: (CastProcessProgress) -> Unit = {},
    ): Result<File>

    /** Aborts an in-flight export. */
    fun cancel()
}

/**
 * One progress tick from a [CastMediaProcessor]. [percent] is advisory (Media3 cannot always
 * estimate it); the rest is only ever populated by the FFmpeg backend, which is the only one that
 * sees real encoder statistics.
 */
data class CastProcessProgress(
    val percent: Int,
    val speedMultiplier: Float? = null,
    val fps: Float? = null,
    val outputBytes: Long? = null,
)

/**
 * The handful of choices that are properties of *how* to process rather than of *what* the output
 * format is, so they have no place in [CastDeliveryPlan].
 *
 * Casting never sets either of these; both exist for the converter, which offers an audio-only
 * preset and a subtitle-handling control.
 */
data class MediaProcessOptions(
    /** Discards the video track entirely. */
    val dropVideo: Boolean = false,
    /**
     * Keeps text subtitle tracks the container carries, when the backend and target container can
     * both hold them. Media3's muxer cannot, so this is honoured only on the FFmpeg path.
     */
    val keepSubtitles: Boolean = false,
)

/**
 * Hardware-accelerated implementation built on Media3 Transformer.
 *
 * Transformer transmuxes rather than re-encodes whenever the requested output format already
 * matches the input, so the remux path costs roughly a file copy. Encoding, when required,
 * goes through MediaCodec and therefore uses the device's hardware encoder.
 */
@OptIn(UnstableApi::class)
class Media3CastMediaProcessor(private val context: Context) : CastMediaProcessor {

    private var transformer: Transformer? = null

    override suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        durationMs: Long?,
        headers: Map<String, String>,
        options: MediaProcessOptions,
        onProgress: (CastProcessProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        // Transformer reports progress only when polled, so nothing ever called onProgress and
        // the UI sat on "Preparing, -1" — no percentage — for the whole export. Polling has to
        // stay on the Transformer's own thread, which is this one.
        val progressJob = launch {
            val holder = ProgressHolder()
            while (isActive) {
                delay(PROGRESS_POLL_MS)
                val instance = transformer ?: continue
                if (instance.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(CastProcessProgress(percent = holder.progress))
                }
            }
        }

        try {
            suspendCancellableCoroutine { continuation ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        transformer = null
                        if (continuation.isActive) continuation.resume(Result.success(output))
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        transformer = null
                        if (continuation.isActive) continuation.resume(Result.failure(exception))
                    }
                }

                val builder = Transformer.Builder(context).addListener(listener)

                // Only pin an output codec for a track the plan actually wants re-encoded.
                // Transformer's default — leaving the MIME type unset — means "same as the input",
                // which is what lets it transmux. Setting these unconditionally forced a full
                // re-encode even on a REMUX plan, so a receiver that decodes the source natively
                // (HEVC on an Ultra, say) still paid for a transcode the planner had ruled out.
                //
                // The codec is read off the plan rather than hardcoded, because the converter can
                // legitimately target HEVC. Cast plans only ever ask for H.264/AAC, so this is a
                // widening with no change to casting behaviour.
                plan.videoTarget?.let { target ->
                    videoMimeTypeFor(target.codec)?.let(builder::setVideoMimeType)
                }
                plan.audioTarget?.let { target ->
                    audioMimeTypeFor(target.codec)?.let(builder::setAudioMimeType)
                }

                // Without an explicit encoder factory Transformer picks its own bitrate, which
                // would make the converter's quality controls do nothing at all. Fallback stays
                // enabled so a device that cannot honour the request degrades instead of failing;
                // onFallbackApplied below reports what actually happened.
                val videoSettings = plan.videoTarget?.let { target ->
                    VideoEncoderSettings.Builder()
                        .setBitrate(target.bitrateBitsPerSecond.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
                        .build()
                }
                val audioSettings = plan.audioTarget?.let { target ->
                    AudioEncoderSettings.Builder()
                        .setBitrate(target.bitrateBitsPerSecond.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
                        .build()
                }
                if (videoSettings != null || audioSettings != null) {
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .apply {
                            videoSettings?.let(::setRequestedVideoEncoderSettings)
                            audioSettings?.let(::setRequestedAudioEncoderSettings)
                        }
                        .setEnableFallback(true)
                        .build()
                    builder.setEncoderFactory(encoderFactory)
                }

                // Transformer's default asset loader builds its own data source and has nowhere to
                // put request headers, so an origin needing them used to fail here — and those are
                // exactly the sources flagged unreachable by the receiver, which is what routes them
                // through this path in the first place. Only swapped in when there are headers to
                // carry, so the ordinary case keeps the stock loader.
                if (headers.isNotEmpty()) {
                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(headers)
                        .setAllowCrossProtocolRedirects(true)
                    builder.setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(
                            context,
                            DefaultDecoderFactory.Builder(context).build(),
                            Clock.DEFAULT,
                            DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory),
                            DataSourceBitmapLoader(context),
                        ),
                    )
                }

                val videoEffects = buildList {
                    plan.videoTarget?.let { target ->
                        // Previously guarded on the target being H.264, which silently skipped
                        // scaling for any other codec — the resolution cap simply did nothing.
                        if (target.width > 0 && target.height > 0) {
                            add(
                                Presentation.createForWidthAndHeight(
                                    target.width,
                                    target.height,
                                    Presentation.LAYOUT_SCALE_TO_FIT,
                                ),
                            )
                        }
                        target.frameRate?.takeIf { it > 0f }?.let { fps ->
                            add(FrameDropEffect.createDefaultFrameDropEffect(fps))
                        }
                    }
                }

                // A downmix is only meaningful when the audio is being re-encoded anyway. The
                // matrix is keyed by input channel count, so one is registered for each layout a
                // real file is likely to carry rather than guessing the source's.
                val audioEffects = buildList {
                    plan.audioTarget?.let { target ->
                        val outputChannels = target.channelCount
                        if (outputChannels in 1..8) {
                            add(
                                ChannelMixingAudioProcessor().apply {
                                    listOf(1, 2, 6, 8)
                                        .filter { it != outputChannels }
                                        .forEach { inputChannels ->
                                            putChannelMixingMatrix(
                                                ChannelMixingMatrix.createForConstantGain(
                                                    inputChannels,
                                                    outputChannels,
                                                ),
                                            )
                                        }
                                },
                            )
                        }
                    }
                }

                val item = EditedMediaItem.Builder(MediaItem.fromUri(sourceUrl))
                    .setRemoveVideo(options.dropVideo)
                    .setEffects(Effects(audioEffects, videoEffects))
                    .build()

                val instance = builder.build()
                transformer = instance
                continuation.invokeOnCancellation { runCatching { instance.cancel() } }

                try {
                    instance.start(item, output.absolutePath)
                } catch (error: Throwable) {
                    transformer = null
                    if (continuation.isActive) continuation.resume(Result.failure(error))
                }
            }
        } finally {
            progressJob.cancel()
        }
    }

    override fun cancel() {
        transformer?.let { runCatching { it.cancel() } }
        transformer = null
    }

    private companion object {
        const val PROGRESS_POLL_MS = 500L
    }
}

/**
 * Chooses the processor for this build.
 *
 * FFmpeg leads when it is bundled, because it decodes formats MediaCodec refuses. Media3
 * remains behind it as a fallback: `h264_mediacodec` is not available on every handset, and
 * when it is missing the Transformer path still handles the ordinary cases.
 */
fun castMediaProcessor(context: Context): CastMediaProcessor {
    val media3 = Media3CastMediaProcessor(context)
    val ffmpeg = createFFmpegCastProcessor(context) ?: return media3
    return FallbackCastMediaProcessor(primary = ffmpeg, secondary = media3)
}

/**
 * Chooses the processor for a *conversion*, which wants the opposite order from casting on the
 * encode path.
 *
 * Cast puts FFmpeg first because its problem is decoding exotic audio in software. A conversion's
 * problem is encoding, and the bundled AAR is FFmpeg 6.0 — MediaCodec encoders landed upstream in
 * 6.1 — so on that build FFmpeg has no video encoder to offer and putting it first would burn a
 * guaranteed failed session on every job before falling back. [ffmpegCanEncodeVideo] comes from an
 * actual `-encoders` probe, so a later AAR flips this back without a code change.
 *
 * On the remux path FFmpeg genuinely is better — it can mux containers Media3's muxer cannot and
 * copy audio Media3 would reject — so it stays first there.
 */
fun conversionMediaProcessor(
    context: Context,
    needsVideoEncode: Boolean,
    ffmpegCanEncodeVideo: Boolean,
): CastMediaProcessor {
    val media3 = Media3CastMediaProcessor(context)
    val ffmpeg = createFFmpegCastProcessor(context) ?: return media3
    return if (needsVideoEncode && !ffmpegCanEncodeVideo) {
        FallbackCastMediaProcessor(primary = media3, secondary = ffmpeg)
    } else {
        FallbackCastMediaProcessor(primary = ffmpeg, secondary = media3)
    }
}

/** MIME type Transformer should target, or null to leave it as the input's. */
internal fun videoMimeTypeFor(codec: CastVideoCodec): String? = when (codec) {
    CastVideoCodec.H264 -> MimeTypes.VIDEO_H264
    CastVideoCodec.HEVC -> MimeTypes.VIDEO_H265
    CastVideoCodec.AV1 -> MimeTypes.VIDEO_AV1
    CastVideoCodec.VP8 -> MimeTypes.VIDEO_VP8
    CastVideoCodec.VP9 -> MimeTypes.VIDEO_VP9
    else -> null
}

internal fun audioMimeTypeFor(codec: CastAudioCodec): String? = when (codec) {
    CastAudioCodec.AAC -> MimeTypes.AUDIO_AAC
    CastAudioCodec.MP3 -> MimeTypes.AUDIO_MPEG
    CastAudioCodec.OPUS -> MimeTypes.AUDIO_OPUS
    CastAudioCodec.VORBIS -> MimeTypes.AUDIO_VORBIS
    else -> null
}

/** Runs [secondary] if [primary] fails, so one unsupported encoder does not sink the cast. */
private class FallbackCastMediaProcessor(
    private val primary: CastMediaProcessor,
    private val secondary: CastMediaProcessor,
) : CastMediaProcessor {

    private var active: CastMediaProcessor = primary

    override suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        durationMs: Long?,
        headers: Map<String, String>,
        options: MediaProcessOptions,
        onProgress: (CastProcessProgress) -> Unit,
    ): Result<File> {
        active = primary
        val first = primary.process(sourceUrl, plan, output, durationMs, headers, options, onProgress)
        if (first.isSuccess) return first

        // A cancellation is a decision, not a failure — retrying the other backend would restart
        // work the user just stopped.
        val error = first.exceptionOrNull()
        if (error is kotlinx.coroutines.CancellationException) return first

        android.util.Log.w("CastProcessor", "Primary processor failed, retrying", error)
        active = secondary
        return secondary.process(sourceUrl, plan, output, durationMs, headers, options, onProgress)
    }

    override fun cancel() {
        active.cancel()
    }
}
