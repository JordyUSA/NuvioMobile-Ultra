package com.nuvio.app.features.casting.model

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

@Serializable
data class TranscodingJob(
    val id: String,
    val sourceUrl: String,
    val targetCodec: VideoCodec,
    val targetResolution: Resolution,
    val targetBitrate: Long,
    val audioCodec: AudioCodec,
    val state: TranscodingState = TranscodingState.PENDING,
    val progress: Int = 0,
    val estimatedTimeRemaining: Long = 0L,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(), // ✅ Fixed for KMP / iOS
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
    val outputPath: String? = null,
    val useHardwareAcceleration: Boolean = true,
)

enum class TranscodingState {
    PENDING,
    INITIALIZING,
    DECODING,
    ENCODING,
    MUXING,
    COMPLETED,
    CANCELLED,
    FAILED
}

@Serializable
data class TranscodingProgress(
    val jobId: String,
    val state: TranscodingState,
    val percentComplete: Int,
    val currentFrame: Long,
    val totalFrames: Long,
    val fps: Float,
    val bitrate: Long,
    val timeElapsed: Long,
    val estimatedTimeRemaining: Long,
)

@Serializable
data class HardwareEncoderInfo(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportedProfiles: List<Int>,
    val supportedLevels: List<Int>,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val maxBitrate: Long,
)

@Serializable
data class StreamMetadata(
    val duration: Long,
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrate: Long,
    val audioChannels: Int,
    val audioSampleRate: Int,
    val container: Container,
)

enum class VideoCodec {
    H264, H265, VP8, VP9, AV1, MPEG2, MPEG4
}

enum class AudioCodec {
    AAC, MP3, OPUS, FLAC, DOLBY_DIGITAL, DOLBY_ATMOS, VORBIS, PCM
}

enum class Container {
    MP4, WEBM, MKV, OGG, TS, MOV
}

enum class Resolution {
    SD, HD, FULL_HD, FOUR_K
}
