package com.nuvio.app.core.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import java.lang.ref.WeakReference

internal actual object AppOrientationController {
    private var activityRef: WeakReference<Activity>? = null
    private var autoRotateEnabled: Boolean = true

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        apply()
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    actual fun setAutoRotateEnabled(enabled: Boolean) {
        autoRotateEnabled = enabled
        apply()
    }

    private fun apply() {
        val activity = activityRef?.get() ?: return
        // UNSPECIFIED rather than SENSOR: it hands the decision back to the system, so the
        // device's own rotation lock still wins, which is what a user expects from a setting
        // that only says "allow" rather than "force".
        activity.requestedOrientation = if (autoRotateEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
