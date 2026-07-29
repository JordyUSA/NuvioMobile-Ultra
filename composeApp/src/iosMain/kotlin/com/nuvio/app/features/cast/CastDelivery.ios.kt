package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.capabilitiesFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS delivery.
 *
 * Direct play only. The remux and transcode paths both need a local HTTP server and a media
 * processor, neither of which exists on iOS yet, so anything the receiver cannot fetch and
 * decode as-is fails with an explanation rather than appearing to work and then stalling on
 * the television. That covers MP4, WebM, HLS and DASH from a reachable origin, which is the
 * common case; Matroska and unsupported codecs are refused.
 */
actual object CastDelivery {

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        val device = (CastPlatform.connection.value as? CastConnectionState.Connected)?.device
            ?: return fail("No Cast device connected")

        val capabilities = capabilitiesFor(
            CastReceiverProfile.forModel(device.modelName, device.hasVideoOutput),
        )

        _status.value = CastDeliveryStatus.Probing
        val container = containerFromUrl(request.url)

        // Without a probe there is nothing to say about the codecs, so the planner is given
        // container and reachability only. It will still reject a container the receiver
        // cannot open, which is the check that matters most here.
        val probe = CastMediaProbe(
            container = container,
            video = null,
            audioTracks = emptyList(),
        )
        val plan = CastDeliveryPlanner.plan(probe, capabilities, request.sourceReachableByReceiver)

        if (plan.mode != CastDeliveryMode.DIRECT || plan.requiresLocalServer) {
            return fail(
                when {
                    !capabilities.supportsVideo -> "${device.name} cannot play video"
                    !request.sourceReachableByReceiver ->
                        "This source is only reachable from your phone, which iOS cannot serve yet"
                    else ->
                        "This file needs converting before it can be cast, " +
                            "which is not supported on iOS yet"
                },
            )
        }

        val result = CastPlatform.load(
            CastMediaRequest(
                contentUrl = request.url,
                contentType = contentTypeFor(container),
                title = request.title,
                subtitle = request.subtitle,
                posterUrl = request.posterUrl,
                startPositionMs = request.startPositionMs,
                isLive = container == CastContainer.HLS || container == CastContainer.DASH,
                subtitles = request.subtitles,
            ),
        )

        return result.fold(
            onSuccess = {
                _status.value = CastDeliveryStatus.Playing(CastDeliveryMode.DIRECT)
                Result.success(Unit)
            },
            onFailure = { error -> fail(error.message ?: "The TV refused the stream") },
        )
    }

    actual fun cancel() {
        _status.value = CastDeliveryStatus.Idle
    }

    private fun fail(message: String): Result<Unit> {
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }
}

/**
 * No probe on iOS yet.
 *
 * Returning a failure is the honest answer and is handled: callers fall back to reasoning
 * from the container alone. Reading track details would mean loading AVAsset keys
 * asynchronously, which belongs with the remux work rather than ahead of it.
 */
actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> = Result.failure(
    UnsupportedOperationException("Media probing is not implemented on iOS"),
)
