package com.nuvio.app.features.cast

/**
 * Swift entry point: `CastBridgeRegistrationKt.registerCastBridge(bridge:)`.
 *
 * Deliberately its own file. Kotlin derives the generated Objective-C facade name from the
 * file name, and `CastPlatform.ios.kt` contains a dot, so the name Swift would have to call
 * is not obvious. A plain file name makes it predictable.
 */
fun registerCastBridge(bridge: CastIosBridge) {
    CastPlatform.attachBridge(bridge)
}
