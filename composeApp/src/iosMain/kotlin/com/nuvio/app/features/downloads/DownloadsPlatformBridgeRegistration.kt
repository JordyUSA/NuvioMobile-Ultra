package com.nuvio.app.features.downloads

/**
 * Swift entry point: `DownloadsPlatformBridgeRegistrationKt.registerDownloadsPlatformBridge(bridge:)`.
 *
 * Its own file for the same reason as `ConverterBridgeRegistration.kt` — a filename containing a
 * dot makes the generated symbol Swift has to call non-obvious.
 */
fun registerDownloadsPlatformBridge(bridge: DownloadsPlatformBridge) {
    DownloadsPlatformHost.attach(bridge)
}
