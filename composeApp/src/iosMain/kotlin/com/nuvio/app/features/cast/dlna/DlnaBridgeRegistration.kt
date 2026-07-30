package com.nuvio.app.features.cast.dlna

/**
 * Swift entry point: `DlnaBridgeRegistrationKt.registerDlnaTransportBridge(bridge:)`.
 *
 * Deliberately its own file, same reason `CastBridgeRegistration.kt` documents: `DlnaPlatform.ios.kt`
 * contains a dot, which would make the generated Objective-C facade name Swift has to call
 * non-obvious.
 */
fun registerDlnaTransportBridge(bridge: DlnaTransportBridge) {
    DlnaPlatform.attachBridge(bridge)
}
