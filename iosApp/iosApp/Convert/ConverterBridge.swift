import Foundation
import ComposeApp

/// Implements the Kotlin `ConverterBridge` over `VideoConverter`.
///
/// Same shape as `CastDeliveryBridge`: the Kotlin side (`ConversionEngine.ios.kt`) drives this
/// exactly as Android drives its own engine, without FFmpegKit needing to be visible to the Kotlin
/// compilation.
final class ConverterBridgeImpl: NSObject, ConverterBridge {

    private let converter = VideoConverter()

    @discardableResult
    static func install() -> ConverterBridgeImpl {
        let bridge = ConverterBridgeImpl()
        ConverterBridgeRegistrationKt.registerConverterBridge(bridge: bridge)
        return bridge
    }

    func start(
        jobId: String,
        sourceUrl: String,
        outputPath: String,
        container: String,
        dropVideo: Bool,
        reencodeVideo: Bool,
        videoCodec: String,
        videoWidth: Int32,
        videoHeight: Int32,
        videoBitrateBitsPerSecond: Int64,
        videoFrameRate: Float,
        preferHardwareEncoder: Bool,
        reencodeAudio: Bool,
        audioCodec: String,
        audioChannelCount: Int32,
        audioBitrateBitsPerSecond: Int64,
        keepSubtitles: Bool,
        durationMs: Int64
    ) {
        let request = VideoConverter.Request(
            jobId: jobId,
            sourceUrl: sourceUrl,
            outputPath: outputPath,
            container: container,
            dropVideo: dropVideo,
            reencodeVideo: reencodeVideo,
            videoCodec: videoCodec,
            videoWidth: Int(videoWidth),
            videoHeight: Int(videoHeight),
            videoBitrateBitsPerSecond: videoBitrateBitsPerSecond,
            videoFrameRate: videoFrameRate,
            preferHardwareEncoder: preferHardwareEncoder,
            reencodeAudio: reencodeAudio,
            audioCodec: audioCodec,
            audioChannelCount: Int(audioChannelCount),
            audioBitrateBitsPerSecond: audioBitrateBitsPerSecond,
            keepSubtitles: keepSubtitles,
            durationMs: durationMs
        )

        converter.process(
            request: request,
            onProgress: { percent in
                ConverterHost.shared.onProgress(jobId: jobId, percent: Int32(percent))
            },
            completion: { result in
                switch result {
                case .success:
                    ConverterHost.shared.onCompleted(jobId: jobId, success: true, message: nil)
                case let .failure(error):
                    ConverterHost.shared.onCompleted(
                        jobId: jobId,
                        success: false,
                        message: error.localizedDescription
                    )
                }
            }
        )
    }

    func cancel(jobId: String) {
        converter.cancel(jobId: jobId)
    }

    func probeEncoderNames() -> String {
        converter.encoderNames()
    }
}
