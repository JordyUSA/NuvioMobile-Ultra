package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSURL

internal actual object ConversionEngine {

    private var pending: PendingJob? = null

    private class PendingJob(
        val id: String,
        val result: CompletableDeferred<Result<Unit>>,
        val onProgress: (ConversionProgress) -> Unit,
    )

    private val listener = object : ConverterCallbackListener {
        override fun onProgress(jobId: String, percent: Int, etaMs: Long, speedMultiplier: Float, fps: Float) {
            // A late callback from a job that has already been cancelled must not move the bar for
            // whatever is running now, which is the whole reason the bridge carries a job id.
            pending?.takeIf { it.id == jobId }?.onProgress?.invoke(
                ConversionProgress(
                    percent = percent,
                    etaMs = etaMs.takeIf { it >= 0L },
                    speedMultiplier = speedMultiplier.takeIf { it >= 0f },
                    fps = fps.takeIf { it >= 0f },
                ),
            )
        }

        override fun onCompleted(jobId: String, success: Boolean, message: String?) {
            val job = pending?.takeIf { it.id == jobId } ?: return
            pending = null
            job.result.complete(
                if (success) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(message ?: "Conversion failed"))
                },
            )
        }
    }

    actual suspend fun convert(
        sourceLocalFileUri: String,
        plan: MediaPlan,
        dropVideo: Boolean,
        keepSubtitles: Boolean,
        outputFileName: String,
        durationMs: Long?,
        preferHardwareEncoder: Boolean,
        onProgress: (ConversionProgress) -> Unit,
    ): Result<ConversionOutput> {
        val bridge = ConverterHost.requireBridge()
            ?: return Result.failure(IllegalStateException("Converter bridge is not installed"))
        val directory = DownloadsPlatformDownloader.downloadsDirectoryPath()
            ?: return Result.failure(IllegalStateException("Downloads directory is unavailable"))

        val jobId = outputFileName
        val workPath = "$directory/$outputFileName$CONVERSION_WORK_SUFFIX"
        val sourcePath = sourceLocalFileUri.toLocalPathOrSelf()

        val deferred = CompletableDeferred<Result<Unit>>()
        pending = PendingJob(jobId, deferred, onProgress)
        ConverterHost.setListener(listener)

        val video = plan.videoTarget
        val audio = plan.audioTarget

        bridge.start(
            jobId = jobId,
            sourceUrl = sourcePath,
            outputPath = workPath,
            container = plan.targetContainer.ffmpegMuxerName(),
            dropVideo = dropVideo,
            reencodeVideo = video != null,
            videoCodec = (video?.codec ?: CastVideoCodec.H264).ffmpegCodecName(),
            videoWidth = video?.width ?: 0,
            videoHeight = video?.height ?: 0,
            videoBitrateBitsPerSecond = video?.bitrateBitsPerSecond ?: 0L,
            videoFrameRate = video?.frameRate ?: 0f,
            preferHardwareEncoder = preferHardwareEncoder,
            reencodeAudio = audio != null,
            audioCodec = (audio?.codec ?: CastAudioCodec.AAC).ffmpegCodecName(),
            audioChannelCount = audio?.channelCount ?: 0,
            audioBitrateBitsPerSecond = audio?.bitrateBitsPerSecond ?: 0L,
            keepSubtitles = keepSubtitles,
            durationMs = durationMs ?: 0L,
        )

        val outcome = try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            bridge.cancel(jobId)
            pending = null
            DownloadsPlatformDownloader.removeFile("file://$workPath")
            throw cancellation
        }

        pending = null

        outcome.exceptionOrNull()?.let { error ->
            DownloadsPlatformDownloader.removeFile("file://$workPath")
            return Result.failure(error)
        }

        val finalUri = DownloadsPlatformDownloader.renameFile(
            fromLocalFileUri = "file://$workPath",
            toFileName = outputFileName,
        ) ?: run {
            DownloadsPlatformDownloader.removeFile("file://$workPath")
            return Result.failure(IllegalStateException("Could not finalize the converted file"))
        }

        val size = DownloadsPlatformDownloader.fileSizeBytes(finalUri)
        if (size == null) {
            DownloadsPlatformDownloader.removeFile(finalUri)
            return Result.failure(IllegalStateException("Conversion produced an empty file"))
        }

        return Result.success(ConversionOutput(localFileUri = finalUri, sizeBytes = size))
    }

    actual fun cancel() {
        val job = pending ?: return
        ConverterHost.requireBridge()?.cancel(job.id)
        pending = null
        job.result.complete(Result.failure(CancellationException("Conversion cancelled")))
    }
}

/** FFmpeg wants a filesystem path, not a `file://` URL, for a local input. */
private fun String.toLocalPathOrSelf(): String {
    if (!startsWith("file:", ignoreCase = true)) return this
    return NSURL.URLWithString(this)?.path ?: removePrefix("file://")
}

private fun CastContainer.ffmpegMuxerName(): String = when (this) {
    CastContainer.MATROSKA -> "matroska"
    CastContainer.WEBM -> "webm"
    else -> "mp4"
}

private fun CastVideoCodec.ffmpegCodecName(): String = when (this) {
    CastVideoCodec.HEVC -> "hevc"
    else -> "h264"
}

private fun CastAudioCodec.ffmpegCodecName(): String = when (this) {
    CastAudioCodec.MP3 -> "mp3"
    else -> "aac"
}
