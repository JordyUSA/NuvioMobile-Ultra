package com.nuvio.app.features.converter

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.converter_preset_audio_only
import nuvio.composeapp.generated.resources.converter_preset_audio_only_description
import nuvio.composeapp.generated.resources.converter_preset_custom
import nuvio.composeapp.generated.resources.converter_preset_custom_description
import nuvio.composeapp.generated.resources.converter_preset_high_quality
import nuvio.composeapp.generated.resources.converter_preset_high_quality_description
import nuvio.composeapp.generated.resources.converter_preset_remux
import nuvio.composeapp.generated.resources.converter_preset_remux_description
import nuvio.composeapp.generated.resources.converter_preset_space_saver
import nuvio.composeapp.generated.resources.converter_preset_space_saver_description
import nuvio.composeapp.generated.resources.converter_preset_universal
import nuvio.composeapp.generated.resources.converter_preset_universal_description
import nuvio.composeapp.generated.resources.converter_status_cancelled
import nuvio.composeapp.generated.resources.converter_status_completed
import nuvio.composeapp.generated.resources.converter_status_failed
import nuvio.composeapp.generated.resources.converter_status_probing
import nuvio.composeapp.generated.resources.converter_status_queued
import nuvio.composeapp.generated.resources.converter_status_running
import org.jetbrains.compose.resources.StringResource

/** Kept out of [ConversionPresets] so that file stays pure data with no resource dependency. */
internal fun ConversionPreset.titleRes(): StringResource = when (this) {
    ConversionPreset.RemuxOnly -> Res.string.converter_preset_remux
    ConversionPreset.UniversalMp4 -> Res.string.converter_preset_universal
    ConversionPreset.PhoneSpaceSaver -> Res.string.converter_preset_space_saver
    ConversionPreset.HighQuality1080p -> Res.string.converter_preset_high_quality
    ConversionPreset.AudioOnly -> Res.string.converter_preset_audio_only
    ConversionPreset.Custom -> Res.string.converter_preset_custom
}

internal fun ConversionPreset.descriptionRes(): StringResource = when (this) {
    ConversionPreset.RemuxOnly -> Res.string.converter_preset_remux_description
    ConversionPreset.UniversalMp4 -> Res.string.converter_preset_universal_description
    ConversionPreset.PhoneSpaceSaver -> Res.string.converter_preset_space_saver_description
    ConversionPreset.HighQuality1080p -> Res.string.converter_preset_high_quality_description
    ConversionPreset.AudioOnly -> Res.string.converter_preset_audio_only_description
    ConversionPreset.Custom -> Res.string.converter_preset_custom_description
}

internal fun ConversionStatus.labelRes(): StringResource = when (this) {
    ConversionStatus.Queued -> Res.string.converter_status_queued
    ConversionStatus.Probing -> Res.string.converter_status_probing
    ConversionStatus.Running -> Res.string.converter_status_running
    ConversionStatus.Completed -> Res.string.converter_status_completed
    ConversionStatus.Failed -> Res.string.converter_status_failed
    ConversionStatus.Cancelled -> Res.string.converter_status_cancelled
}
