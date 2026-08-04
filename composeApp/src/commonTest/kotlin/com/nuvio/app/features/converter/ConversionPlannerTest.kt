package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastAudioStream
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.CastVideoStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionPlannerTest {

    private fun probe(
        container: CastContainer = CastContainer.MATROSKA,
        videoCodec: CastVideoCodec = CastVideoCodec.H264,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Float? = 24f,
        videoBitrate: Long? = null,
        audioCodec: CastAudioCodec = CastAudioCodec.AAC,
        channels: Int = 2,
        durationMs: Long? = 60_000L,
    ) = CastMediaProbe(
        container = container,
        video = CastVideoStream(
            codec = videoCodec,
            width = width,
            height = height,
            frameRate = frameRate,
            bitrateBitsPerSecond = videoBitrate,
        ),
        audioTracks = listOf(
            CastAudioStream(
                codec = audioCodec,
                channelCount = channels,
                sampleRateHz = 48_000,
                bitrateBitsPerSecond = null,
                isDefault = true,
            ),
        ),
        durationMs = durationMs,
    )

    private val h264Only = ConverterCapabilities(
        videoEncoders = setOf(CastVideoCodec.H264),
        audioEncoders = setOf(CastAudioCodec.AAC),
        containers = setOf(CastContainer.MP4),
        canCopyStreams = true,
        backendLabel = "Media3",
        maxEncodeHeight = 1080,
    )

    private val fullFfmpeg = h264Only.copy(
        videoEncoders = setOf(CastVideoCodec.H264, CastVideoCodec.HEVC),
        containers = setOf(CastContainer.MP4, CastContainer.MATROSKA),
        maxEncodeHeight = 2160,
        ffmpegVideoEncoders = setOf(CastVideoCodec.H264, CastVideoCodec.HEVC),
        hasFfmpeg = true,
    )

    @Test
    fun remuxPresetCopiesBothTracks() {
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionPresets.specFor(ConversionPreset.RemuxOnly),
            capabilities = h264Only,
        )

        assertNull(result.plan.videoTarget, "H.264 in MP4 needs no video re-encode")
        assertNull(result.plan.audioTarget, "AAC in MP4 needs no audio re-encode")
        assertEquals(CastContainer.MP4, result.targetContainer)
    }

    @Test
    fun remuxOfDtsAudioForcesAnAacEncode() {
        // The common MKV case: the video is fine, but DTS cannot go into MP4, so "copy" has to
        // become an encode or the output is unplayable.
        val result = ConversionPlanner.plan(
            probe = probe(audioCodec = CastAudioCodec.DTS, channels = 6),
            spec = ConversionPresets.specFor(ConversionPreset.RemuxOnly),
            capabilities = h264Only,
        )

        val audio = assertNotNull(result.plan.audioTarget)
        assertEquals(CastAudioCodec.AAC, audio.codec)
        assertNull(result.plan.videoTarget, "the video track is still copied")
        assertTrue(
            result.adjustments.any { it is ConversionAdjustment.TrackReencodedForContainer },
            "the user should be told why the audio is being re-encoded",
        )
    }

    @Test
    fun resolutionCapNeverUpscales() {
        val result = ConversionPlanner.plan(
            probe = probe(width = 1280, height = 720),
            spec = ConversionPresets.specFor(ConversionPreset.UniversalMp4),
            capabilities = h264Only,
        )

        val video = assertNotNull(result.plan.videoTarget)
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        assertTrue(result.adjustments.none { it is ConversionAdjustment.ResolutionClamped })
    }

    @Test
    fun fourKDownscalesToSevenTwentyPreservingAspectAndEvenDimensions() {
        val result = ConversionPlanner.plan(
            probe = probe(width = 3840, height = 2160),
            spec = ConversionPresets.specFor(ConversionPreset.PhoneSpaceSaver),
            capabilities = h264Only,
        )

        val video = assertNotNull(result.plan.videoTarget)
        assertEquals(720, video.height)
        assertEquals(1280, video.width)
        assertEquals(0, video.width % 2, "H.264 encoders reject odd dimensions")
        assertEquals(0, video.height % 2)
    }

    @Test
    fun bitrateIsNeverRaisedAboveTheSource() {
        val sourceBitrate = 3_000_000L
        val result = ConversionPlanner.plan(
            probe = probe(videoBitrate = sourceBitrate),
            spec = ConversionPresets.specFor(ConversionPreset.HighQuality1080p),
            capabilities = h264Only,
        )

        val video = assertNotNull(result.plan.videoTarget)
        assertEquals(sourceBitrate, video.bitrateBitsPerSecond)
        assertTrue(result.adjustments.any { it is ConversionAdjustment.BitrateClamped })
    }

    @Test
    fun hevcRequestDowngradesWhenTheDeviceCannotEncodeIt() {
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionSpec(videoCodec = CastVideoCodec.HEVC, maxHeight = 1080),
            capabilities = h264Only,
        )

        val video = assertNotNull(result.plan.videoTarget)
        assertEquals(CastVideoCodec.H264, video.codec)
        val downgrade = result.adjustments
            .filterIsInstance<ConversionAdjustment.VideoCodecDowngraded>()
            .single()
        assertEquals(CastVideoCodec.HEVC, downgrade.wanted)
        assertEquals(CastVideoCodec.H264, downgrade.used)
    }

    @Test
    fun hevcIsHonouredWhenTheBackendSupportsIt() {
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionSpec(videoCodec = CastVideoCodec.HEVC, maxHeight = 1080),
            capabilities = fullFfmpeg,
        )

        assertEquals(CastVideoCodec.HEVC, assertNotNull(result.plan.videoTarget).codec)
        assertTrue(result.adjustments.none { it is ConversionAdjustment.VideoCodecDowngraded })
    }

    @Test
    fun matroskaRequestFallsBackToMp4WithoutFfmpeg() {
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionSpec(container = CastContainer.MATROSKA),
            capabilities = h264Only,
        )

        assertEquals(CastContainer.MP4, result.targetContainer)
        assertTrue(result.adjustments.any { it is ConversionAdjustment.ContainerDowngraded })
    }

    @Test
    fun audioOnlyPresetDropsVideo() {
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionPresets.specFor(ConversionPreset.AudioOnly),
            capabilities = h264Only,
        )

        assertTrue(result.dropVideo)
        assertNull(result.plan.videoTarget)
        assertEquals(CastAudioCodec.AAC, assertNotNull(result.plan.audioTarget).codec)
    }

    @Test
    fun aFailedProbeDoesNotStripTheVideoTrack() {
        // A probe that could not read the file reports no video stream, which is indistinguishable
        // from a genuinely audio-only source. Inferring "drop the video" from that would silently
        // turn an unreadable movie into an audio file.
        val unprobeable = CastMediaProbe(
            container = CastContainer.UNKNOWN,
            video = null,
            audioTracks = emptyList(),
        )

        val result = ConversionPlanner.plan(
            probe = unprobeable,
            spec = ConversionPresets.specFor(ConversionPreset.UniversalMp4),
            capabilities = h264Only,
        )

        assertTrue(!result.dropVideo, "only an explicit request may remove the video track")
        assertNull(result.plan.videoTarget, "with nothing known about the video, it is copied")
    }

    @Test
    fun channelCountIsNeverRaisedAboveTheSource() {
        // Asking for 5.1 from a stereo source would invent channels; the planner clamps down.
        val result = ConversionPlanner.plan(
            probe = probe(channels = 2),
            spec = ConversionSpec(audioCodec = CastAudioCodec.AAC, audioChannelCount = 6),
            capabilities = h264Only,
        )

        assertEquals(2, assertNotNull(result.plan.audioTarget).channelCount)
    }

    @Test
    fun estimateIsNullWhenNothingIsReencoded() {
        // A pure copy tracks the source size, which the probe does not report — better to show
        // nothing than a number that is wrong by an order of magnitude.
        val result = ConversionPlanner.plan(
            probe = probe(),
            spec = ConversionPresets.specFor(ConversionPreset.RemuxOnly),
            capabilities = h264Only,
        )

        assertNull(result.estimatedOutputBytes)
    }

    @Test
    fun estimateUsesDurationAndTargetBitrates() {
        val result = ConversionPlanner.plan(
            probe = probe(durationMs = 60_000L),
            spec = ConversionSpec(
                videoCodec = CastVideoCodec.H264,
                maxHeight = 1080,
                videoBitrateBitsPerSecond = 8_000_000L,
                audioCodec = CastAudioCodec.AAC,
                audioBitrateBitsPerSecond = 192_000L,
            ),
            capabilities = h264Only,
        )

        // 60s * (8 Mbps + 192 kbps) / 8 bits.
        assertEquals(61_440_000L, result.estimatedOutputBytes)
    }
}
