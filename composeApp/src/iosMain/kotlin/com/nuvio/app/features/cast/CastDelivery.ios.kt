package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.capabilitiesFor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.coroutines.resume

/**
 * iOS delivery.
 *
 * Mirrors `CastDelivery.android.kt`: probe the source, plan how it has to reach the receiver,
 * produce a playable copy when the plan calls for one, serve it from the phone when the
 * receiver cannot fetch it directly, and hand the result to [CastPlatform]. The Android version
 * reaches Media3 Transformer and a raw-socket HTTP server directly; this one reaches
 * `CastTranscoder` (FFmpegKit) and `CastLocalServer` (Network.framework) through the same kind
 * of Swift bridge `CastPlatform.ios.kt` already uses for the Cast SDK itself, so neither
 * framework's headers have to be visible to the Kotlin compilation.
 */
actual object CastDelivery {

    private var localServerBridge: CastLocalServerBridge? = null
    private var transcoderBridge: CastTranscoderBridge? = null

    private var publishedId: String? = null
    private var workingFilePath: String? = null

    /** Completion for an in-flight transcode, resolved by Swift through [onTranscodeCompleted]. */
    private var pendingTranscode: ((Result<Unit>) -> Unit)? = null
    private var pendingMode: CastDeliveryMode? = null
    private var pendingReasons: List<CastIncompatibility> = emptyList()

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        val server = localServerBridge ?: return fail("Casting is not available")

        val device = (CastPlatform.connection.value as? CastConnectionState.Connected)?.device
            ?: return fail("No Cast device connected")

        releasePrevious()

        val capabilities = capabilitiesFor(
            CastReceiverProfile.forModel(device.modelName, device.hasVideoOutput),
        )

        // --- Probe ---------------------------------------------------------------------
        _status.value = CastDeliveryStatus.Probing
        val probe = probeCastMedia(request.url, request.headers).getOrNull()
        // A probe failure is not fatal; AVFoundation cannot open every container this app
        // plays (Matroska, notably). The receiver may well handle the stream, so fall back to
        // an optimistic direct play driven by the container alone rather than refusing outright.
        val plan = probe?.let {
            CastDeliveryPlanner.plan(it, capabilities, request.sourceReachableByReceiver)
        } ?: CastDeliveryPlan(
            mode = CastDeliveryMode.DIRECT,
            reasons = emptyList(),
            videoTarget = null,
            audioTarget = null,
            targetContainer = containerFromUrl(request.url),
            requiresLocalServer = !request.sourceReachableByReceiver,
        )

        if (plan.mode == CastDeliveryMode.UNSUPPORTED) {
            return fail("${device.name} cannot play video")
        }

        // --- Produce a playable source -------------------------------------------------
        val contentUrl: String
        val contentType: String

        if (plan.mode == CastDeliveryMode.DIRECT) {
            contentType = contentTypeFor(plan.targetContainer)
            contentUrl = if (plan.requiresLocalServer) {
                val id = newId()
                publishedId = id
                server.publishProxy(
                    id, request.url, contentType,
                    request.headers.keys.toList(), request.headers.values.toList(),
                ) ?: return fail("Phone is not on a network the TV can reach")
            } else {
                request.url
            }
        } else {
            val transcoder = transcoderBridge ?: return fail("Casting is not available")
            _status.value = CastDeliveryStatus.Preparing(plan.mode, -1, plan.reasons)

            val outputPath = NSTemporaryDirectory() + "cast_${newId()}.mp4"
            workingFilePath = outputPath

            val produced = runTranscode(transcoder, request, plan, probe?.durationMs, outputPath)
            if (produced.isFailure) {
                return fail(
                    when (plan.mode) {
                        CastDeliveryMode.REMUX -> "Could not repackage this file for casting"
                        else -> "Could not convert this file for casting"
                    },
                )
            }

            contentType = "video/mp4"
            val id = newId()
            publishedId = id
            contentUrl = server.publishLocalFile(id, outputPath, contentType)
                ?: return fail("Phone is not on a network the TV can reach")
        }

        // --- Hand off ------------------------------------------------------------------
        val result = CastPlatform.load(
            CastMediaRequest(
                contentUrl = contentUrl,
                contentType = contentType,
                title = request.title,
                subtitle = request.subtitle,
                posterUrl = request.posterUrl,
                startPositionMs = request.startPositionMs,
                durationMs = probe?.durationMs,
                isLive = probe?.isLive == true && probe.durationMs == null,
                subtitles = request.subtitles,
            ),
        )

        return result.fold(
            onSuccess = {
                _status.value = CastDeliveryStatus.Playing(plan.mode)
                Result.success(Unit)
            },
            onFailure = { error -> fail(error.message ?: "The TV refused the stream") },
        )
    }

    actual fun cancel() {
        transcoderBridge?.cancel()
        pendingTranscode?.invoke(Result.failure(IllegalStateException("Cast was cancelled")))
        pendingTranscode = null
        releasePrevious()
        _status.value = CastDeliveryStatus.Idle
    }

    // -------------------------------------------------------------------------------------
    // Called from Swift.
    // -------------------------------------------------------------------------------------

    fun attachLocalServerBridge(bridge: CastLocalServerBridge) {
        localServerBridge = bridge
    }

    fun attachTranscoderBridge(bridge: CastTranscoderBridge) {
        transcoderBridge = bridge
    }

    fun onTranscodeProgress(percent: Int) {
        val mode = pendingMode ?: return
        _status.value = CastDeliveryStatus.Preparing(mode, percent, pendingReasons)
    }

    fun onTranscodeCompleted(success: Boolean, message: String?) {
        val completion = pendingTranscode ?: return
        pendingTranscode = null
        completion(
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(message ?: "Could not convert this file"))
            },
        )
    }

    // -------------------------------------------------------------------------------------

    private suspend fun runTranscode(
        transcoder: CastTranscoderBridge,
        request: CastStreamRequest,
        plan: CastDeliveryPlan,
        durationMs: Long?,
        outputPath: String,
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        pendingMode = plan.mode
        pendingReasons = plan.reasons
        pendingTranscode = { result ->
            pendingTranscode = null
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            transcoder.cancel()
            pendingTranscode = null
        }

        val video = plan.videoTarget
        val audio = plan.audioTarget
        transcoder.start(
            sourceUrl = request.url,
            headerNames = request.headers.keys.toList(),
            headerValues = request.headers.values.toList(),
            outputPath = outputPath,
            reencodeVideo = video != null,
            videoWidth = video?.width ?: 0,
            videoHeight = video?.height ?: 0,
            videoBitrateBitsPerSecond = video?.bitrateBitsPerSecond ?: 0L,
            videoFrameRate = video?.frameRate ?: 0f,
            reencodeAudio = audio != null,
            audioChannelCount = audio?.channelCount ?: 0,
            audioBitrateBitsPerSecond = audio?.bitrateBitsPerSecond ?: 0L,
            durationMs = durationMs ?: 0L,
        )
    }

    private fun releasePrevious() {
        publishedId?.let { localServerBridge?.unpublish(it) }
        publishedId = null
        workingFilePath?.let { path -> NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
        workingFilePath = null
    }

    private fun fail(message: String): Result<Unit> {
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun newId(): String = NSUUID().UUIDString.replace("-", "")
}
