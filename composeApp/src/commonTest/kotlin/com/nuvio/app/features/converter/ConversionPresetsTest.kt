package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionPresetsTest {

    @Test
    fun everyPresetIsUsableOnTheWeakestBackend() {
        // A preset that cannot run on the minimum capability set would be a dead chip on a
        // bottom-tier device, which is worse than not offering it.
        ConversionPresets.selectable.forEach { preset ->
            assertTrue(
                ConversionPresets.isSupported(preset, ConverterCapabilities.Minimal),
                "$preset should be usable with only H.264/AAC/MP4 available",
            )
        }
    }

    @Test
    fun presetRoundTripsThroughItsSpec() {
        ConversionPresets.selectable.forEach { preset ->
            assertEquals(
                preset,
                ConversionPresets.presetFor(ConversionPresets.specFor(preset)),
                "$preset should be recognised from the spec it produces",
            )
        }
    }

    @Test
    fun anEditedSpecBecomesCustom() {
        val edited = ConversionPresets.specFor(ConversionPreset.UniversalMp4)
            .copy(maxHeight = 480)
        assertEquals(ConversionPreset.Custom, ConversionPresets.presetFor(edited))
    }

    @Test
    fun remuxPresetCopiesRatherThanEncodes() {
        val spec = ConversionPresets.specFor(ConversionPreset.RemuxOnly)
        assertNull(spec.videoCodec, "remux must not name a video encoder")
        assertNull(spec.audioCodec, "remux must not name an audio encoder")
        assertEquals(CastContainer.MP4, spec.container)
    }

    @Test
    fun universalPresetTargetsTheMostCompatibleCombination() {
        val spec = ConversionPresets.specFor(ConversionPreset.UniversalMp4)
        assertEquals(CastVideoCodec.H264, spec.videoCodec)
        assertEquals(CastAudioCodec.AAC, spec.audioCodec)
        assertEquals(CastContainer.MP4, spec.container)
        assertEquals(1080, spec.maxHeight)
    }

    @Test
    fun spaceSaverCapsFrameRateAsWellAsResolution() {
        // The fps cap is the point: 2.5 Mbps at 60fps looks far worse than the same at 30.
        val spec = ConversionPresets.specFor(ConversionPreset.PhoneSpaceSaver)
        assertEquals(720, spec.maxHeight)
        assertEquals(30, spec.maxFrameRate)
    }

    @Test
    fun audioOnlyPresetDropsVideoAndUsesAnM4aExtension() {
        val spec = ConversionPresets.specFor(ConversionPreset.AudioOnly)
        assertTrue(spec.dropVideo)
        assertEquals("m4a", spec.container.fileExtension(audioOnly = true))
    }

    @Test
    fun customIsNotOfferedAsAChip() {
        assertTrue(ConversionPreset.Custom !in ConversionPresets.selectable)
    }
}
