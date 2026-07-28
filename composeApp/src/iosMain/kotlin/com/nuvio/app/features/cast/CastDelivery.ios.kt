package com.nuvio.app.features.cast

import com.nuvio.app.features.cast.model.CastMediaProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS counterparts to the Cast expectations.
 *
 * Inert for the same reason as [CastPlatform]: there is no Cast SDK on this platform yet, and
 * the shared UI gates every entry point on `CastPlatform.isSupported`, so none of this is
 * reachable at runtime. They exist so commonMain continues to compile for iOS.
 */
actual object CastDelivery {

    private val _status = MutableStateFlow<CastDeliveryStatus>(CastDeliveryStatus.Idle)
    actual val status: StateFlow<CastDeliveryStatus> = _status.asStateFlow()

    actual suspend fun cast(request: CastStreamRequest): Result<Unit> =
        Result.failure(UnsupportedOperationException("Casting is not available on iOS"))

    actual fun cancel() = Unit
}

actual suspend fun probeCastMedia(
    url: String,
    headers: Map<String, String>,
): Result<CastMediaProbe> =
    Result.failure(UnsupportedOperationException("Casting is not available on iOS"))
