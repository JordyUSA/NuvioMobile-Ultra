import Foundation
import ComposeApp

/// Implements the Kotlin `CastLocalServerBridge`, `CastTranscoderBridge` and `CastProberBridge`,
/// over `CastLocalServer` and `CastTranscoder`.
///
/// Same shape as `CastBridge`: the Kotlin side (`CastDelivery.ios.kt`, `CastMediaProber.ios.kt`)
/// drives these exactly as it drives Android's local server, media processor and prober, without
/// either FFmpegKit or Network.framework needing to be visible to the Kotlin compilation.
final class CastDeliveryBridge: NSObject, CastLocalServerBridge, CastTranscoderBridge, CastProberBridge {

    private let server = CastLocalServer()
    private let transcoder = CastTranscoder()

    @discardableResult
    static func install() -> CastDeliveryBridge {
        let bridge = CastDeliveryBridge()
        CastDeliveryBridgeRegistrationKt.registerCastLocalServerBridge(bridge: bridge)
        CastDeliveryBridgeRegistrationKt.registerCastTranscoderBridge(bridge: bridge)
        CastDeliveryBridgeRegistrationKt.registerCastProberBridge(bridge: bridge)
        return bridge
    }

    // MARK: - CastLocalServerBridge

    func publishLocalFile(id: String, filePath: String, contentType: String) -> String? {
        server.publish(id: id, payload: .localFile(path: filePath, contentType: contentType))
    }

    func publishProxy(id: String, url: String, contentType: String, headerNames: [String], headerValues: [String]) -> String? {
        server.publish(id: id, payload: .proxy(url: url, contentType: contentType, headers: Self.zip(headerNames, headerValues)))
    }

    func unpublish(id: String) {
        server.unpublish(id: id)
    }

    // MARK: - CastTranscoderBridge

    func start(
        sourceUrl: String,
        headerNames: [String],
        headerValues: [String],
        outputPath: String,
        reencodeVideo: Bool,
        videoWidth: Int32,
        videoHeight: Int32,
        videoBitrateBitsPerSecond: Int64,
        videoFrameRate: Float,
        reencodeAudio: Bool,
        audioChannelCount: Int32,
        audioBitrateBitsPerSecond: Int64,
        durationMs: Int64
    ) {
        let videoTarget: CastTranscoder.VideoTarget? = reencodeVideo
            ? CastTranscoder.VideoTarget(
                width: Int(videoWidth), height: Int(videoHeight),
                bitrateBitsPerSecond: videoBitrateBitsPerSecond, frameRate: videoFrameRate
            )
            : nil
        let audioTarget: CastTranscoder.AudioTarget? = reencodeAudio
            ? CastTranscoder.AudioTarget(channelCount: Int(audioChannelCount), bitrateBitsPerSecond: audioBitrateBitsPerSecond)
            : nil

        transcoder.process(
            sourceUrl: sourceUrl,
            headers: Self.zip(headerNames, headerValues),
            outputPath: outputPath,
            videoTarget: videoTarget,
            audioTarget: audioTarget,
            durationMs: durationMs,
            onProgress: { percent in
                CastDelivery.shared.onTranscodeProgress(percent: Int32(percent))
            },
            completion: { result in
                switch result {
                case .success:
                    CastDelivery.shared.onTranscodeCompleted(success: true, message: nil)
                case let .failure(error):
                    CastDelivery.shared.onTranscodeCompleted(success: false, message: error.localizedDescription)
                }
            }
        )
    }

    func cancel() {
        transcoder.cancel()
    }

    // MARK: - CastProberBridge

    func probe(sourceUrl: String, headerNames: [String], headerValues: [String]) {
        transcoder.probe(sourceUrl: sourceUrl, headers: Self.zip(headerNames, headerValues)) { result in
            switch result {
            case let .success(info):
                CastProberHost.shared.onCompleted(
                    success: true,
                    message: nil,
                    durationMs: info.durationMs,
                    hasVideo: info.hasVideo,
                    videoCodec: info.videoCodec,
                    videoWidth: Int32(info.videoWidth),
                    videoHeight: Int32(info.videoHeight),
                    videoFrameRate: info.videoFrameRate,
                    videoBitrateBitsPerSecond: info.videoBitrateBitsPerSecond,
                    videoProfile: info.videoProfile,
                    videoPixelFormat: info.videoPixelFormat,
                    videoBitsPerRawSample: info.videoBitsPerRawSample,
                    videoColorTransfer: info.videoColorTransfer,
                    audioCodecs: info.audioCodecs,
                    audioChannelCounts: info.audioChannelCounts.map { String($0) },
                    audioSampleRates: info.audioSampleRates.map { String($0) },
                    audioBitrates: info.audioBitrates.map { String($0) },
                    audioLanguages: info.audioLanguages
                )
            case let .failure(error):
                CastProberHost.shared.onCompleted(
                    success: false,
                    message: error.localizedDescription,
                    durationMs: 0,
                    hasVideo: false,
                    videoCodec: "",
                    videoWidth: 0,
                    videoHeight: 0,
                    videoFrameRate: 0,
                    videoBitrateBitsPerSecond: 0,
                    videoProfile: "",
                    videoPixelFormat: "",
                    videoBitsPerRawSample: "",
                    videoColorTransfer: "",
                    audioCodecs: [],
                    audioChannelCounts: [],
                    audioSampleRates: [],
                    audioBitrates: [],
                    audioLanguages: []
                )
            }
        }
    }

    // MARK: -

    private static func zip(_ names: [String], _ values: [String]) -> [String: String] {
        guard names.count == values.count else { return [:] }
        return Dictionary(uniqueKeysWithValues: Swift.zip(names, values))
    }
}
