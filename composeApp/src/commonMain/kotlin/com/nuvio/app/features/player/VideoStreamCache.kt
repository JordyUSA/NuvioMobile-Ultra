package com.nuvio.app.features.player

/**
 * Scratch disk space for streaming playback, so returning to a backgrounded player does not
 * re-download what was already buffered.
 *
 * This cache is deliberately short-lived. It is wiped when the player closes, when the app
 * starts and when the app leaves the foreground, which means it never accumulates across
 * sessions — the start-up wipe is what recovers the space after a crash, when neither of the
 * other two ran.
 */
internal expect object VideoStreamCache {
    fun directoryPath(): String

    fun sizeBytes(): Long

    /**
     * Releases any handle held on the cache directory and empties it.
     *
     * Safe to call with no player running and safe to call repeatedly; the caller does not have
     * to know whether anything was cached.
     */
    fun clear()
}
