package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastMediaProbe
import com.nuvio.app.features.cast.probeCastMedia
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns the conversion queue.
 *
 * Structurally a sibling of `DownloadsRepository` — a global object holding a [StateFlow], a JSON
 * blob persisted per profile — because that is the shape every store in this codebase has and the
 * downloads one is the closest neighbour.
 *
 * Two things are deliberately different:
 *  - **Strictly serial.** Encoding saturates the one hardware encoder the device has; running two
 *    jobs is slower end to end than running them in sequence, drains the battery harder, and would
 *    make progress reporting and the foreground notification lie. `Media3CastMediaProcessor` also
 *    keeps a single `Transformer` field, so parallelism is not free even in principle.
 *  - **No paused state.** Neither Media3 Transformer nor FFmpegKit can pause an in-flight export,
 *    so cancel-and-requeue is the only honest primitive. An interrupted job goes back to
 *    [ConversionStatus.Queued], not to a paused state that could never be resumed from where it
 *    stopped.
 */
object ConverterRepository {

    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runningJob: Job? = null
    private var runningJobId: String? = null
    private var hasLoaded = false
    private var nextOrdinal = 0L

    /** Notified whenever the queue changes, so the platform can run a foreground service. */
    internal var onQueueChanged: ((List<ConversionJob>) -> Unit)? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        cancelRunning()
        loadFromDisk()
    }

    fun clearLocalState() {
        cancelRunning()
        hasLoaded = false
        publish(emptyList())
    }

    // --- Queueing ----------------------------------------------------------------------------

    /**
     * Queues [item] for conversion. Returns null when the download has no playable file, which is
     * the one case the caller has to explain to the user.
     */
    fun enqueue(
        item: DownloadItem,
        preset: ConversionPreset,
        spec: ConversionSpec,
        replaceOriginal: Boolean,
    ): String? {
        ensureLoaded()
        DownloadsRepository.playableLocalFileUri(item) ?: return null

        val now = DownloadsClock.nowEpochMs()
        val job = ConversionJob(
            id = nextJobId(now),
            sourceDownloadId = item.id,
            title = item.title,
            poster = item.poster,
            preset = preset,
            spec = spec,
            replaceOriginal = replaceOriginal,
            status = ConversionStatus.Queued,
            outputFileName = buildOutputFileName(item, spec, now),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        publish(_uiState.value.jobs + job)
        pump()
        return job.id
    }

    /** Returns how many of [items] were actually queued. */
    fun enqueueAll(
        items: List<DownloadItem>,
        preset: ConversionPreset,
        spec: ConversionSpec,
        replaceOriginal: Boolean,
    ): Int = items.count { enqueue(it, preset, spec, replaceOriginal) != null }

    fun cancel(jobId: String) {
        ensureLoaded()
        val job = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (job.status.isTerminal) return

        if (runningJobId == jobId) {
            cancelRunning()
        }
        removeWorkFile(job)
        mutate(jobId) { it.copy(status = ConversionStatus.Cancelled, progressPercent = 0) }
        pump()
    }

    fun retry(jobId: String) {
        ensureLoaded()
        val job = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (!job.status.isTerminal || job.status == ConversionStatus.Completed) return
        mutate(jobId) {
            it.copy(status = ConversionStatus.Queued, progressPercent = 0, errorMessage = null)
        }
        pump()
    }

    /** Drops a finished row from the list. Does not touch the produced file. */
    fun dismiss(jobId: String) {
        ensureLoaded()
        val job = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (!job.status.isTerminal) return
        publish(_uiState.value.jobs.filterNot { it.id == jobId })
    }

    fun clearFinished() {
        ensureLoaded()
        publish(_uiState.value.jobs.filter { it.isActive })
    }

    // --- The pump ----------------------------------------------------------------------------

    private fun pump() {
        if (runningJob?.isActive == true) return
        val next = _uiState.value.jobs.firstOrNull { it.status == ConversionStatus.Queued } ?: run {
            runningJobId = null
            return
        }

        runningJobId = next.id
        runningJob = scope.launch { run(next.id) }
    }

    private suspend fun run(jobId: String) {
        val job = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        val source = DownloadsRepository.uiState.value.items
            .firstOrNull { it.id == job.sourceDownloadId }
        val sourceUri = source?.let(DownloadsRepository::playableLocalFileUri)

        if (source == null || sourceUri == null) {
            // The user deleted the download while it sat in the queue. Nothing to convert, and
            // nothing worth reporting as a failure.
            finish(jobId) { it.copy(status = ConversionStatus.Cancelled) }
            pump()
            return
        }

        mutate(jobId) { it.copy(status = ConversionStatus.Probing, progressPercent = -1) }

        val probe = probeCastMedia(sourceUri).getOrNull() ?: fallbackProbe()
        val capabilities = runCatching { converterCapabilities() }
            .getOrDefault(ConverterCapabilities.Minimal)
        val planned = ConversionPlanner.plan(probe, job.spec, capabilities)

        mutate(jobId) {
            it.copy(
                status = ConversionStatus.Running,
                progressPercent = 0,
                sourceDurationMs = probe.durationMs,
            )
        }

        val outcome = try {
            ConversionEngine.convert(
                sourceLocalFileUri = sourceUri,
                plan = planned.plan,
                dropVideo = planned.dropVideo,
                keepSubtitles = job.spec.subtitles == SubtitleHandling.KeepIfCompatible,
                outputFileName = job.outputFileName,
                durationMs = probe.durationMs,
                preferHardwareEncoder = job.spec.preferHardwareEncoder,
                onProgress = { percent -> updateProgress(jobId, percent) },
            )
        } catch (cancellation: CancellationException) {
            // cancel() has already written the Cancelled state; re-throwing keeps the coroutine's
            // own bookkeeping honest.
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }

        outcome.fold(
            onSuccess = { output -> complete(jobId, source, output) },
            onFailure = { error ->
                finish(jobId) {
                    it.copy(
                        status = ConversionStatus.Failed,
                        errorMessage = error.message ?: "Conversion failed",
                    )
                }
            },
        )

        pump()
    }

    private fun complete(jobId: String, source: DownloadItem, output: ConversionOutput) {
        val job = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        val label = conversionLabel(job.spec)

        val registered = if (job.replaceOriginal) {
            DownloadsRepository.replaceLocalFile(
                downloadId = source.id,
                newFileName = job.outputFileName,
                newLocalFileUri = output.localFileUri,
                newTotalBytes = output.sizeBytes,
                conversionLabel = label,
            )
        } else {
            DownloadsRepository.registerConvertedCopy(
                source = source,
                fileName = job.outputFileName,
                localFileUri = output.localFileUri,
                totalBytes = output.sizeBytes,
                conversionLabel = label,
            ) != null
        }

        if (!registered) {
            // The download record could not be updated, so the produced file has nothing pointing
            // at it. Delete rather than orphan it.
            DownloadsPlatformDownloader.removeFile(output.localFileUri)
            finish(jobId) {
                it.copy(
                    status = ConversionStatus.Failed,
                    errorMessage = "The converted file could not be added to downloads",
                )
            }
            return
        }

        finish(jobId) {
            it.copy(
                status = ConversionStatus.Completed,
                progressPercent = 100,
                outputLocalFileUri = output.localFileUri,
                outputBytes = output.sizeBytes,
                errorMessage = null,
            )
        }
    }

    private fun cancelRunning() {
        ConversionEngine.cancel()
        runningJob?.cancel()
        runningJob = null
        runningJobId = null
    }

    // --- State -------------------------------------------------------------------------------

    private fun updateProgress(jobId: String, percent: Int) {
        val current = _uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
        if (current.progressPercent == percent) return
        // Progress alone is not persisted: a tick every few hundred milliseconds would re-encode
        // the whole payload to disk for information that is worthless after a restart anyway.
        publish(
            _uiState.value.jobs.map { job ->
                if (job.id == jobId) job.copy(progressPercent = percent) else job
            },
            persist = false,
        )
    }

    private fun mutate(jobId: String, transform: (ConversionJob) -> ConversionJob) {
        val now = DownloadsClock.nowEpochMs()
        var changed = false
        val updated = _uiState.value.jobs.map { job ->
            if (job.id != jobId) {
                job
            } else {
                changed = true
                transform(job).copy(updatedAtEpochMs = now)
            }
        }
        if (changed) publish(updated)
    }

    private fun finish(jobId: String, transform: (ConversionJob) -> ConversionJob) {
        if (runningJobId == jobId) {
            runningJobId = null
            runningJob = null
        }
        mutate(jobId, transform)
    }

    private fun publish(jobs: List<ConversionJob>, persist: Boolean = true) {
        _uiState.value = ConverterUiState(jobs = jobs)
        if (persist) persist()
        runCatching { onQueueChanged?.invoke(jobs) }
    }

    private fun persist() {
        runCatching {
            ConverterStorage.savePayload(ConverterCodec.encode(_uiState.value.jobs))
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val stored = runCatching { ConverterStorage.loadPayload() }.getOrNull()
        val decoded = stored?.let(ConverterCodec::decode).orEmpty()

        val repaired = repairLoadedJobs(decoded, DownloadsClock.nowEpochMs())

        publish(repaired)
        sweepOrphanedWorkFiles(repaired)
        pump()
    }

    /**
     * Brings a persisted queue back to a state the pump can act on.
     *
     * Kept pure and separate from [loadFromDisk] so the two decisions it encodes are testable
     * without platform storage, which is the same reason `CastDeliveryPlanner` is a pure object.
     */
    internal fun repairLoadedJobs(jobs: List<ConversionJob>, nowEpochMs: Long): List<ConversionJob> =
        jobs
            // A conversion has no resumable partial, so an interrupted job restarts from zero.
            // Downloads demote to Paused because they genuinely can resume; queueing is the
            // honest analogue here.
            .map { job ->
                if (job.status == ConversionStatus.Running || job.status == ConversionStatus.Probing) {
                    job.copy(status = ConversionStatus.Queued, progressPercent = 0)
                } else {
                    job
                }
            }
            // Finished rows are history, not state. Dropping the stale ones keeps the payload from
            // growing without bound, since every mutation re-encodes the whole thing.
            .filter { job -> job.isActive || nowEpochMs - job.updatedAtEpochMs < FINISHED_RETENTION_MS }

    /**
     * Deletes work files left behind by a crash or a kill.
     *
     * Only files whose job is gone or already finished are removed — a queued job's name is left
     * alone so a restart can reuse it.
     */
    private fun sweepOrphanedWorkFiles(jobs: List<ConversionJob>) {
        val liveWorkNames = jobs.filter { it.isActive }.map { it.outputFileName }.toSet()
        jobs.filterNot { it.isActive }
            .filterNot { it.outputFileName in liveWorkNames }
            .forEach(::removeWorkFile)
    }

    private fun removeWorkFile(job: ConversionJob) {
        val directory = DownloadsPlatformDownloader.downloadsDirectoryPath() ?: return
        DownloadsPlatformDownloader.removeFile(
            "file://$directory/${job.outputFileName}$CONVERSION_WORK_SUFFIX",
        )
    }

    // --- Naming ------------------------------------------------------------------------------

    private fun nextJobId(nowEpochMs: Long): String {
        nextOrdinal += 1L
        return "${nowEpochMs.toString(36)}_${nextOrdinal.toString(36)}"
    }

    /**
     * Mirrors `DownloadsRepository.buildFileName`'s conventions — same sanitization, same length
     * cap, same base36 timestamp suffix — so converted files sort and read like every other file
     * in the downloads directory.
     */
    internal fun buildOutputFileName(
        item: DownloadItem,
        spec: ConversionSpec,
        nowEpochMs: Long,
    ): String {
        val base = item.fileName.substringBeforeLast('.').ifBlank { item.title }
        val extension = spec.container.fileExtension(audioOnly = spec.dropVideo)
        return buildString {
            append(base.sanitizeForFileName().ifBlank { "converted" }.take(80))
            append("_converted_")
            append(nowEpochMs.toString(36))
            append('.')
            append(extension)
        }
    }

    /** A short human description of what a spec produced, shown under the converted download. */
    internal fun conversionLabel(spec: ConversionSpec): String = buildString {
        if (spec.dropVideo) {
            append("Audio only")
        } else {
            spec.maxHeight?.let { append("${it}p ") }
            append(spec.videoCodec?.displayLabel() ?: "Original video")
        }
        append(" • ")
        append(spec.container.displayLabel())
    }

    private fun String.sanitizeForFileName(): String =
        trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

    /**
     * Used when the probe fails, which is survivable: with no video stream described the planner
     * falls back to copying both tracks into the requested container, which is the safest thing to
     * do with a file we could not inspect.
     */
    private fun fallbackProbe(): CastMediaProbe = CastMediaProbe(
        container = CastContainer.UNKNOWN,
        video = null,
        audioTracks = emptyList(),
    )

    private const val FINISHED_RETENTION_MS = 24L * 60L * 60L * 1000L
}

@Serializable
private data class StoredConversionsPayload(
    val jobs: List<ConversionJob> = emptyList(),
)

private object ConverterCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(payload: String): List<ConversionJob> =
        runCatching { json.decodeFromString<StoredConversionsPayload>(payload).jobs }
            .getOrDefault(emptyList())

    fun encode(jobs: Collection<ConversionJob>): String =
        json.encodeToString(StoredConversionsPayload(jobs = jobs.toList()))
}
