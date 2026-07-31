package com.nuvio.app.features.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastPlaybackState
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
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android DLNA sender implementation.
 *
 * Same raw-socket style as `CastLocalServer.kt` — plain JDK `MulticastSocket`/
 * `HttpURLConnection`, no new dependency. Discovery runs on its own daemon thread (SSDP is a
 * blocking receive loop, not something coroutines buy anything for); control calls and playback
 * polling run on a `Dispatchers.IO` scope.
 *
 * [initialize] must be called once with an application context before [startDiscovery], the
 * same convention `CastPlatform.android.kt`/`CastDelivery.android.kt` already use — it only
 * needs the context to acquire a Wi-Fi multicast lock, since `CHANGE_WIFI_MULTICAST_STATE` is
 * already declared in the manifest.
 */
actual object DlnaPlatform {

    actual val isSupported: Boolean = true

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    actual val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _connection = MutableStateFlow<DlnaConnectionState>(DlnaConnectionState.Idle)
    actual val connection: StateFlow<DlnaConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<CastPlaybackState?>(null)
    actual val playback: StateFlow<CastPlaybackState?> = _playback.asStateFlow()

    private var wifiManager: WifiManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveryActive = AtomicBoolean(false)
    private var discoveryThread: Thread? = null
    private var notifyThread: Thread? = null
    private val resolveExecutor = Executors.newCachedThreadPool()

    // Keyed by device-description URL rather than USN: an `ssdp:all` search makes a device answer
    // once per service it hosts, so the same renderer arrives under several USNs pointing at one
    // location, and resolving each of them separately would fetch the same description repeatedly.
    private val devicesByLocation = ConcurrentHashMap<String, DlnaDevice>()
    private val lastSeenByLocation = ConcurrentHashMap<String, Long>()
    private val resolvingLocations = ConcurrentHashMap.newKeySet<String>()

    /** Locations already checked and found not to be renderers, so they aren't re-fetched every round. */
    private val rejectedLocations = ConcurrentHashMap<String, Long>()

    /** `ssdp:byebye` carries no LOCATION, so departures can only be matched through the USN. */
    private val locationByUsn = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var isMutedLocal = false

    fun initialize(context: Context) {
        if (wifiManager != null) return
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    actual fun startDiscovery() {
        if (!discoveryActive.compareAndSet(false, true)) return
        multicastLock = wifiManager?.createMulticastLock("nuvio-dlna-discovery")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        discoveryThread = Thread({ discoveryLoop() }, "dlna-discovery").apply {
            isDaemon = true
            start()
        }
        // Passive listening runs alongside the active search rather than instead of it: a TV
        // switched on while the picker is open announces itself immediately, without waiting
        // for the next search round.
        notifyThread = Thread({ notifyLoop() }, "dlna-notify").apply {
            isDaemon = true
            start()
        }
    }

    actual fun stopDiscovery() {
        if (!discoveryActive.compareAndSet(true, false)) return
        discoveryThread = null
        notifyThread = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun discoveryLoop() {
        while (discoveryActive.get()) {
            runCatching { searchOnce() }.onFailure { Log.w(TAG, "SSDP search failed", it) }
            pruneStale()
            publishDevices()
            var waited = 0L
            while (waited < SEARCH_INTERVAL_MS && discoveryActive.get()) {
                Thread.sleep(SLEEP_STEP_MS)
                waited += SLEEP_STEP_MS
            }
        }
    }

    private fun searchOnce() {
        val socket = MulticastSocket().apply {
            soTimeout = RECEIVE_POLL_TIMEOUT_MS
            timeToLive = 4
        }
        try {
            val group = InetAddress.getByName(DLNA_MULTICAST_ADDRESS)
            val interfaces = multicastInterfaces()

            // SSDP rides on UDP multicast, which is lossy over Wi-Fi, and a single dropped
            // datagram hides a device for a whole search interval. Repeating each target, on
            // every candidate interface, is what the spec expects of a control point and is the
            // single biggest factor in whether a given television turns up at all.
            repeat(SEARCH_ATTEMPTS) { attempt ->
                DLNA_SEARCH_TARGETS.forEach { target ->
                    val request = buildSsdpSearchRequest(target, MX_SECONDS).toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(request, request.size, group, DLNA_MULTICAST_PORT)
                    if (interfaces.isEmpty()) {
                        runCatching { socket.send(packet) }
                    } else {
                        // The default route can be cellular while the TV is on Wi-Fi, so the
                        // interface is chosen explicitly rather than left to the system.
                        interfaces.forEach { nic ->
                            runCatching {
                                socket.networkInterface = nic
                                socket.send(packet)
                            }
                        }
                    }
                }
                if (attempt < SEARCH_ATTEMPTS - 1) Thread.sleep(SEARCH_ATTEMPT_GAP_MS)
            }

            // Devices answer at a random point inside the MX window precisely so they don't all
            // reply at once, so the socket stays open for the whole window; a quiet moment part
            // way through is not the end of it.
            val deadline = System.currentTimeMillis() + SEARCH_WINDOW_MS
            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            while (System.currentTimeMillis() < deadline && discoveryActive.get()) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(response.data, 0, response.length, Charsets.UTF_8)
                parseSsdpResponse(text)?.let { noteAlive(usn = it.usn, location = it.location) }
            }
        } finally {
            socket.close()
        }
    }

    private fun notifyLoop() {
        while (discoveryActive.get()) {
            runCatching { listenForNotifications() }
                .onFailure { Log.w(TAG, "SSDP NOTIFY listener stopped", it) }
            // Rebind after a failure — an interface change mid-session shouldn't silently cost
            // passive discovery for the rest of the session.
            var waited = 0L
            while (waited < NOTIFY_REBIND_DELAY_MS && discoveryActive.get()) {
                Thread.sleep(SLEEP_STEP_MS)
                waited += SLEEP_STEP_MS
            }
        }
    }

    private fun listenForNotifications() {
        // MulticastSocket enables SO_REUSEADDR itself, which matters here: port 1900 is shared
        // with every other UPnP control point on the device.
        val socket = MulticastSocket(DLNA_MULTICAST_PORT).apply { soTimeout = RECEIVE_POLL_TIMEOUT_MS }
        try {
            val group = InetSocketAddress(InetAddress.getByName(DLNA_MULTICAST_ADDRESS), DLNA_MULTICAST_PORT)
            val joined = multicastInterfaces().count { nic ->
                runCatching { socket.joinGroup(group, nic) }.isSuccess
            }
            if (joined == 0) runCatching { socket.joinGroup(group, null) }.getOrThrow()

            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            while (discoveryActive.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parseSsdpNotify(text)?.let(::handleNotification)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleNotification(notification: SsdpNotification) {
        if (notification.isAlive) {
            notification.location?.let { noteAlive(usn = notification.usn, location = it) }
            return
        }
        val location = locationByUsn.remove(notification.usn) ?: return
        lastSeenByLocation.remove(location)
        if (devicesByLocation.remove(location) != null) publishDevices()
    }

    /**
     * Records that whatever lives at [location] is still on the network, and resolves it the
     * first time it is seen.
     */
    private fun noteAlive(usn: String, location: String) {
        val now = System.currentTimeMillis()
        locationByUsn[usn] = location
        lastSeenByLocation[location] = now
        if (devicesByLocation.containsKey(location)) return
        rejectedLocations[location]?.let { if (now - it < REJECTED_TTL_MS) return }
        if (!resolvingLocations.add(location)) return

        resolveExecutor.execute {
            try {
                val device = runCatching { resolveDevice(location) }
                    .onFailure { Log.w(TAG, "Could not resolve $location", it) }
                    .getOrNull()
                if (device != null) {
                    devicesByLocation[location] = device
                    rejectedLocations.remove(location)
                    publishDevices()
                } else {
                    // `ssdp:all` pulls in every UPnP device on the network. Remembering the ones
                    // that turned out not to be renderers keeps the next round from re-fetching
                    // all of their descriptions again.
                    rejectedLocations[location] = System.currentTimeMillis()
                }
            } finally {
                resolvingLocations.remove(location)
            }
        }
    }

    private fun resolveDevice(location: String): DlnaDevice? {
        val xml = httpGet(location)
        val description = parseDeviceDescription(xml, location) ?: return null
        val avTransport = description.service(AV_TRANSPORT_SERVICE_TYPE) ?: return null
        val renderingControl = description.service(RENDERING_CONTROL_SERVICE_TYPE)?.controlUrl
        val connectionManagerUrl = description.service(CONNECTION_MANAGER_SERVICE_TYPE)?.controlUrl

        val sink = connectionManagerUrl?.let { url ->
            runCatching {
                val call = DlnaActions.getProtocolInfo()
                parseSoapResponse(soapPost(url, call), call.action)["Sink"]
            }.getOrNull()
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

    /** Up, non-loopback, multicast-capable interfaces — in practice Wi-Fi, plus Ethernet on a TV box. */
    private fun multicastInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
            .filter { nic -> nic.inetAddresses.asSequence().any { it is Inet4Address } }
            .toList()
    }.getOrDefault(emptyList())

    private fun pruneStale() {
        val now = System.currentTimeMillis()
        val cutoff = now - DEVICE_TTL_MS
        var changed = false
        for (location in lastSeenByLocation.keys.toList()) {
            val lastSeen = lastSeenByLocation[location] ?: continue
            if (lastSeen >= cutoff) continue
            lastSeenByLocation.remove(location)
            if (devicesByLocation.remove(location) != null) changed = true
        }
        for (location in rejectedLocations.keys.toList()) {
            val rejectedAt = rejectedLocations[location] ?: continue
            if (now - rejectedAt >= REJECTED_TTL_MS) rejectedLocations.remove(location)
        }
        for (usn in locationByUsn.keys.toList()) {
            val location = locationByUsn[usn] ?: continue
            if (!lastSeenByLocation.containsKey(location)) locationByUsn.remove(usn)
        }
        if (changed) publishDevices()
    }

    private fun publishDevices() {
        _devices.value = devicesByLocation.values.toList()
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
                runCatching { pollOnce(device) }.onFailure { Log.w(TAG, "Status poll failed", it) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pollOnce(device: DlnaDevice) {
        val transportCall = DlnaActions.getTransportInfo()
        val transportState = parseSoapResponse(
            soapPost(device.avTransportControlUrl, transportCall),
            transportCall.action,
        )["CurrentTransportState"]

        val positionCall = DlnaActions.getPositionInfo()
        val positionFields = parseSoapResponse(
            soapPost(device.avTransportControlUrl, positionCall),
            positionCall.action,
        )

        var volume = _playback.value?.volume ?: 1f
        device.renderingControlControlUrl?.let { url ->
            runCatching {
                val volumeCall = DlnaActions.getVolume()
                parseSoapResponse(soapPost(url, volumeCall), volumeCall.action)["CurrentVolume"]
                    ?.toIntOrNull()
                    ?.let { volume = (it / 100f).coerceIn(0f, 1f) }
            }
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

    actual suspend fun load(request: CastMediaRequest): Result<Unit> = withContext(Dispatchers.IO) {
        val device = (_connection.value as? DlnaConnectionState.Connected)?.device
            ?: return@withContext Result.failure(IllegalStateException("No DLNA device connected"))
        runCatching {
            soapPost(device.avTransportControlUrl, DlnaActions.setAvTransportUri(request.contentUrl, request.contentType, request.title))
            soapPost(device.avTransportControlUrl, DlnaActions.play())
            Unit
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
        }
    }

    actual fun setMuted(muted: Boolean) {
        isMutedLocal = muted
        fireAndForget { device -> device.renderingControlControlUrl?.let { url -> soapPost(url, DlnaActions.setMute(muted)) } }
    }

    actual fun stopPlayback() = fireAndForget { device -> soapPost(device.avTransportControlUrl, DlnaActions.stop()) }

    private fun fireAndForget(action: (DlnaDevice) -> Unit) {
        val device = (_connection.value as? DlnaConnectionState.Connected)?.device ?: return
        scope.launch { runCatching { action(device) }.onFailure { Log.w(TAG, "Control call failed", it) } }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private fun httpGet(url: String, timeoutMs: Int = HTTP_TIMEOUT_MS): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun soapPost(url: String, call: DlnaSoapCall, timeoutMs: Int = HTTP_TIMEOUT_MS): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", call.soapActionHeader)
        }
        connection.outputStream.use { it.write(call.body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val ok = status in 200..299
        val stream = if (ok) connection.inputStream else connection.errorStream
        val body = try {
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
        // A renderer that refuses an action answers 500 with a SOAP Fault. Returning that body
        // as though it were a result made every failure look like a success to load(), which
        // then reported the stream as playing while the television sat idle.
        if (!ok) {
            val detail = parseSoapFault(body)
            throw IllegalStateException(
                if (detail != null) "${call.action} failed: $detail" else "${call.action} failed (HTTP $status)",
            )
        }
        return body
    }

    private const val TAG = "DlnaPlatform"

    /** `MX`: the ceiling, in seconds, on how long a device may wait before answering. */
    private const val MX_SECONDS = 3

    /** Kept above [MX_SECONDS] so the retransmits below still leave a full window to answer in. */
    private const val SEARCH_WINDOW_MS = 5_000L
    private const val SEARCH_ATTEMPTS = 3
    private const val SEARCH_ATTEMPT_GAP_MS = 250L

    /** Short, so both receive loops notice [stopDiscovery] promptly rather than at window end. */
    private const val RECEIVE_POLL_TIMEOUT_MS = 500
    private const val RECEIVE_BUFFER_BYTES = 8_192

    private const val SEARCH_INTERVAL_MS = 10_000L
    private const val SLEEP_STEP_MS = 500L
    private const val DEVICE_TTL_MS = 30_000L

    /** Re-checked occasionally: a device can gain an AVTransport service across a firmware update. */
    private const val REJECTED_TTL_MS = 300_000L
    private const val NOTIFY_REBIND_DELAY_MS = 5_000L

    private const val POLL_INTERVAL_MS = 2_000L
    private const val HTTP_TIMEOUT_MS = 5_000
}
