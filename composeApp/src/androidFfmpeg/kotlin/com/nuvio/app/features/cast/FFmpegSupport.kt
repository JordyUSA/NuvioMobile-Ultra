package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Compiled only when the project is built with `-Pnuvio.android.ffmpeg=true` and the
 * FFmpegKit AAR is present in composeApp/libs. The matching no-op lives in
 * src/androidNoFfmpeg, so the app builds either way.
 */
internal fun createFFmpegCastProcessor(context: Context): CastMediaProcessor? =
    FFmpegCastMediaProcessor()

/**
 * What this particular FFmpeg build can actually do, discovered rather than assumed.
 *
 * The distinction matters: the vendored AAR is FFmpeg 6.0 built without `--enable-gpl`, so there
 * is no libx264, and MediaCodec *encoders* only landed upstream in 6.1 — which means this build
 * very likely has no way to encode video at all, however much `--enable-android-media-codec`
 * suggests otherwise. Asking the binary instead of trusting the build flags is what stops the
 * converter from queueing a job that cannot run, and it means a later AAR upgrades the behaviour
 * without a code change.
 */
internal fun ffmpegCapabilityReport(): FFmpegCapabilityReport? = cachedReport

private val cachedReport: FFmpegCapabilityReport? by lazy {
    runCatching {
        val encoders = FFmpegKit.execute("-hide_banner -encoders").allLogsAsString.orEmpty()
        val muxers = FFmpegKit.execute("-hide_banner -muxers").allLogsAsString.orEmpty()

        val names = KNOWN_ENCODERS.filterTo(mutableSetOf()) { name ->
            Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(encoders)
        }

        val video = buildSet {
            if (ENCODER_H264_MEDIACODEC in names || ENCODER_LIBX264 in names) add(CastVideoCodec.H264)
            if (ENCODER_HEVC_MEDIACODEC in names || ENCODER_LIBX265 in names) add(CastVideoCodec.HEVC)
        }
        val audio = buildSet {
            if (ENCODER_AAC in names) add(CastAudioCodec.AAC)
            if (ENCODER_MP3 in names) add(CastAudioCodec.MP3)
        }
        val containers = buildSet {
            add(CastContainer.MP4)
            if (muxers.contains("matroska")) add(CastContainer.MATROSKA)
            if (muxers.contains("webm")) add(CastContainer.WEBM)
        }

        FFmpegCapabilityReport(
            videoEncoders = video,
            audioEncoders = audio,
            containers = containers,
            hasSoftwareVideoEncoder = ENCODER_LIBX264 in names || ENCODER_LIBX265 in names,
            encoderNames = names,
        ).also { Log.i(TAG, "ffmpeg capabilities: $it") }
    }.getOrElse {
        // A failed probe must not read as "everything works" — the converter would then queue jobs
        // this build cannot run. Reporting no encoders leaves remux available and routes every
        // encode to Media3, which is the safe half of the split.
        Log.w(TAG, "FFmpeg capability probe failed; treating FFmpeg as encode-incapable", it)
        FFmpegCapabilityReport(
            videoEncoders = emptySet(),
            audioEncoders = emptySet(),
            containers = setOf(CastContainer.MP4),
            hasSoftwareVideoEncoder = false,
            encoderNames = emptySet(),
        )
    }
}

/**
 * FFmpeg-backed processor.
 *
 * This is what closes the gap left by Media3 Transformer: Transformer can only decode what
 * the handset's own MediaCodec decodes, so DTS, DTS-HD, TrueHD and unusual containers fail
 * there. FFmpeg decodes them in software while still encoding through MediaCodec, so the
 * expensive half of the work stays on the GPU.
 */
private class FFmpegCastMediaProcessor : CastMediaProcessor {

    @Volatile
    private var sessionId: Long? = null

    override suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        durationMs: Long?,
        headers: Map<String, String>,
        options: MediaProcessOptions,
        onProgress: (CastProcessProgress) -> Unit,
    ): Result<File> {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val command = buildCommand(sourceUrl, plan, output, headers, options)
            ?: return Result.failure(
                IllegalStateException("This FFmpeg build has no encoder for the requested output"),
            )
        Log.i(TAG, "ffmpeg $command")

        return suspendCancellableCoroutine { continuation ->
            val session: FFmpegSession = FFmpegKit.executeAsync(
                command,
                { completed ->
                    sessionId = null
                    if (!continuation.isActive) return@executeAsync
                    if (ReturnCode.isSuccess(completed.returnCode)) {
                        continuation.resume(Result.success(output))
                    } else {
                        // The tail of the log is what actually explains a failure; the return
                        // code alone is almost always just 1.
                        val detail = completed.failStackTrace
                            ?: completed.allLogsAsString?.takeLast(LOG_TAIL_CHARS)
                            ?: "ffmpeg exited with ${completed.returnCode}"
                        continuation.resume(Result.failure(IllegalStateException(detail)))
                    }
                },
                { log -> Log.v(TAG, log.message) },
                { statistics ->
                    val percent = if (durationMs != null && durationMs > 0) {
                        val done = statistics.time.toLong()
                        ((done * 100) / durationMs).coerceIn(0, 100).toInt()
                    } else {
                        -1
                    }
                    onProgress(
                        CastProcessProgress(
                            percent = percent,
                            // getSpeed() is "encode time / wall time", i.e. exactly playback-speed
                            // multiplier already.
                            speedMultiplier = statistics.speed.toFloat().takeIf { it > 0f },
                            fps = statistics.videoFps.takeIf { it > 0f },
                            outputBytes = statistics.size.takeIf { it > 0L },
                        ),
                    )
                },
            )
            sessionId = session.sessionId
            continuation.invokeOnCancellation {
                sessionId?.let { FFmpegKit.cancel(it) }
                sessionId = null
            }
        }
    }

    override fun cancel() {
        sessionId?.let { FFmpegKit.cancel(it) }
        sessionId = null
    }

    /** Null when the requested output needs an encoder this build does not carry. */
    private fun buildCommand(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        headers: Map<String, String>,
        options: MediaProcessOptions,
    ): String? {
        val report = ffmpegCapabilityReport()
        val parts = mutableListOf<String>()

        // Request headers have to precede the input they apply to.
        if (headers.isNotEmpty()) {
            val joined = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            parts += listOf("-headers", quote(joined))
        }
        parts += listOf("-i", quote(sourceUrl))

        val container = plan.targetContainer
        val keepSubtitles = options.keepSubtitles && subtitleCodecFor(container) != null

        // First video and first audio track. Audio is optional so a video-only source does
        // not abort the whole export.
        if (!options.dropVideo) parts += listOf("-map", "0:v:0")
        parts += listOf("-map", "0:a:0?")
        if (keepSubtitles) parts += listOf("-map", "0:s?")

        val video = plan.videoTarget
        when {
            options.dropVideo -> parts += "-vn"
            video == null -> parts += listOf("-c:v", "copy")
            else -> {
                val encoder = videoEncoderName(video.codec, report) ?: return null
                parts += listOf("-c:v", encoder)
                parts += listOf("-b:v", video.bitrateBitsPerSecond.toString())
                if (video.width > 0 && video.height > 0) {
                    parts += listOf("-vf", "scale=${video.width}:${video.height}")
                }
                video.frameRate?.takeIf { it > 0f }?.let { parts += listOf("-r", it.toInt().toString()) }
            }
        }

        val audio = plan.audioTarget
        if (audio == null) {
            parts += listOf("-c:a", "copy")
        } else {
            val encoder = audioEncoderName(audio.codec, report) ?: return null
            parts += listOf("-c:a", encoder)
            parts += listOf("-b:a", audio.bitrateBitsPerSecond.toString())
            parts += listOf("-ac", audio.channelCount.toString())
        }

        // Bitmap subtitles (PGS, VOBSUB) cannot be converted to a text format and MP4 cannot hold
        // them, so even "keep" only ever keeps the text tracks.
        if (keepSubtitles) {
            parts += listOf("-c:s", subtitleCodecFor(container)!!)
        } else {
            parts += "-sn"
        }

        muxerFormatFor(container)?.let { parts += listOf("-f", it) }
        if (container == CastContainer.MP4 || container == CastContainer.QUICKTIME) {
            // Put the index at the front so a player can seek without fetching the tail.
            parts += listOf("-movflags", "+faststart")
        }
        parts += listOf("-y", quote(output.absolutePath))

        return parts.joinToString(" ")
    }

    /**
     * The concrete encoder to name on the command line. MediaCodec is preferred where it exists
     * because it keeps the expensive half of the work off the CPU; libx264 is the fallback on
     * builds that carry it (iOS does, this Android AAR does not).
     */
    private fun videoEncoderName(codec: CastVideoCodec, report: FFmpegCapabilityReport?): String? {
        val names = report?.encoderNames ?: return null
        return when (codec) {
            CastVideoCodec.H264 -> listOf(ENCODER_H264_MEDIACODEC, ENCODER_LIBX264).firstOrNull { it in names }
            CastVideoCodec.HEVC -> listOf(ENCODER_HEVC_MEDIACODEC, ENCODER_LIBX265).firstOrNull { it in names }
            else -> null
        }
    }

    private fun audioEncoderName(codec: CastAudioCodec, report: FFmpegCapabilityReport?): String? {
        val names = report?.encoderNames ?: return null
        return when (codec) {
            CastAudioCodec.AAC -> ENCODER_AAC.takeIf { it in names }
            CastAudioCodec.MP3 -> ENCODER_MP3.takeIf { it in names }
            else -> null
        }
    }

    private fun muxerFormatFor(container: CastContainer): String? = when (container) {
        CastContainer.MATROSKA -> "matroska"
        CastContainer.WEBM -> "webm"
        else -> null
    }

    private fun subtitleCodecFor(container: CastContainer): String? = when (container) {
        CastContainer.MP4, CastContainer.QUICKTIME -> "mov_text"
        CastContainer.MATROSKA -> "srt"
        CastContainer.WEBM -> "webvtt"
        else -> null
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}

private const val TAG = "FFmpegCast"
private const val LOG_TAIL_CHARS = 2000
private const val ENCODER_H264_MEDIACODEC = "h264_mediacodec"
private const val ENCODER_HEVC_MEDIACODEC = "hevc_mediacodec"
private const val ENCODER_LIBX264 = "libx264"
private const val ENCODER_LIBX265 = "libx265"
private const val ENCODER_AAC = "aac"
private const val ENCODER_MP3 = "libmp3lame"

private val KNOWN_ENCODERS = listOf(
    ENCODER_H264_MEDIACODEC,
    ENCODER_HEVC_MEDIACODEC,
    ENCODER_LIBX264,
    ENCODER_LIBX265,
    ENCODER_AAC,
    ENCODER_MP3,
)
