package com.nuvio.app.features.downloads

import com.nuvio.app.features.cast.probeCastMedia

/**
 * FFprobe, through the Cast prober's iOS actual. FFmpegKit is already vendored for this platform
 * and reports codecs, profiles, bit depth and subtitle tracks, so MediaInfoLib would add little
 * here beyond a second native dependency; [parseMediaInfoJson] is common code and waiting for it
 * if that ever changes.
 */
internal actual suspend fun probeDownloadMediaInfo(
    localFileUri: String,
): DownloadMediaInfo? = probeCastMedia(localFileUri).getOrNull()?.toDownloadMediaInfo()
