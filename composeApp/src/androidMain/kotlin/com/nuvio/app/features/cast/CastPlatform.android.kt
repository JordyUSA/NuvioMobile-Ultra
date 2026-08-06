package com.nuvio.app.features.cast

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import com.google.android.gms.cast.CastDevice as GmsCastDevice

/**
 * Google Cast sender implementation.
 *
 * [initialize] must be called once with an application context before anything else; it is
 * safe to call repeatedly. If Google Play services is missing or too old, initialisation
 * fails softly and [isSupported] stays false, so callers can omit the Chromecast affordance
 * rather than present a control that throws. The reason lands in [discovery] rather than being
 * discarded, because "no devices" and "the SDK never started" look identical from the picker.
 *
 * The Cast SDK requires main-thread access, so every SDK interaction is posted there while
 * the exposed state flows can be read from anywhere.
 */
actual object CastPlatform {

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null

    /** True from [startDiscovery] until [stopDiscovery], independent of whether the SDK is up yet. */
    private var discoveryRequested = false
    private var initInFlight = false

    /** Runs the Cast SDK's own start-up work so it stays off the main thread. */
    private val initExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "cast-init").apply { isDaemon = true } }
    }

    /** Routes keyed by the id we hand out in [CastDevice.id]. */
    private val routesById = mutableMapOf<String, MediaRouter.RouteInfo>()

    private var supported = false
    actual val isSupported: Boolean
        get() = supported

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    actual val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _discovery = MutableStateFlow(CastDiscoveryState())
    actual val discovery: StateFlow<CastDiscoveryState> = _discovery.asStateFlow()

    private val _connection = MutableStateFlow<CastConnectionState>(CastConnectionState.Idle)
    actual val connection: StateFlow<CastConnectionState> = _connection.asStateFlow()

    private val _playback = MutableStateFlow<CastPlaybackState?>(null)
    actual val playback: StateFlow<CastPlaybackState?> = _playback.asStateFlow()

    private val currentSession: CastSession?
        get() = castContext?.sessionManager?.currentCastSession

    private val remoteClient: RemoteMediaClient?
        get() = currentSession?.remoteMediaClient

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Android-only entry point. Call from Application or Activity creation.
     *
     * Not part of the shared `expect` because only Android needs a Context.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        runOnMain { ensureCastContext() }
    }

    /**
     * The activity used to ask for `NEARBY_WIFI_DEVICES`. Discovery works without one — it simply
     * cannot prompt, and the picker says so instead.
     */
    private var boundActivity: Activity? = null

    fun bindActivity(activity: Activity) {
        boundActivity = activity
    }

    fun unbindActivity(activity: Activity) {
        if (boundActivity === activity) boundActivity = null
    }

    /**
     * Brings up [CastContext], asynchronously and retryably.
     *
     * The synchronous `getSharedInstance(Context)` throws whenever Play services is momentarily
     * unavailable — updating in the background is enough — and the previous code caught that,
     * swallowed it without a log, and left `supported` false for the rest of the process. Casting
     * then looked broken with no way to find out why. The `Task` overload waits for Play services
     * instead of throwing, the failure path records a reason the picker can show, and nothing
     * latches: the next [startDiscovery] or [refresh] tries again.
     */
    private fun ensureCastContext() {
        if (castContext != null || initInFlight) return
        val context = appContext ?: return

        val availability = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (availability != ConnectionResult.SUCCESS) {
            val reason = runCatching { GoogleApiAvailability.getInstance().getErrorString(availability) }
                .getOrNull() ?: "code $availability"
            Log.w(TAG, "Cast unavailable: Google Play services reported $reason")
            supported = false
            _discovery.value = _discovery.value.copy(
                unavailableReason = "Chromecast needs Google Play services ($reason)",
            )
            return
        }

        initInFlight = true
        runCatching {
            // The Cast SDK does its start-up work on this executor, so it gets a background
            // thread; the Task's listeners come back on the main looper, which is where the
            // MediaRouter and SessionManager calls below have to happen anyway.
            CastContext.getSharedInstance(context, initExecutor)
                .addOnSuccessListener { ctx ->
                    runOnMain {
                        initInFlight = false
                        runCatching { onCastContextReady(context, ctx) }
                            .onFailure { Log.e(TAG, "Cast context came up but could not be wired", it) }
                    }
                }
                .addOnFailureListener { error ->
                    runOnMain {
                        initInFlight = false
                        reportUnavailable("Cast context failed to initialise", error)
                    }
                }
        }.onFailure { error ->
            initInFlight = false
            reportUnavailable("Cast context could not be requested", error)
        }
    }

    private fun reportUnavailable(logMessage: String, error: Throwable) {
        Log.e(TAG, logMessage, error)
        supported = false
        _discovery.value = _discovery.value.copy(
            unavailableReason = "Chromecast could not start (${error.message ?: "unknown error"})",
        )
    }

    private fun onCastContextReady(context: Context, ctx: CastContext) {
        if (castContext != null) return
        castContext = ctx
        mediaRouter = MediaRouter.getInstance(context)
        routeSelector = MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(ctx.castOptions.receiverApplicationId),
            )
            .build()
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        // Adopt a session that already exists, e.g. after a configuration change.
        ctx.sessionManager.currentCastSession?.let(::attachSession)
        supported = true
        _discovery.value = _discovery.value.copy(unavailableReason = null)
        Log.i(TAG, "Cast context ready, receiver app ${ctx.castOptions.receiverApplicationId}")
        // The picker can easily open before Play services hands the context back. Without this,
        // that scan request was dropped on the floor and the dialog searched forever.
        if (discoveryRequested) armScan()
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    actual fun startDiscovery() {
        runOnMain {
            if (discoveryRequested) return@runOnMain
            discoveryRequested = true
            _discovery.value = _discovery.value.copy(isScanning = true)
            requestNearbyWifiPermission()
            ensureCastContext()
            armScan()
        }
    }

    actual fun stopDiscovery() {
        runOnMain {
            if (!discoveryRequested) return@runOnMain
            discoveryRequested = false
            main.removeCallbacks(rearmScan)
            mediaRouter?.removeCallback(routeCallback)
            _discovery.value = _discovery.value.copy(isScanning = false)
        }
    }

    actual fun refresh() {
        runOnMain {
            // A refresh is also the retry for a failed start-up: Play services may simply have
            // been mid-update when the app launched.
            ensureCastContext()
            requestNearbyWifiPermission()
            if (!discoveryRequested) {
                discoveryRequested = true
                _discovery.value = _discovery.value.copy(isScanning = true)
            }
            armScan()
        }
    }

    /**
     * Starts one active-scan round and schedules the next.
     *
     * MediaRouter only honours `CALLBACK_FLAG_PERFORM_ACTIVE_SCAN` for 30 seconds and then
     * silently drops back to passive discovery; re-registering the callback with the flag resets
     * that window. The old code registered once and never again, so any receiver that had not
     * answered within the first half minute — a television switched on while the picker was open,
     * or one that simply answered slowly — never appeared, and the dialog gave no sign that it had
     * stopped looking.
     */
    private fun armScan() {
        // Dropping any pending re-arm first keeps this idempotent: it is called from the timer,
        // from a refresh, and from late SDK/permission arrival, and chaining a second timer off
        // each of those would compound the scan rate.
        main.removeCallbacks(rearmScan)
        val router = mediaRouter ?: return
        val selector = routeSelector ?: return
        if (!discoveryRequested) return
        router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        _discovery.value = _discovery.value.copy(isScanning = true)
        publishRoutes()
        main.postDelayed(rearmScan, ACTIVE_SCAN_REARM_MS)
    }

    private val rearmScan = Runnable { armScan() }

    /**
     * Asks for the nearby-devices permission the first time the picker opens.
     *
     * Wi-Fi discovery on API 33+ is gated on this; without it the scan runs and finds nothing,
     * which is indistinguishable from having no Chromecast on the network. Asking is best-effort —
     * discovery is started either way, and a refusal turns into a hint in the picker rather than
     * blocking the DLNA receivers that share the dialog.
     */
    private fun requestNearbyWifiPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = appContext ?: return
        val granted = ContextCompat.checkSelfPermission(context, NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            _discovery.value = _discovery.value.copy(hint = null)
            return
        }
        val activity = boundActivity
        if (activity == null || permissionRequested) {
            _discovery.value = _discovery.value.copy(hint = NEARBY_WIFI_HINT)
            return
        }
        permissionRequested = true
        runCatching {
            ActivityCompat.requestPermissions(activity, arrayOf(NEARBY_WIFI_DEVICES), PERMISSION_REQUEST_CODE)
        }.onFailure { Log.w(TAG, "Could not ask for nearby-devices permission", it) }
    }

    /** Asked at most once per process, so reopening the picker does not nag. */
    private var permissionRequested = false

    /** Returns true when [requestCode] was ours, mirroring the other platform bridges. */
    fun handlePermissionRequestResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != PERMISSION_REQUEST_CODE) return false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        _discovery.value = _discovery.value.copy(hint = if (granted) null else NEARBY_WIFI_HINT)
        // A grant arriving mid-scan does not retroactively widen the round already in flight.
        if (granted) runOnMain { if (discoveryRequested) armScan() }
        return true
    }

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()

        // The Cast route provider is published by Play services over IPC and can arrive after the
        // callback is registered. Its routes are then already in the router by the time we hear
        // about it, so no per-route add ever fires for them.
        override fun onProviderAdded(router: MediaRouter, provider: MediaRouter.ProviderInfo) = publishRoutes()
        override fun onProviderChanged(router: MediaRouter, provider: MediaRouter.ProviderInfo) = publishRoutes()
        override fun onProviderRemoved(router: MediaRouter, provider: MediaRouter.ProviderInfo) = publishRoutes()
    }

    private fun publishRoutes() {
        val router = mediaRouter ?: return
        val selector = routeSelector ?: return
        val matching = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }

        routesById.clear()
        matching.forEach { routesById[it.id] = it }

        val discovered = matching.map { route ->
            val gmsDevice = route.extras?.let { GmsCastDevice.getFromBundle(it) }
            CastDevice(
                id = route.id,
                name = route.name,
                modelName = gmsDevice?.modelName,
                hasVideoOutput = gmsDevice?.hasCapability(GmsCastDevice.CAPABILITY_VIDEO_OUT) ?: true,
            )
        }
        // Every route change republishes the whole list, including changes that touch nothing we
        // expose (volume, presentation display). Emitting an equal list would recompose the picker
        // for nothing several times a second while a session is live.
        if (_devices.value != discovered) {
            Log.d(TAG, "Cast receivers: ${discovered.joinToString { it.name }}")
            _devices.value = discovered
        }
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    actual fun connect(device: CastDevice) {
        runOnMain {
            val route = routesById[device.id]
            if (route == null) {
                // The receiver dropped off between being listed and being tapped. Reporting it
                // matters because the picker otherwise sits on "Cast to" with nothing happening.
                Log.w(TAG, "No route for ${device.name} (${device.id}) any more")
                _connection.value = CastConnectionState.Failed("${device.name} is no longer reachable")
                publishRoutes()
                return@runOnMain
            }
            _connection.value = CastConnectionState.Connecting(device)
            mediaRouter?.selectRoute(route)
        }
    }

    actual fun disconnect() {
        runOnMain {
            castContext?.sessionManager?.endCurrentSession(true)
            mediaRouter?.let { it.selectRoute(it.defaultRoute) }
        }
    }

    private fun describe(session: CastSession): CastDevice {
        val device = session.castDevice
        return CastDevice(
            id = device?.deviceId ?: session.sessionId.orEmpty(),
            name = device?.friendlyName ?: "Cast device",
            modelName = device?.modelName,
            hasVideoOutput = device?.hasCapability(GmsCastDevice.CAPABILITY_VIDEO_OUT) ?: true,
        )
    }

    private fun attachSession(session: CastSession) {
        _connection.value = CastConnectionState.Connected(describe(session))
        session.remoteMediaClient?.registerCallback(remoteCallback)
        publishPlayback()
    }

    private fun detachSession(session: CastSession?) {
        session?.remoteMediaClient?.unregisterCallback(remoteCallback)
        _connection.value = CastConnectionState.Idle
        _playback.value = null
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attachSession(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attachSession(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detachSession(session)
        override fun onSessionSuspended(session: CastSession, reason: Int) = detachSession(session)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _connection.value = CastConnectionState.Failed("Could not connect (code $error)")
            _playback.value = null
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _connection.value = CastConnectionState.Failed("Could not resume (code $error)")
            _playback.value = null
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
    }

    // ---------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishPlayback()
        override fun onMetadataUpdated() = publishPlayback()
    }

    private fun publishPlayback() {
        val client = remoteClient
        if (client == null || !client.hasMediaSession()) {
            _playback.value = null
            return
        }
        val status = client.mediaStatus
        val session = currentSession
        _playback.value = CastPlaybackState(
            isPlaying = client.isPlaying,
            isBuffering = client.isBuffering ||
                status?.playerState == MediaStatus.PLAYER_STATE_LOADING,
            positionMs = client.approximateStreamPosition,
            durationMs = client.streamDuration.takeIf { it > 0 } ?: 0L,
            volume = session?.volume?.toFloat() ?: 1f,
            isMuted = session?.isMute ?: false,
            title = status?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE),
        )
    }

    actual suspend fun load(request: CastMediaRequest): Result<Unit> {
        val client = remoteClient
            ?: return Result.failure(IllegalStateException("No Cast session"))

        val metadata = MediaMetadata(
            if (request.subtitle != null) {
                MediaMetadata.MEDIA_TYPE_TV_SHOW
            } else {
                MediaMetadata.MEDIA_TYPE_MOVIE
            },
        ).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            request.posterUrl?.takeIf { it.isNotBlank() }?.let {
                addImage(WebImage(Uri.parse(it)))
            }
        }

        val tracks = request.subtitles.map { track ->
            MediaTrack.Builder(track.id, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(track.url)
                .setContentType("text/vtt")
                .setName(track.label ?: track.language ?: "Subtitle")
                .setLanguage(track.language)
                .build()
        }

        val mediaInfo = MediaInfo.Builder(request.contentUrl)
            .setStreamType(
                if (request.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED,
            )
            .setContentType(request.contentType)
            .setMetadata(metadata)
            .apply {
                request.durationMs?.takeIf { it > 0 }?.let { setStreamDuration(it) }
                if (tracks.isNotEmpty()) setMediaTracks(tracks)
            }
            .build()

        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(request.startPositionMs)
            .build()

        return suspendCancellableCoroutine { continuation ->
            runOnMain {
                client.load(loadRequest).setResultCallback { result ->
                    if (continuation.isActive) {
                        if (result.status.isSuccess) {
                            continuation.resume(Result.success(Unit))
                        } else {
                            val message = result.status.statusMessage
                                ?: "Cast load failed (${result.status.statusCode})"
                            continuation.resume(Result.failure(IllegalStateException(message)))
                        }
                    }
                }
            }
        }
    }

    actual fun play() = runOnMain { remoteClient?.play() }

    actual fun pause() = runOnMain { remoteClient?.pause() }

    actual fun seekTo(positionMs: Long) = runOnMain { remoteClient?.seek(positionMs) }

    actual fun setVolume(volume: Float) = runOnMain {
        currentSession?.volume = volume.coerceIn(0f, 1f).toDouble()
    }

    actual fun setMuted(muted: Boolean) = runOnMain { currentSession?.isMute = muted }

    actual fun stopPlayback() = runOnMain { remoteClient?.stop() }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private const val TAG = "CastPlatform"

    /**
     * Comfortably inside MediaRouter's 30-second active-scan window, so the next round starts
     * before the current one is downgraded and scanning never actually lapses.
     */
    private const val ACTIVE_SCAN_REARM_MS = 25_000L

    /** Literal rather than `Manifest.permission.NEARBY_WIFI_DEVICES`, which needs API 33 to resolve. */
    private const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    private const val PERMISSION_REQUEST_CODE = 8721

    private const val NEARBY_WIFI_HINT =
        "Allow nearby device access in Android settings so Chromecasts on your Wi‑Fi can be found."
}
