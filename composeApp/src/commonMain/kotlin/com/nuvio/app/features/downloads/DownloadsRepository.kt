package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probesInFlight = mutableSetOf<String>()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        hasLoaded = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.newestPlayable { item ->
            item.videoId == normalizedVideoId
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.newestPlayable { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber
            }
        } else {
            items.newestPlayable { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null
            }
        }
    }

    /**
     * The most recently touched playable match.
     *
     * Was `firstOrNull`, which was unambiguous only while one file existed per logical episode.
     * The video converter can add a second item with the same identity, and someone who just
     * converted a file for compatibility means that file to be the one that plays — so "newest
     * wins" is both deterministic and the right answer.
     */
    private fun List<DownloadItem>.newestPlayable(
        predicate: (DownloadItem) -> Boolean,
    ): DownloadItem? =
        filter { predicate(it) && it.hasPlayableLocalFile() }
            .maxByOrNull { it.updatedAtEpochMs }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        episodeOverview: String? = null,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            nowEpochMs = now,
        )
        val detailsSnapshot = snapshotCurrentDetails(
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            episodeOverview = episodeOverview,
            detailsSnapshot = detailsSnapshot,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            externalSubtitles = stream.externalSubtitles,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                downloadSpeedBytesPerSecond = 0L,
                // Drop the sample so resuming does not measure a speed across the pause.
                statsUpdatedAtEpochMs = 0L,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            failureReason = null,
            errorDetail = null,
            localFileUri = null,
            downloadSpeedBytesPerSecond = 0L,
            statsUpdatedAtEpochMs = 0L,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)
        item.externalSubtitles.forEach { subtitle ->
            val subtitleUri = subtitle.url.takeIf { it.startsWith("file:", ignoreCase = true) }
                ?: return@forEach
            DownloadsPlatformDownloader.removeFile(subtitleUri)
        }

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
    }

    /**
     * Adds a converted copy alongside the download it came from.
     *
     * Every metadata field is carried over — including [DownloadItem.videoId], so offline detail
     * pages and watch progress still resolve — with a fresh id and its own timestamps. Returns null
     * only when the source has since disappeared, which the caller treats as "delete the file we
     * just produced" rather than as a silent success.
     */
    fun registerConvertedCopy(
        source: DownloadItem,
        fileName: String,
        localFileUri: String,
        totalBytes: Long?,
        conversionLabel: String,
    ): DownloadItem? {
        ensureLoaded()
        if (_uiState.value.items.none { it.id == source.id }) return null

        val now = DownloadsClock.nowEpochMs()
        val copy = source.copy(
            id = nextDownloadId(now),
            fileName = fileName,
            localFileUri = localFileUri,
            totalBytes = totalBytes,
            downloadedBytes = totalBytes ?: source.downloadedBytes,
            downloadSpeedBytesPerSecond = 0L,
            status = DownloadStatus.Completed,
            errorMessage = null,
            convertedFromDownloadId = source.id,
            conversionLabel = conversionLabel,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        publish(listOf(copy) + _uiState.value.items)
        persist()
        return copy
    }

    /**
     * Points an existing download at a converted file, replacing the original on disk.
     *
     * The record is updated and persisted *before* the old file is deleted, so a kill at any point
     * leaves the item pointing at a file that exists. The worst case is one orphaned file, which
     * `resolveLocalFileUri` already tolerates — the opposite order would leave a download entry
     * pointing at nothing.
     *
     * The item's id, videoId and parent metadata are all preserved, so watch progress and library
     * grouping survive the swap.
     */
    fun replaceLocalFile(
        downloadId: String,
        newFileName: String,
        newLocalFileUri: String,
        newTotalBytes: Long?,
        conversionLabel: String,
    ): Boolean {
        ensureLoaded()
        val existing = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
        val previousUri = playableLocalFileUri(existing) ?: existing.localFileUri
        val previousFileName = existing.fileName

        mutateItem(downloadId) { current ->
            current.copy(
                fileName = newFileName,
                localFileUri = newLocalFileUri,
                totalBytes = newTotalBytes,
                downloadedBytes = newTotalBytes ?: current.downloadedBytes,
                status = DownloadStatus.Completed,
                errorMessage = null,
                conversionLabel = conversionLabel,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }

        // Only now, with the new location recorded and persisted, is the old file expendable. A
        // failure here is not worth failing the conversion over — it leaves a stray file, nothing
        // more.
        if (previousUri != null && previousUri != newLocalFileUri) {
            runCatching { DownloadsPlatformDownloader.removeFile(previousUri) }
        }
        if (previousFileName != newFileName) {
            runCatching { DownloadsPlatformDownloader.removePartialFile(previousFileName) }
        }
        return true
    }

    fun findOfflineMetaDetails(type: String, id: String): MetaDetails? {
        ensureLoaded()
        val normalizedType = type.trim().lowercase()
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return null

        return _uiState.value.items
            .asSequence()
            .filter { item ->
                item.parentMetaId.trim() == normalizedId ||
                    item.videoId.trim() == normalizedId ||
                    item.detailsSnapshot?.id?.trim() == normalizedId
            }
            .sortedByDescending { it.updatedAtEpochMs }
            .firstNotNullOfOrNull { item ->
                val offline = item.toOfflineMetaDetails() ?: return@firstNotNullOfOrNull null
                val typeMatches = normalizedType.isBlank() ||
                    offline.type.equals(type, ignoreCase = true) ||
                    item.parentMetaType.equals(type, ignoreCase = true) ||
                    item.contentType.equals(type, ignoreCase = true)
                if (typeMatches) offline else null
            }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = if (item.status == DownloadStatus.Downloading) {
                    item.copy(
                        status = DownloadStatus.Paused,
                        downloadSpeedBytesPerSecond = 0L,
                        statsUpdatedAtEpochMs = 0L,
                        errorMessage = null,
                    )
                } else {
                    item
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
    }

    /**
     * How often the speed, size and ETA text is recomputed. The underlying progress emissions
     * stay at their platform cadence so the bar keeps animating smoothly.
     */
    private const val DownloadStatsSampleIntervalMs = 1_000L

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(
            sourceUrl = item.sourceUrl,
            sourceHeaders = item.sourceHeaders,
            destinationFileName = item.fileName,
        )

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        val now = DownloadsClock.nowEpochMs()
                        val nextDownloadedBytes = downloadedBytes.coerceAtLeast(0L)
                        val sampleElapsedMs = (now - current.statsUpdatedAtEpochMs).coerceAtLeast(0L)

                        // The bar tracks downloadedBytes on every emission; speed, size and ETA
                        // are recomputed only once a second, because text that changes twice a
                        // second is text nobody can read.
                        val takeStatsSample = current.statsUpdatedAtEpochMs <= 0L ||
                            sampleElapsedMs >= DownloadStatsSampleIntervalMs

                        val progressed = current.copy(
                            downloadedBytes = nextDownloadedBytes,
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = now,
                            errorMessage = null,
                            failureReason = null,
                            errorDetail = null,
                        )

                        if (!takeStatsSample) {
                            progressed
                        } else if (current.statsUpdatedAtEpochMs <= 0L) {
                            // First emission of this run: establish the baseline only. Measuring
                            // against a zero timestamp would report a speed of roughly nothing.
                            progressed.copy(
                                statsBytes = nextDownloadedBytes,
                                statsUpdatedAtEpochMs = now,
                            )
                        } else {
                            val byteDelta = (nextDownloadedBytes - current.statsBytes).coerceAtLeast(0L)
                            val instantSpeed = if (byteDelta > 0L && sampleElapsedMs > 0L) {
                                (byteDelta * 1_000L) / sampleElapsedMs
                            } else {
                                null
                            }
                            val smoothedSpeed = when {
                                instantSpeed == null -> current.downloadSpeedBytesPerSecond
                                current.downloadSpeedBytesPerSecond > 0L ->
                                    (
                                        current.downloadSpeedBytesPerSecond.toDouble() * 0.65 +
                                            instantSpeed.toDouble() * 0.35
                                        ).toLong()
                                else -> instantSpeed
                            }.coerceAtLeast(0L)

                            progressed.copy(
                                downloadSpeedBytesPerSecond = smoothedSpeed,
                                statsBytes = nextDownloadedBytes,
                                statsUpdatedAtEpochMs = now,
                            )
                        }
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    val cachedSubtitles = DownloadsPlatformDownloader.cacheSubtitleFiles(
                        subtitles = current.externalSubtitles,
                        companionBaseFileName = current.fileName,
                    )
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        externalSubtitles = cachedSubtitles.ifEmpty { current.externalSubtitles },
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        errorMessage = null,
                        failureReason = null,
                        errorDetail = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                probeMediaInfo(item.id)
            },
            onFailure = { reason, detail ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            downloadSpeedBytesPerSecond = 0L,
                            errorMessage = reason.localizedText(),
                            failureReason = reason,
                            errorDetail = detail.takeIf { it.isNotBlank() },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
        )

        activeHandles[item.id] = handle
    }

    /**
     * Reads container, codec and track detail out of a finished download.
     *
     * Reads the local file with MediaInfoLib where the build carries it, and the Cast pipeline's
     * prober otherwise — MediaExtractor on Android, FFprobe on iOS. Failure is expected and
     * survivable: an exotic container simply leaves [DownloadItem.mediaInfo] null, and the
     * resolution badge falls back to parsing the release name. Nothing about playback or the
     * download itself depends on this succeeding.
     */
    fun probeMediaInfo(downloadId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.mediaInfo != null) return
        val localFileUri = item.localFileUri?.takeIf { it.isNotBlank() } ?: return
        if (!probesInFlight.add(downloadId)) return

        probeScope.launch {
            try {
                val mediaInfo = probeDownloadMediaInfo(localFileUri) ?: return@launch
                mutateItem(downloadId) { current ->
                    current.copy(mediaInfo = mediaInfo)
                }
            } finally {
                probesInFlight.remove(downloadId)
            }
        }
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun snapshotCurrentDetails(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        videoId: String,
    ): DownloadDetailsSnapshot? {
        val candidates = listOf(
            parentMetaType to parentMetaId,
            contentType to parentMetaId,
            parentMetaType to videoId,
            contentType to videoId,
        )

        return candidates
            .asSequence()
            .mapNotNull { (type, id) ->
                val normalizedType = type.trim()
                val normalizedId = id.trim()
                if (normalizedType.isBlank() || normalizedId.isBlank()) {
                    null
                } else {
                    MetaDetailsRepository.peek(normalizedType, normalizedId)
                }
            }
            .firstOrNull()
            ?.toDownloadDetailsSnapshot()
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    nowEpochMs: Long,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(nowEpochMs.toString(36))
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
