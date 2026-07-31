package com.nuvio.app.features.cast.dlna

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastReceiverCapabilities
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.CastVideoCodec
import com.nuvio.app.features.cast.model.capabilitiesFor

/**
 * Pure SSDP/UPnP-AV parsing and SOAP-building logic, shared by both platform actuals.
 *
 * Nothing here does network I/O — each platform's `DlnaPlatform` actual sends the bytes (over
 * `MulticastSocket`/`HttpURLConnection` on Android, over a Swift bridge on iOS) and hands the raw
 * response text back to these functions, the same "platform does I/O, Kotlin does parsing" split
 * `CastTranscoder.swift`/`CastMediaProber.ios.kt` already established for FFprobe. This is a
 * hand-rolled, deliberately narrow parser for the small set of tags UPnP device descriptions and
 * SOAP responses actually use — not a general XML parser.
 */

const val DLNA_MEDIA_RENDERER_SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
const val SSDP_ALL_SEARCH_TARGET = "ssdp:all"
const val DLNA_MULTICAST_ADDRESS = "239.255.255.250"
const val DLNA_MULTICAST_PORT = 1900

/**
 * The search targets sent on every discovery round.
 *
 * `MediaRenderer:1` alone is not enough in practice. A compliant device answers a search for
 * its own type, and one advertising `MediaRenderer:2`/`:3` is supposed to answer a `:1` search
 * too, but plenty of real televisions only reply reliably to `ssdp:all`. Sending both costs one
 * extra datagram and is the difference between a TV appearing and not.
 *
 * `ssdp:all` makes every UPnP device on the network answer — routers, printers, NAS boxes — so
 * whatever consumes these responses has to expect mostly non-renderers and cache the rejections
 * rather than re-fetching each one's description every round.
 */
val DLNA_SEARCH_TARGETS = listOf(DLNA_MEDIA_RENDERER_SEARCH_TARGET, SSDP_ALL_SEARCH_TARGET)

const val AV_TRANSPORT_SERVICE_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"
const val RENDERING_CONTROL_SERVICE_TYPE = "urn:schemas-upnp-org:service:RenderingControl:1"
const val CONNECTION_MANAGER_SERVICE_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"

// --- Discovery ---------------------------------------------------------------------------

/** An SSDP M-SEARCH response's `LOCATION` (device description URL) and `USN` (unique id). */
data class SsdpResponse(val location: String, val usn: String, val searchTarget: String? = null)

/**
 * An unsolicited `NOTIFY * HTTP/1.1` announcement, multicast by a device when it joins the
 * network (`ssdp:alive`) or leaves it cleanly (`ssdp:byebye`).
 *
 * [location] is only present on alive/update announcements; byebye carries just the [usn], so
 * the listener has to remember which location that USN was last seen at to act on it.
 */
data class SsdpNotification(val usn: String, val location: String?, val isAlive: Boolean)

/** An `M-SEARCH * HTTP/1.1` request body, sent as a UDP datagram to [DLNA_MULTICAST_ADDRESS]. */
fun buildSsdpSearchRequest(
    searchTarget: String = DLNA_MEDIA_RENDERER_SEARCH_TARGET,
    mx: Int = 3,
): String =
    "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $DLNA_MULTICAST_ADDRESS:$DLNA_MULTICAST_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mx\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n"

/** Parses a raw SSDP search-response datagram. Null when it isn't a usable renderer response. */
fun parseSsdpResponse(raw: String): SsdpResponse? {
    if (!raw.startsWith("HTTP/1.1 200", ignoreCase = true)) return null
    val headers = parseHttpHeaders(raw)
    val location = headers["location"] ?: return null
    val usn = headers["usn"] ?: location
    return SsdpResponse(location = location, usn = usn, searchTarget = headers["st"])
}

/**
 * Parses an unsolicited SSDP `NOTIFY` announcement. Null when it isn't one, or when it carries
 * too little to act on.
 *
 * Without this, a television only becomes visible on the next active search round — switching
 * one on while the picker is already open would otherwise leave it missing for a full search
 * interval, or indefinitely if its M-SEARCH replies keep getting dropped.
 */
fun parseSsdpNotify(raw: String): SsdpNotification? {
    if (!raw.startsWith("NOTIFY", ignoreCase = true)) return null
    val headers = parseHttpHeaders(raw)
    val usn = headers["usn"] ?: return null
    val isAlive = when (headers["nts"]?.trim()?.lowercase()) {
        // ssdp:update is a device changing its advertisement (a new boot id), not a departure.
        "ssdp:alive", "ssdp:update" -> true
        "ssdp:byebye" -> false
        else -> return null
    }
    val location = headers["location"]?.takeIf { it.isNotBlank() }
    if (isAlive && location == null) return null
    return SsdpNotification(usn = usn, location = location, isAlive = isAlive)
}

private fun parseHttpHeaders(raw: String): Map<String, String> =
    raw.lineSequence()
        .drop(1) // status line
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        .toMap()

// --- Device description -------------------------------------------------------------------

data class DlnaServiceInfo(val serviceType: String, val controlUrl: String)

data class DlnaDeviceDescription(
    val friendlyName: String,
    val modelName: String?,
    val udn: String,
    val services: List<DlnaServiceInfo>,
) {
    fun service(serviceType: String): DlnaServiceInfo? = services.firstOrNull { it.serviceType == serviceType }
}

/**
 * Parses a UPnP device description document. Only the root `<device>` is read — embedded
 * sub-devices (rare for a plain MediaRenderer) are not walked, a deliberate v1 simplification.
 */
fun parseDeviceDescription(xml: String, baseUrl: String): DlnaDeviceDescription? {
    val friendlyName = extractTag(xml, "friendlyName") ?: return null
    val modelName = extractTag(xml, "modelName")
    val udn = extractTag(xml, "UDN") ?: return null

    val services = Regex("(?s)<service[ >].*?</service>")
        .findAll(xml)
        .mapNotNull { match ->
            val block = match.value
            val serviceType = extractTag(block, "serviceType") ?: return@mapNotNull null
            val controlUrlRaw = extractTag(block, "controlURL") ?: return@mapNotNull null
            DlnaServiceInfo(serviceType = serviceType, controlUrl = resolveUrl(baseUrl, controlUrlRaw))
        }
        .toList()

    return DlnaDeviceDescription(friendlyName = friendlyName, modelName = modelName, udn = udn, services = services)
}

/** Resolves a possibly-relative UPnP URL (controlURL, eventSubURL, ...) against its device's own location. */
fun resolveUrl(baseUrl: String, reference: String): String {
    if (reference.startsWith("http://", ignoreCase = true) || reference.startsWith("https://", ignoreCase = true)) {
        return reference
    }
    val schemeEnd = baseUrl.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val authorityEnd = baseUrl.indexOf('/', schemeEnd).let { if (it < 0) baseUrl.length else it }
    val origin = baseUrl.substring(0, authorityEnd)
    if (reference.startsWith("/")) return origin + reference
    val basePath = baseUrl.substring(authorityEnd).substringBeforeLast('/', "")
    return "$origin$basePath/$reference"
}

// --- XML helpers ----------------------------------------------------------------------------

/**
 * The text content of the first `<tagName>` or namespaced `<ns:tagName>` element, XML-unescaped.
 */
fun extractTag(xml: String, tagName: String): String? {
    val regex = Regex("(?s)<(?:[\\w-]+:)?$tagName(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$tagName>")
    val match = regex.find(xml) ?: return null
    return unescapeXml(match.groupValues[1].trim())
}

fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

fun unescapeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

// --- DIDL-Lite --------------------------------------------------------------------------------

/**
 * Minimal DIDL-Lite item metadata for `SetAVTransportURI`'s `CurrentURIMetaData` argument.
 *
 * [subtitleUrl] adds an out-of-band subtitle track. There is no single way to do this that every
 * renderer honours, so all three of the conventions in circulation are emitted together — the
 * `sec:` attributes Samsung reads, a `<res>` entry with a subtitle `protocolInfo`, and the
 * `pv:subtitleFileUri` some others use. A renderer that recognises none of them ignores the extra
 * elements rather than rejecting the document, so emitting all three costs nothing.
 */
fun buildDidlLite(
    title: String,
    contentUrl: String,
    contentType: String,
    subtitleUrl: String? = null,
    subtitleMimeType: String = "text/vtt",
): String {
    val upnpClass = if (contentType.startsWith("audio/")) "object.item.audioItem" else "object.item.videoItem"
    val subtitleExtension = subtitleUrl?.substringAfterLast('.', "srt")?.takeIf { it.length in 1..5 } ?: "srt"
    return buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        append("xmlns:sec=\"http://www.sec.co.kr/\" ")
        append("xmlns:pv=\"http://www.pv.com/pvns/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>${escapeXml(title)}</dc:title>")
        append("<upnp:class>$upnpClass</upnp:class>")
        if (subtitleUrl != null) {
            append("<sec:CaptionInfoEx sec:type=\"$subtitleExtension\">${escapeXml(subtitleUrl)}</sec:CaptionInfoEx>")
            append("<sec:CaptionInfo sec:type=\"$subtitleExtension\">${escapeXml(subtitleUrl)}</sec:CaptionInfo>")
            append("<pv:subtitleFileUri>${escapeXml(subtitleUrl)}</pv:subtitleFileUri>")
            append("<pv:subtitleFileType>$subtitleExtension</pv:subtitleFileType>")
        }
        append("<res protocolInfo=\"http-get:*:${escapeXml(contentType)}:*\">${escapeXml(contentUrl)}</res>")
        if (subtitleUrl != null) {
            append("<res protocolInfo=\"http-get:*:${escapeXml(subtitleMimeType)}:*\">${escapeXml(subtitleUrl)}</res>")
        }
        append("</item></DIDL-Lite>")
    }
}

// --- SOAP -------------------------------------------------------------------------------------

/** A ready-to-send SOAP action call: the request body plus what the `SOAPACTION` header needs. */
data class DlnaSoapCall(val serviceType: String, val action: String, val body: String) {
    val soapActionHeader: String get() = "\"$serviceType#$action\""
}

private fun soapCall(serviceType: String, action: String, args: Map<String, String>): DlnaSoapCall {
    val body = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:$action xmlns:u=\"$serviceType\">")
        args.forEach { (key, value) -> append("<$key>${escapeXml(value)}</$key>") }
        append("</u:$action></s:Body></s:Envelope>")
    }
    return DlnaSoapCall(serviceType, action, body)
}

/**
 * Parses the `<Response-tag-content>` children of a SOAP action response into a flat map.
 * Returns an empty map — not null — on a SOAP Fault or unrecognised body, since every caller
 * already treats "the argument I wanted isn't in here" as the correct failure signal.
 */
fun parseSoapResponse(xml: String, action: String): Map<String, String> {
    val bodyRegex = Regex("(?s)<(?:[\\w-]+:)?${action}Response[^>]*>(.*?)</(?:[\\w-]+:)?${action}Response>")
    val body = bodyRegex.find(xml)?.groupValues?.get(1) ?: return emptyMap()
    return Regex("(?s)<([\\w-]+)>(.*?)</\\1>")
        .findAll(body)
        .associate { it.groupValues[1] to unescapeXml(it.groupValues[2].trim()) }
}

/**
 * A human-readable description of a UPnP SOAP Fault, or null when [xml] isn't one.
 *
 * A renderer refusing an action answers HTTP 500 with a `<s:Fault>` carrying a UPnP `errorCode`.
 * That code is the only thing that says *why* — "701 Transition not available", "714 Illegal MIME
 * type" — so it is worth pulling out rather than reporting a bare status.
 */
fun parseSoapFault(xml: String): String? {
    if (!xml.contains("Fault", ignoreCase = true)) return null
    val code = extractTag(xml, "errorCode")
    val description = extractTag(xml, "errorDescription")
    val faultString = extractTag(xml, "faultstring")
    return when {
        code != null -> listOfNotNull(code, description).joinToString(" ")
        description != null -> description
        faultString != null -> faultString
        else -> null
    }
}

/** Every AVTransport/RenderingControl/ConnectionManager action this app needs, pre-built. */
object DlnaActions {
    fun setAvTransportUri(
        contentUrl: String,
        contentType: String,
        title: String,
        subtitleUrl: String? = null,
    ): DlnaSoapCall = soapCall(
        AV_TRANSPORT_SERVICE_TYPE, "SetAVTransportURI",
        linkedMapOf(
            "InstanceID" to "0",
            "CurrentURI" to contentUrl,
            "CurrentURIMetaData" to buildDidlLite(title, contentUrl, contentType, subtitleUrl),
        ),
    )

    fun play(): DlnaSoapCall = soapCall(AV_TRANSPORT_SERVICE_TYPE, "Play", linkedMapOf("InstanceID" to "0", "Speed" to "1"))
    fun pause(): DlnaSoapCall = soapCall(AV_TRANSPORT_SERVICE_TYPE, "Pause", linkedMapOf("InstanceID" to "0"))
    fun stop(): DlnaSoapCall = soapCall(AV_TRANSPORT_SERVICE_TYPE, "Stop", linkedMapOf("InstanceID" to "0"))

    fun seek(positionMs: Long): DlnaSoapCall = soapCall(
        AV_TRANSPORT_SERVICE_TYPE, "Seek",
        linkedMapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatMsAsUpnpTime(positionMs)),
    )

    fun getTransportInfo(): DlnaSoapCall =
        soapCall(AV_TRANSPORT_SERVICE_TYPE, "GetTransportInfo", linkedMapOf("InstanceID" to "0"))

    fun getPositionInfo(): DlnaSoapCall =
        soapCall(AV_TRANSPORT_SERVICE_TYPE, "GetPositionInfo", linkedMapOf("InstanceID" to "0"))

    fun setVolume(percent: Int): DlnaSoapCall = soapCall(
        RENDERING_CONTROL_SERVICE_TYPE, "SetVolume",
        linkedMapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()),
    )

    fun getVolume(): DlnaSoapCall =
        soapCall(RENDERING_CONTROL_SERVICE_TYPE, "GetVolume", linkedMapOf("InstanceID" to "0", "Channel" to "Master"))

    fun setMute(muted: Boolean): DlnaSoapCall = soapCall(
        RENDERING_CONTROL_SERVICE_TYPE, "SetMute",
        linkedMapOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0"),
    )

    fun getProtocolInfo(): DlnaSoapCall = soapCall(CONNECTION_MANAGER_SERVICE_TYPE, "GetProtocolInfo", emptyMap())
}

// --- Time ---------------------------------------------------------------------------------

/** UPnP's `H+:MM:SS[.fff]` time format, as used by `RelTime`/`TrackDuration`/`Seek`'s `Target`. */
fun parseUpnpTimeToMs(time: String?): Long? {
    if (time.isNullOrBlank()) return null
    val parts = time.substringBefore('.').split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toLongOrNull() ?: return null
    return (hours * 3600 + minutes * 60 + seconds) * 1000
}

fun formatMsAsUpnpTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

// --- Capabilities ---------------------------------------------------------------------------

/**
 * Maps a `ConnectionManager` `GetProtocolInfo` `Sink` value (comma-separated
 * `protocol:network:contentFormat:additionalInfo` entries) to [CastReceiverCapabilities].
 *
 * DLNA protocol info rarely communicates codec-level detail (H.264 profile/level, HEVC,
 * 10-bit...) the way Chromecast's model-name heuristic does, so this only widens the
 * conservative floor ([CastReceiverProfile.GEN_1_2]'s capabilities, the same one Chromecast
 * falls back to for unrecognised devices) by whatever MIME types the renderer explicitly lists,
 * rather than attempting to infer more than the protocol actually tells us. When the call
 * failed, timed out, or reported nothing usable, the floor itself is the whole answer.
 */
fun capabilitiesFromProtocolInfo(sink: String?): CastReceiverCapabilities {
    val floor = capabilitiesFor(CastReceiverProfile.GEN_1_2)
    if (sink.isNullOrBlank()) return floor

    val mimeTypes = sink.split(',')
        .mapNotNull { entry -> entry.split(':').getOrNull(2)?.trim()?.lowercase() }
        .toSet()
    if (mimeTypes.isEmpty()) return floor

    val containers = buildSet {
        addAll(floor.containers)
        if (mimeTypes.any { it.contains("video/x-matroska") || it.contains("video/x-mkv") }) add(CastContainer.MATROSKA)
        if (mimeTypes.any { it.contains("video/webm") }) add(CastContainer.WEBM)
        if (mimeTypes.any { it.contains("video/mp4") || it.contains("video/quicktime") }) add(CastContainer.MP4)
        if (mimeTypes.any { it.contains("video/mp2t") }) add(CastContainer.MPEG_TS)
    }
    val audioCodecs = buildSet {
        addAll(floor.audioCodecs)
        if (mimeTypes.any { it.contains("audio/flac") || it.contains("audio/x-flac") }) add(CastAudioCodec.FLAC)
        if (mimeTypes.any { it.contains("audio/opus") }) add(CastAudioCodec.OPUS)
        if (mimeTypes.any { it.contains("audio/mpeg") }) add(CastAudioCodec.MP3)
    }
    val videoCodecs = buildSet {
        addAll(floor.videoCodecs)
        if (mimeTypes.any { it.contains("video/webm") }) add(CastVideoCodec.VP8)
    }

    return floor.copy(containers = containers, audioCodecs = audioCodecs, videoCodecs = videoCodecs)
}
