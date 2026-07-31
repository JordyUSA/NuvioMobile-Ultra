package com.nuvio.app.features.cast.dlna

import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastPlaybackState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.resume

/**
 * Implemented in Swift, over plain BSD sockets for SSDP multicast and `URLSession` for the SOAP
 * HTTP calls. Mirrors `CastIosBridge`'s "Swift does the I/O, Kotlin does the parsing" split:
 * [startDiscovery] fires SSDP search cycles whose results come back through
 * [DlnaPlatform.onSsdpResponse]/[DlnaPlatform.onSearchCycleCompleted], and [httpGet]/[httpPost]
 * report through [DlnaPlatform.onHttpResult] keyed by `requestId`, since more than one HTTP call
 * can be in flight at once (device-description fetch, `GetProtocolInfo`, and playback polling
 * all overlap in practice).
 */
interface DlnaTransportBridge {
    fun startDiscovery()
    fun stopDiscovery()
    fun httpGet(url: String, requestId: String)
    fun httpPost(url: String, soapActionHeader: String, body: String, requestId: String)
}

/**
 * iOS DLNA sender implementation.
 *
 * Every entry point here — the bridge callbacks below and the `actual` functions the shared UI
 * calls — is expected to run on the main thread, the same discipline `CastBridge.swift`'s
 * `onMain` already enforces for the Cast SDK: the internal [scope] is pinned to
 * [Dispatchers.Main] and the Swift side dispatches its callbacks there too, so the mutable
 * device/request maps below never need their own locking.
 */
actual object DlnaPlatform {

    actual val isSupported: Boolean = true

    private var bridge: DlnaTransportBridge? = null

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    actual val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _connection = MutableStateFlow<DlnaConnectionState>(DlnaConnectionState.Idle)
    actual val connection: StateFlow<DlnaConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<CastPlaybackState?>(null)
    actual val playback: StateFlow<CastPlaybackState?> = _playback.asStateFlow()

    // Keyed by device-description URL rather than USN: an `ssdp:all` search makes a device answer
    // once per service it hosts, so the same renderer arrives under several USNs pointing at one
    // location, and resolving each of them separately would fetch the same description repeatedly.
    private val devicesByLocation = mutableMapOf<String, DlnaDevice>()
    private val lastSeenByLocation = mutableMapOf<String, Long>()
    private val resolvingLocations = mutableSetOf<String>()

    /** Locations already checked and found not to be renderers, so they aren't re-fetched every round. */
    private val rejectedLocations = mutableMapOf<String, Long>()

    /** `ssdp:byebye` carries no LOCATION, so departures can only be matched through the USN. */
    private val locationByUsn = mutableMapOf<String, String>()

    private val pendingHttp = mutableMapOf<String, (Result<String>) -> Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null
    private var isMutedLocal = false
    private var requestCounter = 0L

    fun attachBridge(bridge: DlnaTransportBridge) {
        this.bridge = bridge
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    actual fun startDiscovery() {
        bridge?.startDiscovery()
    }

    actual fun stopDiscovery() {
        bridge?.stopDiscovery()
    }

    /** Called from Swift for every SSDP search-response datagram received. */
    fun onSsdpResponse(raw: String) {
        val ssdp = parseSsdpResponse(raw) ?: return
        noteAlive(usn = ssdp.usn, location = ssdp.location)
    }

    /** Called from Swift for every unsolicited SSDP `NOTIFY` announcement received. */
    fun onSsdpNotify(raw: String) {
        val notification = parseSsdpNotify(raw) ?: return
        if (notification.isAlive) {
            notification.location?.let { noteAlive(usn = notification.usn, location = it) }
            return
        }
        val location = locationByUsn.remove(notification.usn) ?: return
        lastSeenByLocation.remove(location)
        if (devicesByLocation.remove(location) != null) {
            _devices.value = devicesByLocation.values.toList()
        }
    }

    /** Called from Swift once a search window closes, so devices that stopped answering expire. */
    fun onSearchCycleCompleted() {
        val now = currentTimeMs()
        val cutoff = now - DEVICE_TTL_MS
        val stale = lastSeenByLocation.filterValues { it < cutoff }.keys.toList()
        stale.forEach { location ->
            lastSeenByLocation.remove(location)
            devicesByLocation.remove(location)
        }
        rejectedLocations.entries.removeAll { now - it.value >= REJECTED_TTL_MS }
        locationByUsn.entries.removeAll { it.value !in lastSeenByLocation }
        if (stale.isNotEmpty()) _devices.value = devicesByLocation.values.toList()
    }

    /**
     * Records that whatever lives at [location] is still on the network, and resolves it the
     * first time it is seen.
     */
    private fun noteAlive(usn: String, location: String) {
        val now = currentTimeMs()
        locationByUsn[usn] = location
        lastSeenByLocation[location] = now
        if (devicesByLocation.containsKey(location)) return
        rejectedLocations[location]?.let { if (now - it < REJECTED_TTL_MS) return }
        if (!resolvingLocations.add(location)) return

        scope.launch {
            val device = runCatching { resolveDevice(location) }.getOrNull()
            resolvingLocations.remove(location)
            if (device != null) {
                devicesByLocation[location] = device
                rejectedLocations.remove(location)
                _devices.value = devicesByLocation.values.toList()
            } else {
                // `ssdp:all` pulls in every UPnP device on the network. Remembering the ones that
                // turned out not to be renderers keeps the next round from re-fetching all of
                // their descriptions again.
                rejectedLocations[location] = currentTimeMs()
            }
        }
    }

    private suspend fun resolveDevice(location: String): DlnaDevice? {
        val xml = httpGet(location).getOrNull() ?: return null
        val description = parseDeviceDescription(xml, location) ?: return null
        val avTransport = description.service(AV_TRANSPORT_SERVICE_TYPE) ?: return null
        val renderingControl = description.service(RENDERING_CONTROL_SERVICE_TYPE)?.controlUrl
        val connectionManagerUrl = description.service(CONNECTION_MANAGER_SERVICE_TYPE)?.controlUrl

        val sink = connectionManagerUrl?.let { url ->
            val call = DlnaActions.getProtocolInfo()
            soapPost(url, call).getOrNull()?.let { parseSoapResponse(it, call.action)["Sink"] }
        }

        return DlnaDevice(
            id = description.udn,
            name = description.friendlyName,
            modelName = description.modelName,
            location = location,
            avTransportControlUrl = avTransport.controlUrl,
            renderingControlControlUrl = renderingControl,
            capabilities = capabilitiesFromProtocolInfo(sink),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    actual fun connect(device: DlnaDevice) {
        isMutedLocal = false
        _connection.value = DlnaConnectionState.Connected(device)
        startPolling(device)
    }

    actual fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        _connection.value = DlnaConnectionState.Idle
        _playback.value = null
    }

    private fun startPolling(device: DlnaDevice) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce(device) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce(device: DlnaDevice) {
        val transportCall = DlnaActions.getTransportInfo()
        val transportState = soapPost(device.avTransportControlUrl, transportCall).getOrNull()
            ?.let { parseSoapResponse(it, transportCall.action)["CurrentTransportState"] }

        val positionCall = DlnaActions.getPositionInfo()
        val positionFields = soapPost(device.avTransportControlUrl, positionCall).getOrNull()
            ?.let { parseSoapResponse(it, positionCall.action) }
            ?: emptyMap()

        var volume = _playback.value?.volume ?: 1f
        device.renderingControlControlUrl?.let { url ->
            val volumeCall = DlnaActions.getVolume()
            soapPost(url, volumeCall).getOrNull()
                ?.let { parseSoapResponse(it, volumeCall.action)["CurrentVolume"] }
                ?.toIntOrNull()
                ?.let { volume = (it / 100f).coerceIn(0f, 1f) }
        }

        _playback.value = CastPlaybackState(
            isPlaying = transportState == "PLAYING",
            isBuffering = transportState == "TRANSITIONING",
            positionMs = parseUpnpTimeToMs(positionFields["RelTime"]) ?: 0L,
            durationMs = parseUpnpTimeToMs(positionFields["TrackDuration"]) ?: 0L,
            volume = volume,
            isMuted = isMutedLocal,
            title = null,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Control
    // ---------------------------------------------------------------------------------------

    actual suspend fun load(request: CastMediaRequest): Result<Unit> {
        val device = (_connection.value as? DlnaConnectionState.Connected)?.device
            ?: return Result.failure(IllegalStateException("No DLNA device connected"))
        return runCatching {
            soapPost(
                device.avTransportControlUrl,
                DlnaActions.setAvTransportUri(request.contentUrl, request.contentType, request.title),
            ).getOrThrow()
            soapPost(device.avTransportControlUrl, DlnaActions.play()).getOrThrow()
        }
    }

    actual fun play() = fireAndForget { device -> soapPost(device.avTransportControlUrl, DlnaActions.play()) }

    actual fun pause() = fireAndForget { device -> soapPost(device.avTransportControlUrl, DlnaActions.pause()) }

    actual fun seekTo(positionMs: Long) = fireAndForget { device ->
        soapPost(device.avTransportControlUrl, DlnaActions.seek(positionMs))
    }

    actual fun setVolume(volume: Float) = fireAndForget { device ->
        device.renderingControlControlUrl?.let { url ->
            soapPost(url, DlnaActions.setVolume((volume.coerceIn(0f, 1f) * 100).toInt()))
        } ?: Result.success("")
    }

    actual fun setMuted(muted: Boolean) {
        isMutedLocal = muted
        fireAndForget { device ->
            device.renderingControlControlUrl?.let { url -> soapPost(url, DlnaActions.setMute(muted)) }
                ?: Result.success("")
        }
    }

    actual fun stopPlayback() = fireAndForget { device -> soapPost(device.avTransportControlUrl, DlnaActions.stop()) }

    private fun fireAndForget(action: suspend (DlnaDevice) -> Result<String>) {
        val device = (_connection.value as? DlnaConnectionState.Connected)?.device ?: return
        scope.launch { runCatching { action(device) } }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP bridge
    // ---------------------------------------------------------------------------------------

    private suspend fun httpGet(url: String): Result<String> = suspendCancellableCoroutine { continuation ->
        val target = bridge
        if (target == null) {
            continuation.resume(Result.failure(IllegalStateException("DLNA is not available")))
            return@suspendCancellableCoroutine
        }
        val requestId = nextRequestId()
        pendingHttp[requestId] = { result -> if (continuation.isActive) continuation.resume(result) }
        target.httpGet(url, requestId)
    }

    private suspend fun soapPost(url: String, call: DlnaSoapCall): Result<String> =
        suspendCancellableCoroutine { continuation ->
            val target = bridge
            if (target == null) {
                continuation.resume(Result.failure(IllegalStateException("DLNA is not available")))
                return@suspendCancellableCoroutine
            }
            val requestId = nextRequestId()
            pendingHttp[requestId] = { result -> if (continuation.isActive) continuation.resume(result) }
            target.httpPost(url, call.soapActionHeader, call.body, requestId)
        }

    /** Called from Swift when an [DlnaTransportBridge.httpGet]/`httpPost` call finishes. */
    fun onHttpResult(requestId: String, success: Boolean, body: String?, message: String?) {
        val completion = pendingHttp.remove(requestId) ?: return
        completion(
            if (success) {
                Result.success(body.orEmpty())
            } else {
                Result.failure(IllegalStateException(message ?: "Request failed"))
            },
        )
    }

    private fun nextRequestId(): String {
        requestCounter += 1
        return requestCounter.toString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun currentTimeMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private const val DEVICE_TTL_MS = 30_000L

    /** Re-checked occasionally: a device can gain an AVTransport service across a firmware update. */
    private const val REJECTED_TTL_MS = 300_000L

    private const val POLL_INTERVAL_MS = 2_000L
}
