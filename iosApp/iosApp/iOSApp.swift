import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    /// Held for the lifetime of the app: the bridge is the Cast SDK's session and discovery
    /// listener, and those are weak references, so letting it deallocate would silently stop
    /// state reaching the shared code.
    private let castBridge = CastBridge.install()

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
