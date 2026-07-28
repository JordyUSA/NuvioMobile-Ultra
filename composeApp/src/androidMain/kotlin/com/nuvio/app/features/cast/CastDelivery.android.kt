package com.nuvio.app.features.cast

import android.content.Context
import android.util.Log
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.capabilitiesFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

actual object CastDelivery {

    private const val TAG = "CastDelivery"

    private var appContext: Context? = null
    private var processor: CastMediaProcessor? = null
    private val server = CastLocalServer()
    private var publishedId: String? = null
    private var workingFile: File? = null

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    /** Android-only entry point; call alongside [CastPlatform.initialize]. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        val context = appContext
            ?: return fail("Casting is not initialised")

        val device = (CastPlatform.connection.value as? CastConnectionState.Connected)?.device
            ?: return fail("No Cast device connected")

        releasePrevious()

        val capabilities = capabilitiesFor(
            CastReceiverProfile.forModel(device.modelName, device.hasVideoOutput),
        )

        // --- Probe ---------------------------------------------------------------------
        _status.value = CastDeliveryStatus.Probing
        val probe = probeCastMedia(request.url, request.headers).getOrElse { error ->
            // A probe failure is not fatal. The receiver may well handle the stream, so fall
            // back to an optimistic direct play rather than refusing to cast at all.
            Log.w(TAG, "Probe failed, attempting direct play", error)
            null
        }

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
                server.publish(
                    id,
                    CastLocalServer.Payload.Proxy(request.url, contentType, request.headers),
                ) ?: return fail("Phone is not on a network the TV can reach")
            } else {
                request.url
            }
        } else {
            _status.value = CastDeliveryStatus.Preparing(plan.mode, -1, plan.reasons)

            val engine = processor ?: castMediaProcessor(context).also { processor = it }
            val output = File(context.cacheDir, "cast/${newId()}.mp4")
            workingFile = output

            val produced = engine.process(
                sourceUrl = request.url,
                plan = plan,
                output = output,
                durationMs = probe?.durationMs,
                headers = request.headers,
            ) { progress ->
                _status.value = CastDeliveryStatus.Preparing(plan.mode, progress, plan.reasons)
            }.getOrElse { error ->
                Log.w(TAG, "${plan.mode} failed", error)
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
            contentUrl = server.publish(id, CastLocalServer.Payload.LocalFile(produced, contentType))
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
        processor?.cancel()
        releasePrevious()
        _status.value = CastDeliveryStatus.Idle
    }

    private fun releasePrevious() {
        publishedId?.let(server::unpublish)
        publishedId = null
        workingFile?.let { file -> runCatching { if (file.exists()) file.delete() } }
        workingFile = null
    }

    private fun fail(message: String): Result<Unit> {
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
