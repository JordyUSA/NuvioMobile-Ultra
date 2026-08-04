package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The handful of visual treatments a status pill can carry, independent of what produced it. */
enum class PillTone {
    Neutral,
    Info,
    Success,
    Warning,
    Danger,
}

/**
 * A small rounded status badge, e.g. "Queued", "Converting", "Failed". Shared between download and
 * conversion rows so the two lists read as one visual language despite having unrelated status
 * enums underneath.
 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val color = when (tone) {
        PillTone.Neutral -> tokens.colors.textMuted
        PillTone.Info -> tokens.colors.info
        PillTone.Success -> tokens.colors.success
        PillTone.Warning -> tokens.colors.warning
        PillTone.Danger -> tokens.colors.danger
    }
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Space.s8))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = NuvioTokens.Space.s8, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
