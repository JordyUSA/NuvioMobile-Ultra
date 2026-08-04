package com.nuvio.app.features.downloads

import com.nuvio.app.features.cast.model.CastMediaProbe
import kotlinx.serialization.Serializable

/**
 * What a finished download actually contains, read from the file rather than guessed from its
 * name.
 *
 * Persisted with the download so the file is probed once, on completion, instead of on every
 * open — and so the resolution badge is available offline without touching the file at all.
 */
@Serializable
data class DownloadMediaInfo(
    val container: String,
    val durationMs: Long? = null,
    val video: DownloadVideoStreamInfo? = null,
    val audioTracks: List<DownloadAudioStreamInfo> = emptyList(),
    val subtitleTracks: List<DownloadSubtitleStreamInfo> = emptyList(),
)

@Serializable
data class DownloadVideoStreamInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    val frameRate: Float? = null,
    val bitrateBitsPerSecond: Long? = null,
    val dynamicRange: String? = null,
    val bitDepth: Int = 8,
    val profile: String? = null,
)

@Serializable
data class DownloadAudioStreamInfo(
    val codec: String,
    val channelCount: Int,
    val sampleRateHz: Int? = null,
    val bitrateBitsPerSecond: Long? = null,
    val language: String? = null,
    val isDefault: Boolean = false,
)

@Serializable
data class DownloadSubtitleStreamInfo(
    val language: String? = null,
    val label: String? = null,
    val isBitmap: Boolean = false,
)

internal fun CastMediaProbe.toDownloadMediaInfo(): DownloadMediaInfo =
    DownloadMediaInfo(
        container = container.name,
        durationMs = durationMs?.takeIf { it > 0L },
        video = video?.let { stream ->
            DownloadVideoStreamInfo(
                codec = stream.codec.name,
                width = stream.width,
                height = stream.height,
                frameRate = stream.frameRate,
                bitrateBitsPerSecond = stream.bitrateBitsPerSecond,
                dynamicRange = stream.dynamicRange.name,
                bitDepth = stream.bitDepth,
                profile = stream.profile,
            )
        },
        audioTracks = audioTracks.map { stream ->
            DownloadAudioStreamInfo(
                codec = stream.codec.name,
                channelCount = stream.channelCount,
                sampleRateHz = stream.sampleRateHz,
                bitrateBitsPerSecond = stream.bitrateBitsPerSecond,
                language = stream.language,
                isDefault = stream.isDefault,
            )
        },
        subtitleTracks = subtitleTracks.map { stream ->
            DownloadSubtitleStreamInfo(
                language = stream.language,
                label = stream.label,
                isBitmap = stream.isBitmap,
            )
        },
    )

/**
 * The resolution badge shown on a downloaded item, e.g. "1080p".
 *
 * Prefers the probed height, since that is the truth about the file. Falls back to parsing the
 * stream title or file name, which covers downloads made before probing existed and files the
 * prober cannot open.
 */
internal fun DownloadItem.resolutionBadge(): String? =
    mediaInfo?.video?.height?.let(::resolutionLabelForHeight)
        ?: parseResolutionLabel(streamTitle)
        ?: parseResolutionLabel(streamSubtitle)
        ?: parseResolutionLabel(fileName)

private fun resolutionLabelForHeight(height: Int): String? = when {
    height <= 0 -> null
    // Bucketed rather than exact: a 2:1 or 21:9 master is not 1080 tall but is still a 1080p
    // release, and labelling it "800p" would be technically true and useless.
    height >= 2000 -> "2160p"
    height >= 1400 -> "1440p"
    height >= 1000 -> "1080p"
    height >= 700 -> "720p"
    height >= 500 -> "576p"
    height >= 400 -> "480p"
    else -> "${height}p"
}

private val resolutionPattern = Regex("""\b(2160|1440|1080|720|576|480|360)p\b""", RegexOption.IGNORE_CASE)
private val fourKPattern = Regex("""\b(4k|uhd)\b""", RegexOption.IGNORE_CASE)

private fun parseResolutionLabel(value: String?): String? {
    val text = value?.takeIf { it.isNotBlank() } ?: return null
    resolutionPattern.find(text)?.let { return "${it.groupValues[1]}p" }
    if (fourKPattern.containsMatchIn(text)) return "2160p"
    return null
}
