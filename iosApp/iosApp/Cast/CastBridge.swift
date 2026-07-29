import Foundation
import GoogleCast
import ComposeApp

/// Google Cast sender for iOS.
///
/// Implements the Kotlin `CastIosBridge` so the shared cast code — the delivery planner, the
/// receiver capability matrix and the Compose UI — drives iOS exactly as it drives Android,
/// without the Kotlin compilation needing any Cast headers.
///
/// Everything here runs on the main thread: the Cast SDK requires it, and the Kotlin state
/// flows are read from the UI anyway.
final class CastBridge: NSObject, CastIosBridge {

    /// Google's styled Default Media Receiver. Must match `CastOptionsProvider` on Android and
    /// the second Bonjour service declared in Info.plist.
    private static let receiverApplicationID = kGCKDefaultMediaReceiverApplicationID

    private var discoveryObserver: NSObjectProtocol?
    private var isDiscovering = false

    /// Devices are addressed by `deviceID` across the bridge, so the SDK objects stay here.
    private var knownDevices: [String: GCKDevice] = [:]

    private var sessionManager: GCKSessionManager { GCKCastContext.sharedInstance().sessionManager }
    private var discoveryManager: GCKDiscoveryManager { GCKCastContext.sharedInstance().discoveryManager }

    private var remoteMediaClient: GCKRemoteMediaClient? {
        sessionManager.currentCastSession?.remoteMediaClient
    }

    /// Starts the SDK and registers with Kotlin.
    ///
    /// Failure is soft on purpose: if the Cast context cannot start, no bridge is registered,
    /// `CastPlatform.isSupported` stays false and the shared UI simply omits the Cast button
    /// instead of offering one that cannot work.
    @discardableResult
    static func install() -> CastBridge? {
        let criteria = GCKDiscoveryCriteria(applicationID: receiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        // Discovery is started explicitly when the picker opens rather than at launch, so the
        // radio is not kept busy browsing for devices the whole time the app is running.
        options.startDiscoveryAfterFirstTapOnCastButton = false
        options.suspendSessionsWhenBackgrounded = false
        GCKCastContext.setSharedInstanceWith(options)

        let bridge = CastBridge()
        GCKCastContext.sharedInstance().sessionManager.add(bridge)
        bridge.discoveryManager.add(bridge)
        CastBridgeRegistrationKt.registerCastBridge(bridge: bridge)
        return bridge
    }

    // MARK: - CastIosBridge

    func startDiscovery() {
        onMain {
            guard !self.isDiscovering else { return }
            self.isDiscovering = true
            self.discoveryManager.startDiscovery()
            self.publishDevices()
        }
    }

    func stopDiscovery() {
        onMain {
            guard self.isDiscovering else { return }
            self.isDiscovering = false
            self.discoveryManager.stopDiscovery()
        }
    }

    func connect(deviceId: String) {
        onMain {
            guard let device = self.knownDevices[deviceId] else { return }
            self.sessionManager.startSession(with: device)
        }
    }

    func disconnect() {
        onMain { self.sessionManager.endSessionAndStopCasting(true) }
    }

    func load(
        contentUrl: String,
        contentType: String,
        title: String,
        subtitle: String?,
        posterUrl: String?,
        startPositionMs: Int64,
        durationMs: Int64,
        isLive: Bool
    ) {
        onMain {
            guard let client = self.remoteMediaClient, let url = URL(string: contentUrl) else {
                CastPlatform.shared.onLoadResult(success: false, message: "No Cast session")
                return
            }

            let metadata = GCKMediaMetadata(metadataType: subtitle == nil ? .movie : .tvShow)
            metadata.setString(title, forKey: kGCKMetadataKeyTitle)
            if let subtitle { metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle) }
            if let posterUrl, let poster = URL(string: posterUrl) {
                metadata.addImage(GCKImage(url: poster, width: 480, height: 720))
            }

            let builder = GCKMediaInformationBuilder(contentURL: url)
            builder.streamType = isLive ? .live : .buffered
            builder.contentType = contentType
            builder.metadata = metadata
            if durationMs > 0 { builder.streamDuration = Double(durationMs) / 1000.0 }

            let options = GCKMediaLoadOptions()
            options.autoplay = true
            options.playPosition = Double(startPositionMs) / 1000.0

            let request = client.loadMedia(builder.build(), with: options)
            request.delegate = self
        }
    }

    func play() { onMain { self.remoteMediaClient?.play() } }

    func pause() { onMain { self.remoteMediaClient?.pause() } }

    func seekTo(positionMs: Int64) {
        onMain {
            let options = GCKMediaSeekOptions()
            options.interval = Double(positionMs) / 1000.0
            self.remoteMediaClient?.seek(with: options)
        }
    }

    func setVolume(volume: Float) {
        onMain { self.sessionManager.currentCastSession?.setDeviceVolume(volume) }
    }

    func setMuted(muted: Bool) {
        onMain { self.sessionManager.currentCastSession?.setDeviceMuted(muted) }
    }

    func stopPlayback() { onMain { self.remoteMediaClient?.stop() } }

    // MARK: - Publishing state back to Kotlin

    private func publishDevices() {
        var devices: [CastDevice] = []
        knownDevices.removeAll()
        for index in 0..<discoveryManager.deviceCount {
            let device = discoveryManager.device(at: index)
            knownDevices[device.deviceID] = device
            devices.append(
                CastDevice(
                    id: device.deviceID,
                    name: device.friendlyName ?? "Cast device",
                    modelName: device.modelName,
                    // Speakers advertise no video output; the shared planner refuses video
                    // for those rather than letting it fail on the device.
                    hasVideoOutput: device.hasCapabilities(GCKDeviceCapabilities.videoOut)
                )
            )
        }
        CastPlatform.shared.onDevicesChanged(devices: devices)
    }

    private func describe(_ session: GCKCastSession) -> CastDevice {
        let device = session.device
        return CastDevice(
            id: device.deviceID,
            name: device.friendlyName ?? "Cast device",
            modelName: device.modelName,
            hasVideoOutput: device.hasCapabilities(GCKDeviceCapabilities.videoOut)
        )
    }

    private func publishPlayback() {
        guard let client = remoteMediaClient, client.mediaStatus != nil else {
            CastPlatform.shared.onPlaybackCleared()
            return
        }
        let status = client.mediaStatus
        let session = sessionManager.currentCastSession
        let duration = status?.mediaInformation?.streamDuration ?? 0
        CastPlatform.shared.onPlaybackChanged(
            isPlaying: status?.playerState == .playing,
            isBuffering: status?.playerState == .buffering || status?.playerState == .loading,
            positionMs: Int64((client.approximateStreamPosition() * 1000).rounded()),
            durationMs: duration.isFinite && duration > 0 ? Int64(duration * 1000) : 0,
            volume: session?.currentDeviceVolume ?? 1.0,
            isMuted: session?.currentDeviceMuted ?? false,
            title: status?.mediaInformation?.metadata?.string(forKey: kGCKMetadataKeyTitle)
        )
    }

    private func onMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread { block() } else { DispatchQueue.main.async(execute: block) }
    }
}

// MARK: - GCKDiscoveryManagerListener

extension CastBridge: GCKDiscoveryManagerListener {
    func didUpdateDeviceList() { publishDevices() }
    func didInsert(_ device: GCKDevice, at index: UInt) { publishDevices() }
    func didUpdate(_ device: GCKDevice, at index: UInt) { publishDevices() }
    func didRemove(_ device: GCKDevice, at index: UInt) { publishDevices() }
}

// MARK: - GCKSessionManagerListener

extension CastBridge: GCKSessionManagerListener {

    func sessionManager(_ manager: GCKSessionManager, didStart session: GCKCastSession) {
        attach(session)
    }

    func sessionManager(_ manager: GCKSessionManager, didResumeCastSession session: GCKCastSession) {
        attach(session)
    }

    func sessionManager(_ manager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        session.remoteMediaClient?.remove(self)
        CastPlatform.shared.onConnectionChanged(state: 0, device: nil, message: nil)
    }

    func sessionManager(
        _ manager: GCKSessionManager,
        didFailToStart session: GCKCastSession,
        withError error: Error
    ) {
        CastPlatform.shared.onConnectionChanged(
            state: 3, device: nil, message: error.localizedDescription
        )
    }

    private func attach(_ session: GCKCastSession) {
        session.remoteMediaClient?.add(self)
        CastPlatform.shared.onConnectionChanged(state: 2, device: describe(session), message: nil)
        publishPlayback()
    }
}

// MARK: - GCKRemoteMediaClientListener

extension CastBridge: GCKRemoteMediaClientListener {
    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        publishPlayback()
    }
}

// MARK: - GCKRequestDelegate

extension CastBridge: GCKRequestDelegate {

    func requestDidComplete(_ request: GCKRequest) {
        CastPlatform.shared.onLoadResult(success: true, message: nil)
    }

    func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        CastPlatform.shared.onLoadResult(success: false, message: error.localizedDescription)
    }

    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        CastPlatform.shared.onLoadResult(success: false, message: "Cast load was cancelled")
    }
}
