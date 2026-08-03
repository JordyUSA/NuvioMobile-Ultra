package com.nuvio.app.features.downloads

import com.nuvio.app.features.converter.ConversionJob
import com.nuvio.app.features.converter.ConversionStatus
import com.nuvio.app.features.converter.ConverterUiState
import com.nuvio.app.features.converter.isActive

/**
 * A download and a conversion are unrelated status enums today, but the redesigned list treats
 * them as one thing to select, filter and act on. This union is the seam: everything downstream of
 * building the list (selection keys, filter tabs, the bulk action bar) reads one of these instead
 * of switching on which repository something came from.
 */
internal sealed interface DownloadsListEntry {
    /** Selection key. Prefixed per source so a download and a conversion can never collide. */
    val entryId: String
    val updatedAtEpochMs: Long

    /** The id inside the originating repository, without the entry-kind prefix. */
    val rawId: String

    data class Download(val item: DownloadItem) : DownloadsListEntry {
        override val entryId: String get() = "dl:${item.id}"
        override val updatedAtEpochMs: Long get() = item.updatedAtEpochMs
        override val rawId: String get() = item.id
    }

    data class Conversion(val job: ConversionJob) : DownloadsListEntry {
        override val entryId: String get() = "cv:${job.id}"
        override val updatedAtEpochMs: Long get() = job.updatedAtEpochMs
        override val rawId: String get() = job.id
    }
}

internal enum class DownloadsListFilter {
    All,
    Active,
    Completed,
    Failed,
}

internal fun DownloadsListEntry.matches(filter: DownloadsListFilter): Boolean = when (filter) {
    DownloadsListFilter.All -> true
    DownloadsListFilter.Active -> when (this) {
        is DownloadsListEntry.Download -> item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused
        is DownloadsListEntry.Conversion -> job.status.isActive
    }
    DownloadsListFilter.Completed -> when (this) {
        is DownloadsListEntry.Download -> item.status == DownloadStatus.Completed
        // A completed conversion's output already exists as its own Download entry (with a
        // conversionLabel badge) — listing the job here too would show the same result twice.
        is DownloadsListEntry.Conversion -> false
    }
    DownloadsListFilter.Failed -> when (this) {
        is DownloadsListEntry.Download -> item.status == DownloadStatus.Failed
        is DownloadsListEntry.Conversion -> job.status == ConversionStatus.Failed
    }
}

/** Builds the unified entry list a filter tab or bulk action operates on. */
internal fun buildDownloadsListEntries(
    downloads: List<DownloadItem>,
    conversions: ConverterUiState,
): List<DownloadsListEntry> =
    conversions.jobs.map(DownloadsListEntry::Conversion) +
        downloads.map(DownloadsListEntry::Download)

internal fun List<DownloadsListEntry>.countsByFilter(): Map<DownloadsListFilter, Int> =
    DownloadsListFilter.entries.associateWith { filter -> count { it.matches(filter) } }
