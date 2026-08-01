import Foundation
import UIKit
import ffmpegkit

/// Converts a downloaded file into a different container/codec — the iOS half of the video
/// converter, and the counterpart of Android's `ConversionEngine.android.kt`.
///
/// Structurally a sibling of `CastTranscoder`: same FFmpegKit call shape, same VideoToolbox →
/// libx264 fallback (VideoToolbox has a small system-wide limit on concurrent encoder sessions and
/// refuses outright once it is exhausted, which is exactly the case a background conversion running
/// alongside a foreground cast makes more likely), and the same pre-typed-closure discipline the
/// note in `run` explains.
///
/// What it adds over the cast transcoder is container and codec choice, an audio-only mode, and a
/// job id — the converter has a queue, so a completion has to say which job it belongs to.
final class VideoConverter {

    struct Request {
        let jobId: String
        let sourceUrl: String
        let outputPath: String
        /// "mp4", "matroska" or "webm".
        let container: String
        let dropVideo: Bool
        let reencodeVideo: Bool
        /// "h264" or "hevc".
        let videoCodec: String
        let videoWidth: Int
        let videoHeight: Int
        let videoBitrateBitsPerSecond: Int64
        /// 0 means "keep the source rate".
        let videoFrameRate: Float
        let preferHardwareEncoder: Bool
        let reencodeAudio: Bool
        /// "aac" or "mp3".
        let audioCodec: String
        let audioChannelCount: Int
        let audioBitrateBitsPerSecond: Int64
        let keepSubtitles: Bool
        let durationMs: Int64
    }

    private enum Encoder {
        case hardware
        case software
    }

    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var activeJobId: String?

    /// `completion` is always called exactly once, on the main thread.
    func process(
        request: Request,
        onProgress: @escaping (Int) -> Void,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        try? FileManager.default.removeItem(atPath: request.outputPath)

        guard FFmpegSessionCoordinator.shared.begin(.converter) else {
            completion(.failure(Self.error("Another media session is already running")))
            return
        }

        activeJobId = request.jobId
        beginBackgroundAssertion()

        let finish: (Result<Void, Error>) -> Void = { [weak self] result in
            guard let self else { return }
            FFmpegSessionCoordinator.shared.end(.converter)
            self.activeJobId = nil
            self.endBackgroundAssertion()
            DispatchQueue.main.async { completion(result) }
        }

        run(
            encoder: request.preferHardwareEncoder ? .hardware : .software,
            request: request,
            onProgress: onProgress
        ) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                finish(.success(()))
            case let .failure(error):
                // Only worth a second attempt when a video encode was actually requested: a remux
                // or an audio-only failure will not be fixed by swapping video encoders.
                guard request.reencodeVideo, !request.dropVideo, request.preferHardwareEncoder else {
                    finish(.failure(error))
                    return
                }
                self.run(
                    encoder: .software,
                    request: request,
                    onProgress: onProgress
                ) { retry in
                    switch retry {
                    case .success: finish(.success(()))
                    case let .failure(retryError): finish(.failure(retryError))
                    }
                }
            }
        }
    }

    func cancel(jobId: String) {
        guard activeJobId == jobId else { return }
        FFmpegSessionCoordinator.shared.cancel(.converter)
    }

    /// Whitespace-separated encoder names from `-encoders`, for the Kotlin capability probe.
    func encoderNames() -> String {
        guard let session = FFmpegKit.execute("-hide_banner -encoders"),
              let logs = session.getAllLogsAsString() else {
            return ""
        }
        let known = [
            "h264_videotoolbox", "hevc_videotoolbox",
            "libx264", "libx265",
            "aac", "libmp3lame",
        ]
        return known.filter { logs.contains($0) }.joined(separator: " ")
    }

    // MARK: - Private

    private func run(
        encoder: Encoder,
        request: Request,
        onProgress: @escaping (Int) -> Void,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        let arguments = buildArguments(encoder: encoder, request: request)
        let durationMs = request.durationMs

        // Pre-typed as their own `let` bindings, with the exact typealiases FFmpegKit.h declares,
        // for the reason documented at length in `CastTranscoder.run`: inline trailing closures
        // make Swift solve the whole call and every closure body as one inference problem, which
        // proved unstable across otherwise-identical archive runs.
        let completeCallback: FFmpegSessionCompleteCallback = { (session: FFmpegSession?) in
            guard let session else {
                completion(.failure(Self.error("ffmpeg session was nil")))
                return
            }
            if ReturnCode.isSuccess(session.getReturnCode()) {
                completion(.success(()))
            } else if ReturnCode.isCancel(session.getReturnCode()) {
                completion(.failure(Self.error("Conversion was cancelled")))
            } else {
                let detail = session.getFailStackTrace()
                    ?? session.getAllLogsAsString()?.suffix(2000).description
                    ?? "ffmpeg exited with \(session.getReturnCode()?.getValue() ?? -1)"
                completion(.failure(Self.error(detail)))
            }
        }

        let statisticsCallback: StatisticsCallback = { (statistics: Statistics?) in
            guard let statistics, durationMs > 0 else { return }
            let done = Int64(statistics.getTime())
            let percent = Int((done * 100) / durationMs)
            DispatchQueue.main.async { onProgress(min(max(percent, 0), 100)) }
        }

        FFmpegKit.execute(
            withArgumentsAsync: arguments,
            withCompleteCallback: completeCallback,
            withLogCallback: nil,
            withStatisticsCallback: statisticsCallback
        )
    }

    private func buildArguments(encoder: Encoder, request: Request) -> [String] {
        var arguments: [String] = ["-i", request.sourceUrl]

        let subtitleCodec = Self.subtitleCodec(for: request.container)
        let keepSubtitles = request.keepSubtitles && subtitleCodec != nil

        if !request.dropVideo { arguments += ["-map", "0:v:0"] }
        arguments += ["-map", "0:a:0?"]
        if keepSubtitles { arguments += ["-map", "0:s?"] }

        if request.dropVideo {
            arguments += ["-vn"]
        } else if !request.reencodeVideo {
            arguments += ["-c:v", "copy"]
        } else {
            arguments += ["-c:v", Self.videoEncoderName(codec: request.videoCodec, encoder: encoder)]
            arguments += ["-b:v", String(request.videoBitrateBitsPerSecond)]
            if request.videoWidth > 0, request.videoHeight > 0 {
                arguments += ["-vf", "scale=\(request.videoWidth):\(request.videoHeight)"]
            }
            if request.videoFrameRate > 0 {
                arguments += ["-r", String(Int(request.videoFrameRate))]
            }
        }

        if request.reencodeAudio {
            arguments += ["-c:a", request.audioCodec == "mp3" ? "libmp3lame" : "aac"]
            arguments += ["-b:a", String(request.audioBitrateBitsPerSecond)]
            if request.audioChannelCount > 0 {
                arguments += ["-ac", String(request.audioChannelCount)]
            }
        } else {
            arguments += ["-c:a", "copy"]
        }

        if let subtitleCodec, keepSubtitles {
            arguments += ["-c:s", subtitleCodec]
        } else {
            arguments += ["-sn"]
        }

        if request.container != "mp4" {
            arguments += ["-f", request.container]
        } else {
            // Index at the front, so a player can seek without fetching the tail.
            arguments += ["-movflags", "+faststart"]
        }

        arguments += ["-y", request.outputPath]
        return arguments
    }

    private static func videoEncoderName(codec: String, encoder: Encoder) -> String {
        switch (codec, encoder) {
        case ("hevc", .hardware): return "hevc_videotoolbox"
        case ("hevc", .software): return "libx265"
        case (_, .hardware): return "h264_videotoolbox"
        default: return "libx264"
        }
    }

    private static func subtitleCodec(for container: String) -> String? {
        switch container {
        case "mp4": return "mov_text"
        case "matroska": return "srt"
        case "webm": return "webvtt"
        default: return nil
        }
    }

    /// Buys the app-switch and brief-lock cases. iOS still suspends a long encode; the repository
    /// re-queues an interrupted job rather than failing it.
    ///
    /// Hopped to the main queue because `process` is driven from Kotlin's conversion coroutine,
    /// which is not the main thread, and every `UIApplication` member touched here is main-thread
    /// only — off-main access trips the main thread checker rather than failing quietly.
    private func beginBackgroundAssertion() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            UIApplication.shared.isIdleTimerDisabled = true
            self.backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "nuvio.conversion") { [weak self] in
                self?.endBackgroundAssertion()
            }
        }
    }

    private func endBackgroundAssertion() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            UIApplication.shared.isIdleTimerDisabled = false
            guard self.backgroundTask != .invalid else { return }
            UIApplication.shared.endBackgroundTask(self.backgroundTask)
            self.backgroundTask = .invalid
        }
    }

    private static func error(_ message: String) -> NSError {
        NSError(domain: "VideoConverter", code: -1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
