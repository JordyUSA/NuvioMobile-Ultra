package com.nuvio.app.core.ui

import platform.Foundation.NSNotificationCenter

/**
 * Swift owns the orientation mask (OrientationLockCoordinator), because UIKit asks the app
 * delegate for it. This posts the same style of notification the player's landscape lock uses.
 */
internal actual object AppOrientationController {
    actual fun setAutoRotateEnabled(enabled: Boolean) {
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = if (enabled) "NuvioAllowAutoRotate" else "NuvioLockPortrait",
            `object` = null,
        )
    }
}
