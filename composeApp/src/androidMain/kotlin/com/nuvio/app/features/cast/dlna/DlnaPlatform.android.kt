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
import java.net.InetAddress
import java.net.MulticastSocket
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
    private val resolveExecutor = Executors.newCachedThreadPool()
    private val devicesByUsn = ConcurrentHashMap<String, DlnaDevice>()
    private val lastSeenByUsn = ConcurrentHashMap<String, Long>()

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
    }

    actual fun stopDiscovery() {
        if (!discoveryActive.compareAndSet(true, false)) return
        discoveryThread = null
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
            soTimeout = SEARCH_WINDOW_MS.toInt()
            timeToLive = 4
        }
        try {
            val group = InetAddress.getByName(DLNA_MULTICAST_ADDRESS)
            val request = buildSsdpSearchRequest().toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(request, request.size, group, DLNA_MULTICAST_PORT))

            val deadline = System.currentTimeMillis() + SEARCH_WINDOW_MS
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(response.data, 0, response.length, Charsets.UTF_8)
                parseSsdpResponse(text)?.let(::handleSsdpResponse)
            }
        } finally {
            socket.close()
        }
    }

    private fun handleSsdpResponse(ssdp: SsdpResponse) {
        lastSeenByUsn[ssdp.usn] = System.currentTimeMillis()
        if (devicesByUsn.containsKey(ssdp.usn)) return
        resolveExecutor.execute {
            val device = runCatching { resolveDevice(ssdp) }
                .onFailure { Log.w(TAG, "Could not resolve ${ssdp.location}", it) }
                .getOrNull() ?: return@execute
            devicesByUsn[ssdp.usn] = device
            publishDevices()
        }
    }

    private fun resolveDevice(ssdp: SsdpResponse): DlnaDevice? {
        val xml = httpGet(ssdp.location)
        val description = parseDeviceDescription(xml, ssdp.location) ?: return null
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
            location = ssdp.location,
            avTransportControlUrl = avTransport.controlUrl,
            renderingControlControlUrl = renderingControl,
            capabilities = capabilitiesFromProtocolInfo(sink),
        )
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - DEVICE_TTL_MS
        lastSeenByUsn.filterValues { it < cutoff }.keys.forEach { usn ->
            lastSeenByUsn.remove(usn)
            devicesByUsn.remove(usn)
        }
    }

    private fun publishDevices() {
        _devices.value = devicesByUsn.values.toList()
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
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return try {
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
    }

    private const val TAG = "DlnaPlatform"
    private const val SEARCH_WINDOW_MS = 3_000L
    private const val SEARCH_INTERVAL_MS = 10_000L
    private const val SLEEP_STEP_MS = 500L
    private const val DEVICE_TTL_MS = 30_000L
    private const val POLL_INTERVAL_MS = 2_000L
    private const val HTTP_TIMEOUT_MS = 5_000
}
