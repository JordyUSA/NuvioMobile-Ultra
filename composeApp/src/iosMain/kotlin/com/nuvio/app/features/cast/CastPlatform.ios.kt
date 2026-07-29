package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Implemented in Swift, over the Google Cast SDK.
 *
 * The SDK is an Objective-C xcframework linked into iosApp rather than into the Kotlin
 * compilation, so it is reached through this interface instead of cinterop. That keeps the
 * Kotlin side free of any Cast headers and means the framework only has to be wired into the
 * Xcode target, which is where it already is.
 */
interface CastIosBridge {
    fun startDiscovery()
    fun stopDiscovery()
    fun connect(deviceId: String)
    fun disconnect()
    fun load(
        contentUrl: String,
        contentType: String,
        title: String,
        subtitle: String?,
        posterUrl: String?,
        startPositionMs: Long,
        durationMs: Long,
        isLive: Boolean,
    )
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
    fun stopPlayback()
}

actual object CastPlatform {

    private var bridge: CastIosBridge? = null

    /**
     * True once Swift has registered a bridge. Until then the shared UI hides casting rather
     * than offering a control that cannot work, which is also what happens on a device where
     * the Cast SDK fails to start.
     */
    actual val isSupported: Boolean
        get() = bridge != null

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    actual val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _connection = MutableStateFlow<CastConnectionState>(CastConnectionState.Idle)
    actual val connection: StateFlow<CastConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<CastPlaybackState?>(null)
    actual val playback: StateFlow<CastPlaybackState?> = _playback.asStateFlow()

    /** Completion for the in-flight [load], resolved by Swift through [onLoadResult]. */
    private var pendingLoad: ((Result<Unit>) -> Unit)? = null

    actual fun startDiscovery() {
        bridge?.startDiscovery()
    }

    actual fun stopDiscovery() {
        bridge?.stopDiscovery()
    }

    actual fun connect(device: CastDevice) {
        val target = bridge ?: return
        _connection.value = CastConnectionState.Connecting(device)
        target.connect(device.id)
    }

    actual fun disconnect() {
        bridge?.disconnect()
    }

    actual suspend fun load(request: CastMediaRequest): Result<Unit> {
        val target = bridge
            ?: return Result.failure(IllegalStateException("Casting is not available"))

        return suspendCancellableCoroutine { continuation ->
            pendingLoad = { result ->
                pendingLoad = null
                if (continuation.isActive) continuation.resume(result)
            }
            continuation.invokeOnCancellation { pendingLoad = null }
            target.load(
                contentUrl = request.contentUrl,
                contentType = request.contentType,
                title = request.title,
                subtitle = request.subtitle,
                posterUrl = request.posterUrl,
                startPositionMs = request.startPositionMs,
                durationMs = request.durationMs ?: 0L,
                isLive = request.isLive,
            )
        }
    }

    actual fun play() {
        bridge?.play()
    }

    actual fun pause() {
        bridge?.pause()
    }

    actual fun seekTo(positionMs: Long) {
        bridge?.seekTo(positionMs)
    }

    actual fun setVolume(volume: Float) {
        bridge?.setVolume(volume.coerceIn(0f, 1f))
    }

    actual fun setMuted(muted: Boolean) {
        bridge?.setMuted(muted)
    }

    actual fun stopPlayback() {
        bridge?.stopPlayback()
    }

    // -------------------------------------------------------------------------------------
    // Called from Swift. Kept to scalars and the shared data classes so the generated
    // Objective-C header stays simple to call.
    // -------------------------------------------------------------------------------------

    fun attachBridge(bridge: CastIosBridge) {
        this.bridge = bridge
    }

    fun onDevicesChanged(devices: List<CastDevice>) {
        _devices.value = devices
    }

    /** [state]: 0 idle, 1 connecting, 2 connected, 3 failed. */
    fun onConnectionChanged(state: Int, device: CastDevice?, message: String?) {
        _connection.value = when (state) {
            1 -> device?.let(CastConnectionState::Connecting) ?: CastConnectionState.Idle
            2 -> device?.let(CastConnectionState::Connected) ?: CastConnectionState.Idle
            3 -> CastConnectionState.Failed(message ?: "Could not connect")
            else -> CastConnectionState.Idle
        }
        if (state != 2) _playback.value = null
    }

    fun onPlaybackChanged(
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        durationMs: Long,
        volume: Float,
        isMuted: Boolean,
        title: String?,
    ) {
        _playback.value = CastPlaybackState(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = positionMs,
            durationMs = durationMs,
            volume = volume,
            isMuted = isMuted,
            title = title,
        )
    }

    fun onPlaybackCleared() {
        _playback.value = null
    }

    fun onLoadResult(success: Boolean, message: String?) {
        val completion = pendingLoad ?: return
        completion(
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(message ?: "The TV refused the stream"))
            },
        )
    }
}
