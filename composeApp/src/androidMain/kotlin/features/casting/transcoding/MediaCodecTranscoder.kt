package com.nuvio.app.features.casting.transcoding

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.nuvio.app.features.casting.model.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer

class MediaCodecTranscoder(
    private val sourceFile: File,
    private val job: TranscodingJob,
    private val onProgress: (TranscodingProgress) -> Unit,
) {
    private val TAG = "MediaCodecTranscoder"
    private val encoderDetector = HardwareEncoderDetector()
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var extractors: List<MediaExtractor> = emptyList()
    private var isCancelled = false

    suspend fun transcode(): Result<String> = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Starting transcode job: ${job.id}")
            updateProgress(TranscodingState.INITIALIZING, 0)

            // Detect best encoder for target codec
            val encoderInfo = encoderDetector.getBestEncoderForCodec(job.targetCodec)
                ?: return@withContext Result.failure(
                    Exception("No encoder found for ${job.targetCodec}")
                )

            Log.d(TAG, "Using encoder: ${encoderInfo.name} (hardware: ${encoderInfo.isHardwareAccelerated})")

            val outputFile = File(sourceFile.parent, "transcoded_${job.id}.mp4")
            val metadata = extractStreamMetadata(sourceFile)
                ?: return@withContext Result.failure(Exception("Failed to extract stream metadata"))

            setupMediaCodec(encoderInfo, metadata, outputFile)
            performTranscoding(metadata)

            if (isCancelled) {
                outputFile.delete()
                return@withContext Result.failure(Exception("Transcoding cancelled"))
            }

            updateProgress(TranscodingState.COMPLETED, 100)
            Log.d(TAG, "Transcoding completed: ${outputFile.absolutePath}")
            Result.success(outputFile.absolutePath)

        } catch (e: CancellationException) {
            isCancelled = true
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcoding failed", e)
            updateProgress(TranscodingState.FAILED, job.progress)
            Result.failure(e)
        } finally {
            cleanup()
        }
    }

    private fun extractStreamMetadata(sourceFile: File): StreamMetadata? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)

            val videoTrack = (0 until extractor.trackCount)
                .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return null

            val format = extractor.getTrackFormat(videoTrack)

            StreamMetadata(
                duration = format.getLong(MediaFormat.KEY_DURATION),
                videoCodec = detectCodec(format.getString(MediaFormat.KEY_MIME) ?: ""),
                audioCodec = AudioCodec.AAC,
                width = format.getInteger(MediaFormat.KEY_WIDTH),
                height = format.getInteger(MediaFormat.KEY_HEIGHT),
                frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE),
                bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong(),
                audioChannels = 2,
                audioSampleRate = 48000,
                container = Container.MP4
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract metadata", e)
            null
        }
    }

    private fun setupMediaCodec(
        encoderInfo: HardwareEncoderInfo,
        metadata: StreamMetadata,
        outputFile: File
    ) {
        val format = MediaFormat.createVideoFormat(
            encoderInfo.mimeType,
            metadata.width,
            metadata.height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, job.targetBitrate.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, metadata.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodec.CodecCapabilities.COLOR_FormatSurface)
        }

        mediaCodec = MediaCodec.createEncoderByType(encoderInfo.mimeType)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()

        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun performTranscoding(metadata: StreamMetadata) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)

        val videoTrackIndex = (0 until extractor.trackCount)
            .firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: return

        extractor.selectTrack(videoTrackIndex)
        updateProgress(TranscodingState.DECODING, 5)

        val inputBuffers = codec.inputBuffers
        val outputBuffers = codec.outputBuffers
        val bufferInfo = MediaCodec.BufferInfo()
        var presentationTime = 0L
        var frameCount = 0L
        val totalFrames = (metadata.duration * metadata.frameRate) / 1_000_000L

        val outputTrackIndex = IntArray(1) { -1 }
        var muxerStarted = false

        while (!isCancelled) {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = inputBuffers[inputBufferIndex]
                val sampleSize = extractor.readSampleData(inputBuffer, 0)

                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    presentationTime = extractor.sampleTime
                    codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTime, 0)
                    extractor.advance()
                    frameCount++
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputBufferIndex >= 0 -> {
                    if (!muxerStarted) {
                        val outputFormat = codec.outputFormat
                        outputTrackIndex[0] = muxer.addTrack(outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    val outputBuffer = outputBuffers[outputBufferIndex]
                    muxer.writeSampleData(outputTrackIndex[0], outputBuffer, bufferInfo)
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    val progress = ((frameCount.toFloat() / totalFrames) * 100).toInt().coerceIn(0, 99)
                    updateProgress(TranscodingState.ENCODING, progress)
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Format changed, update output format
                    val outputFormat = codec.outputFormat
                    if (outputTrackIndex[0] < 0) {
                        outputTrackIndex[0] = muxer.addTrack(outputFormat)
                        if (!muxerStarted) {
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                }
            }

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
            }
        }

        updateProgress(TranscodingState.MUXING, 95)
        extractor.release()
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

    private fun detectCodec(mimeType: String): VideoCodec {
        return when {
            mimeType.contains("avc") -> VideoCodec.H264
            mimeType.contains("hevc") -> VideoCodec.H265
            mimeType.contains("vp9") -> VideoCodec.VP9
            mimeType.contains("vp8") -> VideoCodec.VP8
            mimeType.contains("av01") -> VideoCodec.AV1
            else -> VideoCodec.H264
        }
    }

    fun cancel() {
        isCancelled = true
    }

    private fun cleanup() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaMuxer?.stop()
            mediaMuxer?.release()
            extractors.forEach { it.release() }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
