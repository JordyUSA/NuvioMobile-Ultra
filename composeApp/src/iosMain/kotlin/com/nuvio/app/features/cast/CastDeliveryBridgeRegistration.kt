package com.nuvio.app.features.cast

/**
 * Swift entry points: `CastDeliveryBridgeRegistrationKt.registerCastLocalServerBridge(bridge:)`
 * and `...registerCastTranscoderBridge(bridge:)`.
 *
 * Deliberately their own file, same reason as `CastBridgeRegistration.kt`: `CastDelivery.ios.kt`
 * contains a dot, which would make the symbol Swift has to call non-obvious.
 */
fun registerCastLocalServerBridge(bridge: CastLocalServerBridge) {
    CastDelivery.attachLocalServerBridge(bridge)
}

fun registerCastTranscoderBridge(bridge: CastTranscoderBridge) {
    CastDelivery.attachTranscoderBridge(bridge)
}
