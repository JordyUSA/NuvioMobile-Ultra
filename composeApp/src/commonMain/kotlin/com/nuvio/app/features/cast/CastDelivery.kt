package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.StateFlow

/** What the sender is doing on the way to getting pixels onto the television. */
sealed interface CastDeliveryStatus {
    data object Idle : CastDeliveryStatus

    data object Probing : CastDeliveryStatus

    /** Repackaging or re-encoding. [progress] is 0..100, or -1 when not estimable. */
    data class Preparing(
        val mode: CastDeliveryMode,
        val progress: Int,
        val reasons: List<CastIncompatibility>,
    ) : CastDeliveryStatus

    /** Handed off to the receiver. */
    data class Playing(val mode: CastDeliveryMode) : CastDeliveryStatus

    data class Failed(val message: String) : CastDeliveryStatus
}

data class CastStreamRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val startPositionMs: Long = 0L,
    val subtitles: List<CastSubtitleTrack> = emptyList(),
    /**
     * False for anything the receiver cannot fetch itself: loopback addresses from the torrent
     * engine, on-disk downloads, or origins that require request headers.
     */
    val sourceReachableByReceiver: Boolean = true,
)

/**
 * Drives a stream all the way to the receiver: probe it, decide how it must be delivered,
 * repackage or re-encode when necessary, serve it if the receiver cannot reach the original,
 * and finally load it.
 */
expect object CastDelivery {

    val status: StateFlow<CastDeliveryStatus>

    suspend fun cast(request: CastStreamRequest): Result<Unit>

    /** Cancels any in-flight preparation and releases the local server. */
    fun cancel()
}
