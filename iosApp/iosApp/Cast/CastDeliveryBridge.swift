import Foundation
import ComposeApp

/// Implements the Kotlin `CastLocalServerBridge` and `CastTranscoderBridge`, over
/// `CastLocalServer` and `CastTranscoder`.
///
/// Same shape as `CastBridge`: the Kotlin side (`CastDelivery.ios.kt`) drives these exactly as
/// it drives Android's local server and media processor, without either FFmpegKit or
/// Network.framework needing to be visible to the Kotlin compilation.
final class CastDeliveryBridge: NSObject, CastLocalServerBridge, CastTranscoderBridge {

    private let server = CastLocalServer()
    private let transcoder = CastTranscoder()

    @discardableResult
    static func install() -> CastDeliveryBridge {
        let bridge = CastDeliveryBridge()
        CastDeliveryBridgeRegistrationKt.registerCastLocalServerBridge(bridge: bridge)
        CastDeliveryBridgeRegistrationKt.registerCastTranscoderBridge(bridge: bridge)
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

    // MARK: -

    private static func zip(_ names: [String], _ values: [String]) -> [String: String] {
        guard names.count == values.count else { return [:] }
        return Dictionary(uniqueKeysWithValues: Swift.zip(names, values))
    }
}
