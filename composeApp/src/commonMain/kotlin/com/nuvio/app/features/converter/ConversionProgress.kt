package com.nuvio.app.features.converter

/**
 * A richer progress tick than a bare percentage.
 *
 * [percent] is 0..100, or -1 when the backend cannot estimate it at all (Media3 before its first
 * poll, or a probe step). [etaMs]/[speedMultiplier]/[fps] come straight from FFmpeg's `Statistics`
 * callback when the engine is FFmpeg; Media3's `Transformer` exposes no such detail, so on that
 * path they stay null and [ConverterRepository] derives an ETA from percent-over-time instead.
 */
data class ConversionProgress(
    val percent: Int,
    val etaMs: Long? = null,
    val speedMultiplier: Float? = null,
    val fps: Float? = null,
) {
    companion object {
        fun indeterminate(): ConversionProgress = ConversionProgress(percent = -1)
    }
}
