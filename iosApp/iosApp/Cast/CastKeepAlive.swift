import Foundation
import AVFoundation
import ComposeApp

/// Keeps the process running while the phone is serving a stream to the television.
///
/// When the receiver cannot fetch a source itself — a torrent's loopback address, a file on
/// disk, an origin that needs request headers, anything remuxed or transcoded — it pulls the
/// media over HTTP from `CastLocalServer` instead. iOS suspends a backgrounded app, its
/// `NWListener` stops being scheduled, and the television stalls mid-frame until the app is
/// foregrounded again. That is exactly what "it buffers forever when I switch apps, then
/// recovers when I switch back" is. `beginBackgroundTask` does not help: it buys about thirty
/// seconds, nowhere near the length of a film.
///
/// The app already declares the `audio` background mode, and iOS keeps an app running while it
/// is genuinely playing audio, so this plays silence on a loop for exactly as long as something
/// is being served. It mixes with other audio rather than interrupting it, is silent at both
/// the buffer and the volume level, and stops the moment the last route is unpublished — the
/// phone is never held awake once casting has ended.
final class CastKeepAlive {

    static let shared = CastKeepAlive()

    private let lock = NSLock()
    private var holders = 0
    private var player: AVAudioPlayer?

    private init() {}

    /// Balanced with `release()`. Reference counted because several routes can be published at
    /// once — the media and its subtitle tracks are separate payloads.
    func retain() {
        lock.lock()
        holders += 1
        let shouldStart = holders == 1
        lock.unlock()
        guard shouldStart else { return }
        DispatchQueue.main.async { self.start() }
    }

    func release() {
        lock.lock()
        holders = max(0, holders - 1)
        let shouldStop = holders == 0
        lock.unlock()
        guard shouldStop else { return }
        DispatchQueue.main.async { self.stop() }
    }

    /// Drops every hold at once, for teardown paths that discard all routes together.
    func releaseAll() {
        lock.lock()
        let wasHeld = holders > 0
        holders = 0
        lock.unlock()
        guard wasHeld else { return }
        DispatchQueue.main.async { self.stop() }
    }

    private func start() {
        guard player == nil else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            // .mixWithOthers so casting never silences music or a podcast the user already has
            // going; this session exists to keep the process scheduled, not to be heard.
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
            let looping = try AVAudioPlayer(data: Self.silence())
            looping.numberOfLoops = -1
            looping.volume = 0
            looping.play()
            player = looping
            CastDiagnostics.shared.log(tag: "Cast", message: "holding the app awake while serving to the TV")
        } catch {
            // Not fatal: casting still works in the foreground, it just will not survive the
            // app being backgrounded. Worth saying so rather than failing silently.
            CastDiagnostics.shared.log(
                tag: "Cast",
                message: "could not hold the app awake (\(error.localizedDescription)); background casting will stall"
            )
        }
    }

    private func stop() {
        guard player != nil else { return }
        player?.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        CastDiagnostics.shared.log(tag: "Cast", message: "released the app-awake hold")
    }

    /// A half second of 8 kHz mono silence, as an in-memory WAV. Generated rather than shipped
    /// as an asset so there is no bundle resource to lose — which is the failure this very
    /// feature area already suffered once, when the Cast SDK's own bundles were never copied.
    private static func silence(seconds: Double = 0.5, sampleRate: Int = 8000) -> Data {
        let frames = Int(Double(sampleRate) * seconds)
        let payloadBytes = frames * 2
        var wav = Data()
        func append32(_ value: Int) {
            var little = UInt32(value).littleEndian
            withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) }
        }
        func append16(_ value: Int) {
            var little = UInt16(value).littleEndian
            withUnsafeBytes(of: &little) { wav.append(contentsOf: $0) }
        }
        wav.append(contentsOf: Array("RIFF".utf8))
        append32(36 + payloadBytes)
        wav.append(contentsOf: Array("WAVE".utf8))
        wav.append(contentsOf: Array("fmt ".utf8))
        append32(16)          // PCM header length
        append16(1)           // PCM
        append16(1)           // mono
        append32(sampleRate)
        append32(sampleRate * 2)  // byte rate
        append16(2)           // block align
        append16(16)          // bits per sample
        wav.append(contentsOf: Array("data".utf8))
        append32(payloadBytes)
        wav.append(Data(count: payloadBytes))
        return wav
    }
}
