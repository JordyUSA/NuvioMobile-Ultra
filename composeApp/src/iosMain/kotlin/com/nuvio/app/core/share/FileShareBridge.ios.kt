package com.nuvio.app.core.share

import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

internal actual object FileShareBridge {

    actual fun shareFile(localFileUri: String, displayName: String): Boolean {
        val path = localFileUri.toLocalPath() ?: return false
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return false

        val presenter = topViewController() ?: return false
        val controller = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path)),
            applicationActivities = null,
        )
        // Without an anchor the sheet crashes on iPad, where it has to be presented as a popover.
        controller.popoverPresentationController?.sourceView = presenter.view
        presenter.presentViewController(controller, animated = true, completion = null)
        return true
    }

    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (controller?.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }

    private fun String.toLocalPath(): String? {
        val trimmed = trim().takeIf { it.isNotBlank() } ?: return null
        if (!trimmed.startsWith("file:")) return trimmed
        return NSURL.URLWithString(trimmed)?.path
    }
}
