package com.nuvio.app.features.converter

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.nuvio.app.features.cast.CastMediaProcessor
import com.nuvio.app.features.cast.CastProcessProgress
import com.nuvio.app.features.cast.MediaProcessOptions
import com.nuvio.app.features.cast.conversionMediaProcessor
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import java.io.File

internal actual object ConversionEngine {

    private const val TAG = "ConversionEngine"
    private const val WAKE_LOCK_TAG = "nuvio:conversion"

    /**
     * A conversion can outlast the system's patience for a job with the screen off. Downloads get
     * away without a wake lock because network activity keeps waking the device; a pure-CPU encode
     * has no such luck and will simply crawl or stall. Capped so a wedged job cannot hold the CPU
     * awake indefinitely.
     */
    private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L

    private var appContext: Context? = null

    @Volatile
    private var activeProcessor: CastMediaProcessor? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
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
        val context = appContext
            ?: return Result.failure(IllegalStateException("Conversion engine is not initialized"))
        val directory = DownloadsPlatformDownloader.downloadsDirectoryPath()
            ?: return Result.failure(IllegalStateException("Downloads directory is unavailable"))

        // Written under a temporary name so a crash or a cancellation can never leave a truncated
        // file wearing a real one. Only the rename below makes it visible as a finished output.
        val workFile = File(directory, "$outputFileName$CONVERSION_WORK_SUFFIX")
        val capabilities = converterCapabilities()

        val processor = conversionMediaProcessor(
            context = context,
            needsVideoEncode = plan.videoTarget != null,
            ffmpegCanEncodeVideo = capabilities.ffmpegVideoEncoders.isNotEmpty(),
        )
        activeProcessor = processor

        val wakeLock = acquireWakeLock(context)
        try {
            val produced = processor.process(
                sourceUrl = sourceLocalFileUri,
                plan = plan,
                output = workFile,
                durationMs = durationMs,
                headers = emptyMap(),
                options = MediaProcessOptions(
                    dropVideo = dropVideo,
                    keepSubtitles = keepSubtitles,
                ),
                onProgress = { raw -> onProgress(raw.toConversionProgress(durationMs)) },
            ).getOrElse { error ->
                workFile.delete()
                return Result.failure(error)
            }

            if (!produced.exists() || produced.length() <= 0L) {
                produced.delete()
                return Result.failure(IllegalStateException("Conversion produced an empty file"))
            }

            val finalUri = DownloadsPlatformDownloader.renameFile(
                fromLocalFileUri = produced.toURI().toString(),
                toFileName = outputFileName,
            ) ?: run {
                produced.delete()
                return Result.failure(IllegalStateException("Could not finalize the converted file"))
            }

            return Result.success(
                ConversionOutput(
                    localFileUri = finalUri,
                    sizeBytes = DownloadsPlatformDownloader.fileSizeBytes(finalUri),
                ),
            )
        } finally {
            activeProcessor = null
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                .onFailure { Log.w(TAG, "Releasing the conversion wake lock failed", it) }
        }
    }

    actual fun cancel() {
        activeProcessor?.cancel()
    }

    /**
     * [CastProcessProgress.speedMultiplier] is encode-time-over-wall-time, so the remaining wall
     * time is just the remaining source duration divided by it — no EWMA needed since FFmpeg's own
     * statistic already smooths this.
     */
    private fun CastProcessProgress.toConversionProgress(durationMs: Long?): ConversionProgress {
        val eta = if (durationMs != null && durationMs > 0 && percent in 0..100) {
            val remainingMs = durationMs - (durationMs * percent / 100)
            speedMultiplier?.takeIf { it > 0f }?.let { (remainingMs / it).toLong() }
        } else {
            null
        }
        return ConversionProgress(
            percent = percent,
            etaMs = eta,
            speedMultiplier = speedMultiplier,
            fps = fps,
        )
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock? =
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { Log.w(TAG, "Could not acquire a wake lock; the encode may stall when idle", it) }
            .getOrNull()
}
