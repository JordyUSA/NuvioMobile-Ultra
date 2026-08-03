package com.nuvio.app.features.downloads

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.nuvio.app.features.converter.ConversionEngine
import com.nuvio.app.features.converter.ConverterRepository
import com.nuvio.app.features.converter.ConverterStorage

class DownloadsForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        initializeDownloadRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initializeDownloadRuntime()

        DownloadsRepository.ensureLoaded()
        ConverterRepository.ensureLoaded()
        val items = DownloadsRepository.uiState.value.items
        startForeground(
            DownloadsLiveStatusPlatform.foregroundNotificationId(),
            DownloadsLiveStatusPlatform.buildForegroundNotification(this, items),
        )

        // A conversion holds the service open the same way a download does: it is long-running
        // work the user expects to keep going with the app in the background.
        val hasDownloads = items.any { it.status == DownloadStatus.Downloading }
        val hasConversions = ConverterRepository.uiState.value.hasActiveJobs
        if (!hasDownloads && !hasConversions) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeDownloadRuntime() {
        val context = applicationContext
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsLiveStatusPlatform.initialize(context)
        // The service can be recreated after process death, so the converter's own runtime has to
        // be re-established here too or a restarted job fails on "not initialized".
        ConverterStorage.initialize(context)
        ConversionEngine.initialize(context)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadsForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadsForegroundService::class.java))
        }
    }
}
