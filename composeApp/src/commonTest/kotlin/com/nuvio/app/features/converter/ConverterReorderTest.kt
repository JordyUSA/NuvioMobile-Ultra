package com.nuvio.app.features.converter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Covers the queue drag-reorder splice in isolation — [ConverterRepository.reorder] itself just
 * hands this to [ConverterRepository.publish], which reaches platform storage and has no Context
 * on the host test JVM, same reasoning as [ConverterQueueRepairTest].
 */
class ConverterReorderTest {

    private val now = 1_700_000_000_000L

    private fun job(id: String) = ConversionJob(
        id = id,
        sourceDownloadId = "download_$id",
        title = "Title $id",
        preset = ConversionPreset.UniversalMp4,
        spec = ConversionPresets.specFor(ConversionPreset.UniversalMp4),
        status = ConversionStatus.Queued,
        outputFileName = "$id.mp4",
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
    )

    @Test
    fun movingAJobEarlierShiftsThePumpOrder() {
        val jobs = listOf(job("a"), job("b"), job("c"))
        val reordered = ConverterRepository.reorderJobs(jobs, fromIndex = 2, toIndex = 0)

        assertEquals(listOf("c", "a", "b"), reordered.map { it.id })
    }

    @Test
    fun movingAJobLaterShiftsThePumpOrder() {
        val jobs = listOf(job("a"), job("b"), job("c"))
        val reordered = ConverterRepository.reorderJobs(jobs, fromIndex = 0, toIndex = 2)

        assertEquals(listOf("b", "c", "a"), reordered.map { it.id })
    }

    @Test
    fun sameIndexIsANoOp() {
        val jobs = listOf(job("a"), job("b"))
        val reordered = ConverterRepository.reorderJobs(jobs, fromIndex = 1, toIndex = 1)

        assertSame(jobs, reordered)
    }

    @Test
    fun outOfRangeIndicesAreIgnored() {
        val jobs = listOf(job("a"), job("b"))
        val reordered = ConverterRepository.reorderJobs(jobs, fromIndex = 0, toIndex = 5)

        assertSame(jobs, reordered)
    }
}
