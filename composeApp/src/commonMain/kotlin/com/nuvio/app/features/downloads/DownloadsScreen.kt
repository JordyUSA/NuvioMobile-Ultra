package com.nuvio.app.features.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PillTone
import com.nuvio.app.core.ui.SelectionActionBar
import com.nuvio.app.core.ui.StatusPill
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.converter.ConversionJob
import com.nuvio.app.features.converter.ConversionPreset
import com.nuvio.app.features.converter.ConversionPresets
import com.nuvio.app.features.converter.ConversionStatus
import com.nuvio.app.features.converter.ConverterRepository
import com.nuvio.app.features.converter.isActive
import com.nuvio.app.features.converter.isTerminal
import com.nuvio.app.features.converter.labelRes
import com.nuvio.app.features.converter.titleRes
import com.nuvio.app.features.library.LibraryDownloadsEmptyState
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onNavigateToConverter: (List<String>) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()

    val converterState by remember {
        ConverterRepository.ensureLoaded()
        ConverterRepository.uiState
    }.collectAsStateWithLifecycle()

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)
    val shareFailedText = stringResource(Res.string.downloads_share_failed)

    // Selection mode lives here rather than in the repository: it is view state, gone the moment
    // the screen leaves the back stack. Deliberately not `rememberSaveable` — the default saver
    // cannot round-trip an arbitrary Set<String> through a Bundle, and a selection is cheap enough
    // to redo that surviving a process death is not worth a custom Saver.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedFilter by rememberSaveable { mutableStateOf(DownloadsListFilter.All) }
    var actionSheetEntry by remember { mutableStateOf<DownloadsListEntry?>(null) }
    var presetPickerJobId by remember { mutableStateOf<String?>(null) }
    var errorDialogEntry by remember { mutableStateOf<DownloadsListEntry?>(null) }
    var pendingBulkDelete by remember { mutableStateOf<List<DownloadsListEntry>?>(null) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(entry: DownloadsListEntry) {
        selectedIds = if (entry.entryId in selectedIds) selectedIds - entry.entryId else selectedIds + entry.entryId
        if (selectedIds.isEmpty()) selectionMode = false
    }

    fun shareEntry(entry: DownloadsListEntry) {
        val (uri, title) = when (entry) {
            is DownloadsListEntry.Download -> DownloadsRepository.playableLocalFileUri(entry.item) to entry.item.title
            is DownloadsListEntry.Conversion -> entry.job.outputLocalFileUri to entry.job.title
        }
        if (uri == null || !DownloadsPlatformDownloader.shareFile(uri, title)) {
            NuvioToastController.show(shareFailedText)
        }
    }

    val entries = remember(uiState.items, converterState.jobs) {
        buildDownloadsListEntries(uiState.items, converterState)
    }
    val entryByKey = remember(entries) { entries.associateBy { it.entryId } }
    val counts = remember(entries) { entries.countsByFilter() }
    val visibleEntries = remember(entries, selectedFilter) { entries.filter { it.matches(selectedFilter) } }
    val selectedEntries = remember(selectedIds, entryByKey) { selectedIds.mapNotNull { entryByKey[it] } }

    val completedEpisodes = remember(uiState.items) {
        uiState.completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    fun bulkConvert() {
        val ids = selectedEntries.filterIsInstance<DownloadsListEntry.Download>().map { it.item.id }
        exitSelection()
        if (ids.isNotEmpty()) onNavigateToConverter(ids)
    }

    fun bulkShare() {
        selectedEntries.filterIsInstance<DownloadsListEntry.Download>().singleOrNull()?.let(::shareEntry)
        exitSelection()
    }

    fun bulkCancelConversions() {
        selectedEntries.filterIsInstance<DownloadsListEntry.Conversion>()
            .filter { it.job.isActive }
            .forEach { ConverterRepository.cancel(it.job.id) }
        exitSelection()
    }

    fun performBulkDelete(targets: List<DownloadsListEntry>) {
        targets.forEach { entry ->
            when (entry) {
                is DownloadsListEntry.Download -> DownloadsRepository.cancelDownload(entry.item.id)
                is DownloadsListEntry.Conversion -> if (entry.job.status.isTerminal) {
                    ConverterRepository.dismiss(entry.job.id)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NuvioScreen(modifier = Modifier.fillMaxSize()) {
            stickyHeader {
                NuvioScreenHeader(
                    title = if (selectedShowId == null) {
                        stringResource(Res.string.compose_settings_root_downloads_title)
                    } else {
                        selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                    },
                    onBack = {
                        when {
                            selectionMode -> exitSelection()
                            selectedShowId != null -> onBackFromShow?.invoke() ?: run { selectedShowId = null }
                            else -> onBack()
                        }
                    },
                    actions = {
                        if (selectionMode) {
                            Text(
                                text = stringResource(Res.string.converter_selection_count, selectedIds.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = {
                                    selectedIds = if (selectedIds.size == visibleEntries.size) {
                                        emptySet()
                                    } else {
                                        visibleEntries.map { it.entryId }.toSet()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = stringResource(Res.string.downloads_select_all),
                                )
                            }
                            IconButton(onClick = ::exitSelection) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(Res.string.converter_action_dismiss),
                                )
                            }
                        } else {
                            IconButton(onClick = { selectionMode = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = stringResource(Res.string.converter_action_select),
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                        NuvioToastController.show(openDownloadsDirectoryFailedText)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = stringResource(Res.string.downloads_open_directory),
                                )
                            }
                        }
                    },
                )
            }

            if (selectedShowId == null) {
                item(key = "storage_footprint") { StorageFootprintBar(uiState.completedItems) }
                item(key = "low_power_banner") { LowPowerBanner(isVisible = converterState.hasActiveJobs) }
                item(key = "filter_tabs") {
                    DownloadsFilterTabs(
                        selected = selectedFilter,
                        counts = counts,
                        onSelected = { selectedFilter = it },
                    )
                }

                unifiedRootContent(
                    entries = visibleEntries,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelection = ::toggleSelection,
                    onEnterSelection = { entry ->
                        selectionMode = true
                        selectedIds = setOf(entry.entryId)
                    },
                    onOpenDownload = onOpenDownload,
                    onOpenShow = { showId, title ->
                        onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                    },
                    onOpenMenu = { actionSheetEntry = it },
                    onOpenError = { errorDialogEntry = it },
                )
            } else {
                downloadsShowContent(
                    showId = selectedShowId.orEmpty(),
                    episodes = completedEpisodes,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelection = { item -> toggleSelection(DownloadsListEntry.Download(item)) },
                    onEnterSelection = { item ->
                        selectionMode = true
                        selectedIds = setOf(DownloadsListEntry.Download(item).entryId)
                    },
                    onOpenDownload = onOpenDownload,
                    onOpenMenu = { actionSheetEntry = DownloadsListEntry.Download(it) },
                )
            }
        }

        AnimatedVisibility(
            visible = selectionMode && selectedIds.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SelectionActionBar(
                selectedCount = selectedIds.size,
                canConvert = selectedEntries.isNotEmpty() &&
                    selectedEntries.all { it is DownloadsListEntry.Download && it.item.status == DownloadStatus.Completed },
                canShare = selectedEntries.size == 1 &&
                    selectedEntries.first().let { it is DownloadsListEntry.Download && it.item.status == DownloadStatus.Completed },
                canCancel = selectedEntries.any { it is DownloadsListEntry.Conversion && it.job.isActive },
                canDelete = selectedEntries.isNotEmpty(),
                onConvert = ::bulkConvert,
                onShare = ::bulkShare,
                onCancel = ::bulkCancelConversions,
                onDelete = { pendingBulkDelete = selectedEntries },
            )
        }
    }

    actionSheetEntry?.let { entry ->
        EntryActionSheet(
            entry = entry,
            onDismiss = { actionSheetEntry = null },
            onOpenDownload = onOpenDownload,
            onConvert = { onNavigateToConverter(listOf(it.id)) },
            onShare = ::shareEntry,
            onChangePreset = { job ->
                actionSheetEntry = null
                presetPickerJobId = job.id
            },
            onShowError = {
                actionSheetEntry = null
                errorDialogEntry = it
            },
        )
    }

    presetPickerJobId?.let { jobId ->
        PresetPickerSheet(
            onDismiss = { presetPickerJobId = null },
            onSelected = { preset ->
                ConverterRepository.updateSpec(jobId, preset, ConversionPresets.specFor(preset))
                presetPickerJobId = null
            },
        )
    }

    errorDialogEntry?.let { entry ->
        val message = when (entry) {
            is DownloadsListEntry.Download -> entry.item.errorMessage
            is DownloadsListEntry.Conversion -> entry.job.errorMessage
        }.orEmpty().ifBlank { stringResource(Res.string.downloads_status_failed) }

        NuvioStatusModal(
            title = stringResource(Res.string.downloads_error_dialog_title),
            message = message,
            isVisible = true,
            confirmText = stringResource(Res.string.downloads_error_dialog_dismiss),
            onConfirm = { errorDialogEntry = null },
        )
    }

    pendingBulkDelete?.let { targets ->
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_delete_title),
            message = stringResource(Res.string.downloads_bulk_delete, targets.size),
            isVisible = true,
            confirmText = stringResource(Res.string.action_delete),
            dismissText = stringResource(Res.string.action_cancel),
            onConfirm = {
                performBulkDelete(targets)
                pendingBulkDelete = null
                exitSelection()
            },
            onDismiss = { pendingBulkDelete = null },
        )
    }
}

// --- Storage + low power ------------------------------------------------------------------

@Composable
private fun StorageFootprintBar(completedDownloads: List<DownloadItem>) {
    val usedBytes = remember(completedDownloads) {
        completedDownloads.sumOf { it.totalBytes ?: it.downloadedBytes }
    }
    val freeBytes = remember { DownloadsPlatformDownloader.availableStorageBytes() }
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(
                Res.string.downloads_storage_summary,
                formatDownloadBytes(usedBytes),
                freeBytes?.let(::formatDownloadBytes) ?: "—",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        if (freeBytes != null && freeBytes < LOW_STORAGE_THRESHOLD_BYTES) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = tokens.colors.warning,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(Res.string.downloads_storage_low_warning, formatDownloadBytes(freeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.warning,
                )
            }
        }
    }
}

@Composable
private fun LowPowerBanner(isVisible: Boolean) {
    if (!isVisible) return
    val active = remember { DownloadsPlatformDownloader.isLowPowerModeActive() }
    if (!active) return
    val tokens = MaterialTheme.nuvio
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = tokens.colors.warning,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = stringResource(Res.string.downloads_low_power_warning),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.warning,
        )
    }
}

private const val LOW_STORAGE_THRESHOLD_BYTES = 1024L * 1024L * 1024L // 1 GiB

// --- Filter tabs ---------------------------------------------------------------------------

@Composable
private fun DownloadsFilterTabs(
    selected: DownloadsListFilter,
    counts: Map<DownloadsListFilter, Int>,
    onSelected: (DownloadsListFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DownloadsListFilter.entries.forEach { filter ->
            DownloadsFilterChip(
                label = filterLabel(filter),
                count = counts[filter] ?: 0,
                selected = selected == filter,
                onClick = { onSelected(filter) },
            )
        }
    }
}

@Composable
private fun filterLabel(filter: DownloadsListFilter): String = when (filter) {
    DownloadsListFilter.All -> stringResource(Res.string.downloads_tab_all)
    DownloadsListFilter.Active -> stringResource(Res.string.downloads_tab_active)
    DownloadsListFilter.Completed -> stringResource(Res.string.downloads_tab_completed)
    DownloadsListFilter.Failed -> stringResource(Res.string.downloads_tab_failed)
}

@Composable
private fun DownloadsFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = tokens.shapes.chip,
        color = if (selected) tokens.colors.accent else tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
            )
            if (count > 0) {
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.textMuted,
                )
            }
        }
    }
}

// --- Root content ----------------------------------------------------------------------------

private fun LazyListScope.unifiedRootContent(
    entries: List<DownloadsListEntry>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (DownloadsListEntry) -> Unit,
    onEnterSelection: (DownloadsListEntry) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
    onOpenMenu: (DownloadsListEntry) -> Unit,
    onOpenError: (DownloadsListEntry) -> Unit,
) {
    val conversions = entries.filterIsInstance<DownloadsListEntry.Conversion>()
    val queued = conversions.filter { it.job.status == ConversionStatus.Queued }
    val converting = conversions.filter {
        it.job.status == ConversionStatus.Probing || it.job.status == ConversionStatus.Running
    }
    val history = conversions.filter { it.job.status.isTerminal }
        .sortedByDescending { it.job.updatedAtEpochMs }

    val downloads = entries.filterIsInstance<DownloadsListEntry.Download>().map { it.item }
    val activeDownloads = downloads.filter {
        it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Paused
    }
    val completedMovies = downloads.filter { it.status == DownloadStatus.Completed && !it.isEpisode }
    val completedShows = downloads
        .filter { it.status == DownloadStatus.Completed && it.isEpisode }
        .groupBy { it.parentMetaId }
        .mapNotNull { (_, episodes) -> episodes.firstOrNull()?.let { first -> first to episodes } }
        .sortedBy { (item, _) -> item.title.lowercase() }
    val failedDownloads = downloads.filter { it.status == DownloadStatus.Failed }

    if (queued.size > 1) {
        item(key = "queue_header") { SectionTitle(stringResource(Res.string.downloads_queue_title)) }
        item(key = "queue_reorder") {
            QueueReorderSection(
                jobs = queued.map { it.job },
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelection = { onToggleSelection(DownloadsListEntry.Conversion(it)) },
                onOpenMenu = { onOpenMenu(DownloadsListEntry.Conversion(it)) },
            )
        }
    } else if (queued.size == 1) {
        item(key = "queue_header") { SectionTitle(stringResource(Res.string.downloads_queue_title)) }
        conversionRowItems(queued, selectionMode, selectedIds, onToggleSelection, onEnterSelection, onOpenMenu, onOpenError)
    }

    if (converting.isNotEmpty()) {
        item(key = "converting_header") { SectionTitle(stringResource(Res.string.converter_section_title)) }
        conversionRowItems(converting, selectionMode, selectedIds, onToggleSelection, onEnterSelection, onOpenMenu, onOpenError)
    }

    if (activeDownloads.isNotEmpty()) {
        item(key = "active_header") { SectionTitle(stringResource(Res.string.downloads_section_active)) }
        items(activeDownloads, key = { "dl:${it.id}" }) { item ->
            UnifiedDownloadRow(
                item = item,
                selectionMode = selectionMode,
                isSelected = "dl:${item.id}" in selectedIds,
                onOpen = { onOpenDownload(item) },
                onToggleSelection = { onToggleSelection(DownloadsListEntry.Download(item)) },
                onEnterSelection = { onEnterSelection(DownloadsListEntry.Download(item)) },
                onOpenMenu = { onOpenMenu(DownloadsListEntry.Download(item)) },
                onOpenError = { onOpenError(DownloadsListEntry.Download(item)) },
            )
        }
    }

    if (completedMovies.isNotEmpty()) {
        item(key = "movies_header") { SectionTitle(stringResource(Res.string.downloads_section_movies)) }
        items(completedMovies, key = { "dl:${it.id}" }) { item ->
            UnifiedDownloadRow(
                item = item,
                selectionMode = selectionMode,
                isSelected = "dl:${item.id}" in selectedIds,
                onOpen = { onOpenDownload(item) },
                onToggleSelection = { onToggleSelection(DownloadsListEntry.Download(item)) },
                onEnterSelection = { onEnterSelection(DownloadsListEntry.Download(item)) },
                onOpenMenu = { onOpenMenu(DownloadsListEntry.Download(item)) },
                onOpenError = { onOpenError(DownloadsListEntry.Download(item)) },
            )
        }
    }

    if (completedShows.isNotEmpty()) {
        item(key = "shows_header") { SectionTitle(stringResource(Res.string.downloads_section_shows)) }
        items(completedShows, key = { (item, _) -> "show:${item.parentMetaId}" }) { (item, episodes) ->
            ShowGroupRow(item = item, episodeCount = episodes.size, onClick = { onOpenShow(item.parentMetaId, item.title) })
        }
    }

    if (failedDownloads.isNotEmpty()) {
        item(key = "failed_downloads_header") { SectionTitle(stringResource(Res.string.downloads_tab_failed)) }
        items(failedDownloads, key = { "dl:${it.id}" }) { item ->
            UnifiedDownloadRow(
                item = item,
                selectionMode = selectionMode,
                isSelected = "dl:${item.id}" in selectedIds,
                onOpen = { onOpenDownload(item) },
                onToggleSelection = { onToggleSelection(DownloadsListEntry.Download(item)) },
                onEnterSelection = { onEnterSelection(DownloadsListEntry.Download(item)) },
                onOpenMenu = { onOpenMenu(DownloadsListEntry.Download(item)) },
                onOpenError = { onOpenError(DownloadsListEntry.Download(item)) },
            )
        }
    }

    if (history.isNotEmpty()) {
        item(key = "history_header") { SectionTitle(stringResource(Res.string.downloads_history_title)) }
        conversionRowItems(history, selectionMode, selectedIds, onToggleSelection, onEnterSelection, onOpenMenu, onOpenError)
    }

    if (entries.isEmpty()) {
        item(key = "empty_state") {
            LibraryDownloadsEmptyState(onManageClick = null)
        }
    }
}

private fun LazyListScope.conversionRowItems(
    conversions: List<DownloadsListEntry.Conversion>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (DownloadsListEntry) -> Unit,
    onEnterSelection: (DownloadsListEntry) -> Unit,
    onOpenMenu: (DownloadsListEntry) -> Unit,
    onOpenError: (DownloadsListEntry) -> Unit,
) {
    items(conversions, key = { it.entryId }) { entry ->
        UnifiedConversionRow(
            job = entry.job,
            selectionMode = selectionMode,
            isSelected = entry.entryId in selectedIds,
            onToggleSelection = { onToggleSelection(entry) },
            onEnterSelection = { onEnterSelection(entry) },
            onOpenMenu = { onOpenMenu(entry) },
            onOpenError = { onOpenError(entry) },
        )
    }
}

@Composable
private fun QueueReorderSection(
    jobs: List<ConversionJob>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (ConversionJob) -> Unit,
    onOpenMenu: (ConversionJob) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState = lazyListState) { from, to ->
        ConverterRepository.reorder(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (jobs.size * 96).dp.coerceAtMost(560.dp)),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(jobs, key = { "cv:${it.id}" }) { job ->
            ReorderableItem(reorderableState, key = "cv:${job.id}") { _ ->
                UnifiedConversionRow(
                    job = job,
                    selectionMode = selectionMode,
                    isSelected = "cv:${job.id}" in selectedIds,
                    onToggleSelection = { onToggleSelection(job) },
                    onEnterSelection = {},
                    onOpenMenu = { onOpenMenu(job) },
                    onOpenError = {},
                    dragHandleScope = this@ReorderableItem,
                )
            }
        }
    }
}

@Composable
private fun ShowGroupRow(
    item: DownloadItem,
    episodeCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.downloads_episode_count, episodeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onToggleSelection: (DownloadItem) -> Unit,
    onEnterSelection: (DownloadItem) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenMenu: (DownloadItem) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(sortedEpisodes, key = { "dl:${it.id}" }) { item ->
            UnifiedDownloadRow(
                item = item,
                selectionMode = selectionMode,
                isSelected = "dl:${item.id}" in selectedIds,
                onOpen = { onOpenDownload(item) },
                onToggleSelection = { onToggleSelection(item) },
                onEnterSelection = { onEnterSelection(item) },
                onOpenMenu = { onOpenMenu(item) },
                onOpenError = {},
            )
        }
    }
}

// --- Rows --------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UnifiedDownloadRow(
    item: DownloadItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenError: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(item = item, displayTitle = displayTitle)
    val progressInfoLines = item.downloadProgressInfoLines()
    val tokens = MaterialTheme.nuvio

    LaunchedEffect(item.id, item.status, item.localFileUri) {
        if (item.status == DownloadStatus.Completed && !item.isConverted) {
            val uri = DownloadsRepository.playableLocalFileUri(item)
            if (uri != null) DownloadFormatProbeCache.probe(item.id, uri)
        }
    }
    val probe = if (item.status == DownloadStatus.Completed) DownloadFormatProbeCache.get(item.id) else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(
                enabled = item.isPlayable || selectionMode,
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        item.status == DownloadStatus.Failed -> onOpenError()
                        else -> onOpen()
                    }
                },
                onLongClick = { if (!selectionMode && item.isPlayable) onEnterSelection() },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else tokens.colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = downloadStatusLabel(item), tone = downloadStatusTone(item))
                        formatBadge(item, probe)?.let { badge ->
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.status == DownloadStatus.Downloading) {
                        progressInfoLines.take(2).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (selectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
                } else {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(Res.string.downloads_action_more),
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.Downloading) {
                if (item.totalBytes != null && item.totalBytes > 0L) {
                    LinearProgressIndicator(progress = { item.progressFraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun UnifiedConversionRow(
    job: ConversionJob,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenError: () -> Unit,
    dragHandleScope: ReorderableCollectionItemScope? = null,
) {
    val tokens = MaterialTheme.nuvio

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        job.status == ConversionStatus.Failed -> onOpenError()
                        else -> Unit
                    }
                },
                onLongClick = { if (!selectionMode) onEnterSelection() },
            ),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = stringResource(job.status.labelRes()), tone = conversionStatusTone(job.status))
                        Text(
                            text = ConverterRepository.conversionLabel(job.spec),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    conversionDetailLine(job)?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (job.status == ConversionStatus.Failed) tokens.colors.danger else tokens.colors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (job.isActive && !job.hasIndeterminateProgress) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { job.progressFraction }, modifier = Modifier.fillMaxWidth())
                    } else if (job.isActive) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (selectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
                } else {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(Res.string.downloads_action_more),
                            tint = tokens.colors.textSecondary,
                        )
                    }
                    if (dragHandleScope != null) {
                        with(dragHandleScope) {
                            IconButton(
                                modifier = Modifier.draggableHandle(),
                                onClick = {},
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = null,
                                    tint = tokens.colors.textMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Action sheets and small pickers ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryActionSheet(
    entry: DownloadsListEntry,
    onDismiss: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onConvert: (DownloadItem) -> Unit,
    onShare: (DownloadsListEntry) -> Unit,
    onChangePreset: (ConversionJob) -> Unit,
    onShowError: (DownloadsListEntry) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismissThen(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            when (entry) {
                is DownloadsListEntry.Download -> {
                    val item = entry.item
                    when (item.status) {
                        DownloadStatus.Downloading -> ActionRow(Icons.Rounded.Pause, stringResource(Res.string.compose_action_pause)) {
                            dismissThen { DownloadsRepository.pauseDownload(item.id) }
                        }
                        DownloadStatus.Paused -> ActionRow(Icons.Rounded.PlayArrow, stringResource(Res.string.action_resume)) {
                            dismissThen { DownloadsRepository.resumeDownload(item.id) }
                        }
                        DownloadStatus.Failed -> {
                            ActionRow(Icons.Rounded.Refresh, stringResource(Res.string.action_retry)) {
                                dismissThen { DownloadsRepository.retryDownload(item.id) }
                            }
                            ActionRow(Icons.Rounded.Warning, stringResource(Res.string.downloads_error_dialog_title)) {
                                dismissThen { onShowError(entry) }
                            }
                        }
                        DownloadStatus.Completed -> {
                            ActionRow(Icons.Rounded.PlayArrow, stringResource(Res.string.action_play)) {
                                dismissThen { onOpenDownload(item) }
                            }
                            ActionRow(Icons.Rounded.Share, stringResource(Res.string.converter_action_share)) {
                                dismissThen { onShare(entry) }
                            }
                            ActionRow(Icons.Rounded.Tune, stringResource(Res.string.converter_action_convert)) {
                                dismissThen { onConvert(item) }
                            }
                        }
                    }
                    ActionRow(Icons.Rounded.Delete, stringResource(Res.string.action_delete)) {
                        dismissThen { DownloadsRepository.cancelDownload(item.id) }
                    }
                }

                is DownloadsListEntry.Conversion -> {
                    val job = entry.job
                    if (job.isActive) {
                        ActionRow(Icons.Rounded.Close, stringResource(Res.string.converter_action_cancel)) {
                            dismissThen { ConverterRepository.cancel(job.id) }
                        }
                    }
                    if (job.status == ConversionStatus.Queued) {
                        ActionRow(Icons.Rounded.Tune, stringResource(Res.string.converter_action_change_preset)) {
                            dismissThen { onChangePreset(job) }
                        }
                    }
                    if (job.status == ConversionStatus.Completed) {
                        job.outputLocalFileUri?.let {
                            ActionRow(Icons.Rounded.Share, stringResource(Res.string.converter_action_share)) {
                                dismissThen { onShare(entry) }
                            }
                        }
                    }
                    if (job.status == ConversionStatus.Failed) {
                        ActionRow(Icons.Rounded.Refresh, stringResource(Res.string.converter_action_retry)) {
                            dismissThen { ConverterRepository.retry(job.id) }
                        }
                        ActionRow(Icons.Rounded.Warning, stringResource(Res.string.downloads_error_dialog_title)) {
                            dismissThen { onShowError(entry) }
                        }
                    }
                    if (job.status.isTerminal) {
                        ActionRow(Icons.Rounded.Delete, stringResource(Res.string.converter_action_dismiss)) {
                            dismissThen { ConverterRepository.dismiss(job.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tokens.colors.textSecondary)
        Spacer(modifier = Modifier.size(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPickerSheet(
    onDismiss: () -> Unit,
    onSelected: (ConversionPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            ConversionPresets.selectable.forEach { preset ->
                ActionRow(icon = Icons.Rounded.Tune, label = stringResource(preset.titleRes())) {
                    onSelected(preset)
                }
            }
        }
    }
}

// --- Small helpers -------------------------------------------------------------------------

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun downloadStatusLabel(item: DownloadItem): String = when (item.status) {
    DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, formatBytes(item.downloadedBytes))
    DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, formatBytes(item.downloadedBytes))
    DownloadStatus.Completed -> stringResource(Res.string.downloads_status_completed, formatBytes(item.totalBytes ?: item.downloadedBytes))
    DownloadStatus.Failed -> stringResource(Res.string.downloads_status_failed)
}

private fun downloadStatusTone(item: DownloadItem): PillTone = when (item.status) {
    DownloadStatus.Downloading -> PillTone.Info
    DownloadStatus.Paused -> PillTone.Neutral
    DownloadStatus.Completed -> PillTone.Success
    DownloadStatus.Failed -> PillTone.Danger
}

private fun conversionStatusTone(status: ConversionStatus): PillTone = when (status) {
    ConversionStatus.Queued -> PillTone.Neutral
    ConversionStatus.Probing, ConversionStatus.Running -> PillTone.Info
    ConversionStatus.Completed -> PillTone.Success
    ConversionStatus.Failed -> PillTone.Danger
    ConversionStatus.Cancelled -> PillTone.Neutral
}

/** "42% • 1.4x speed • 24 fps • ~1m 15s left", trimmed to whatever the engine actually reported. */
@Composable
private fun conversionDetailLine(job: ConversionJob): String? {
    if (job.status == ConversionStatus.Failed) return job.errorMessage
    if (!job.isActive) {
        return job.outputBytes?.let { formatDownloadBytes(it) }
    }
    val parts = buildList {
        if (job.progressPercent in 0..100) add("${job.progressPercent}%")
        job.speedMultiplier?.let { add(stringResource(Res.string.converter_speed_multiplier, formatOneDecimal(it))) }
        job.fps?.let { add(stringResource(Res.string.converter_fps_label, it.toInt().toString())) }
        job.etaMs?.let { add(stringResource(Res.string.converter_eta_label, formatEtaMs(it))) }
        job.estimatedOutputBytes?.let { add(stringResource(Res.string.converter_estimated_size_short, formatDownloadBytes(it))) }
    }
    return parts.joinToString(" • ").takeIf { it.isNotBlank() }
}

private fun formatOneDecimal(value: Float): String {
    val rounded = (value * 10f).toInt() / 10f
    return rounded.toString()
}

private fun formatEtaMs(etaMs: Long): String {
    val totalSeconds = (etaMs / 1000L).coerceAtLeast(0L)
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    if (minutes < 60L) return if (seconds > 0L && minutes < 10L) "${minutes}m ${seconds}s" else "${minutes}m"
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return if (remainingMinutes > 0L) "${hours}h ${remainingMinutes}m" else "${hours}h"
}

@Composable
private fun formatBadge(item: DownloadItem, probe: com.nuvio.app.features.cast.model.CastMediaProbe?): String? {
    item.conversionLabel?.let { return it }
    return probe?.originalFormatLabel(item.totalBytes)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
