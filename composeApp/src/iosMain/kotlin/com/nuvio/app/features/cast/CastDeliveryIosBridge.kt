package com.nuvio.app.features.cast

/**
 * Implemented in Swift, over `CastLocalServer` (Network.framework).
 *
 * Same reasoning as `CastIosBridge`: kept to scalars, with header maps flattened to parallel
 * name/value lists, so the generated Objective-C header stays simple to call and Kotlin never
 * needs to know how the server actually listens for connections.
 */
interface CastLocalServerBridge {
    /** Returns the URL to hand the receiver, or null when no usable LAN address is available. */
    fun publishLocalFile(id: String, filePath: String, contentType: String): String?

    fun publishProxy(
        id: String,
        url: String,
        contentType: String,
        headerNames: List<String>,
        headerValues: List<String>,
    ): String?

    fun unpublish(id: String)
}

/**
 * Implemented in Swift, over `CastTranscoder` (FFmpegKit).
 *
 * One transcode runs at a time, matching `CastMediaProcessor` on Android, so there is no
 * request id here — `start` replaces whatever was running. Completion and progress are reported
 * back through `CastDelivery.onTranscodeProgress`/`onTranscodeCompleted` rather than a callback
 * parameter, for the same reason `CastPlatform.load` resolves through `onLoadResult`: a Swift
 * closure held across the Kotlin/Objective-C boundary is a needless second place for the
 * lifetime of this call to go wrong.
 */
interface CastTranscoderBridge {
    fun start(
        sourceUrl: String,
        headerNames: List<String>,
        headerValues: List<String>,
        outputPath: String,
        /** False means "copy the video track untouched"; the fields below are then ignored. */
        reencodeVideo: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        videoBitrateBitsPerSecond: Long,
        /** 0 means "let the encoder keep the source rate". */
        videoFrameRate: Float,
        /** False means "copy the audio track untouched"; the fields below are then ignored. */
        reencodeAudio: Boolean,
        audioChannelCount: Int,
        audioBitrateBitsPerSecond: Long,
        durationMs: Long,
    )

    fun cancel()
}

/**
 * Implemented in Swift, over `CastTranscoder`'s FFprobe entry point.
 *
 * One probe in flight at a time, matching `CastTranscoderBridge`, so there is no request id here
 * either — completion is reported back through `CastProberHost.onCompleted` for the same "no
 * closure held across the Kotlin/Objective-C boundary" reason described on
 * `CastTranscoderBridge`. Every field Swift hands back is a raw FFprobe value (a codec short
 * name, a color-transfer string, ...); `CastMediaProber.ios.kt` does the classification into
 * Cast's shared enums, so this interface only has to describe what FFprobe reports, not what it
 * means.
 */
interface CastProberBridge {
    fun probe(
        sourceUrl: String,
        headerNames: List<String>,
        headerValues: List<String>,
    )
}
