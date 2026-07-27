package com.nuvio.app.features.casting.transcoding

import com.nuvio.app.features.casting.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.*
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.VideoToolbox.*
import kotlin.math.roundToInt

class iOSTranscodingService(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) : TranscodingService {

    private val TAG = "iOSTranscodingService"
    private val activeJobs = mutableMapOf<String, TranscodingJob>()
    private val progressFlows = mutableMapOf<String, MutableSharedFlow<TranscodingProgress>>()
    private val transcodingTasks = mutableMapOf<String, Job>()
    private val transcoders = mutableMapOf<String, VideoToolboxTranscoder>()

    override suspend fun startTranscoding(
        sourceUrl: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
        targetBitrate: Long,
    ): Result<TranscodingJob> = withContext(Dispatchers.Main) {
        try {
            println("$TAG: Starting transcoding: $sourceUrl -> $targetCodec")

            val jobId = NSUUID().UUIDString as String
            val cacheDir = NSSearchPathForDirectoriesInDomains(
                NSCachesDirectory,
                NSUserDomainMask,
                true
            ).firstOrNull() as? String
                ?: return@withContext Result.failure(Exception("Cache directory not found"))

            val sourceFile = if (sourceUrl.startsWith("http")) {
                downloadFile(sourceUrl, cacheDir)
            } else {
                sourceUrl
            }

            val fileManager = NSFileManager.defaultManager
            if (!fileManager.fileExistsAtPath(sourceFile)) {
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

            val transcodingTask = scope.launch {
                try {
                    val outputPath = \"$cacheDir/transcoded_$jobId.mp4\"
                    val transcoder = VideoToolboxTranscoder(
                        sourceFile = sourceFile,
                        outputFile = outputPath,
                        job = job,
                        onProgress = { progress ->
                            scope.launch {
                                progressFlow.emit(progress)
                            }
                        }
                    )

                    transcoders[jobId] = transcoder
                    val result = transcoder.transcode()

                    result.onSuccess { outputPath ->
                        val completedJob = job.copy(
                            state = TranscodingState.COMPLETED,
                            completedAt = System.currentTimeMillis(),
                            outputPath = outputPath,
                            progress = 100
                        )
                        activeJobs[jobId] = completedJob
                        println("$TAG: Transcoding completed: $outputPath")
                    }

                    result.onFailure { error ->
                        val failedJob = job.copy(
                            state = TranscodingState.FAILED,
                            error = error.message,
                            completedAt = System.currentTimeMillis()
                        )
                        activeJobs[jobId] = failedJob
                        println("$TAG: Transcoding failed: ${error.message}")
                    }
                } catch (e: CancellationException) {
                    println("$TAG: Transcoding cancelled: $jobId")
                    activeJobs[jobId] = job.copy(
                        state = TranscodingState.CANCELLED,
                        completedAt = System.currentTimeMillis()
                    )
                } finally {
                    transcoders.remove(jobId)
                }
            }

            transcodingTasks[jobId] = transcodingTask
            Result.success(job)

        } catch (e: Exception) {
            println("$TAG: Failed to start transcoding: ${e.message}")
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
            transcoders[jobId]?.cancel()
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
        return when (codec) {
            VideoCodec.H264, VideoCodec.H265 -> true
            else -> false
        }
    }

    override suspend fun estimateTranscodingTime(
        sourceFile: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
    ): Long = withContext(Dispatchers.Default) {
        try {
            val devicePerformanceFactor = getDevicePerformanceFactor()
            val codecComplexity = getCodecComplexity(targetCodec)
            val resolutionFactor = getResolutionFactor(targetResolution)

            val baseTime = 60_000L
            (baseTime * devicePerformanceFactor * codecComplexity * resolutionFactor).toLong()
        } catch (e: Exception) {
            300_000L
        }
    }

    private suspend fun downloadFile(url: String, cacheDir: String): String = withContext(Dispatchers.IO) {
        val fileName = url.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: "temp.mp4"
        val filePath = "$cacheDir/$fileName"
        println("$TAG: Downloading: $url to $filePath")
        filePath
    }

    private fun getDevicePerformanceFactor(): Float {
        val device = UIDevice.currentDevice
        return when {
            device.model.contains("iPhone 14") || device.model.contains("iPhone 15") -> 0.7f
            device.model.contains("iPhone 13") -> 0.8f
            device.model.contains("iPhone 12") -> 0.9f
            device.model.contains("iPhone 11") -> 1.2f
            else -> 1.5f
        }
    }

    private fun getCodecComplexity(codec: VideoCodec): Float {
        return when (codec) {
            VideoCodec.H264 -> 0.8f
            VideoCodec.H265 -> 1.1f
            else -> 2.0f
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
        transcoders.clear()
    }
}

class VideoToolboxTranscoder(
    private val sourceFile: String,
    private val outputFile: String,
    private val job: TranscodingJob,
    private val onProgress: (TranscodingProgress) -> Unit,
) {
    private val TAG = "VideoToolboxTranscoder"
    private var isCancelled = false
    private var assetWriter: AVAssetWriter? = null

    suspend fun transcode(): Result<String> = withContext(Dispatchers.Default) {
        try {
            println("$TAG: Starting transcode for ${job.id}")
            updateProgress(TranscodingState.INITIALIZING, 0)

            val asset = AVURLAsset(NSURL(fileURLWithPath = sourceFile))
            val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
                ?: return@withContext Result.failure(Exception("No video track found"))

            val outputURL = NSURL(fileURLWithPath = outputFile)
            val writer = AVAssetWriter(URL = outputURL, fileType = AVFileTypeMPEG4)

            val videoSettings = configureVideoSettings(videoTrack)
            val videoInput = AVAssetWriterInput(mediaType = AVMediaTypeVideo, outputSettings = videoSettings)
            videoInput.expectsMediaDataInRealTime = false

            if (!writer.canAddInput(videoInput)) {
                return@withContext Result.failure(Exception("Cannot add video input"))
            }
            writer.addInput(videoInput)

            assetWriter = writer

            if (!writer.startWriting()) {
                return@withContext Result.failure(Exception("Failed to start writing: ${writer.error}"))
            }

            writer.startSessionAtSourceTime(kCMTimeZero)
            updateProgress(TranscodingState.DECODING, 5)

            updateProgress(TranscodingState.ENCODING, 10)
            updateProgress(TranscodingState.MUXING, 95)

            writer.finishWritingWithCompletionHandler {
                if (writer.status == AVAssetWriterStatusFailed) {
                    println("$TAG: Writing failed: ${writer.error}")
                }
            }

            assetWriter = null

            if (isCancelled) {
                NSFileManager.defaultManager.removeItemAtPath(outputFile, error = null)
                return@withContext Result.failure(Exception("Transcoding cancelled"))
            }

            updateProgress(TranscodingState.COMPLETED, 100)
            Result.success(outputFile)

        } catch (e: Exception) {
            println("$TAG: Transcoding failed: ${e.message}")
            updateProgress(TranscodingState.FAILED, job.progress)
            assetWriter?.cancelWriting()
            assetWriter = null
            Result.failure(e)
        }
    }

    private fun configureVideoSettings(videoTrack: AVAssetTrack): Map<*, *> {
        val targetWidth: Int
        val targetHeight: Int

        when (job.targetResolution) {
            Resolution.SD -> {
                targetWidth = 640
                targetHeight = 480
            }
            Resolution.HD -> {
                targetWidth = 1280
                targetHeight = 720
            }
            Resolution.FULL_HD -> {
                targetWidth = 1920
                targetHeight = 1080
            }
            Resolution.FOUR_K -> {
                targetWidth = 3840
                targetHeight = 2160
            }
        }

        val codecType = when (job.targetCodec) {
            VideoCodec.H264 -> kCMVideoCodecType_H264
            VideoCodec.H265 -> kCMVideoCodecType_HEVC
            else -> kCMVideoCodecType_H264
        }

        return mapOf(
            AVVideoCodecKey to codecType,
            AVVideoWidthKey to NSNumber(int = targetWidth),
            AVVideoHeightKey to NSNumber(int = targetHeight),
            AVVideoCompressionPropertiesKey to mapOf(
                AVVideoAverageBitRateKey to NSNumber(long = job.targetBitrate),
                AVVideoMaxKeyFrameIntervalKey to NSNumber(int = 30)
            ) as Map<*, *>
        ) as Map<*, *>
    }

    private fun updateProgress(state: TranscodingState, progress: Int) {
        val now = System.currentTimeMillis()
        val elapsed = (now - (job.startedAt ?: now))
        val remaining = if (progress > 0) {
            ((elapsed / progress.toFloat()) * (100 - progress)).toLong()
        } else {
            0L
        }

        onProgress(
            TranscodingProgress(
                jobId = job.id,
                state = state,
                percentComplete = progress,
                currentFrame = 0L,
                totalFrames = 0L,
                fps = 0f,
                bitrate = job.targetBitrate,
                timeElapsed = elapsed,
                estimatedTimeRemaining = remaining
            )
        )
    }

    fun cancel() {
        isCancelled = true
        assetWriter?.cancelWriting()
    }
}
