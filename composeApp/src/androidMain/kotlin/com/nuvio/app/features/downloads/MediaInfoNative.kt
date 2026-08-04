package com.nuvio.app.features.downloads

import android.util.Log

/**
 * MediaInfoLib, when the build carries it.
 *
 * The native library ships in an optional AAR produced by the build-mediainfo workflow, so it is
 * genuinely absent from an ordinary checkout. That is why nothing here is a compile-time
 * dependency: the JNI declaration compiles regardless, and the one thing that can fail — loading
 * the library — is checked once and turned into a boolean. Callers fall back to MediaExtractor.
 */
internal object MediaInfoNative {

    private val available: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY_NAME) }
            .onFailure {
                Log.i(TAG, "MediaInfoLib is not bundled in this build; using MediaExtractor instead")
            }
            .isSuccess
    }

    val isAvailable: Boolean get() = available

    /** MediaInfoLib's full report as JSON, or null if it could not read the file. */
    fun informJson(filePath: String): String? {
        if (!available) return null
        return runCatching {
            nativeInformJson(filePath.encodeToByteArray())?.decodeToString()
        }.onFailure {
            Log.w(TAG, "MediaInfo probe failed", it)
        }.getOrNull()
    }

    private external fun nativeInformJson(filePathUtf8: ByteArray): ByteArray?
}

private const val TAG = "MediaInfo"
private const val LIBRARY_NAME = "mediainfo_jni"
