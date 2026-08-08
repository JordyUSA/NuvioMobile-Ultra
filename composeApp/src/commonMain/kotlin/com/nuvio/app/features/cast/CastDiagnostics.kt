package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

/**
 * In-app log for cast discovery and delivery, so a user on a sideloaded build — no Mac, no
 * Console.app — can copy what actually happened out of the device picker and report it.
 *
 * Swift writes into it through the generated framework (`CastDiagnostics.shared.log`), Kotlin
 * directly. Callers stay on the main thread, the discipline `CastBridge.swift` and
 * `DlnaTransport.swift` already keep for every call across the bridge, which is what makes the
 * unsynchronised state below safe.
 *
 * A ring buffer, not a file: the interesting window is the seconds around an empty scan, and a
 * bounded buffer can run forever without care.
 */
object CastDiagnostics {

    private const val MAX_LINES = 1500

    /**
     * Publishing rebuilds the whole snapshot, so it is rate limited rather than done per line.
     * The Cast SDK can log far faster than any UI could render — during playback it narrates
     * every socket read and media status — and rebuilding a 1500-line list on each of those
     * was enough main-thread work to get the app killed by the watchdog mid-stream.
     */
    private const val PUBLISH_INTERVAL_MS = 250L

    private val started = TimeSource.Monotonic.markNow()

    /** Appended to on every call; snapshots are taken from it rather than rebuilt per line. */
    private val buffer = ArrayDeque<String>()
    private var lastPublishMs = -PUBLISH_INTERVAL_MS

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val elapsed = started.elapsedNow().inWholeMilliseconds
        buffer.addLast("${timestamp(elapsed)} [$tag] $message")
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        if (elapsed - lastPublishMs >= PUBLISH_INTERVAL_MS) {
            lastPublishMs = elapsed
            publish()
        }
    }

    /**
     * Publishes whatever arrived since the last rate-limited snapshot. The viewer calls this on
     * a timer so the final lines of a burst are not left sitting in the buffer unseen.
     */
    fun flush() = publish()

    fun clear() {
        buffer.clear()
        publish()
    }

    fun dump(): String = buffer.joinToString("\n")

    private fun publish() {
        _lines.value = buffer.toList()
    }

    private fun timestamp(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000
        val seconds = ((elapsedMs / 1000) % 60).toString().padStart(2, '0')
        val millis = (elapsedMs % 1000).toString().padStart(3, '0')
        return "$minutes:$seconds.$millis"
    }
}
