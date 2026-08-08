package com.nuvio.app.features.cast

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

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

    /** Set when a conversion reports failure, so the wait for a playable playlist gives up. */
    private var transcodeFailed = false

    /** True once the receiver has the stream, after which progress no longer drives status. */
    private var handedOff = false

    /** Name of the HLS playlist inside the working directory. */
    private const val PLAYLIST_NAME = "index.m3u8"
    private const val POLL_INTERVAL_MS = 250L
    private const val PLAYLIST_TIMEOUT_MS = 90_000L

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun cast(request: CastStreamRequest): Result<Unit> {
        val server = localServerBridge ?: return fail("Casting is not available")

        val receiver = resolveActiveReceiver() ?: return fail("No Cast device connected")

        releasePrevious()
        handedOff = false

        val capabilities = receiver.capabilities

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

        // Which route a stream takes decides everything downstream, and nothing recorded it —
        // a log showing a failure two seconds after loading gave no way to tell whether the
        // television had been handed a proxy URL, a playlist, or the origin itself.
        CastDiagnostics.log(
            "Deliver",
            "plan=${plan.mode} container=${plan.targetContainer} " +
                "localServer=${plan.requiresLocalServer} reachable=${request.sourceReachableByReceiver} " +
                "video=${probe?.videoCodec ?: "?"} audio=${probe?.audioCodecs?.firstOrNull() ?: "?"} " +
                "reasons=${plan.reasons.joinToString(",") { it::class.simpleName ?: "?" }}",
        )

        if (plan.mode == CastDeliveryMode.UNSUPPORTED) {
            return fail("${receiver.name} cannot play video")
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

            // HLS into a directory, not a single file. The receiver is handed the playlist as
            // soon as the first segments exist and plays them while the rest is still being
            // produced, instead of waiting for an entire film to be converted first.
            val directory = NSTemporaryDirectory() + "cast_${newId()}"
            if (!NSFileManager.defaultManager.createDirectoryAtPath(
                    directory, withIntermediateDirectories = true, attributes = null, error = null,
                )
            ) {
                return fail("Could not prepare this file for casting")
            }
            workingFilePath = directory

            startTranscode(transcoder, request, plan, probe?.durationMs, "$directory/$PLAYLIST_NAME")

            // Wait only for enough of a head start to play from, not for the whole conversion.
            if (!awaitPlayablePlaylist(directory)) {
                transcoder.cancel()
                return fail(
                    when (plan.mode) {
                        CastDeliveryMode.REMUX -> "Could not repackage this file for casting"
                        else -> "Could not convert this file for casting"
                    },
                )
            }

            contentType = "application/x-mpegURL"
            val id = newId()
            publishedId = id
            contentUrl = server.publishDirectory(id, directory, PLAYLIST_NAME)
                ?: return fail("Phone is not on a network the TV can reach")
        }

        // --- Hand off ------------------------------------------------------------------
        CastDiagnostics.log("Deliver", "handing over $contentType — $contentUrl")

        val result = receiver.deliver(
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
                handedOff = true
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
        // Conversion continues long after the receiver has started playing now, and those
        // updates must not drag the status back to "preparing" while the television is
        // already showing the picture.
        if (handedOff) return
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

    /**
     * True once the playlist lists a segment the receiver could start on.
     *
     * ffmpeg only appends a segment once it has been closed and renamed into place, so a name
     * appearing here is a file that exists in full. Two segments are waited for rather than
     * one: handing over on the very first leaves no margin at all, and the second costs a few
     * seconds against a conversion that runs for many minutes.
     */
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun awaitPlayablePlaylist(directory: String): Boolean {
        var waited = 0L
        while (waited < PLAYLIST_TIMEOUT_MS) {
            // A conversion that dies reports through onTranscodeCompleted; without this the
            // wait would sit here until the deadline for a job that is already over.
            if (transcodeFailed) return false

            val entries = NSFileManager.defaultManager
                .contentsOfDirectoryAtPath(directory, error = null)
                ?.filterIsInstance<String>()
                .orEmpty()
            // ffmpeg writes each segment under a temporary name and renames it once closed, so
            // a segment visible under its final name is a whole one. Two rather than one: a
            // single segment leaves the receiver with no margin, and the second costs a few
            // seconds against a conversion that runs for many minutes.
            val playlistWritten = entries.any { it == PLAYLIST_NAME }
            val finishedSegments = entries.count { it.startsWith("seg") && !it.endsWith(".tmp") }
            if (playlistWritten && finishedSegments >= 2) return true

            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return false
    }

    private fun startTranscode(
        transcoder: CastTranscoderBridge,
        request: CastStreamRequest,
        plan: CastDeliveryPlan,
        durationMs: Long?,
        outputPath: String,
    ) {
        pendingMode = plan.mode
        pendingReasons = plan.reasons
        transcodeFailed = false
        // Deliberately not awaited. The conversion keeps running long after the receiver has
        // started playing, and only reports back here if it fails.
        pendingTranscode = { result ->
            pendingTranscode = null
            if (result.isFailure) transcodeFailed = true
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

    @OptIn(ExperimentalForeignApi::class)
    private fun releasePrevious() {
        // A conversion now outlives the handoff, so it has to be stopped before its working
        // directory is deleted out from under it.
        transcoderBridge?.cancel()
        pendingTranscode = null
        publishedId?.let { localServerBridge?.unpublish(it) }
        publishedId = null
        workingFilePath?.let { path -> NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
        workingFilePath = null
    }

    private fun fail(message: String): Result<Unit> {
        CastDiagnostics.log("Deliver", "failed: $message")
        _status.value = CastDeliveryStatus.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun newId(): String = NSUUID().UUIDString.replace("-", "")
}
