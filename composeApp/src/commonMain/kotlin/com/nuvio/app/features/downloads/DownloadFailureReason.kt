package com.nuvio.app.features.downloads

import com.nuvio.app.core.i18n.localizedDownloadFailureReason
import kotlinx.serialization.Serializable

/**
 * Why a download stopped, in terms a user can act on.
 *
 * Failures used to surface `Throwable.message` verbatim, which meant an untranslated OkHttp or
 * NSURLError string — "unexpected end of stream", "The request timed out. (NSURLErrorDomain -1001)"
 * — in a list of movie titles. Those say nothing about what to do next.
 *
 * The raw text is not discarded; it moves to `DownloadItem.errorDetail`, behind the overflow
 * menu, where someone diagnosing a problem can still get at it.
 */
@Serializable
enum class DownloadFailureReason {
    /** The device has no usable network. Retrying now will fail the same way. */
    NoConnection,

    /** The server accepted the connection and then stopped responding. Retrying often works. */
    Timeout,

    /**
     * 401/403/410. Debrid and addon links are commonly time-limited, so the actionable advice is
     * to re-resolve the stream rather than to retry the dead URL.
     */
    LinkExpired,

    /** 404. The file is gone from the source; a different source is the only way forward. */
    NotFound,

    /** 5xx. Nothing wrong on this end. */
    ServerError,

    /** The write failed for lack of space. */
    OutOfStorage,

    /** The write failed for some other reason — permissions, a removed volume, a locked file. */
    FileWriteFailed,

    /** The stream cannot be downloaded at all, e.g. an adaptive manifest. */
    Unsupported,

    /** Genuinely unclassified. The detail text is the only clue, and it is kept. */
    Unknown,
    ;

    fun localizedText(): String = localizedDownloadFailureReason(name)
}

/**
 * Maps an HTTP status onto a reason, or null when the status is not itself the failure.
 */
fun downloadFailureReasonForHttpStatus(status: Int): DownloadFailureReason = when (status) {
    401, 403, 410 -> DownloadFailureReason.LinkExpired
    404 -> DownloadFailureReason.NotFound
    in 500..599 -> DownloadFailureReason.ServerError
    else -> DownloadFailureReason.Unknown
}
