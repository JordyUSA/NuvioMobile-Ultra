package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastAudioStream
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastDynamicRange
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.CastVideoStream
import com.nuvio.app.features.cast.model.capabilitiesFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastDeliveryPlannerTest {

    private fun probe(
        container: CastContainer = CastContainer.MP4,
        videoCodec: CastVideoCodec = CastVideoCodec.H264,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Float? = 24f,
        bitDepth: Int = 8,
        range: CastDynamicRange = CastDynamicRange.SDR,
        profile: String? = null,
        audioCodec: CastAudioCodec = CastAudioCodec.AAC,
        channels: Int = 2,
        videoBitrate: Long? = null,
    ) = CastMediaProbe(
        container = container,
        video = CastVideoStream(
            codec = videoCodec,
            width = width,
            height = height,
            frameRate = frameRate,
            bitrateBitsPerSecond = videoBitrate,
            dynamicRange = range,
            bitDepth = bitDepth,
            profile = profile,
        ),
        audioTracks = listOf(
            CastAudioStream(
                codec = audioCodec,
                channelCount = channels,
                sampleRateHz = 48_000,
                bitrateBitsPerSecond = null,
            ),
        ),
    )

    private val gen12 = capabilitiesFor(CastReceiverProfile.GEN_1_2)
    private val ultra = capabilitiesFor(CastReceiverProfile.ULTRA)

    @Test
    fun `mp4 h264 aac plays directly`() {
        val plan = CastDeliveryPlanner.plan(probe(), gen12, sourceReachableByReceiver = true)

        assertEquals(CastDeliveryMode.DIRECT, plan.mode)
        assertTrue(plan.reasons.isEmpty())
        assertNull(plan.videoTarget)
        assertNull(plan.audioTarget)
        // A publicly reachable direct play needs no server on the phone.
        assertTrue(!plan.requiresLocalServer)
    }

    @Test
    fun `matroska with supported codecs only needs a remux`() {
        val plan = CastDeliveryPlanner.plan(
            probe(container = CastContainer.MATROSKA),
            gen12,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.REMUX, plan.mode)
        // Nothing is re-encoded: both tracks are copied into the new container.
        assertNull(plan.videoTarget)
        assertNull(plan.audioTarget)
        assertEquals(CastContainer.MP4, plan.targetContainer)
        // Repackaged output is produced locally, so it must be served locally.
        assertTrue(plan.requiresLocalServer)
        assertTrue(plan.reasons.any { it is CastIncompatibility.ContainerUnsupported })
    }

    @Test
    fun `hevc forces a transcode on a first generation dongle`() {
        val plan = CastDeliveryPlanner.plan(
            probe(container = CastContainer.MATROSKA, videoCodec = CastVideoCodec.HEVC, bitDepth = 10),
            gen12,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.TRANSCODE, plan.mode)
        val target = assertNotNull(plan.videoTarget)
        assertEquals(CastVideoCodec.H264, target.codec)
        assertTrue(plan.reasons.any { it is CastIncompatibility.VideoCodecUnsupported })
        assertTrue(plan.reasons.any { it is CastIncompatibility.BitDepthUnsupported })
    }

    @Test
    fun `the same hevc file only needs a remux on an ultra`() {
        val plan = CastDeliveryPlanner.plan(
            probe(container = CastContainer.MATROSKA, videoCodec = CastVideoCodec.HEVC, bitDepth = 10),
            ultra,
            sourceReachableByReceiver = true,
        )

        // Ultra decodes HEVC Main10 natively, so this is a container problem only.
        assertEquals(CastDeliveryMode.REMUX, plan.mode)
        assertNull(plan.videoTarget)
    }

    @Test
    fun `4k is downscaled to the receiver ceiling preserving aspect ratio`() {
        val plan = CastDeliveryPlanner.plan(
            probe(width = 3840, height = 2160, videoCodec = CastVideoCodec.HEVC),
            gen12,
            sourceReachableByReceiver = true,
        )

        val target = assertNotNull(plan.videoTarget)
        assertEquals(1920, target.width)
        assertEquals(1080, target.height)
        assertTrue(plan.reasons.any { it is CastIncompatibility.ResolutionTooHigh })
    }

    @Test
    fun `dts audio is re-encoded but the video is copied`() {
        val plan = CastDeliveryPlanner.plan(
            probe(container = CastContainer.MATROSKA, audioCodec = CastAudioCodec.DTS, channels = 6),
            ultra,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.REMUX, plan.mode)
        assertNull(plan.videoTarget)
        val audio = assertNotNull(plan.audioTarget)
        assertEquals(CastAudioCodec.AAC, audio.codec)
        assertEquals(6, audio.channelCount)
    }

    @Test
    fun `dolby digital is re-encoded because passthrough cannot be verified from the sender`() {
        val plan = CastDeliveryPlanner.plan(
            probe(audioCodec = CastAudioCodec.EAC3),
            ultra,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.REMUX, plan.mode)
        assertNotNull(plan.audioTarget)
    }

    @Test
    fun `high frame rate is capped for a 30fps receiver`() {
        val plan = CastDeliveryPlanner.plan(
            probe(frameRate = 60f),
            gen12,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.TRANSCODE, plan.mode)
        assertEquals(30f, assertNotNull(plan.videoTarget).frameRate)
    }

    @Test
    fun `h264 above the receiver level is transcoded`() {
        val plan = CastDeliveryPlanner.plan(
            probe(profile = "High Profile Level 5.1"),
            gen12,
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.TRANSCODE, plan.mode)
        assertTrue(plan.reasons.any { it is CastIncompatibility.VideoLevelTooHigh })
    }

    @Test
    fun `transcode never spends more bits than the source had`() {
        val plan = CastDeliveryPlanner.plan(
            probe(videoCodec = CastVideoCodec.AV1, videoBitrate = 1_200_000),
            gen12,
            sourceReachableByReceiver = true,
        )

        assertEquals(1_200_000L, assertNotNull(plan.videoTarget).bitrateBitsPerSecond)
    }

    @Test
    fun `video sent to a speaker is unsupported`() {
        val plan = CastDeliveryPlanner.plan(
            probe(),
            capabilitiesFor(CastReceiverProfile.AUDIO_ONLY),
            sourceReachableByReceiver = true,
        )

        assertEquals(CastDeliveryMode.UNSUPPORTED, plan.mode)
    }

    @Test
    fun `an unreachable source is served locally even when it plays directly`() {
        val plan = CastDeliveryPlanner.plan(probe(), gen12, sourceReachableByReceiver = false)

        assertEquals(CastDeliveryMode.DIRECT, plan.mode)
        assertTrue(plan.requiresLocalServer)
    }

    @Test
    fun `unknown models fall back to the conservative baseline`() {
        assertEquals(
            capabilitiesFor(CastReceiverProfile.GEN_1_2).videoCodecs,
            capabilitiesFor(CastReceiverProfile.UNKNOWN).videoCodecs,
        )
    }
}

class CastReceiverProfileTest {

    @Test
    fun `model names map to the right profile`() {
        assertEquals(CastReceiverProfile.ULTRA, CastReceiverProfile.forModel("Chromecast Ultra"))
        assertEquals(CastReceiverProfile.GEN_1_2, CastReceiverProfile.forModel("Chromecast"))
        assertEquals(CastReceiverProfile.AUDIO_ONLY, CastReceiverProfile.forModel("Google Home Mini"))
        assertEquals(CastReceiverProfile.UNKNOWN, CastReceiverProfile.forModel("Some New Dongle"))
    }

    @Test
    fun `the hd google tv model is not mistaken for the 4k one`() {
        assertEquals(
            CastReceiverProfile.GOOGLE_TV_HD,
            CastReceiverProfile.forModel("Chromecast with Google TV HD"),
        )
        assertEquals(
            CastReceiverProfile.GOOGLE_TV_4K,
            CastReceiverProfile.forModel("Chromecast with Google TV"),
        )
    }

    @Test
    fun `the capability flag wins over the model name`() {
        assertEquals(
            CastReceiverProfile.AUDIO_ONLY,
            CastReceiverProfile.forModel("Chromecast Ultra", hasVideoOutput = false),
        )
    }
}
