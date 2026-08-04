package com.nuvio.app.features.downloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Turns MediaInfoLib's JSON report into [DownloadMediaInfo].
 *
 * MediaInfoLib already serialises everything it found, so the native bridge is one call and all
 * of the interpretation happens here — in common code, where it is testable and where the iOS
 * side can adopt it unchanged once MediaInfoLib is built for that platform too.
 *
 * Written defensively on purpose. MediaInfo emits every value as a string, occasionally as an
 * array when a track carries several, and the exact key set moves between releases; a field this
 * parser does not recognise has to degrade to "not shown", never to a crash, because this is
 * advisory UI sitting on top of a file the user already has.
 */
private val mediaInfoJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun parseMediaInfoJson(json: String): DownloadMediaInfo? {
    val root = runCatching { mediaInfoJson.parseToJsonElement(json) }.getOrNull() as? JsonObject
        ?: return null
    val media = root["media"] as? JsonObject ?: return null
    val tracks = (media["track"]).asObjectList()

    val general = tracks.firstOrNull { it.str("@type") == "General" } ?: return null
    val video = tracks.firstOrNull { it.str("@type") == "Video" }
    val audio = tracks.filter { it.str("@type") == "Audio" }
    val text = tracks.filter { it.str("@type") == "Text" }

    return DownloadMediaInfo(
        container = containerNameFor(general.str("Format")),
        // MediaInfo reports duration in fractional seconds. General carries it for the file;
        // a video-only track duration is the fallback for containers that omit it up top.
        durationMs = (general.str("Duration") ?: video?.str("Duration"))
            ?.toDoubleOrNull()
            ?.let { (it * 1000.0).toLong() }
            ?.takeIf { it > 0L },
        video = video?.let { track ->
            DownloadVideoStreamInfo(
                codec = videoCodecNameFor(track.str("Format")),
                width = track.str("Width")?.toIntOrNull() ?: 0,
                height = track.str("Height")?.toIntOrNull() ?: 0,
                frameRate = track.str("FrameRate")?.toFloatOrNull()?.takeIf { it > 0f },
                bitrateBitsPerSecond = (track.str("BitRate") ?: track.str("BitRate_Nominal"))
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L },
                dynamicRange = dynamicRangeFor(track),
                bitDepth = track.str("BitDepth")?.toIntOrNull()?.takeIf { it > 0 } ?: 8,
                profile = track.str("Format_Profile"),
            )
        },
        audioTracks = audio.mapIndexed { index, track ->
            DownloadAudioStreamInfo(
                codec = audioCodecNameFor(track),
                channelCount = track.str("Channels")?.toIntOrNull() ?: 0,
                sampleRateHz = track.str("SamplingRate")?.toIntOrNull()?.takeIf { it > 0 },
                bitrateBitsPerSecond = (track.str("BitRate") ?: track.str("BitRate_Nominal"))
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L },
                language = track.str("Language"),
                // A container with no explicit default plays its first track, so treating
                // track zero as default matches what the player will actually do.
                isDefault = track.str("Default") == "Yes" || (index == 0 && audio.size == 1),
            )
        },
        subtitleTracks = text.map { track ->
            val format = track.str("Format")
            DownloadSubtitleStreamInfo(
                language = track.str("Language"),
                label = track.str("Title") ?: format,
                isBitmap = format != null && format in bitmapSubtitleFormats,
            )
        },
    )
}

/**
 * Kept aligned with the CastContainer names the iOS FFprobe path produces, so the media info
 * sheet reads the same on both platforms regardless of which prober filled it in.
 */
private fun containerNameFor(format: String?): String = when (format) {
    "Matroska" -> "MATROSKA"
    "MPEG-4" -> "MP4"
    "QuickTime" -> "QUICKTIME"
    "WebM" -> "WEBM"
    "AVI" -> "AVI"
    "MPEG-TS" -> "MPEG_TS"
    "HLS" -> "HLS"
    "DASH", "MPEG-DASH" -> "DASH"
    null -> "UNKNOWN"
    else -> format
}

/**
 * Maps onto CastVideoCodec's names where one exists, and otherwise passes MediaInfo's own name
 * through: the sheet renders an unrecognised codec verbatim, which tells the user more than
 * collapsing it to "UNKNOWN" would.
 */
private fun videoCodecNameFor(format: String?): String = when (format) {
    "HEVC" -> "HEVC"
    "AVC" -> "H264"
    "VP9" -> "VP9"
    "VP8" -> "VP8"
    "AV1" -> "AV1"
    "MPEG Video" -> "MPEG2"
    "MPEG-4 Visual" -> "MPEG4"
    "VC-1" -> "VC1"
    null -> "UNKNOWN"
    else -> format
}

private fun audioCodecNameFor(track: JsonObject): String {
    val format = track.str("Format") ?: return "UNKNOWN"
    val commercial = track.str("Format_Commercial_IfAny").orEmpty()
    return when {
        // DTS-HD MA and DTS:X both report Format "DTS"; only the commercial name distinguishes
        // a lossless track from the core it is built on.
        format == "DTS" && commercial.contains("Master Audio") -> "DTS_HD"
        format == "DTS" && commercial.contains("DTS:X") -> "DTS_HD"
        format == "DTS" -> "DTS"
        format == "MLP FBA" -> "TRUEHD"
        format == "E-AC-3" -> "EAC3"
        format == "AC-3" -> "AC3"
        format == "AAC" -> "AAC"
        format == "MPEG Audio" -> "MP3"
        format == "Opus" -> "OPUS"
        format == "Vorbis" -> "VORBIS"
        format == "FLAC" -> "FLAC"
        format == "PCM" -> "PCM"
        else -> format
    }
}

/**
 * Prefers the name a release would use ("Dolby Vision", "HDR10+"). Falls back to the transfer
 * function, which is what actually decides how the frame is tone-mapped and is present on plain
 * HDR10 files that carry no HDR_Format at all.
 */
private fun dynamicRangeFor(track: JsonObject): String {
    track.str("HDR_Format_Commercial")?.let { return it }
    track.str("HDR_Format")?.let { return it }
    val transfer = track.str("transfer_characteristics").orEmpty()
    return when {
        transfer.contains("PQ") -> "HDR10"
        transfer.contains("HLG") -> "HLG"
        else -> "SDR"
    }
}

private val bitmapSubtitleFormats = setOf("PGS", "VobSub", "DVB Subtitle", "ARIB STD-B24")

/** MediaInfo emits a single track as a one-element array, but not every writer of this JSON does. */
private fun JsonElement?.asObjectList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

/** Blank is the same as absent here: MediaInfo emits empty strings for fields it could not read. */
private fun JsonObject.str(key: String): String? =
    this[key]?.firstScalar()?.takeIf { it.isNotBlank() }

private fun JsonElement.firstScalar(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonArray -> firstNotNullOfOrNull { it.firstScalar() }
    else -> null
}
