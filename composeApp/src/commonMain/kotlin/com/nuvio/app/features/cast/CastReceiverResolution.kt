package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.dlna.DlnaConnectionState
import com.nuvio.app.features.cast.dlna.DlnaPlatform
import com.nuvio.app.features.cast.model.CastReceiverCapabilities
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.capabilitiesFor

/**
 * The receiver [CastDelivery]'s probe/plan/transcode/serve pipeline is currently handing off to,
 * abstracted away from which transport (Chromecast or DLNA) it actually is. This is the only
 * place that pipeline needs to know two transports exist at all — everything upstream
 * (`CastDeliveryPlanner`, the transcoder bridges, `CastLocalServer`) already only deals in
 * [CastReceiverCapabilities] and a URL to hand off to, neither of which cares who's on the
 * other end.
 */
internal data class ActiveCastReceiver(
    val id: String,
    val name: String,
    val capabilities: CastReceiverCapabilities,
    val deliver: suspend (CastMediaRequest) -> Result<Unit>,
)

internal enum class CastTransport { CHROMECAST, DLNA }

/**
 * The transport the user last picked in the receiver dialog.
 *
 * Tearing down a session is not instantaneous — `CastPlatform.disconnect()` only clears its state
 * once the Cast SDK reports the session ended — while connecting to a DLNA renderer is immediate.
 * Between the two, both transports report themselves connected, and resolving that by fixed
 * preference sent the stream to the receiver the user had just switched away from.
 */
internal var selectedCastTransport: CastTransport? = null

internal fun resolveActiveReceiver(): ActiveCastReceiver? {
    val chromecast = (CastPlatform.connection.value as? CastConnectionState.Connected)?.let { connected ->
        ActiveCastReceiver(
            id = connected.device.id,
            name = connected.device.name,
            capabilities = capabilitiesFor(
                CastReceiverProfile.forModel(connected.device.modelName, connected.device.hasVideoOutput),
            ),
            deliver = CastPlatform::load,
        )
    }
    val dlna = (DlnaPlatform.connection.value as? DlnaConnectionState.Connected)?.let { connected ->
        ActiveCastReceiver(
            id = connected.device.id,
            name = connected.device.name,
            capabilities = connected.device.capabilities,
            deliver = DlnaPlatform::load,
        )
    }

    if (chromecast != null && dlna != null) {
        return if (selectedCastTransport == CastTransport.DLNA) dlna else chromecast
    }
    return chromecast ?: dlna
}
