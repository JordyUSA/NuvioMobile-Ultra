package com.nuvio.app.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.converter_action_share
import nuvio.composeapp.generated.resources.downloads_bulk_cancel
import nuvio.composeapp.generated.resources.downloads_bulk_convert
import nuvio.composeapp.generated.resources.downloads_bulk_delete
import org.jetbrains.compose.resources.stringResource

/**
 * Bottom-pinned bulk action bar for a multi-select mode. Shared between `DownloadsScreen` (a
 * row list) and `LibraryScreen`'s Downloads grid — the bar itself is presentation-agnostic, only
 * the selection it acts on differs.
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    canConvert: Boolean,
    canShare: Boolean,
    canCancel: Boolean,
    canDelete: Boolean,
    onConvert: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canConvert) {
                SelectionBarActionButton(
                    icon = Icons.Rounded.Tune,
                    label = stringResource(Res.string.downloads_bulk_convert, selectedCount),
                    onClick = onConvert,
                )
            }
            if (canShare) {
                SelectionBarActionButton(
                    icon = Icons.Rounded.Share,
                    label = stringResource(Res.string.converter_action_share),
                    onClick = onShare,
                )
            }
            if (canCancel) {
                SelectionBarActionButton(
                    icon = Icons.Rounded.Close,
                    label = stringResource(Res.string.downloads_bulk_cancel, selectedCount),
                    onClick = onCancel,
                )
            }
            if (canDelete) {
                SelectionBarActionButton(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(Res.string.downloads_bulk_delete, selectedCount),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SelectionBarActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tokens.colors.textPrimary)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tokens.colors.textSecondary)
    }
}
