import Foundation
import ffmpegkit

/// Turns a source the receiver cannot play into one it can — the iOS counterpart of
/// `FFmpegCastMediaProcessor` (Android).
///
/// There is no Media3-Transformer equivalent on iOS to sit in front of this the way Android
/// does, so FFmpegKit covers both jobs here: repackaging (`-c copy`, for the Matroska/AVI case)
/// and re-encoding when the codec itself is the problem. Video encoding prefers
/// `h264_videotoolbox`, iOS's hardware encoder, for the same reason the Android build prefers
/// `h264_mediacodec` — it keeps the expensive half of the work off the CPU. Unlike Android,
/// this build does carry a software encoder (`libx264`, the reason the build is GPL rather than
/// LGPL): VideoToolbox has a small limit on concurrent encoder sessions system-wide and will
/// refuse a request outright when it is exhausted, and libx264 is what keeps that from being a
/// hard failure.
final class CastTranscoder {

    struct VideoTarget {
        let width: Int
        let height: Int
        let bitrateBitsPerSecond: Int64
        /// 0 means "let the encoder keep the source rate".
        let frameRate: Float
    }

    struct AudioTarget {
        let channelCount: Int
        let bitrateBitsPerSecond: Int64
    }

    private enum VideoEncoder: String {
        case videoToolbox = "h264_videotoolbox"
        case libx264 = "libx264"
    }

    private var activeSessionId: Int32?

    /// Produces a receiver-playable file at `outputPath`.
    ///
    /// `onProgress` reports 0...100 and is advisory; not every input carries a duration FFmpeg
    /// can use to estimate it. `completion` is always called exactly once, on the main thread.
    func process(
        sourceUrl: String,
        headers: [String: String],
        outputPath: String,
        videoTarget: VideoTarget?,
        audioTarget: AudioTarget?,
        durationMs: Int64,
        onProgress: @escaping (Int) -> Void,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        try? FileManager.default.removeItem(atPath: outputPath)

        let needsVideoEncode = videoTarget != nil
        run(
            encoder: .videoToolbox,
            sourceUrl: sourceUrl,
            headers: headers,
            outputPath: outputPath,
            videoTarget: videoTarget,
            audioTarget: audioTarget,
            durationMs: durationMs,
            onProgress: onProgress
        ) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                completion(.success(()))
            case let .failure(error):
                // VideoToolbox can refuse a session outright (too many concurrent encoders,
                // an unsupported profile); only worth a second attempt when a video encode was
                // actually requested, since a remux or audio-only failure will not be fixed by
                // switching encoders.
                guard needsVideoEncode else {
                    completion(.failure(error))
                    return
                }
                self.run(
                    encoder: .libx264,
                    sourceUrl: sourceUrl,
                    headers: headers,
                    outputPath: outputPath,
                    videoTarget: videoTarget,
                    audioTarget: audioTarget,
                    durationMs: durationMs,
                    onProgress: onProgress,
                    completion: completion
                )
            }
        }
    }

    func cancel() {
        if let sessionId = activeSessionId {
            FFmpegKit.cancel(sessionId)
        }
        activeSessionId = nil
    }

    // MARK: - Private

    private func run(
        encoder: VideoEncoder,
        sourceUrl: String,
        headers: [String: String],
        outputPath: String,
        videoTarget: VideoTarget?,
        audioTarget: AudioTarget?,
        durationMs: Int64,
        onProgress: @escaping (Int) -> Void,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        let arguments = buildArguments(
            encoder: encoder,
            sourceUrl: sourceUrl,
            headers: headers,
            outputPath: outputPath,
            videoTarget: videoTarget,
            audioTarget: audioTarget
        )

        let session = FFmpegKit.executeWithArgumentsAsync(
            arguments,
            withCompleteCallback: { [weak self] session in
                self?.activeSessionId = nil
                guard let session else {
                    DispatchQueue.main.async { completion(.failure(Self.error("ffmpeg session was nil"))) }
                    return
                }
                if ReturnCode.isSuccess(session.getReturnCode()) {
                    DispatchQueue.main.async { completion(.success(())) }
                } else if ReturnCode.isCancel(session.getReturnCode()) {
                    DispatchQueue.main.async { completion(.failure(Self.error("Cast was cancelled"))) }
                } else {
                    // The return code alone ("1") never explains anything; the tail of the
                    // session log is what does.
                    let detail = session.getFailStackTrace()
                        ?? session.getAllLogsAsString()?.suffix(2000).description
                        ?? "ffmpeg exited with \(session.getReturnCode()?.getValue() ?? -1)"
                    DispatchQueue.main.async { completion(.failure(Self.error(detail))) }
                }
            },
            withLogCallback: nil,
            withStatisticsCallback: { statistics in
                guard let statistics, durationMs > 0 else { return }
                let done = Int64(statistics.getTime())
                let percent = Int((done * 100) / durationMs)
                DispatchQueue.main.async { onProgress(min(max(percent, 0), 100)) }
            }
        )
        activeSessionId = session?.getSessionId()
    }

    private func buildArguments(
        encoder: VideoEncoder,
        sourceUrl: String,
        headers: [String: String],
        outputPath: String,
        videoTarget: VideoTarget?,
        audioTarget: AudioTarget?
    ) -> [String] {
        var arguments: [String] = []

        // Request headers have to precede the input they apply to.
        if !headers.isEmpty {
            let joined = headers.map { "\($0.key): \($0.value)\r\n" }.joined()
            arguments += ["-headers", joined]
        }
        arguments += ["-i", sourceUrl]

        // First video and first audio track. Audio is optional so a video-only source does not
        // abort the whole export.
        arguments += ["-map", "0:v:0", "-map", "0:a:0?"]

        if let video = videoTarget {
            arguments += ["-c:v", encoder.rawValue]
            arguments += ["-b:v", String(video.bitrateBitsPerSecond)]
            arguments += ["-vf", "scale=\(video.width):\(video.height)"]
            if video.frameRate > 0 {
                arguments += ["-r", String(Int(video.frameRate))]
            }
        } else {
            arguments += ["-c:v", "copy"]
        }

        if let audio = audioTarget {
            // The planner only ever targets AAC: the one codec every Cast receiver decodes
            // without relying on HDMI passthrough.
            arguments += ["-c:a", "aac"]
            arguments += ["-b:a", String(audio.bitrateBitsPerSecond)]
            arguments += ["-ac", String(audio.channelCount)]
        } else {
            arguments += ["-c:a", "copy"]
        }

        // Subtitles are delivered to the receiver as separate VTT tracks, so drop any the
        // container carries rather than failing on a codec MP4 cannot hold.
        arguments += ["-sn"]
        // Put the index at the front so the receiver can seek without fetching the tail.
        arguments += ["-movflags", "+faststart"]
        arguments += ["-y", outputPath]

        return arguments
    }

    private static func error(_ message: String) -> Error {
        NSError(domain: "CastTranscoder", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
