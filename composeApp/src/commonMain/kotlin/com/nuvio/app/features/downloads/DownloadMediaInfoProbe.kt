package com.nuvio.app.features.downloads

/**
 * Reads what is actually inside a finished download.
 *
 * A separate seam from [com.nuvio.app.features.cast.probeCastMedia] because the two answer
 * different questions. The Cast prober only has to establish what a receiver would need to
 * negotiate, so MediaExtractor is enough for it; this one is shown to a person, and MediaExtractor
 * cannot see codec profiles, HDR flavour, subtitle tracks or track titles at all.
 *
 * Android prefers MediaInfoLib when the build carries it and falls back to the Cast prober
 * otherwise. iOS goes straight to the Cast prober, which is FFprobe there and already reports
 * most of the same detail.
 */
internal expect suspend fun probeDownloadMediaInfo(localFileUri: String): DownloadMediaInfo?
