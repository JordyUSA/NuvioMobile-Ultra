package com.nuvio.app.features.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.android.gms.cast.CastDevice as GmsCastDevice

/**
 * Google Cast sender implementation.
 *
 * [initialize] must be called once with an application context before anything else; it is
 * safe to call repeatedly. If Google Play services is missing or too old, initialisation
 * fails softly and [isSupported] stays false so the UI hides casting entirely rather than
 * presenting a control that throws.
 *
 * The Cast SDK requires main-thread access, so every SDK interaction is posted there while
 * the exposed state flows can be read from anywhere.
 */
actual object CastPlatform {

    private val main = Handler(Looper.getMainLooper())

    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var discoveryActive = false

    /** Routes keyed by the id we hand out in [CastDevice.id]. */
    private val routesById = mutableMapOf<String, MediaRouter.RouteInfo>()

    private var supported = false
    actual val isSupported: Boolean
        get() = supported

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    actual val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _connection = MutableStateFlow<CastConnectionState>(CastConnectionState.Idle)
    actual val connection: StateFlow<CastConnectionState> = _connection.asStateFlow()

    // Android's MediaRouter gives no signal that separates "blocked" from "empty network",
    // so there is never a diagnostic to show.
    actual val discoveryDiagnostic: StateFlow<String?> = MutableStateFlow(null)

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
        if (castContext != null) return
        val appContext = context.applicationContext
        runOnMain {
            if (castContext != null) return@runOnMain
            try {
                val ctx = CastContext.getSharedInstance(appContext)
                castContext = ctx
                mediaRouter = MediaRouter.getInstance(appContext)
                routeSelector = MediaRouteSelector.Builder()
                    .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                            ctx.castOptions.receiverApplicationId,
                        ),
                    )
                    .build()
                ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                // Adopt a session that already exists, e.g. after a configuration change.
                ctx.sessionManager.currentCastSession?.let(::attachSession)
                supported = true
            } catch (error: Throwable) {
                // Missing or outdated Play services, or a device with Cast stripped out.
                supported = false
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    actual fun startDiscovery() {
        runOnMain {
            val router = mediaRouter ?: return@runOnMain
            val selector = routeSelector ?: return@runOnMain
            if (discoveryActive) return@runOnMain
            discoveryActive = true
            // Active scan, not CALLBACK_FLAG_REQUEST_DISCOVERY: passive discovery is slower to
            // populate and misses receivers that are already idle. It costs more radio, which is
            // acceptable because discovery is scoped to the picker dialog being on screen.
            router.addCallback(
                selector,
                routeCallback,
                MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            publishRoutes()
        }
    }

    actual fun stopDiscovery() {
        runOnMain {
            if (!discoveryActive) return@runOnMain
            discoveryActive = false
            mediaRouter?.removeCallback(routeCallback)
        }
    }

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    private fun publishRoutes() {
        val router = mediaRouter ?: return
        val selector = routeSelector ?: return
        val discovered = router.routes
            .filter { !it.isDefault && it.matchesSelector(selector) }
            .also { routes ->
                routesById.clear()
                routes.forEach { routesById[it.id] = it }
            }
            .map { route ->
                val gmsDevice = route.extras?.let { GmsCastDevice.getFromBundle(it) }
                CastDevice(
                    id = route.id,
                    name = route.name,
                    modelName = gmsDevice?.modelName,
                    hasVideoOutput = gmsDevice?.hasCapability(GmsCastDevice.CAPABILITY_VIDEO_OUT) ?: true,
                )
            }
        _devices.value = discovered
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    actual fun connect(device: CastDevice) {
        runOnMain {
            val route = routesById[device.id] ?: return@runOnMain
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
}
