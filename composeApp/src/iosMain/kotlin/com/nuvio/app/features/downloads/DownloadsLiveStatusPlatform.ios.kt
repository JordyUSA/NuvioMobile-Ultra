package com.nuvio.app.features.downloads

import com.nuvio.app.features.converter.ConversionJob
import com.nuvio.app.features.converter.ConversionStatus
import com.nuvio.app.features.converter.ConverterRepository
import com.nuvio.app.features.converter.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null
    private val lastConversionStatusById = mutableMapOf<String, ConversionStatus>()
    private var notificationAuthorizationRequested = false

    init {
        ConverterRepository.onQueueChanged = ::notifyConversionCompletions
    }

    /**
     * Mirrors the Android diff in `DownloadsLiveStatusPlatform.android.kt`: only a job seen active
     * in a previous call and now terminal gets a notification, so restoring an already-finished job
     * from disk on launch stays silent. First use of `UNUserNotificationCenter` in this codebase —
     * authorization is requested lazily and a refusal is swallowed exactly like a denied
     * `POST_NOTIFICATIONS` is on Android.
     */
    private fun notifyConversionCompletions(jobs: List<ConversionJob>) {
        val seenIds = mutableSetOf<String>()
        val justFinished = mutableListOf<ConversionJob>()
        jobs.forEach { job ->
            seenIds += job.id
            val previousStatus = lastConversionStatusById[job.id]
            lastConversionStatusById[job.id] = job.status
            val finished = previousStatus != null &&
                previousStatus.isActive &&
                (job.status == ConversionStatus.Completed || job.status == ConversionStatus.Failed)
            if (finished) justFinished += job
        }
        lastConversionStatusById.keys.retainAll(seenIds)
        if (justFinished.isEmpty()) return

        ensureNotificationAuthorization {
            justFinished.forEach(::postConversionCompletionNotification)
        }
    }

    private fun ensureNotificationAuthorization(onGranted: () -> Unit) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized,
                UNAuthorizationStatusProvisional,
                UNAuthorizationStatusEphemeral,
                -> onGranted()

                UNAuthorizationStatusNotDetermined -> {
                    if (notificationAuthorizationRequested) return@getNotificationSettingsWithCompletionHandler
                    notificationAuthorizationRequested = true
                    center.requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
                    ) { granted, _ ->
                        if (granted) onGranted()
                    }
                }
                // Denied, or an unrecognized future case: stay silent.
                else -> Unit
            }
        }
    }

    private fun postConversionCompletionNotification(job: ConversionJob) {
        val content = UNMutableNotificationContent().apply {
            setTitle(job.title)
            setBody(
                if (job.status == ConversionStatus.Completed) {
                    "Conversion finished"
                } else {
                    job.errorMessage?.takeIf { it.isNotBlank() } ?: "Conversion failed"
                },
            )
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "nuvio.conversion.${job.id}",
            content = content,
            trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter()
            .addNotificationRequest(request, withCompletionHandler = null)
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val primary = items
            .filter { item ->
                item.status == DownloadStatus.Downloading ||
                    item.status == DownloadStatus.Paused ||
                    item.status == DownloadStatus.Failed
            }
            .sortedWith(
                compareBy<DownloadItem> { statusPriority(it.status) }
                    .thenByDescending { it.updatedAtEpochMs },
            )
            .firstOrNull()

        val payload = primary?.let { item ->
            json.encodeToString(
                DownloadsLiveStatusPayload(
                    id = item.id,
                    title = item.title,
                    subtitle = item.displaySubtitle,
                    providerName = item.providerName,
                    streamTitle = item.streamTitle,
                    artworkUrl = item.iosArtworkUrl(),
                    status = item.status.name,
                    downloadedBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    downloadSpeedBytesPerSecond = item.downloadSpeedBytesPerSecond,
                    estimatedRemainingSeconds = item.estimatedRemainingSeconds,
                    progressPercent = if (item.totalBytes != null && item.totalBytes > 0L) {
                        ((item.downloadedBytes.toDouble() / item.totalBytes.toDouble()) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                    } else {
                        -1
                    },
                ),
            )
        }

        if (payload == lastPayload) return
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun statusPriority(status: DownloadStatus): Int = when (status) {
        DownloadStatus.Downloading -> 0
        DownloadStatus.Paused -> 1
        DownloadStatus.Failed -> 2
        DownloadStatus.Completed -> 3
    }

    private fun DownloadItem.iosArtworkUrl(): String? =
        listOf(
            episodeThumbnail,
            poster,
            background,
            detailsSnapshot?.poster,
            detailsSnapshot?.background,
        )
            .firstOrNull { it?.startsWith("http", ignoreCase = true) == true }
}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val providerName: String,
    val streamTitle: String,
    val artworkUrl: String? = null,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val estimatedRemainingSeconds: Long? = null,
    val progressPercent: Int,
)
