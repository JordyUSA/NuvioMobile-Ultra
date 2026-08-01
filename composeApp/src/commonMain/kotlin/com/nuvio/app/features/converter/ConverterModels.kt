package com.nuvio.app.features.converter

import com.nuvio.app.features.cast.CastDeliveryPlan
import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlinx.serialization.Serializable

/**
 * A conversion is planned as a [CastDeliveryPlan] and executed by the same processors the cast
 * pipeline uses. The alias exists only so the converter's own code does not read as though it is
 * casting something; renaming the type itself would churn the planner, both platform processors,
 * both probers, the iOS bridge and the cast tests for no behavioural gain.
 */
internal typealias MediaPlan = CastDeliveryPlan

/**
 * What the user picked in the convert sheet.
 *
 * [ConversionPreset.Custom] is never offered as a chip — it is what the selection becomes the
 * moment an advanced control is touched, so the sheet can show that the preset no longer
 * describes what will actually run.
 */
@Serializable
enum class ConversionPreset {
    UniversalMp4,
    PhoneSpaceSaver,
    HighQuality1080p,
    AudioOnly,
    RemuxOnly,
    Custom,
}

/** What to do with subtitle tracks the source container carries. */
@Serializable
enum class SubtitleHandling {
    /** Drop them. Text tracks survive as the sidecar files downloads already caches. */
    Drop,

    /** Keep them when the target container can hold them; bitmap tracks are dropped regardless. */
    KeepIfCompatible,
}

/**
 * A platform-neutral description of the wanted output.
 *
 * Deliberately expressed in the same enums the probe reports ([CastVideoCodec] and friends) so
 * that [ConversionPlanner] is a straight mapping rather than a translation table, and so the
 * advanced UI can build its option lists out of the very values a probe can produce.
 *
 * A null codec means "copy this track untouched" — that is what makes the remux preset cheap, and
 * it maps directly onto the null targets [CastDeliveryPlan] already understands.
 */
@Serializable
data class ConversionSpec(
    val container: CastContainer = CastContainer.MP4,
    val videoCodec: CastVideoCodec? = CastVideoCodec.H264,
    /** Downscale ceiling. Null keeps the source resolution; never upscales. */
    val maxHeight: Int? = 1080,
    /** Null derives a bitrate from the output height. */
    val videoBitrateBitsPerSecond: Long? = null,
    val maxFrameRate: Int? = null,
    val audioCodec: CastAudioCodec? = CastAudioCodec.AAC,
    val audioBitrateBitsPerSecond: Long = 192_000L,
    /** 0 keeps the source channel count. */
    val audioChannelCount: Int = 2,
    val subtitles: SubtitleHandling = SubtitleHandling.Drop,
    val preferHardwareEncoder: Boolean = true,
    /** True drops the video track entirely, for the audio-only preset. */
    val dropVideo: Boolean = false,
)

@Serializable
enum class ConversionStatus {
    Queued,
    Probing,
    Running,
    Completed,
    Failed,
    Cancelled,
}

val ConversionStatus.isTerminal: Boolean
    get() = this == ConversionStatus.Completed ||
        this == ConversionStatus.Failed ||
        this == ConversionStatus.Cancelled

val ConversionStatus.isActive: Boolean
    get() = !isTerminal

@Serializable
data class ConversionJob(
    val id: String,
    val sourceDownloadId: String,
    /**
     * Title and artwork are denormalized rather than looked up from the download, so a queue row
     * still renders after the source download has been deleted out from under it.
     */
    val title: String,
    val poster: String? = null,
    val preset: ConversionPreset,
    val spec: ConversionSpec,
    val replaceOriginal: Boolean = false,
    val status: ConversionStatus,
    /** 0..100, or -1 when the engine cannot estimate it. */
    val progressPercent: Int = 0,
    val outputFileName: String,
    val outputLocalFileUri: String? = null,
    val sourceDurationMs: Long? = null,
    val outputBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    val isActive: Boolean
        get() = status.isActive

    val progressFraction: Float
        get() = if (progressPercent < 0) 0f else (progressPercent / 100f).coerceIn(0f, 1f)

    val hasIndeterminateProgress: Boolean
        get() = progressPercent < 0 || status == ConversionStatus.Probing
}

data class ConverterUiState(
    val jobs: List<ConversionJob> = emptyList(),
) {
    val activeJobs: List<ConversionJob>
        get() = jobs.filter { it.isActive }

    val hasActiveJobs: Boolean
        get() = jobs.any { it.isActive }

    fun jobsForDownload(downloadId: String): List<ConversionJob> =
        jobs.filter { it.sourceDownloadId == downloadId }

    fun hasActiveJobForDownload(downloadId: String): Boolean =
        jobs.any { it.sourceDownloadId == downloadId && it.isActive }
}

/** The extension a container is written with. Drives both the output filename and the muxer. */
fun CastContainer.fileExtension(audioOnly: Boolean = false): String = when (this) {
    CastContainer.MP4, CastContainer.QUICKTIME -> if (audioOnly) "m4a" else "mp4"
    CastContainer.WEBM -> "webm"
    CastContainer.MATROSKA -> "mkv"
    CastContainer.MPEG_TS -> "ts"
    CastContainer.AVI -> "avi"
    // Neither adaptive format is a conversion target; the planner never selects them, so this is
    // only ever a defensive fallback.
    CastContainer.HLS, CastContainer.DASH, CastContainer.UNKNOWN -> "mp4"
}

/** Human-facing codec labels for the advanced sheet. Not localized: these are format names. */
fun CastVideoCodec.displayLabel(): String = when (this) {
    CastVideoCodec.H264 -> "H.264"
    CastVideoCodec.HEVC -> "HEVC (H.265)"
    CastVideoCodec.VP8 -> "VP8"
    CastVideoCodec.VP9 -> "VP9"
    CastVideoCodec.AV1 -> "AV1"
    CastVideoCodec.MPEG2 -> "MPEG-2"
    CastVideoCodec.MPEG4 -> "MPEG-4"
    CastVideoCodec.VC1 -> "VC-1"
    CastVideoCodec.UNKNOWN -> "Unknown"
}

fun CastAudioCodec.displayLabel(): String = when (this) {
    CastAudioCodec.AAC -> "AAC"
    CastAudioCodec.MP3 -> "MP3"
    CastAudioCodec.OPUS -> "Opus"
    CastAudioCodec.VORBIS -> "Vorbis"
    CastAudioCodec.FLAC -> "FLAC"
    CastAudioCodec.PCM -> "PCM"
    CastAudioCodec.AC3 -> "Dolby Digital"
    CastAudioCodec.EAC3 -> "Dolby Digital Plus"
    CastAudioCodec.DTS -> "DTS"
    CastAudioCodec.DTS_HD -> "DTS-HD"
    CastAudioCodec.TRUEHD -> "Dolby TrueHD"
    CastAudioCodec.UNKNOWN -> "Unknown"
}

fun CastContainer.displayLabel(): String = when (this) {
    CastContainer.MP4 -> "MP4"
    CastContainer.QUICKTIME -> "MOV"
    CastContainer.WEBM -> "WebM"
    CastContainer.MATROSKA -> "MKV"
    CastContainer.MPEG_TS -> "TS"
    CastContainer.AVI -> "AVI"
    CastContainer.HLS -> "HLS"
    CastContainer.DASH -> "DASH"
    CastContainer.UNKNOWN -> "Unknown"
}
