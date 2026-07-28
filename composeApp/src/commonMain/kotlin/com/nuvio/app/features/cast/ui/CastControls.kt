package com.nuvio.app.features.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.cast.CastConnectionState
import com.nuvio.app.features.cast.CastDelivery
import com.nuvio.app.features.cast.CastDeliveryMode
import com.nuvio.app.features.cast.CastDeliveryStatus
import com.nuvio.app.features.cast.CastDevice
import com.nuvio.app.features.cast.CastIncompatibility
import com.nuvio.app.features.cast.CastPlatform
import com.nuvio.app.features.cast.CastStreamRequest

/**
 * Whether the Cast affordance should be shown at all.
 *
 * False on platforms without a Cast implementation, so the caller can omit the button rather
 * than render a control that cannot do anything.
 */
val castingAvailable: Boolean
    get() = CastPlatform.isSupported

/**
 * Receiver picker.
 *
 * Discovery runs only while this dialog is on screen: mDNS browsing keeps the radio busy, so
 * it is scoped to the moment the user is actually choosing a device.
 */
@Composable
fun CastDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (CastDevice) -> Unit,
) {
    val devices by CastPlatform.devices.collectAsState()
    val connection by CastPlatform.connection.collectAsState()

    DisposableEffect(Unit) {
        CastPlatform.startDiscovery()
        onDispose { CastPlatform.stopDiscovery() }
    }

    val connected = (connection as? CastConnectionState.Connected)?.device

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected != null) "Casting to ${connected.name}" else "Cast to") },
        text = {
            Column {
                when {
                    connection is CastConnectionState.Connecting -> Text("Connecting…")
                    devices.isEmpty() -> Text("Looking for devices on your Wi‑Fi…")
                    else -> devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                device.modelName?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                (connection as? CastConnectionState.Failed)?.let {
                    Text(it.message, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (connected != null) {
                TextButton(onClick = {
                    CastDelivery.cancel()
                    CastPlatform.disconnect()
                    onDismiss()
                }) { Text("Stop casting") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/**
 * Starts casting [request] as soon as a session is established, and reports progress.
 *
 * Kept separate from the picker so the connect step and the deliver step do not race: the
 * receiver has to be connected before the planner can know what it supports.
 */
@Composable
fun CastDeliveryEffect(
    request: CastStreamRequest?,
    onFinished: (Result<Unit>) -> Unit = {},
) {
    val connection by CastPlatform.connection.collectAsState()
    var lastCastUrl by remember { mutableStateOf<String?>(null) }

    // The work runs in LaunchedEffect's own scope, so leaving the player cancels an in-flight
    // transcode rather than leaving it running against a discarded composition.
    LaunchedEffect(connection, request?.url) {
        val target = request ?: return@LaunchedEffect
        if (connection !is CastConnectionState.Connected) return@LaunchedEffect
        if (lastCastUrl == target.url) return@LaunchedEffect
        lastCastUrl = target.url
        onFinished(CastDelivery.cast(target))
    }
}

/** One-line human summary of the delivery status, for a toast or overlay. */
@Composable
fun castStatusMessage(): String? {
    val status by CastDelivery.status.collectAsState()
    return when (val value = status) {
        is CastDeliveryStatus.Idle -> null
        is CastDeliveryStatus.Probing -> "Checking what your TV supports…"
        is CastDeliveryStatus.Preparing -> buildString {
            append(
                when (value.mode) {
                    CastDeliveryMode.REMUX -> "Repackaging for your TV"
                    CastDeliveryMode.TRANSCODE -> "Converting for your TV"
                    else -> "Preparing"
                },
            )
            if (value.progress in 0..100) append(" — ${value.progress}%")
            value.reasons.firstOrNull()?.let { append(" (${describe(it)})") }
        }
        is CastDeliveryStatus.Playing -> null
        is CastDeliveryStatus.Failed -> value.message
    }
}

private fun describe(reason: CastIncompatibility): String = when (reason) {
    is CastIncompatibility.VideoCodecUnsupported -> "${reason.codec} not supported"
    is CastIncompatibility.AudioCodecUnsupported -> "${reason.codec} audio not supported"
    is CastIncompatibility.ContainerUnsupported -> "${reason.container} container not supported"
    is CastIncompatibility.ResolutionTooHigh -> "${reason.width}×${reason.height} too large"
    is CastIncompatibility.FrameRateTooHigh -> "${reason.frameRate.toInt()}fps too high"
    is CastIncompatibility.BitDepthUnsupported -> "${reason.bitDepth}-bit not supported"
    is CastIncompatibility.DynamicRangeUnsupported -> "${reason.range} not supported"
    is CastIncompatibility.VideoLevelTooHigh -> "codec level too high"
    CastIncompatibility.ReceiverHasNoVideoOutput -> "audio-only device"
}
