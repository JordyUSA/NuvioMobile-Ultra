package com.nuvio.app.features.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.nuvio.app.features.cast.CastDiscoveryState
import com.nuvio.app.features.cast.CastIncompatibility
import com.nuvio.app.features.cast.CastPlatform
import com.nuvio.app.features.cast.CastStreamRequest
import com.nuvio.app.features.cast.CastTransport
import com.nuvio.app.features.cast.resolveActiveReceiver
import com.nuvio.app.features.cast.selectedCastTransport
import com.nuvio.app.features.cast.dlna.DlnaConnectionState
import com.nuvio.app.features.cast.dlna.DlnaDevice
import com.nuvio.app.features.cast.dlna.DlnaPlatform
import kotlinx.coroutines.delay

/**
 * Whether the Cast affordance should be shown at all.
 *
 * False on platforms without either transport, so the caller can omit the button rather than
 * render a control that cannot do anything.
 */
val castingAvailable: Boolean
    get() = CastPlatform.isSupported || DlnaPlatform.isSupported

/**
 * A receiver reachable on the local network, regardless of which protocol it speaks —
 * [CastDevicePickerDialog] lists Chromecast and DLNA renderers side by side under this one type
 * so the caller doesn't need to know or care which transport a given row is.
 */
sealed interface CastReceiver {
    val id: String
    val name: String
    val modelName: String?
    val protocolLabel: String

    data class Chromecast(val device: CastDevice) : CastReceiver {
        override val id get() = device.id
        override val name get() = device.name
        override val modelName get() = device.modelName
        override val protocolLabel get() = "Chromecast"
    }

    data class Dlna(val device: DlnaDevice) : CastReceiver {
        override val id get() = device.id
        override val name get() = device.name
        override val modelName get() = device.modelName
        override val protocolLabel get() = "DLNA"
    }
}

/**
 * Receiver picker, merging Chromecast and DLNA devices into one list.
 *
 * Discovery runs only while this dialog is on screen: both mDNS and SSDP browsing keep the
 * radio busy, so they're scoped to the moment the user is actually choosing a device. Only one
 * receiver is ever active at a time — selecting a device on one transport disconnects whichever
 * device is connected on the other, matching [CastDelivery]'s single-session model.
 */
@Composable
fun CastDevicePickerDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (CastReceiver) -> Unit,
) {
    val chromecastDevices by CastPlatform.devices.collectAsState()
    val dlnaDevices by DlnaPlatform.devices.collectAsState()
    val receivers = remember(chromecastDevices, dlnaDevices) {
        chromecastDevices.map(CastReceiver::Chromecast) + dlnaDevices.map(CastReceiver::Dlna)
    }

    val castConnection by CastPlatform.connection.collectAsState()
    val dlnaConnection by DlnaPlatform.connection.collectAsState()
    val castDiscovery by CastPlatform.discovery.collectAsState()

    DisposableEffect(Unit) {
        CastPlatform.startDiscovery()
        DlnaPlatform.startDiscovery()
        onDispose {
            CastPlatform.stopDiscovery()
            DlnaPlatform.stopDiscovery()
        }
    }

    val connectedName = (castConnection as? CastConnectionState.Connected)?.device?.name
        ?: (dlnaConnection as? DlnaConnectionState.Connected)?.device?.name
    val connecting = castConnection is CastConnectionState.Connecting
    val failureMessage = (castConnection as? CastConnectionState.Failed)?.message
        ?: (dlnaConnection as? DlnaConnectionState.Failed)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connectedName != null) "Casting to $connectedName" else "Cast to") },
        text = {
            Column {
                when {
                    connecting -> Text("Connecting…")
                    receivers.isEmpty() -> EmptyReceiverList(castDiscovery)
                    else -> receivers.forEach { receiver ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Only one receiver is active at a time, so switching
                                    // transports drops whichever one is currently connected.
                                    // Recording the choice matters because the teardown is not
                                    // instantaneous: until it completes both transports report
                                    // themselves connected, and this is what tells them apart.
                                    when (receiver) {
                                        is CastReceiver.Dlna -> {
                                            selectedCastTransport = CastTransport.DLNA
                                            CastPlatform.disconnect()
                                        }
                                        is CastReceiver.Chromecast -> {
                                            selectedCastTransport = CastTransport.CHROMECAST
                                            DlnaPlatform.disconnect()
                                        }
                                    }
                                    onDeviceSelected(receiver)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(receiver.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(receiver.modelName, receiver.protocolLabel).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                // Shown alongside a populated list too: a Chromecast that never came up is worth
                // saying out loud even when DLNA renderers did answer, otherwise its absence
                // reads as "there is no Chromecast here".
                if (receivers.isNotEmpty() && !connecting) {
                    castDiscovery.unavailableReason?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                failureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (connectedName != null) {
                TextButton(onClick = {
                    CastDelivery.cancel()
                    CastPlatform.disconnect()
                    DlnaPlatform.disconnect()
                    onDismiss()
                }) { Text("Stop casting") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            // Also the retry for a Cast SDK that failed to start, so it stays enabled when
            // discovery reports itself unavailable.
            TextButton(onClick = { CastPlatform.refresh() }) { Text("Rescan") }
        },
    )
}

/**
 * Stands in for the device list while nothing has answered.
 *
 * A bare "Looking for devices…" is indistinguishable from a scan that has silently stopped, which
 * is exactly what a lapsed active scan used to look like. The spinner is tied to discovery's own
 * scanning flag rather than being decorative, so it goes away when scanning genuinely has, and the
 * network advice is held back until a scan has been running long enough that an empty network is a
 * likelier explanation than a slow one.
 */
@Composable
private fun EmptyReceiverList(discovery: CastDiscoveryState) {
    var searchedLongEnough by remember { mutableStateOf(false) }
    LaunchedEffect(discovery.isScanning) {
        searchedLongEnough = false
        if (discovery.isScanning) {
            delay(ADVICE_DELAY_MS)
            searchedLongEnough = true
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (discovery.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                if (discovery.isScanning) "Looking for devices on your Wi‑Fi…" else "No devices found.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        discovery.unavailableReason?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        discovery.hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (searchedLongEnough) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Still nothing. Check that your phone and TV are on the same Wi‑Fi network, and " +
                    "that the router does not have AP isolation or client isolation turned on.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Comfortably past two of Android's 30-second active-scan windows, so a receiver that simply
 * answers slowly has had every chance before the dialog starts blaming the network.
 */
private const val ADVICE_DELAY_MS = 45_000L

/**
 * Starts casting [request] as soon as a session is established on either transport, and reports
 * progress.
 *
 * Kept separate from the picker so the connect step and the deliver step do not race: the
 * receiver has to be connected before the planner can know what it supports.
 */
@Composable
fun CastDeliveryEffect(
    request: CastStreamRequest?,
    onFinished: (Result<Unit>) -> Unit = {},
) {
    // Collected purely so a connection change recomposes this and re-resolves the receiver
    // below through the same rule CastDelivery itself uses.
    val castConnection by CastPlatform.connection.collectAsState()
    val dlnaConnection by DlnaPlatform.connection.collectAsState()
    val receiverId = remember(castConnection, dlnaConnection) { resolveActiveReceiver()?.id }

    // Keyed by receiver as well as URL. Keyed by URL alone, moving the same title to a second
    // television did nothing at all: the effect re-ran, saw the unchanged URL and returned
    // early, leaving the newly picked receiver black.
    var lastDelivered by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The work runs in LaunchedEffect's own scope, so leaving the player cancels an in-flight
    // transcode rather than leaving it running against a discarded composition.
    LaunchedEffect(receiverId, request?.url) {
        val target = request ?: return@LaunchedEffect
        val receiver = receiverId ?: return@LaunchedEffect
        val delivery = receiver to target.url
        if (lastDelivered == delivery) return@LaunchedEffect
        lastDelivered = delivery
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
