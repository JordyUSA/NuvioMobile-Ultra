package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedByteSize
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_media_info
import nuvio.composeapp.generated.resources.downloads_media_info_audio
import nuvio.composeapp.generated.resources.downloads_media_info_bit_depth
import nuvio.composeapp.generated.resources.downloads_media_info_bitrate
import nuvio.composeapp.generated.resources.downloads_media_info_channels
import nuvio.composeapp.generated.resources.downloads_media_info_codec
import nuvio.composeapp.generated.resources.downloads_media_info_container
import nuvio.composeapp.generated.resources.downloads_media_info_duration
import nuvio.composeapp.generated.resources.downloads_media_info_dynamic_range
import nuvio.composeapp.generated.resources.downloads_media_info_error_details
import nuvio.composeapp.generated.resources.downloads_media_info_file
import nuvio.composeapp.generated.resources.downloads_media_info_frame_rate
import nuvio.composeapp.generated.resources.downloads_media_info_language
import nuvio.composeapp.generated.resources.downloads_media_info_provider
import nuvio.composeapp.generated.resources.downloads_media_info_reading
import nuvio.composeapp.generated.resources.downloads_media_info_resolution
import nuvio.composeapp.generated.resources.downloads_media_info_sample_rate
import nuvio.composeapp.generated.resources.downloads_media_info_size
import nuvio.composeapp.generated.resources.downloads_media_info_source
import nuvio.composeapp.generated.resources.downloads_media_info_subtitles
import nuvio.composeapp.generated.resources.downloads_media_info_track
import nuvio.composeapp.generated.resources.downloads_media_info_video
import org.jetbrains.compose.resources.stringResource

/**
 * What is actually inside a downloaded file: container, codecs, tracks, and the source it came
 * from.
 *
 * Everything shown is either persisted on the item or read from the file by the probe, so the
 * sheet works offline. When the probe has not run yet — an older download, or one whose probe
 * failed — the sheet still shows what is known rather than an empty panel, and kicks off a
 * probe so a second open has more to say.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadMediaInfoSheet(
    item: DownloadItem,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(item.id, item.mediaInfo == null) {
        if (item.mediaInfo == null) {
            DownloadsRepository.probeMediaInfo(item.id)
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.downloads_media_info),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.nuvio.colors.textPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            InfoRow(stringResource(Res.string.downloads_media_info_file), item.fileName)
            InfoRow(
                label = stringResource(Res.string.downloads_media_info_size),
                value = localizedByteSize(item.totalBytes ?: item.downloadedBytes),
            )
            item.mediaInfo?.container?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let { container ->
                InfoRow(stringResource(Res.string.downloads_media_info_container), container)
            }
            item.mediaInfo?.durationMs?.let { durationMs ->
                InfoRow(
                    label = stringResource(Res.string.downloads_media_info_duration),
                    value = formatMediaDuration(durationMs),
                )
            }
            InfoRow(stringResource(Res.string.downloads_media_info_provider), item.providerName)
            InfoRow(stringResource(Res.string.downloads_media_info_source), item.streamTitle)

            item.mediaInfo?.video?.let { video ->
                SectionHeader(stringResource(Res.string.downloads_media_info_video))
                InfoRow(stringResource(Res.string.downloads_media_info_codec), formatCodecName(video.codec))
                if (video.width > 0 && video.height > 0) {
                    InfoRow(
                        label = stringResource(Res.string.downloads_media_info_resolution),
                        value = "${video.width} × ${video.height}",
                    )
                }
                video.frameRate?.takeIf { it > 0f }?.let { frameRate ->
                    InfoRow(
                        label = stringResource(Res.string.downloads_media_info_frame_rate),
                        value = "${roundToOneDecimal(frameRate)} fps",
                    )
                }
                video.bitrateBitsPerSecond?.takeIf { it > 0L }?.let { bitrate ->
                    InfoRow(stringResource(Res.string.downloads_media_info_bitrate), formatBitrate(bitrate))
                }
                video.dynamicRange?.takeIf { it.isNotBlank() }?.let { range ->
                    InfoRow(stringResource(Res.string.downloads_media_info_dynamic_range), range)
                }
                InfoRow(
                    label = stringResource(Res.string.downloads_media_info_bit_depth),
                    value = "${video.bitDepth}-bit",
                )
                video.profile?.takeIf { it.isNotBlank() }?.let { profile ->
                    InfoRow(stringResource(Res.string.downloads_media_info_codec), profile)
                }
            }

            item.mediaInfo?.audioTracks?.forEachIndexed { index, audio ->
                SectionHeader(
                    if (item.mediaInfo?.audioTracks.orEmpty().size > 1) {
                        stringResource(Res.string.downloads_media_info_audio) + " · " +
                            stringResource(Res.string.downloads_media_info_track, index + 1)
                    } else {
                        stringResource(Res.string.downloads_media_info_audio)
                    },
                )
                InfoRow(stringResource(Res.string.downloads_media_info_codec), formatCodecName(audio.codec))
                if (audio.channelCount > 0) {
                    InfoRow(
                        label = stringResource(Res.string.downloads_media_info_channels),
                        value = formatChannelLayout(audio.channelCount),
                    )
                }
                audio.sampleRateHz?.takeIf { it > 0 }?.let { sampleRate ->
                    InfoRow(
                        label = stringResource(Res.string.downloads_media_info_sample_rate),
                        value = "${roundToOneDecimal(sampleRate / 1000f)} kHz",
                    )
                }
                audio.bitrateBitsPerSecond?.takeIf { it > 0L }?.let { bitrate ->
                    InfoRow(stringResource(Res.string.downloads_media_info_bitrate), formatBitrate(bitrate))
                }
                audio.language?.takeIf { it.isNotBlank() }?.let { language ->
                    InfoRow(stringResource(Res.string.downloads_media_info_language), language)
                }
            }

            val subtitleTracks = item.mediaInfo?.subtitleTracks.orEmpty()
            if (subtitleTracks.isNotEmpty()) {
                SectionHeader(stringResource(Res.string.downloads_media_info_subtitles))
                subtitleTracks.forEachIndexed { index, subtitle ->
                    InfoRow(
                        label = subtitle.label?.takeIf { it.isNotBlank() }
                            ?: stringResource(Res.string.downloads_media_info_track, index + 1),
                        value = subtitle.language?.takeIf { it.isNotBlank() }.orEmpty().ifBlank { "—" },
                    )
                }
            }

            item.errorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                SectionHeader(stringResource(Res.string.downloads_media_info_error_details))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            if (item.mediaInfo == null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.downloads_media_info_reading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(12.dp))
    NuvioBottomSheetDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.nuvio.colors.accent,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/** Turns the prober's enum names into what a release would call them. */
private fun formatCodecName(codec: String): String = when (codec) {
    "HEVC" -> "HEVC (H.265)"
    "H264" -> "H.264 (AVC)"
    "EAC3" -> "E-AC-3"
    "DTS_HD" -> "DTS-HD"
    "TRUEHD" -> "TrueHD"
    "UNKNOWN" -> "—"
    else -> codec
}

private fun formatChannelLayout(channelCount: Int): String = when (channelCount) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channelCount ch"
}

private fun formatBitrate(bitsPerSecond: Long): String {
    val mbps = bitsPerSecond / 1_000_000.0
    return if (mbps >= 1.0) {
        "${roundToOneDecimal(mbps.toFloat())} Mb/s"
    } else {
        "${(bitsPerSecond / 1_000L)} kb/s"
    }
}

private fun formatMediaDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m ${seconds}s"
    }
}

private fun roundToOneDecimal(value: Float): String {
    val rounded = (value * 10f).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}
