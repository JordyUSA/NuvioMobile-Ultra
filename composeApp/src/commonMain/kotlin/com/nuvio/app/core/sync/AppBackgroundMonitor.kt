package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow

/**
 * The mirror of [AppForegroundMonitor]: emits when the app leaves the foreground.
 *
 * Termination is not observable on either platform with any guarantee — Android can kill the
 * process outright and iOS only sometimes delivers a will-terminate notification — so this
 * covers backgrounding as well. Anything relying on it has to be safe to run repeatedly, and
 * has to be paired with a start-up sweep for the times no event arrives at all.
 */
internal expect object AppBackgroundMonitor {
    fun events(): Flow<Unit>
}
