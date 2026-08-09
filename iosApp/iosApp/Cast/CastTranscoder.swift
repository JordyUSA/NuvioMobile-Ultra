import Foundation
import ffmpegkit
import ComposeApp

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

    /// Cancels every FFmpegKit session rather than tracking this one's id: the session id accessor
    /// turned out to be unreliable to reference from Swift across otherwise-identical builds (see
    /// the note in `run` below), so cancelling everything sidesteps needing it at all.
    ///
    /// That is only safe while exactly one session exists, which stopped being automatic once the
    /// video converter started using FFmpegKit too. `FFmpegSessionCoordinator` restores the
    /// invariant by admitting one owner at a time and ignoring a cancel from anyone else, so this
    /// no longer reaches across and kills a running conversion.
    func cancel() {
        FFmpegSessionCoordinator.shared.cancel(.cast)
    }

    // MARK: - Probing

    /// Raw fields read off FFprobe's stream/format JSON: the primary video stream and every
    /// audio stream, unclassified. `CastMediaProber.ios.kt` maps codec names, the color-transfer
    /// string and bit depth to the shared Cast enums, mirroring the split
    /// `CastMediaProber.android.kt` already has between `MediaExtractor` extraction and
    /// classification. Kept to scalars, strings and arrays of those — no custom bridge type —
    /// for the same interop-stability reason `CastTranscoderBridge.start` is scalars-only.
    struct ProbeResult {
        let durationMs: Int64
        let hasVideo: Bool
        let videoCodec: String
        let videoWidth: Int
        let videoHeight: Int
        let videoFrameRate: Float
        let videoBitrateBitsPerSecond: Int64
        /// e.g. "High Profile Level 5.1" — the exact shape `CastDeliveryPlanner.levelTimesTen`
        /// parses, built here from FFprobe's separate `profile` and `level` fields since FFprobe
        /// reports them apart rather than combined.
        let videoProfile: String
        let videoPixelFormat: String
        let videoBitsPerRawSample: String
        let videoColorTransfer: String
        let audioCodecs: [String]
        let audioChannelCounts: [Int]
        let audioSampleRates: [Int]
        let audioBitrates: [Int64]
        let audioLanguages: [String]
    }

    /// Reads codec/format metadata with FFprobe, without decoding or downloading the file body —
    /// it reads only enough of the container to parse track headers, the same reason
    /// `MediaExtractor` is cheap to call against a remote URL on Android.
    ///
    /// `completion` is always called exactly once, on the main thread, within `timeoutMs` even if
    /// FFprobe itself hangs — a slow or unresponsive remote source should not block casting
    /// forever.
    func probe(
        sourceUrl: String,
        headers: [String: String],
        timeoutMs: Int32 = 10000,
        completion: @escaping (Result<ProbeResult, Error>) -> Void
    ) {
        var arguments: [String] = ["-hide_banner"]
        if !headers.isEmpty {
            let joined = headers.map { "\($0.key): \($0.value)\r\n" }.joined()
            arguments += ["-headers", joined]
        }
        arguments += ["-i", sourceUrl, "-show_format", "-show_streams", "-of", "json"]

        // Same pre-typed-callback approach as `run` below, for the same reason: keeping the
        // closure's type explicit turns this into ordinary already-typed-argument matching
        // instead of a joint inference problem for Swift's type checker.
        let completeCallback: MediaInformationSessionCompleteCallback = { (session: MediaInformationSession?) in
            guard let session else {
                DispatchQueue.main.async { completion(.failure(Self.error("ffprobe session was nil"))) }
                return
            }
            // `getMediaInformation` is documented to return nil exactly when the command failed
            // or the output could not be parsed — the definitive success check for this session
            // type, unlike FFmpegSession's return-code check.
            if let info = session.getMediaInformation() {
                DispatchQueue.main.async { completion(.success(Self.parseProbe(info))) }
            } else {
                let detail = session.getFailStackTrace()
                    ?? session.getAllLogsAsString()?.suffix(2000).description
                    ?? "ffprobe returned no media information"
                DispatchQueue.main.async { completion(.failure(Self.error(detail))) }
            }
        }

        FFprobeKit.getMediaInformation(
            fromCommandArgumentsAsync: arguments,
            withCompleteCallback: completeCallback,
            withLogCallback: nil,
            onDispatchQueue: DispatchQueue.global(qos: .utility),
            withTimeout: timeoutMs
        )
    }

    /// Reads a stream property as text whatever ffprobe actually emitted for it.
    ///
    /// FFmpegKit declares every property accessor as returning `NSString`, but it hands back
    /// the raw JSON value unchecked, and ffprobe emits numbers for several of these keys —
    /// `level` and `channels` among them. Swift trusts the declaration and force-bridges, so an
    /// `NSNumber` gets sent a string selector and the process aborts. That is not hypothetical:
    /// it is what `String._unconditionallyBridgeFromObjectiveC` inside `parseProbe` was doing
    /// in two crash reports from the field, on every stream whose probe reported a codec level.
    ///
    /// Going through `getProperty`, which is honestly typed as `id`, removes the whole class of
    /// bug rather than the two keys that happened to be caught.
    private static func text(_ stream: StreamInformation?, _ key: String) -> String? {
        switch stream?.getProperty(key) {
        case let value as NSString: return value as String
        case let value as NSNumber: return value.stringValue
        default: return nil
        }
    }

    /// The same for the format-level dictionary, which has the same unchecked typing.
    private static func text(_ info: MediaInformation, _ key: String) -> String? {
        switch info.getProperty(key) {
        case let value as NSString: return value as String
        case let value as NSNumber: return value.stringValue
        default: return nil
        }
    }

    private static func parseProbe(_ info: MediaInformation) -> ProbeResult {
        let streams = (info.getStreams() as? [StreamInformation]) ?? []
        let video = streams.first { text($0, "codec_type") == "video" }
        let audioStreams = streams.filter { text($0, "codec_type") == "audio" }

        let durationSeconds = Double(text(info, "duration") ?? "") ?? 0
        let level = Int(text(video, "level") ?? "") ?? 0
        let profileName = text(video, "profile") ?? ""
        let profile = level > 0 ? "\(profileName) Profile Level \(Double(level) / 10.0)" : profileName

        return ProbeResult(
            durationMs: Int64(durationSeconds * 1000),
            hasVideo: video != nil,
            videoCodec: text(video, "codec_name") ?? "",
            videoWidth: video?.getWidth()?.intValue ?? 0,
            videoHeight: video?.getHeight()?.intValue ?? 0,
            videoFrameRate: frameRate(video),
            videoBitrateBitsPerSecond: Int64(text(video, "bit_rate") ?? "") ?? 0,
            videoProfile: profile,
            videoPixelFormat: text(video, "pix_fmt") ?? "",
            videoBitsPerRawSample: text(video, "bits_per_raw_sample") ?? "",
            videoColorTransfer: text(video, "color_transfer") ?? "",
            audioCodecs: audioStreams.map { text($0, "codec_name") ?? "" },
            audioChannelCounts: audioStreams.map { Int(text($0, "channels") ?? "") ?? 2 },
            audioSampleRates: audioStreams.map { Int(text($0, "sample_rate") ?? "") ?? 0 },
            audioBitrates: audioStreams.map { Int64(text($0, "bit_rate") ?? "") ?? 0 },
            audioLanguages: audioStreams.map { stream in
                switch stream.getTags()?["language"] {
                case let value as NSString: return value as String
                case let value as NSNumber: return value.stringValue
                default: return ""
                }
            }
        )
    }

    /// FFprobe's real frame rate (`r_frame_rate`) is populated for effectively every container;
    /// average (`avg_frame_rate`) is preferred when present since it accounts for variable frame
    /// timing, but falls back to real when average is unknown ("0/0", common when duration
    /// metadata is missing).
    private static func frameRate(_ stream: StreamInformation?) -> Float {
        // Read through the same type-safe accessor as everything else: these are strings like
        // "30000/1001" in practice, but they come from the identical unchecked dictionary.
        for candidate in [text(stream, "avg_frame_rate"), text(stream, "r_frame_rate")] {
            guard let candidate, !candidate.isEmpty else { continue }
            let parts = candidate.split(separator: "/")
            if parts.count == 2, let num = Float(parts[0]), let den = Float(parts[1]), den > 0 {
                let value = num / den
                if value > 0 { return value }
            } else if let value = Float(candidate), value > 0 {
                return value
            }
        }
        return 0
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

        // Pre-typed as their own `let` bindings, with the exact typealiases FFmpegKit.h
        // declares, rather than passed as inline trailing closures. Swift's type checker has
        // to solve this whole four-labeled-argument call and every closure body together when
        // they are inline, and that joint inference proved unstable across otherwise-identical
        // archive runs — the same untouched line resolved on one run and failed to on the
        // next, purely because unrelated syntax changed earlier in the same statement. Giving
        // each closure a concrete type up front turns the call itself into ordinary
        // already-typed-argument matching instead of a shared inference problem.
        FFmpegSessionCoordinator.shared.begin(.cast)

        let completeCallback: FFmpegSessionCompleteCallback = { (session: FFmpegSession?) in
            FFmpegSessionCoordinator.shared.end(.cast)
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
        }

        let statisticsCallback: StatisticsCallback = { (statistics: Statistics?) in
            guard let statistics, durationMs > 0 else { return }
            let done = Int64(statistics.getTime())
            let percent = Int((done * 100) / durationMs)
            DispatchQueue.main.async { onProgress(min(max(percent, 0), 100)) }
        }

        // The exact command, because a conversion that fails in seconds fails on its arguments
        // and there is otherwise no way to see what was actually run. The source URL is
        // trimmed: it can carry credentials, and its shape is not what is in question here.
        let redacted = arguments.map { $0.hasPrefix("http") ? "<source-url>" : $0 }
        CastDiagnostics.shared.log(tag: "Deliver", message: "ffmpeg \(redacted.joined(separator: " "))")

        // The return value (the created session) is intentionally not captured — see cancel()
        // above for why nothing here needs its id.
        FFmpegKit.execute(
            withArgumentsAsync: arguments,
            withCompleteCallback: completeCallback,
            withLogCallback: nil,
            withStatisticsCallback: statisticsCallback
        )
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
        // container carries rather than failing on a codec the container cannot hold.
        arguments += ["-sn"]

        if outputPath.hasSuffix(".m3u8") {
            // Segmented output, so the receiver can start playing while the rest is still
            // being produced. The previous output was a single MP4 finished with
            // `+faststart`, which rewrites the whole file at the end to move the index to the
            // front — worthless until the very last moment, so nothing could reach the
            // television until an entire film had been converted.
            //
            // Written with the `segment` muxer rather than `hls`, because this FFmpegKit build
            // does not contain an `hls` muxer at all: its libavformat carries adts, dash,
            // ipod, matroska, mpegts and segment, and nothing else. Asking for `-f hls` is
            // rejected outright in about a second, which is exactly what the field logs showed
            // and is also why the original single-file MP4 never worked — there is no `mp4`
            // muxer here either.
            //
            // `segment` covers the same ground: it closes a segment every few seconds and
            // writes an m3u8 listing the ones that are finished. `+live` keeps that playlist
            // updating as they appear and leaves off the end marker until the run completes,
            // which is what tells the receiver more is still coming. A list size of zero keeps
            // every entry, so seeking back over what already exists still works.
            let directory = (outputPath as NSString).deletingLastPathComponent
            arguments += ["-f", "segment", "-segment_time", "4"]
            // MPEG-TS for every case: it carries H.264 and HEVC alike, and the fragmented-MP4
            // alternative needs the muxer this build lacks.
            arguments += ["-segment_format", "mpegts"]
            arguments += ["-segment_list_type", "m3u8"]
            arguments += ["-segment_list", outputPath]
            arguments += ["-segment_list_size", "0"]
            arguments += ["-segment_list_flags", "+live"]
            // The final argument is the segment pattern, not the playlist: the playlist is
            // named by -segment_list above.
            arguments += ["-y", "\(directory)/seg%05d.ts"]
            return arguments
        }

        // Put the index at the front so the receiver can seek without fetching the tail.
        arguments += ["-movflags", "+faststart"]
        arguments += ["-y", outputPath]

        return arguments
    }

    private static func error(_ message: String) -> Error {
        NSError(domain: "CastTranscoder", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
