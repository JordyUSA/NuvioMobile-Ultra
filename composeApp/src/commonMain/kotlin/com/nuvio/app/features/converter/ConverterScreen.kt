package com.nuvio.app.features.converter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.probeCastMedia
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.formatDownloadBytes
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsSwitchRow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The converter, as a normal navigation destination.
 *
 * Was originally a bottom sheet. Every one of the 7 controls below opens its own dropdown sheet,
 * and nesting a sheet inside a sheet turned out to be a real bug, not just an edge case: on iOS
 * both sheets share one global "currently presented sheet" reference, so opening a dropdown here
 * force-dismissed the outer sheet and then left that global pointing at an orphaned controller —
 * which is why the sheet closed on interaction and then refused to reopen at all. A full screen
 * has no such collision, and rides `NuvioScreen`'s `LazyColumn` for scrolling for free, which the
 * sheet never had either.
 *
 * Opened for a single download or for a batch; the batch case shares one preset and one
 * replace-original choice across every selected item, which is what makes multi-select worth
 * having at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    items: List<DownloadItem>,
    isTablet: Boolean,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    var capabilities by remember { mutableStateOf<ConverterCapabilities?>(null) }
    var probe by remember { mutableStateOf<CastMediaProbe?>(null) }
    var probeFinished by remember { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(ConversionPreset.RemuxOnly) }
    var spec by remember { mutableStateOf(ConversionPresets.specFor(ConversionPreset.RemuxOnly)) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var replaceOriginal by rememberSaveable { mutableStateOf(false) }

    val primary = items.firstOrNull()

    LaunchedEffect(Unit) {
        capabilities = runCatching { converterCapabilities() }
            .getOrDefault(ConverterCapabilities.Minimal)
    }

    // Only the first item is probed. Probing a whole batch would delay the screen for information
    // the user cannot act on per-item anyway, since one preset applies to all of them.
    LaunchedEffect(primary?.id) {
        val uri = primary?.let(DownloadsRepository::playableLocalFileUri)
        probe = uri?.let { probeCastMedia(it).getOrNull() }
        probeFinished = true
    }

    val resolved = capabilities ?: ConverterCapabilities.Minimal
    val planned = remember(probe, spec, resolved) {
        probe?.let { ConversionPlanner.plan(it, spec, resolved) }
    }

    fun applySpec(update: ConversionSpec) {
        spec = update
        preset = ConversionPresets.presetFor(update)
    }

    val convertedToast = stringResource(Res.string.converter_enqueue_started)
    val missingToast = stringResource(Res.string.converter_enqueue_source_missing)

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (items.size == 1) {
                    stringResource(Res.string.converter_sheet_title)
                } else {
                    stringResource(Res.string.converter_sheet_title_batch, items.size)
                },
                onBack = onBack,
            )
        }

        if (primary == null) {
            return@NuvioScreen
        }

        item {
            SourceSummary(
                item = primary,
                probe = probe,
                probeFinished = probeFinished,
            )
        }

        item {
            PresetChips(
                selected = preset,
                capabilities = resolved,
                onSelect = { chosen ->
                    preset = chosen
                    spec = ConversionPresets.specFor(chosen)
                },
            )
        }

        planned?.estimatedOutputBytes?.let { estimate ->
            item {
                Text(
                    text = stringResource(
                        Res.string.converter_estimated_size,
                        formatDownloadBytes(estimate),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        planned?.adjustments.orEmpty().forEach { adjustment ->
            item {
                adjustment.describe()?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        item {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.converter_replace_title),
                    description = stringResource(Res.string.converter_replace_description),
                    checked = replaceOriginal,
                    isTablet = isTablet,
                    onCheckedChange = { replaceOriginal = it },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.converter_advanced_title),
                    description = stringResource(Res.string.converter_advanced_description),
                    checked = advancedExpanded,
                    isTablet = isTablet,
                    onCheckedChange = { advancedExpanded = it },
                )
            }
        }

        item {
            AnimatedVisibility(visible = advancedExpanded) {
                AdvancedControls(
                    spec = spec,
                    capabilities = resolved,
                    isTablet = isTablet,
                    onSpecChange = ::applySpec,
                )
            }
        }

        item {
            Text(
                text = stringResource(Res.string.converter_backend_label, resolved.backendLabel),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }

        item {
            NuvioPrimaryButton(
                text = if (items.size == 1) {
                    stringResource(Res.string.converter_action_convert)
                } else {
                    stringResource(Res.string.converter_action_convert_count, items.size)
                },
                onClick = {
                    val queued = ConverterRepository.enqueueAll(
                        items = items,
                        preset = preset,
                        spec = spec,
                        replaceOriginal = replaceOriginal,
                    )
                    NuvioToastController.show(if (queued > 0) convertedToast else missingToast)
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun SourceSummary(
    item: DownloadItem,
    probe: CastMediaProbe?,
    probeFinished: Boolean,
) {
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
        when {
            !probeFinished -> NuvioLoadingIndicator()
            else -> Text(
                text = describeSource(item, probe),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

/**
 * "MKV • HEVC 1080p • Dolby TrueHD 7.1 • 4.2 GB", degrading gracefully to just the size when the
 * probe could not read the file — which is survivable, and better than an empty line.
 */
private fun describeSource(item: DownloadItem, probe: CastMediaProbe?): String {
    val parts = mutableListOf<String>()
    probe?.container?.takeIf { it != CastContainer.UNKNOWN }?.let { parts += it.displayLabel() }
    probe?.video?.let { video ->
        parts += buildString {
            append(video.codec.displayLabel())
            if (video.height > 0) append(" ${video.height}p")
        }
    }
    probe?.primaryAudio?.let { audio ->
        parts += buildString {
            append(audio.codec.displayLabel())
            if (audio.channelCount > 0) append(" ${channelLabel(audio.channelCount)}")
        }
    }
    item.totalBytes?.takeIf { it > 0L }?.let { parts += formatDownloadBytes(it) }
    return parts.joinToString(" • ")
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PresetChips(
    selected: ConversionPreset,
    capabilities: ConverterCapabilities,
    onSelect: (ConversionPreset) -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConversionPresets.selectable.forEach { preset ->
            val supported = ConversionPresets.isSupported(preset, capabilities)
            FilterChip(
                selected = preset == selected,
                enabled = supported,
                onClick = { onSelect(preset) },
                label = { Text(text = stringResource(preset.titleRes())) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tokens.colors.accent,
                    selectedLabelColor = tokens.colors.textPrimary,
                ),
            )
        }
        if (selected == ConversionPreset.Custom) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(text = stringResource(Res.string.converter_preset_custom)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tokens.colors.accent,
                    selectedLabelColor = tokens.colors.textPrimary,
                ),
            )
        }
    }

    Text(
        text = stringResource(selected.descriptionRes()),
        style = MaterialTheme.typography.bodySmall,
        color = tokens.colors.textMuted,
    )
}

@Composable
private fun AdvancedControls(
    spec: ConversionSpec,
    capabilities: ConverterCapabilities,
    isTablet: Boolean,
    onSpecChange: (ConversionSpec) -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
        // Every list is built from what the probe reported this backend can do, so an option that
        // would fail later is simply not offered rather than shown and then rejected.
        val containerOptions = capabilities.containers
            .sortedBy { it.displayLabel() }
            .map { NuvioDropdownOption(key = it.name, label = it.displayLabel()) }
        if (containerOptions.size > 1) {
            NuvioDropdownChip(
                title = stringResource(Res.string.converter_field_container),
                label = spec.container.displayLabel(),
                selectedKey = spec.container.name,
                options = containerOptions,
                onSelected = { option ->
                    onSpecChange(spec.copy(container = CastContainer.valueOf(option.key)))
                },
            )
        }

        val copyKey = "COPY"
        val videoOptions = buildList {
            add(NuvioDropdownOption(copyKey, stringResource(Res.string.converter_option_copy)))
            capabilities.videoEncoders.sortedBy { it.name }.forEach {
                add(NuvioDropdownOption(it.name, it.displayLabel()))
            }
        }
        NuvioDropdownChip(
            title = stringResource(Res.string.converter_field_video_codec),
            label = spec.videoCodec?.displayLabel() ?: stringResource(Res.string.converter_option_copy),
            selectedKey = spec.videoCodec?.name ?: copyKey,
            options = videoOptions,
            enabled = !spec.dropVideo,
            onSelected = { option ->
                onSpecChange(
                    spec.copy(
                        videoCodec = option.key.takeIf { it != copyKey }?.let(CastVideoCodec::valueOf),
                    ),
                )
            },
        )

        val heightOptions = listOf(null, 2160, 1080, 720, 480)
            .filter { it == null || it <= maxOf(capabilities.maxEncodeHeight, 1) || capabilities.maxEncodeHeight <= 0 }
            .map { height ->
                NuvioDropdownOption(
                    key = height?.toString() ?: "SOURCE",
                    label = height?.let { "${it}p" } ?: stringResource(Res.string.converter_option_keep),
                )
            }
        NuvioDropdownChip(
            title = stringResource(Res.string.converter_field_resolution),
            label = spec.maxHeight?.let { "${it}p" } ?: stringResource(Res.string.converter_option_keep),
            selectedKey = spec.maxHeight?.toString() ?: "SOURCE",
            options = heightOptions,
            enabled = spec.videoCodec != null && !spec.dropVideo,
            onSelected = { option ->
                onSpecChange(spec.copy(maxHeight = option.key.toIntOrNull()))
            },
        )

        val frameRateOptions = listOf(null, 60, 30, 24).map { fps ->
            NuvioDropdownOption(
                key = fps?.toString() ?: "SOURCE",
                label = fps?.let { "$it fps" } ?: stringResource(Res.string.converter_option_keep),
            )
        }
        NuvioDropdownChip(
            title = stringResource(Res.string.converter_field_frame_rate),
            label = spec.maxFrameRate?.let { "$it fps" } ?: stringResource(Res.string.converter_option_keep),
            selectedKey = spec.maxFrameRate?.toString() ?: "SOURCE",
            options = frameRateOptions,
            enabled = spec.videoCodec != null && !spec.dropVideo,
            onSelected = { option -> onSpecChange(spec.copy(maxFrameRate = option.key.toIntOrNull())) },
        )

        if (spec.videoCodec != null && !spec.dropVideo) {
            BitrateSlider(
                title = stringResource(Res.string.converter_field_video_bitrate),
                valueBitsPerSecond = spec.videoBitrateBitsPerSecond ?: ConversionPresets.BITRATE_1080P,
                rangeBitsPerSecond = VIDEO_BITRATE_MIN..VIDEO_BITRATE_MAX,
                stepBitsPerSecond = VIDEO_BITRATE_STEP,
                format = { "${it / 1_000_000.0} Mbps" },
                onCommit = { onSpecChange(spec.copy(videoBitrateBitsPerSecond = it)) },
            )
        }

        val audioOptions = buildList {
            add(NuvioDropdownOption(copyKey, stringResource(Res.string.converter_option_copy)))
            capabilities.audioEncoders.sortedBy { it.name }.forEach {
                add(NuvioDropdownOption(it.name, it.displayLabel()))
            }
        }
        NuvioDropdownChip(
            title = stringResource(Res.string.converter_field_audio_codec),
            label = spec.audioCodec?.displayLabel() ?: stringResource(Res.string.converter_option_copy),
            selectedKey = spec.audioCodec?.name ?: copyKey,
            options = audioOptions,
            onSelected = { option ->
                onSpecChange(
                    spec.copy(
                        audioCodec = option.key.takeIf { it != copyKey }?.let(CastAudioCodec::valueOf),
                    ),
                )
            },
        )

        if (spec.audioCodec != null) {
            BitrateSlider(
                title = stringResource(Res.string.converter_field_audio_bitrate),
                valueBitsPerSecond = spec.audioBitrateBitsPerSecond,
                rangeBitsPerSecond = AUDIO_BITRATE_MIN..AUDIO_BITRATE_MAX,
                stepBitsPerSecond = AUDIO_BITRATE_STEP,
                format = { "${it / 1000} kbps" },
                onCommit = { onSpecChange(spec.copy(audioBitrateBitsPerSecond = it)) },
            )

            val channelOptions = listOf(
                ConversionPresets.CHANNELS_KEEP to stringResource(Res.string.converter_option_keep),
                1 to "Mono",
                2 to "Stereo",
                6 to "5.1",
            ).map { (value, label) -> NuvioDropdownOption(value.toString(), label) }
            NuvioDropdownChip(
                title = stringResource(Res.string.converter_field_audio_channels),
                label = channelOptions
                    .firstOrNull { it.key == spec.audioChannelCount.toString() }
                    ?.label
                    .orEmpty(),
                selectedKey = spec.audioChannelCount.toString(),
                options = channelOptions,
                onSelected = { option ->
                    onSpecChange(spec.copy(audioChannelCount = option.key.toIntOrNull() ?: 2))
                },
            )
        }

        // Media3 cannot embed subtitles at all, so offering the choice on a build without FFmpeg
        // would be offering something that silently does nothing.
        if (capabilities.hasFfmpeg) {
            val subtitleOptions = listOf(
                SubtitleHandling.Drop to stringResource(Res.string.converter_option_subtitles_drop),
                SubtitleHandling.KeepIfCompatible to stringResource(Res.string.converter_option_subtitles_keep),
            ).map { (value, label) -> NuvioDropdownOption(value.name, label) }
            NuvioDropdownChip(
                title = stringResource(Res.string.converter_field_subtitles),
                // Safe lookup: the subtitle row is gated on `capabilities.hasFfmpeg` while
                // `spec.subtitles` isn't, so a future capability edge case could otherwise land
                // here with a value this particular option list doesn't contain.
                label = subtitleOptions.firstOrNull { it.key == spec.subtitles.name }?.label.orEmpty(),
                selectedKey = spec.subtitles.name,
                options = subtitleOptions,
                onSelected = { option ->
                    onSpecChange(spec.copy(subtitles = SubtitleHandling.valueOf(option.key)))
                },
            )
        }

        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.converter_field_hardware),
                description = stringResource(Res.string.converter_field_hardware_description),
                checked = spec.preferHardwareEncoder,
                isTablet = isTablet,
                onCheckedChange = { onSpecChange(spec.copy(preferHardwareEncoder = it)) },
            )
        }
    }
}

/**
 * Drags locally and commits on release, following the settings slider convention — writing on
 * every drag frame would rebuild the plan dozens of times a second.
 */
@Composable
private fun BitrateSlider(
    title: String,
    valueBitsPerSecond: Long,
    rangeBitsPerSecond: LongRange,
    stepBitsPerSecond: Long,
    format: (Long) -> String,
    onCommit: (Long) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var draft by remember(valueBitsPerSecond) { mutableStateOf(valueBitsPerSecond.toFloat()) }
    val steps = ((rangeBitsPerSecond.last - rangeBitsPerSecond.first) / stepBitsPerSecond).toInt() - 1

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = format(draft.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft.toLong()) },
            valueRange = rangeBitsPerSecond.first.toFloat()..rangeBitsPerSecond.last.toFloat(),
            steps = steps.coerceAtLeast(0),
        )
    }
}

/**
 * The note shown under the presets when the planner had to change something.
 *
 * Null for adjustments that describe ordinary planning rather than a compromise — telling someone
 * their frame-rate cap was not needed is noise, not information.
 */
@Composable
private fun ConversionAdjustment.describe(): String? = when (this) {
    is ConversionAdjustment.VideoCodecDowngraded -> stringResource(
        Res.string.converter_adjust_video_codec,
        wanted.displayLabel(),
        used.displayLabel(),
    )
    is ConversionAdjustment.AudioCodecDowngraded -> stringResource(
        Res.string.converter_adjust_audio_codec,
        wanted.displayLabel(),
        used.displayLabel(),
    )
    is ConversionAdjustment.ContainerDowngraded -> stringResource(
        Res.string.converter_adjust_container,
        wanted.displayLabel(),
        used.displayLabel(),
    )
    is ConversionAdjustment.TrackReencodedForContainer -> stringResource(
        Res.string.converter_adjust_track_reencoded,
        codec,
    )
    is ConversionAdjustment.BitrateClamped -> stringResource(Res.string.converter_adjust_bitrate_clamped)
    else -> null
}

private const val VIDEO_BITRATE_MIN = 500_000L
private const val VIDEO_BITRATE_MAX = 20_000_000L
private const val VIDEO_BITRATE_STEP = 500_000L
private const val AUDIO_BITRATE_MIN = 64_000L
private const val AUDIO_BITRATE_MAX = 512_000L
private const val AUDIO_BITRATE_STEP = 32_000L
