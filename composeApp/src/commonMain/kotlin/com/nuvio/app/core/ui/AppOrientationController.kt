package com.nuvio.app.core.ui

/**
 * Whether the app outside the player may follow the device's orientation.
 *
 * Both platforms ship with rotation unrestricted, so this exists to let a user turn it off: a
 * browsing screen that flips to landscape every time the phone tilts in bed is a nuisance, and
 * the OS rotation lock is a blunt, app-wide instrument.
 *
 * The player is deliberately unaffected. It locks itself to landscape while it is on screen and
 * restores whatever was set before, so this preference composes with it rather than fighting it.
 */
internal expect object AppOrientationController {
    fun setAutoRotateEnabled(enabled: Boolean)
}
