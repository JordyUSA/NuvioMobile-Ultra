package com.nuvio.app.features.converter

/**
 * Suffix the engine writes under before renaming on success.
 *
 * Shared rather than private to each actual because the repository sweeps orphaned work files on
 * launch, and a mismatch between what the engine writes and what the sweep looks for would leave
 * dead bytes on disk after every crash.
 */
internal const val CONVERSION_WORK_SUFFIX = ".converting"

/** Where the finished file landed. [sizeBytes] is null when the platform could not stat it. */
internal data class ConversionOutput(
    val localFileUri: String,
    val sizeBytes: Long?,
)

/**
 * Runs one conversion at a time.
 *
 * Singleton rather than an injectable interface because the thing it wraps is itself a scarce
 * global: a hardware encoder session. `Media3CastMediaProcessor` holds a single `Transformer`
 * field and FFmpegKit's Android API is static, so there is nothing to be gained from allowing two
 * instances and a lot to be confused by.
 *
 * The engine writes to `<outputFileName>.converting` and only renames on success, so a crash or a
 * cancellation can never leave a truncated file wearing a real name.
 */
internal expect object ConversionEngine {

    /**
     * [onProgress] reports 0..100 in [ConversionProgress.percent], or -1 when the backend cannot
     * estimate — Media3's Transformer genuinely cannot for some inputs, so the UI must handle an
     * indeterminate bar. The remaining fields are FFmpeg-only; Media3 leaves them null.
     */
    suspend fun convert(
        sourceLocalFileUri: String,
        plan: MediaPlan,
        dropVideo: Boolean,
        /**
         * Honoured only where the backend can mux subtitles — Media3's muxer cannot, so on a build
         * without FFmpeg this is a no-op and the sheet does not offer the control.
         */
        keepSubtitles: Boolean,
        outputFileName: String,
        durationMs: Long?,
        preferHardwareEncoder: Boolean,
        onProgress: (ConversionProgress) -> Unit,
    ): Result<ConversionOutput>

    fun cancel()
}
