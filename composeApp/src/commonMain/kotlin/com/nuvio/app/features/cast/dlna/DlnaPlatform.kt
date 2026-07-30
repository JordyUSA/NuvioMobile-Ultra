package com.nuvio.app.features.cast.dlna

import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastPlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * The sender-side DLNA/UPnP-AV integration — the counterpart of `CastPlatform` for receivers
 * that speak SSDP discovery and AVTransport SOAP control instead of the Cast SDK.
 *
 * Reuses [CastMediaRequest] and [CastPlaybackState] as-is rather than defining DLNA-specific
 * equivalents: a "URL, MIME type, title, start position" load request and an "isPlaying,
 * position, duration, volume" playback snapshot mean the same thing regardless of which
 * protocol delivered them.
 *
 * Unlike [com.nuvio.app.features.cast.CastPlatform], there is no missing-SDK case: SSDP and SOAP
 * are hand-rolled on both platforms (raw sockets on Android, a Swift bridge on iOS), so
 * [isSupported] is unconditionally true.
 *
 * [playback] has no push source the way Cast SDK's `RemoteMediaClient.Callback` does — DLNA
 * eventing (GENA) needs a subscriber HTTP endpoint this app does not run. Each actual instead
 * polls `GetTransportInfo`/`GetPositionInfo` (and `GetVolume` when the device has a
 * RenderingControl service) on a short interval while [connection] is `Connected`.
 */
expect object DlnaPlatform {

    val isSupported: Boolean

    val devices: StateFlow<List<DlnaDevice>>
    val connection: StateFlow<DlnaConnectionState>
    val playback: StateFlow<CastPlaybackState?>

    /** Begins SSDP discovery. Cheap to call repeatedly. */
    fun startDiscovery()

    fun stopDiscovery()

    fun connect(device: DlnaDevice)

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
