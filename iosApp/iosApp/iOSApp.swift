import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    /// Held for the lifetime of the app: the bridge is the Cast SDK's session and discovery
    /// listener, and those are weak references, so letting it deallocate would silently stop
    /// state reaching the shared code.
    private let castBridge = CastBridge.install()

    /// Backs the local HTTP server and FFmpegKit transcoder `CastDelivery.ios.kt` drives for
    /// the remux/transcode paths. Held for the same reason as `castBridge`: letting it
    /// deallocate would tear down any in-flight local server or transcode.
    private let castDeliveryBridge = CastDeliveryBridge.install()

    /// Backs `DlnaPlatform.ios.kt`'s SSDP discovery and SOAP control. Held for the same
    /// lifetime reason as the two bridges above: it owns the discovery timer and any in-flight
    /// HTTP requests, which letting it deallocate would silently cut off.
    private let dlnaTransport = DlnaTransport.install()

    /// Backs `ConversionEngine.ios.kt`'s FFmpegKit conversions. Held for the same lifetime reason
    /// as the bridges above: it owns any in-flight conversion and its background-task assertion.
    private let converterBridge = ConverterBridgeImpl.install()

    /// Backs `DownloadsPlatformDownloader.ios.kt`'s share sheet and Low Power Mode check. Stateless
    /// itself, but installed alongside the others so every Kotlin-side bridge is wired in one place.
    private let downloadsPlatformBridge = DownloadsPlatformBridgeImpl.install()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}
