package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.StateFlow

/** A Cast receiver visible on the local network. */
data class CastDevice(
    val id: String,
    val name: String,
    val modelName: String?,
    val hasVideoOutput: Boolean,
)

sealed interface CastConnectionState {
    /** Discovery is off, or no receiver has answered yet. */
    data object Idle : CastConnectionState
    data class Connecting(val device: CastDevice) : CastConnectionState
    data class Connected(val device: CastDevice) : CastConnectionState
    data class Failed(val message: String) : CastConnectionState
}

/** Mirrors the receiver's own player state. Null when nothing is loaded. */
data class CastPlaybackState(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
    val isMuted: Boolean,
    val title: String? = null,
)

data class CastSubtitleTrack(
    val id: Long,
    val url: String,
    val language: String?,
    val label: String?,
)

/** Everything the receiver needs in order to start playing. */
data class CastMediaRequest(
    val contentUrl: String,
    /** MIME type of [contentUrl], e.g. "video/mp4" or "application/x-mpegURL". */
    val contentType: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val startPositionMs: Long = 0L,
    val durationMs: Long? = null,
    val isLive: Boolean = false,
    val subtitles: List<CastSubtitleTrack> = emptyList(),
)

/**
 * The sender-side Cast integration.
 *
 * Implemented with the Google Cast SDK on Android. The iOS actual is a stub that reports
 * [isSupported] false, because Google's Cast iOS SDK ships only via CocoaPods or a vendored
 * xcframework and this project builds from a bare xcodeproj; wiring it up is build surgery
 * that has not been done. Callers must branch on [isSupported] rather than assuming a session
 * can be established.
 */
expect object CastPlatform {

    /** False on platforms with no Cast implementation. Nothing else here is meaningful then. */
    val isSupported: Boolean

    val devices: StateFlow<List<CastDevice>>
    val connection: StateFlow<CastConnectionState>
    val playback: StateFlow<CastPlaybackState?>

    /** Begins listening for receivers. Cheap to call repeatedly. */
    fun startDiscovery()

    fun stopDiscovery()

    fun connect(device: CastDevice)

    fun disconnect()

    suspend fun load(request: CastMediaRequest): Result<Unit>

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setVolume(volume: Float)

    fun setMuted(muted: Boolean)

    /** Stops playback and unloads the media, keeping the session open. */
    fun stopPlayback()
}
