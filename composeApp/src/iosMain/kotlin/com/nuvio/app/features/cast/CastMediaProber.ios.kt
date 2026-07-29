package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastMediaProbe

/**
 * No probe on iOS.
 *
 * A codec-level probe was attempted here across several `compileKotlinIosArm64` runs, built on
 * AVFoundation's `load<Property>WithCompletionHandler:` API (the real replacement for the
 * synchronous `AVAssetTrack` properties, which are absent from this project's Kotlin/Native
 * AVFoundation bindings entirely). The individual calls resolved, but the same code compiled in
 * one file arrangement and failed the same way in another — moving code that was never touched
 * between runs changed whether unrelated lines type-checked. That is a sign of a genuine
 * instability in this specific interop surface, not a wrong guess to iterate past, so it is not
 * implemented here.
 *
 * Returning a failure is the honest answer and is handled: callers fall back to reasoning from
 * the container alone. That still covers the common cases — Matroska gets remuxed, an
 * unreachable source gets served locally — just not a codec-driven re-encode decision, which
 * needs real track data this probe cannot safely provide.
 */
actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> = Result.failure(
    UnsupportedOperationException("Media probing is not implemented on iOS"),
)
