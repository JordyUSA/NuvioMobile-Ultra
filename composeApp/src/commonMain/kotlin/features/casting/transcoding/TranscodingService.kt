package com.nuvio.app.features.casting.transcoding

import com.nuvio.app.features.casting.model.*
import kotlinx.coroutines.flow.Flow

interface TranscodingService {
    /**
     * Start on-device transcoding job
     */
    suspend fun startTranscoding(
        sourceUrl: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
        targetBitrate: Long,
    ): Result<TranscodingJob>

    /**
     * Get transcoding progress updates
     */
    fun getProgressUpdates(jobId: String): Flow<TranscodingProgress>

    /**
     * Cancel ongoing transcoding job
     */
    suspend fun cancelTranscoding(jobId: String): Result<Unit>

    /**
     * Get job status
     */
    suspend fun getJobStatus(jobId: String): TranscodingJob?

    /**
     * List all active transcoding jobs
     */
    suspend fun listActiveJobs(): List<TranscodingJob>

    /**
     * Check if device supports hardware acceleration
     */
    suspend fun supportsHardwareAcceleration(codec: VideoCodec): Boolean

    /**
     * Get estimated transcoding time
     */
    suspend fun estimateTranscodingTime(
        sourceFile: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
    ): Long
}
