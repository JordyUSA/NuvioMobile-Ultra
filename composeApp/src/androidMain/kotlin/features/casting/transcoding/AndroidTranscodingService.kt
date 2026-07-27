package com.nuvio.app.features.casting.transcoding

import android.content.Context
import android.util.Log
import com.nuvio.app.features.casting.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.*

class AndroidTranscodingService(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) : TranscodingService {

    private val TAG = "AndroidTranscodingService"
    private val encoderDetector = HardwareEncoderDetector()
    private val activeJobs = mutableMapOf<String, TranscodingJob>()
    private val progressFlows = mutableMapOf<String, MutableSharedFlow<TranscodingProgress>>()
    private val transcodingTasks = mutableMapOf<String, Job>()

    override suspend fun startTranscoding(
        sourceUrl: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
        targetBitrate: Long,
    ): Result<TranscodingJob> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting transcoding: $sourceUrl -> $targetCodec")

            val jobId = UUID.randomUUID().toString()
            val cacheDir = File(context.cacheDir, "transcoding")
            cacheDir.mkdirs()

            // Download file if remote URL
            val sourceFile = if (sourceUrl.startsWith("http")) {
                downloadFile(sourceUrl, cacheDir)
            } else {
                File(sourceUrl)
            }

            if (!sourceFile.exists()) {
                return@withContext Result.failure(Exception("Source file not found: $sourceUrl"))
            }

            val job = TranscodingJob(
                id = jobId,
                sourceUrl = sourceUrl,
                targetCodec = targetCodec,
                targetResolution = targetResolution,
                targetBitrate = targetBitrate,
                audioCodec = AudioCodec.AAC,
                state = TranscodingState.PENDING,
                useHardwareAcceleration = supportsHardwareAcceleration(targetCodec),
                startedAt = System.currentTimeMillis()
            )

            activeJobs[jobId] = job
            val progressFlow = MutableSharedFlow<TranscodingProgress>(replay = 1)
            progressFlows[jobId] = progressFlow

            // Start transcoding in background
            val transcodingTask = scope.launch {
                try {
                    val transcoder = MediaCodecTranscoder(
                        sourceFile = sourceFile,
                        job = job,
                        onProgress = { progress ->
                            scope.launch {
                                progressFlow.emit(progress)
                            }
                        }
                    )

                    val result = transcoder.transcode()
                    result.onSuccess { outputPath ->
                        val completedJob = job.copy(
                            state = TranscodingState.COMPLETED,
                            completedAt = System.currentTimeMillis(),
                            outputPath = outputPath,
                            progress = 100
                        )
                        activeJobs[jobId] = completedJob
                        Log.d(TAG, "Transcoding completed: $outputPath")
                    }
                    result.onFailure { error ->
                        val failedJob = job.copy(
                            state = TranscodingState.FAILED,
                            error = error.message,
                            completedAt = System.currentTimeMillis()
                        )
                        activeJobs[jobId] = failedJob
                        Log.e(TAG, "Transcoding failed", error)
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Transcoding cancelled: $jobId")
                    activeJobs[jobId] = job.copy(
                        state = TranscodingState.CANCELLED,
                        completedAt = System.currentTimeMillis()
                    )
                }
            }

            transcodingTasks[jobId] = transcodingTask
            Result.success(job)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transcoding", e)
            Result.failure(e)
        }
    }

    override fun getProgressUpdates(jobId: String): Flow<TranscodingProgress> {
        return progressFlows[jobId]
            ?.asSharedFlow()
            ?: throw IllegalArgumentException("Job not found: $jobId")
    }

    override suspend fun cancelTranscoding(jobId: String): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            transcodingTasks[jobId]?.cancel()
            activeJobs[jobId] = activeJobs[jobId]?.copy(
                state = TranscodingState.CANCELLED,
                completedAt = System.currentTimeMillis()
            ) ?: throw IllegalArgumentException("Job not found")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getJobStatus(jobId: String): TranscodingJob? {
        return activeJobs[jobId]
    }

    override suspend fun listActiveJobs(): List<TranscodingJob> {
        return activeJobs.values.filter {
            it.state !in listOf(TranscodingState.COMPLETED, TranscodingState.FAILED, TranscodingState.CANCELLED)
        }
    }

    override suspend fun supportsHardwareAcceleration(codec: VideoCodec): Boolean {
        return encoderDetector.canHardwareAccelerate(codec)
    }

    override suspend fun estimateTranscodingTime(
        sourceFile: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
    ): Long = withContext(Dispatchers.Default) {
        try {
            // Estimate based on device performance
            val devicePerformanceFactor = getDevicePerformanceFactor()
            val codecComplexity = getCodecComplexity(targetCodec)
            val resolutionFactor = getResolutionFactor(targetResolution)

            val baseTime = 60_000L // Base 60 seconds estimate
            (baseTime * devicePerformanceFactor * codecComplexity * resolutionFactor).toLong()
        } catch (e: Exception) {
            300_000L // 5 minute default fallback
        }
    }

    private suspend fun downloadFile(url: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val fileName = url.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: "temp.mp4"
        val file = File(cacheDir, fileName)

        // Simple download logic - in production use OkHttp or similar
        Log.d(TAG, "Downloading: $url to ${file.absolutePath}")
        // TODO: Implement actual download with progress tracking

        file
    }

    private fun getDevicePerformanceFactor(): Float {
        // On older devices, transcoding takes longer
        return when {
            Runtime.getRuntime().availableProcessors() >= 8 -> 0.8f
            Runtime.getRuntime().availableProcessors() >= 4 -> 1.0f
            else -> 1.5f
        }
    }

    private fun getCodecComplexity(codec: VideoCodec): Float {
        return when (codec) {
            VideoCodec.H264 -> 0.8f
            VideoCodec.VP8 -> 0.9f
            VideoCodec.H265 -> 1.2f
            VideoCodec.VP9 -> 1.3f
            VideoCodec.AV1 -> 2.0f
            else -> 1.0f
        }
    }

    private fun getResolutionFactor(resolution: Resolution): Float {
        return when (resolution) {
            Resolution.SD -> 0.5f
            Resolution.HD -> 0.8f
            Resolution.FULL_HD -> 1.0f
            Resolution.FOUR_K -> 2.5f
        }
    }

    fun cleanup() {
        scope.cancel()
        activeJobs.clear()
        progressFlows.clear()
        transcodingTasks.clear()
    }
}
