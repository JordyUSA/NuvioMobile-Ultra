package com.nuvio.app.features.cast.dlna

import com.nuvio.app.features.cast.model.CastReceiverCapabilities

/**
 * A DLNA/UPnP-AV MediaRenderer visible on the local network, discovered via SSDP.
 *
 * Unlike [com.nuvio.app.features.cast.CastDevice] (which the Cast SDK hands us fully formed),
 * everything here is scraped from the device description XML and a `ConnectionManager`
 * `GetProtocolInfo` call made once at discovery time, so [capabilities] is computed up front
 * rather than derived lazily the way `CastReceiverProfile.forModel` is for Chromecast.
 */
data class DlnaDevice(
    /** The device's USN/UDN, unique per renderer. */
    val id: String,
    /** UPnP `friendlyName`. */
    val name: String,
    /** UPnP `modelName`, advisory only — nothing here keys behaviour off it the way Cast does. */
    val modelName: String?,
    /** The device description XML's URL, kept for re-fetching and for resolving relative control URLs. */
    val location: String,
    val avTransportControlUrl: String,
    /** Null when the renderer's description omits a RenderingControl service entirely. */
    val renderingControlControlUrl: String?,
    val capabilities: CastReceiverCapabilities,
)

sealed interface DlnaConnectionState {
    data object Idle : DlnaConnectionState
    data class Connected(val device: DlnaDevice) : DlnaConnectionState
    data class Failed(val message: String) : DlnaConnectionState
}
