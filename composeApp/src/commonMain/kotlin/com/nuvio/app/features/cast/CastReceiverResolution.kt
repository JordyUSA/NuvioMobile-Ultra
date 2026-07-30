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
    val name: String,
    val capabilities: CastReceiverCapabilities,
    val deliver: suspend (CastMediaRequest) -> Result<Unit>,
)

internal fun resolveActiveReceiver(): ActiveCastReceiver? {
    (CastPlatform.connection.value as? CastConnectionState.Connected)?.let { connected ->
        return ActiveCastReceiver(
            name = connected.device.name,
            capabilities = capabilitiesFor(
                CastReceiverProfile.forModel(connected.device.modelName, connected.device.hasVideoOutput),
            ),
            deliver = CastPlatform::load,
        )
    }
    (DlnaPlatform.connection.value as? DlnaConnectionState.Connected)?.let { connected ->
        return ActiveCastReceiver(
            name = connected.device.name,
            capabilities = connected.device.capabilities,
            deliver = DlnaPlatform::load,
        )
    }
    return null
}
