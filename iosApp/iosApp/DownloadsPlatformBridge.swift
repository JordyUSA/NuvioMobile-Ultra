import Foundation
import UIKit
import ComposeApp

/// Implements the Kotlin `DownloadsPlatformBridge` — the share sheet and Low Power Mode, the two
/// members `DownloadsPlatformDownloader.ios.kt` could not call directly (see the interface's doc
/// comment on the Kotlin side for why).
final class DownloadsPlatformBridgeImpl: NSObject, DownloadsPlatformBridge {

    @discardableResult
    static func install() -> DownloadsPlatformBridgeImpl {
        let bridge = DownloadsPlatformBridgeImpl()
        DownloadsPlatformBridgeRegistrationKt.registerDownloadsPlatformBridge(bridge: bridge)
        return bridge
    }

    func shareFile(path: String, title: String) -> Bool {
        guard let presenter = Self.topViewController() else { return false }

        DispatchQueue.main.async {
            let activityController = UIActivityViewController(
                activityItems: [URL(fileURLWithPath: path)],
                applicationActivities: nil
            )
            // Required on iPad or presenting crashes: a share sheet with no popover anchor has
            // nowhere to point its arrow.
            if let popover = activityController.popoverPresentationController {
                popover.sourceView = presenter.view
                popover.sourceRect = presenter.view.bounds
            }
            presenter.present(activityController, animated: true)
        }
        return true
    }

    func isLowPowerModeActive() -> Bool {
        ProcessInfo.processInfo.isLowPowerModeEnabled
    }

    private static func topViewController() -> UIViewController? {
        let rootViewController = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController

        var controller = rootViewController
        while let presented = controller?.presentedViewController {
            controller = presented
        }
        return controller
    }
}
