package com.nuvio.app.core.sync

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification

internal actual object AppBackgroundMonitor {
    actual fun events(): Flow<Unit> = callbackFlow {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            UIApplicationDidEnterBackgroundNotification,
            UIApplicationWillTerminateNotification,
        ).map { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = null,
            ) { _ ->
                trySend(Unit)
            }
        }

        awaitClose {
            observers.forEach(center::removeObserver)
        }
    }
}
