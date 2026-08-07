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
 * unsynchronised read-modify-write below safe.
 *
 * A ring buffer, not a file: the interesting window is the seconds around an empty scan, and a
 * bounded buffer can run forever — verbose Cast SDK logging included — without care.
 */
object CastDiagnostics {

    private const val MAX_LINES = 1500

    private val started = TimeSource.Monotonic.markNow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        val elapsed = started.elapsedNow().inWholeMilliseconds
        val minutes = elapsed / 60_000
        val seconds = ((elapsed / 1000) % 60).toString().padStart(2, '0')
        val millis = (elapsed % 1000).toString().padStart(3, '0')
        val next = _lines.value + "$minutes:$seconds.$millis [$tag] $message"
        _lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = _lines.value.joinToString("\n")
}
