package com.nuvio.app.features.converter

/**
 * Implemented in Swift, over `VideoConverter` (FFmpegKit).
 *
 * Same scalars-only shape as `CastTranscoderBridge`, and for the same reason: keeping the
 * generated Objective-C header to primitives and flat string lists is what has kept this interop
 * surface stable, where richer signatures have not been.
 *
 * Unlike the cast transcoder this carries a [jobId]. The converter has a queue, so a completion
 * has to be correlated with the job it belongs to — a late callback from a job the user cancelled
 * must not be mistaken for the current one finishing.
 */
interface ConverterBridge {
    fun start(
        jobId: String,
        sourceUrl: String,
        outputPath: String,
        /** "mp4", "matroska" or "webm". */
        container: String,
        dropVideo: Boolean,
        /** False means "copy the video track untouched"; the video fields below are then ignored. */
        reencodeVideo: Boolean,
        /** "h264" or "hevc". */
        videoCodec: String,
        videoWidth: Int,
        videoHeight: Int,
        videoBitrateBitsPerSecond: Long,
        /** 0 means "let the encoder keep the source rate". */
        videoFrameRate: Float,
        preferHardwareEncoder: Boolean,
        /** False means "copy the audio track untouched"; the audio fields below are then ignored. */
        reencodeAudio: Boolean,
        /** "aac". */
        audioCodec: String,
        audioChannelCount: Int,
        audioBitrateBitsPerSecond: Long,
        keepSubtitles: Boolean,
        durationMs: Long,
    )

    fun cancel(jobId: String)

    /**
     * Whitespace-separated FFmpeg encoder names, as reported by `-encoders`.
     *
     * Returned as one string rather than a list for the same interop-simplicity reason as the rest
     * of this interface; Kotlin does the parsing.
     */
    fun probeEncoderNames(): String
}

/**
 * Where Swift reports back to. Mirrors `CastProberHost`: completion travels through a host object
 * rather than a Swift closure held across the Kotlin/Objective-C boundary, which is one less place
 * for the lifetime of a call to go wrong.
 */
object ConverterHost {

    private var bridge: ConverterBridge? = null
    private var listener: ConverterCallbackListener? = null

    fun attach(bridge: ConverterBridge) {
        this.bridge = bridge
    }

    internal fun requireBridge(): ConverterBridge? = bridge

    internal fun setListener(listener: ConverterCallbackListener?) {
        this.listener = listener
    }

    /**
     * [etaMs]/[speedMultiplier]/[fps] cross the Kotlin/Objective-C boundary as sentinel-bearing
     * primitives rather than nullables, matching the scalars-only discipline the rest of this
     * bridge already follows: -1 means "not available this tick".
     */
    fun onProgress(jobId: String, percent: Int, etaMs: Long, speedMultiplier: Float, fps: Float) {
        listener?.onProgress(jobId, percent, etaMs, speedMultiplier, fps)
    }

    fun onCompleted(jobId: String, success: Boolean, message: String?) {
        listener?.onCompleted(jobId, success, message)
    }
}

internal interface ConverterCallbackListener {
    fun onProgress(jobId: String, percent: Int, etaMs: Long, speedMultiplier: Float, fps: Float)
    fun onCompleted(jobId: String, success: Boolean, message: String?)
}
