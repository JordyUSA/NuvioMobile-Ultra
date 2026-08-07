import Foundation
import UIKit
import Network
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

    /// Companion browse over the same `_googlecast._tcp` service the SDK watches, but through
    /// Network.framework, which reports what the SDK swallows: a denied Local Network
    /// permission surfaces as a `waiting` state, and the OS's own view of how many receivers
    /// exist is visible regardless of what the SDK's device list says. It also guarantees the
    /// permission prompt fires — on iOS 18 some sideloaded apps never get prompted through the
    /// SDK's older browse path.
    private var probeBrowser: NWBrowser?
    private var lastProbeDrivenRestart = Date.distantPast

    /// Logged again at every startDiscovery so it sits next to the interesting window in the
    /// diagnostics ring buffer instead of being pushed out by the SDK's verbose stream.
    private var environmentSummary = ""

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
        // Discovery must be fully manual — started when the picker opens, stopped when it
        // closes. startDiscoveryAfterFirstTapOnCastButton=false alone does not do that: it
        // defers to disableDiscoveryAutostart, whose default (false) starts discovery the
        // moment the context is created. That put iOS's one-shot Local Network permission
        // prompt on the launch screen, where "Don't Allow" is the reflexive answer — and a
        // denial leaves discovery returning empty lists forever with no error. Both flags
        // together keep the browse, and therefore the prompt, inside the picker where the
        // user can see why they're being asked.
        options.disableDiscoveryAutostart = true
        options.startDiscoveryAfterFirstTapOnCastButton = false
        options.suspendSessionsWhenBackgrounded = false
        GCKCastContext.setSharedInstanceWith(options)

        let bridge = CastBridge()

        // Google's documented debugging path: without this delegate the SDK's discovery
        // failures — permission refusals, socket errors, dead browses — are discarded, and an
        // empty device list is indistinguishable from a healthy empty network. Verbose, on
        // purpose: every line also lands in CastDiagnostics, the in-app console the user can
        // copy out of the device picker, because a sideloaded install has no Console.app.
        let logFilter = GCKLoggerFilter()
        logFilter.minimumLevel = .verbose
        GCKLogger.sharedInstance().filter = logFilter
        GCKLogger.sharedInstance().delegate = bridge

        GCKCastContext.sharedInstance().sessionManager.add(bridge)
        bridge.discoveryManager.add(bridge)
        CastBridgeRegistrationKt.registerCastBridge(bridge: bridge)

        bridge.environmentSummary =
            "SDK \(kGCKFrameworkVersion) · iOS \(UIDevice.current.systemVersion) · " +
            (Bundle.main.bundleIdentifier ?? "unknown bundle")
        CastDiagnostics.shared.log(tag: "Cast", message: "installed — \(bridge.environmentSummary)")
        return bridge
    }

    // MARK: - CastIosBridge

    func startDiscovery() {
        onMain {
            guard !self.isDiscovering else { return }
            self.isDiscovering = true
            CastDiagnostics.shared.log(tag: "Cast", message: "startDiscovery — \(self.environmentSummary)")
            // The first browse ever is what triggers iOS's Local Network permission alert,
            // and that browse is already dead by the time the user taps Allow — it never
            // recovers on its own, so a first open of the picker finds nothing even after
            // granting. The alert deactivates the app, so becoming active again while the
            // picker is open is the cue to restart the browse. It also catches the user
            // coming back from Settings after flipping the Local Network toggle.
            self.discoveryObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                guard let self, self.isDiscovering else { return }
                CastDiagnostics.shared.log(tag: "Cast", message: "app became active while discovering — restarting SDK browse")
                self.discoveryManager.stopDiscovery()
                self.discoveryManager.startDiscovery()
            }
            self.discoveryManager.startDiscovery()
            self.startProbe()
            self.publishDevices()
        }
    }

    func stopDiscovery() {
        onMain {
            guard self.isDiscovering else { return }
            self.isDiscovering = false
            CastDiagnostics.shared.log(tag: "Cast", message: "stopDiscovery")
            if let observer = self.discoveryObserver {
                NotificationCenter.default.removeObserver(observer)
                self.discoveryObserver = nil
            }
            self.stopProbe()
            self.discoveryManager.stopDiscovery()
        }
    }

    private func startProbe() {
        stopProbe()
        let browser = NWBrowser(
            for: .bonjour(type: "_googlecast._tcp", domain: nil),
            using: NWParameters()
        )
        browser.stateUpdateHandler = { [weak self] state in
            guard let self, self.isDiscovering else { return }
            CastDiagnostics.shared.log(tag: "Probe", message: "NWBrowser state: \(state)")
            // `waiting` while browsing the local network means iOS refused the browse —
            // in practice the Local Network permission — and it will sit there forever
            // rather than fail. That is the only programmatic view of the denial Apple
            // offers, so pass it up for the picker to explain.
            if case .waiting = state {
                CastPlatform.shared.onDiscoveryProbe(blocked: true, mdnsDeviceCount: 0)
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self, self.isDiscovering else { return }
            let names = results.compactMap { result -> String? in
                if case .service(let name, _, _, _) = result.endpoint { return name }
                return nil
            }
            CastDiagnostics.shared.log(
                tag: "Probe",
                message: "mDNS sees \(results.count) receiver(s): \(names.joined(separator: ", "))"
            )
            CastPlatform.shared.onDiscoveryProbe(
                blocked: false,
                mdnsDeviceCount: Int32(results.count)
            )
            // The OS sees receivers the SDK doesn't: its browse most likely died in the
            // permission race and never recovered, and a restart re-browses under the
            // now-granted permission. Rate limited so a flapping network can't thrash it.
            if !results.isEmpty, self.discoveryManager.deviceCount == 0,
               Date().timeIntervalSince(self.lastProbeDrivenRestart) > 5 {
                self.lastProbeDrivenRestart = Date()
                CastDiagnostics.shared.log(tag: "Probe", message: "OS sees receivers but SDK list is empty — cycling SDK browse")
                self.discoveryManager.stopDiscovery()
                self.discoveryManager.startDiscovery()
            }
        }
        browser.start(queue: .main)
        probeBrowser = browser
    }

    private func stopProbe() {
        probeBrowser?.cancel()
        probeBrowser = nil
    }

    func connect(deviceId: String) {
        onMain {
            guard let device = self.knownDevices[deviceId] else {
                CastDiagnostics.shared.log(tag: "Cast", message: "connect: unknown device id \(deviceId)")
                return
            }
            CastDiagnostics.shared.log(tag: "Cast", message: "connect → \(device.friendlyName ?? deviceId)")
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
        CastDiagnostics.shared.log(
            tag: "Cast",
            message: "SDK device list: \(devices.count) — \(devices.map { $0.name }.joined(separator: ", "))"
        )
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
        CastDiagnostics.shared.log(
            tag: "Cast",
            message: "session ended" + (error.map { ": \($0.localizedDescription)" } ?? "")
        )
        CastPlatform.shared.onConnectionChanged(state: 0, device: nil, message: nil)
    }

    func sessionManager(
        _ manager: GCKSessionManager,
        didFailToStart session: GCKCastSession,
        withError error: Error
    ) {
        CastDiagnostics.shared.log(tag: "Cast", message: "session failed to start: \(error.localizedDescription)")
        CastPlatform.shared.onConnectionChanged(
            state: 3, device: nil, message: error.localizedDescription
        )
    }

    private func attach(_ session: GCKCastSession) {
        session.remoteMediaClient?.add(self)
        CastDiagnostics.shared.log(tag: "Cast", message: "session connected: \(session.device.friendlyName ?? "?")")
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

// MARK: - GCKLoggerDelegate

extension CastBridge: GCKLoggerDelegate {
    func logMessage(_ message: String, at level: GCKLoggerLevel, fromFunction function: String, location: String) {
        NSLog("[GCKCast] %@ %@", function, message)
        // The SDK logs from arbitrary threads; the diagnostics buffer is main-thread-only.
        onMain { CastDiagnostics.shared.log(tag: "GCK", message: "\(function): \(message)") }
    }
}

// MARK: - GCKRequestDelegate

extension CastBridge: GCKRequestDelegate {

    func requestDidComplete(_ request: GCKRequest) {
        CastDiagnostics.shared.log(tag: "Cast", message: "load request completed")
        CastPlatform.shared.onLoadResult(success: true, message: nil)
    }

    func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        CastDiagnostics.shared.log(tag: "Cast", message: "load request failed: \(error.localizedDescription)")
        CastPlatform.shared.onLoadResult(success: false, message: error.localizedDescription)
    }

    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        CastPlatform.shared.onLoadResult(success: false, message: "Cast load was cancelled")
    }
}
