package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS has no Cast implementation yet.
 *
 * Google's Cast iOS SDK is distributed as a CocoaPods pod or a vendored xcframework, and
 * iosApp is built directly from iosApp.xcodeproj with neither in place. Adding it converts
 * the build to a workspace and changes the CI packaging, so it is deliberately out of scope
 * here rather than half-wired.
 *
 * [isSupported] is false and every call is inert, so the shared UI simply omits the Cast
 * button on iOS instead of offering a control that cannot work.
 */
actual object CastPlatform {

    actual val isSupported: Boolean = false

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    actual val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _connection = MutableStateFlow<CastConnectionState>(CastConnectionState.Idle)
    actual val connection: StateFlow<CastConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<CastPlaybackState?>(null)
    actual val playback: StateFlow<CastPlaybackState?> = _playback.asStateFlow()

    actual fun startDiscovery() = Unit

    actual fun stopDiscovery() = Unit

    actual fun connect(device: CastDevice) = Unit

    actual fun disconnect() = Unit

    actual suspend fun load(request: CastMediaRequest): Result<Unit> =
        Result.failure(UnsupportedOperationException("Casting is not available on iOS"))

    actual fun play() = Unit

    actual fun pause() = Unit

    actual fun seekTo(positionMs: Long) = Unit

    actual fun setVolume(volume: Float) = Unit

    actual fun setMuted(muted: Boolean) = Unit

    actual fun stopPlayback() = Unit
}
