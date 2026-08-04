package com.nuvio.app.features.downloads

/**
 * Implemented in Swift.
 *
 * Both members reach UIKit/Foundation surface that Kotlin/Native's default Darwin interop does not
 * expose cleanly for this toolchain (`UIViewController.popoverPresentationController` and
 * `NSProcessInfo.isLowPowerModeEnabled` both failed to resolve when called directly from Kotlin) —
 * the same reason the converter and cast features already route anything UIKit-presentation-shaped
 * through a Swift bridge rather than calling it from Kotlin. Scalars-only, matching
 * `ConverterBridge`/`CastTranscoderBridge`.
 */
interface DownloadsPlatformBridge {
    /** Presents the share sheet for the file at [path]. Returns false if it could not be shown. */
    fun shareFile(path: String, title: String): Boolean

    fun isLowPowerModeActive(): Boolean
}

object DownloadsPlatformHost {
    private var bridge: DownloadsPlatformBridge? = null

    fun attach(bridge: DownloadsPlatformBridge) {
        this.bridge = bridge
    }

    internal fun requireBridge(): DownloadsPlatformBridge? = bridge
}
