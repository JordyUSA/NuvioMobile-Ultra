package com.nuvio.app.features.converter

/**
 * Swift entry point: `ConverterBridgeRegistrationKt.registerConverterBridge(bridge:)`.
 *
 * Its own file for the same reason as `CastDeliveryBridgeRegistration.kt` — a filename containing
 * a dot makes the generated symbol Swift has to call non-obvious.
 */
fun registerConverterBridge(bridge: ConverterBridge) {
    ConverterHost.attach(bridge)
}
