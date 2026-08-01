package com.nuvio.app.features.converter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers what happens to a persisted queue on launch — the path a crash or a force-stop takes.
 *
 * Only the pure repair step is exercised, not [ConverterRepository] itself: the repository reaches
 * platform storage, which on the host test JVM has no Context to initialize from. Extracting the
 * decisions into a pure function is what makes them checkable at all.
 */
class ConverterQueueRepairTest {

    private val now = 1_700_000_000_000L

    private fun job(
        id: String,
        status: ConversionStatus,
        updatedAtEpochMs: Long = now,
        progressPercent: Int = 0,
    ) = ConversionJob(
        id = id,
        sourceDownloadId = "download_$id",
        title = "Title $id",
        preset = ConversionPreset.UniversalMp4,
        spec = ConversionPresets.specFor(ConversionPreset.UniversalMp4),
        status = status,
        progressPercent = progressPercent,
        outputFileName = "$id.mp4",
        createdAtEpochMs = updatedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    @Test
    fun anInterruptedJobIsRequeuedRatherThanFailed() {
        // An encode cannot resume from a partial, so the honest recovery is to run it again —
        // unlike a download, which demotes to Paused because it genuinely can pick up where it
        // left off.
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(job("a", ConversionStatus.Running, progressPercent = 63)),
            nowEpochMs = now,
        )

        assertEquals(ConversionStatus.Queued, repaired.single().status)
        assertEquals(0, repaired.single().progressPercent, "progress from the dead run is meaningless")
    }

    @Test
    fun anInterruptedProbeIsAlsoRequeued() {
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(job("a", ConversionStatus.Probing)),
            nowEpochMs = now,
        )

        assertEquals(ConversionStatus.Queued, repaired.single().status)
    }

    @Test
    fun queuedJobsSurviveUntouched() {
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(job("a", ConversionStatus.Queued)),
            nowEpochMs = now,
        )

        assertEquals(ConversionStatus.Queued, repaired.single().status)
    }

    @Test
    fun recentlyFinishedJobsAreKeptAsHistory() {
        val anHourAgo = now - 60L * 60L * 1000L
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(
                job("done", ConversionStatus.Completed, updatedAtEpochMs = anHourAgo),
                job("failed", ConversionStatus.Failed, updatedAtEpochMs = anHourAgo),
            ),
            nowEpochMs = now,
        )

        assertEquals(2, repaired.size)
    }

    @Test
    fun staleFinishedJobsAreDropped() {
        // Every mutation re-encodes the whole payload, so finished rows cannot accumulate forever.
        val twoDaysAgo = now - 48L * 60L * 60L * 1000L
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(
                job("old", ConversionStatus.Completed, updatedAtEpochMs = twoDaysAgo),
                job("fresh", ConversionStatus.Completed, updatedAtEpochMs = now),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("fresh"), repaired.map { it.id })
    }

    @Test
    fun aStaleButStillActiveJobIsNeverDropped() {
        // Age only retires finished work. A job the user queued and never got to still matters.
        val longAgo = now - 30L * 24L * 60L * 60L * 1000L
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(job("ancient", ConversionStatus.Queued, updatedAtEpochMs = longAgo)),
            nowEpochMs = now,
        )

        assertEquals(1, repaired.size)
    }

    @Test
    fun queueOrderIsPreservedSoThePumpRunsFifo() {
        val repaired = ConverterRepository.repairLoadedJobs(
            jobs = listOf(
                job("first", ConversionStatus.Queued),
                job("second", ConversionStatus.Running),
                job("third", ConversionStatus.Queued),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("first", "second", "third"), repaired.map { it.id })
        assertTrue(repaired.all { it.status == ConversionStatus.Queued })
    }
}
