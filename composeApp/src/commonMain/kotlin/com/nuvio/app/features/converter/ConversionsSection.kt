package com.nuvio.app.features.converter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.formatDownloadBytes
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The conversion queue, rendered above the downloads list.
 *
 * Deliberately not a screen of its own: a conversion is about a file that lives in Downloads, and
 * a separate route would mean a settings entry, two `when` branches and a search index entry for
 * what is usually an empty list.
 */
internal fun LazyListScope.conversionsContent(
    state: ConverterUiState,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    if (state.jobs.isEmpty()) return

    item(key = "conversions_header") {
        Column {
            NuvioSectionLabel(text = stringResource(Res.string.converter_section_title))
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    items(state.jobs, key = { "conversion_${it.id}" }) { job ->
        ConversionRow(
            job = job,
            onCancel = { onCancel(job.id) },
            onRetry = { onRetry(job.id) },
            onDismiss = { onDismiss(job.id) },
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ConversionRow(
    job: ConversionJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        shape = tokens.shapes.compactCard,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = job.statusLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == ConversionStatus.Failed) {
                        tokens.colors.danger
                    } else {
                        tokens.colors.textMuted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (job.isActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Transformer cannot always estimate progress, so an indeterminate bar is a
                    // real state here rather than a loading placeholder.
                    if (job.hasIndeterminateProgress) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { job.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.size(8.dp))

            when {
                job.isActive -> IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.converter_action_cancel),
                        tint = tokens.colors.textSecondary,
                    )
                }
                job.status == ConversionStatus.Failed -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.converter_action_retry),
                            tint = tokens.colors.textSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.converter_action_dismiss),
                            tint = tokens.colors.textSecondary,
                        )
                    }
                }
                else -> IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(Res.string.converter_action_dismiss),
                        tint = tokens.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversionJob.statusLine(): String {
    val status = stringResource(status.labelRes())
    val detail = when {
        this.status == ConversionStatus.Failed -> errorMessage
        this.status == ConversionStatus.Completed -> outputBytes?.let(::formatDownloadBytes)
        this.status == ConversionStatus.Running && progressPercent >= 0 -> "$progressPercent%"
        else -> null
    }
    return listOfNotNull(status, detail).joinToString(" • ")
}
