package com.nuvio.app.core.share

/**
 * Hands a local file to the platform share sheet.
 *
 * Returns false when there is nothing to share or no way to present the sheet, so the caller can
 * show a toast rather than leaving the user tapping a button that silently does nothing.
 */
internal expect object FileShareBridge {
    fun shareFile(localFileUri: String, displayName: String): Boolean
}
