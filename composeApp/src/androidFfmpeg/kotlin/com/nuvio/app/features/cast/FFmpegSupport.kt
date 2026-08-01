package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
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
        onProgress: (Int) -> Unit,
    ): Result<File> {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val command = buildCommand(sourceUrl, plan, output, headers)
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
                    if (durationMs != null && durationMs > 0) {
                        val done = statistics.time.toLong()
                        onProgress(((done * 100) / durationMs).coerceIn(0, 100).toInt())
                    }
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

    private fun buildCommand(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        headers: Map<String, String>,
    ): String {
        val parts = mutableListOf<String>()

        // Request headers have to precede the input they apply to.
        if (headers.isNotEmpty()) {
            val joined = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            parts += listOf("-headers", quote(joined))
        }
        parts += listOf("-i", quote(sourceUrl))

        // First video and first audio track. Audio is optional so a video-only source does
        // not abort the whole export.
        parts += listOf("-map", "0:v:0", "-map", "0:a:0?")

        val video = plan.videoTarget
        if (video == null) {
            parts += listOf("-c:v", "copy")
        } else {
            // Hardware encoder. This is the entire reason for enabling MediaCodec in the
            // build; software x264 would also drag the GPL in.
            parts += listOf("-c:v", "h264_mediacodec")
            parts += listOf("-b:v", video.bitrateBitsPerSecond.toString())
            parts += listOf("-vf", "scale=${video.width}:${video.height}")
            video.frameRate?.let { parts += listOf("-r", it.toInt().toString()) }
        }

        val audio = plan.audioTarget
        if (audio == null) {
            parts += listOf("-c:a", "copy")
        } else {
            // The planner only ever targets AAC, since it is the one codec every Cast
            // receiver decodes without relying on HDMI passthrough.
            parts += listOf("-c:a", "aac")
            parts += listOf("-b:a", audio.bitrateBitsPerSecond.toString())
            parts += listOf("-ac", audio.channelCount.toString())
        }

        // Subtitles are delivered to the receiver as separate VTT tracks, so drop any the
        // container carries rather than failing on a codec MP4 cannot hold.
        parts += listOf("-sn")
        // Put the index at the front so the receiver can seek without fetching the tail.
        parts += listOf("-movflags", "+faststart")
        parts += listOf("-y", quote(output.absolutePath))

        return parts.joinToString(" ")
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "FFmpegCast"
        const val LOG_TAIL_CHARS = 2000
    }
}
