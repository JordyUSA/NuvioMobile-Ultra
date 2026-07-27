package com.nuvio.app.features.casting.transcoding

import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.nuvio.app.features.casting.model.HardwareEncoderInfo
import com.nuvio.app.features.casting.model.VideoCodec

class HardwareEncoderDetector {
    private val TAG = "HardwareEncoderDetector"

    fun detectAvailableEncoders(): List<HardwareEncoderInfo> {
        val encoders = mutableListOf<HardwareEncoderInfo>()
        val mediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        // Target video codecs for Chromecast
        val targetMimes = listOf(
            "video/avc",        // H.264
            "video/hevc",       // H.265
            "video/x-vnd.on2.vp9",  // VP9
            "video/x-vnd.on2.vp8",  // VP8
            "video/av01"        // AV1
        )

        for (codecInfo in mediaCodecList.codecInfos) {
            if (codecInfo.isEncoder) continue

            for (mime in codecInfo.supportedTypes) {
                if (mime !in targetMimes) continue

                try {
                    val capabilities = codecInfo.getCapabilitiesForType(mime)
                    val videoCapabilities = capabilities.videoCapabilities

                    encoders.add(
                        HardwareEncoderInfo(
                            name = codecInfo.name,
                            mimeType = mime,
                            isHardwareAccelerated = codecInfo.isHardwareAccelerated,
                            supportedProfiles = extractProfiles(codecInfo, mime),
                            supportedLevels = extractLevels(codecInfo, mime),
                            maxWidth = videoCapabilities.supportedWidths.upper,
                            maxHeight = videoCapabilities.supportedHeights.upper,
                            maxFrameRate = videoCapabilities.supportedFrameRates.upper.toInt(),
                            maxBitrate = videoCapabilities.bitrateRange.upper.toLong()
                        )
                    )
                    Log.d(TAG, "Found encoder: ${codecInfo.name} for $mime")
                } catch (e: Exception) {
                    Log.d(TAG, "Codec $mime not supported: ${e.message}")
                }
            }
        }

        return encoders
    }

    fun getBestEncoderForCodec(targetCodec: VideoCodec): HardwareEncoderInfo? {
        val encoders = detectAvailableEncoders()
        val targetMime = when (targetCodec) {
            VideoCodec.H264 -> "video/avc"
            VideoCodec.H265 -> "video/hevc"
            VideoCodec.VP9 -> "video/x-vnd.on2.vp9"
            VideoCodec.VP8 -> "video/x-vnd.on2.vp8"
            VideoCodec.AV1 -> "video/av01"
            else -> return null
        }

        // Prefer hardware accelerated encoders
        return encoders.filter { it.mimeType == targetMime }
            .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }
            .firstOrNull()
    }

    private fun extractProfiles(codecInfo: MediaCodec.CodecInfo, mime: String): List<Int> {
        return try {
            val capabilities = codecInfo.getCapabilitiesForType(mime)
            when (mime) {
                "video/avc" -> {
                    val caps = capabilities as? MediaCodec.CodecCapabilities
                    caps?.profileLevels?.map { it.profile }?.distinct() ?: emptyList()
                }
                "video/hevc" -> {
                    val caps = capabilities as? MediaCodec.CodecCapabilities
                    caps?.profileLevels?.map { it.profile }?.distinct() ?: emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractLevels(codecInfo: MediaCodec.CodecInfo, mime: String): List<Int> {
        return try {
            val capabilities = codecInfo.getCapabilitiesForType(mime)
            when (mime) {
                "video/avc", "video/hevc" -> {
                    val caps = capabilities as? MediaCodec.CodecCapabilities
                    caps?.profileLevels?.map { it.level }?.distinct() ?: emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun canHardwareAccelerate(targetCodec: VideoCodec): Boolean {
        return getBestEncoderForCodec(targetCodec)?.isHardwareAccelerated == true
    }

    fun getEncoderCapabilities(targetCodec: VideoCodec): HardwareEncoderInfo? {
        return getBestEncoderForCodec(targetCodec)
    }
}
