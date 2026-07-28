package com.nuvio.app.features.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Cast SDK entry point. Referenced by name from AndroidManifest via the
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` meta-data key; without
 * that registration `CastContext.getSharedInstance` throws, which is why the previous branch's
 * Cast dependency never did anything.
 *
 * Uses the styled default media receiver, which plays MP4/WebM/HLS/DASH and renders VTT
 * subtitles. That is what the delivery planner targets, so no custom receiver is needed.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName("com.nuvio.app.MainActivity")
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APP_ID)
            .setCastMediaOptions(mediaOptions)
            // Let the hardware volume keys drive the television while a session is live.
            .setEnableReconnectionService(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        /** Google's styled Default Media Receiver. */
        const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
    }
}
