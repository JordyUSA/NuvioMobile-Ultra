package com.nuvio.app.features.cast.dlna

import com.nuvio.app.features.cast.model.CastAudioCodec
import com.nuvio.app.features.cast.model.CastContainer
import com.nuvio.app.features.cast.model.CastReceiverProfile
import com.nuvio.app.features.cast.model.capabilitiesFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsdpTest {

    @Test
    fun `search request targets the multicast address and the media renderer type`() {
        val request = buildSsdpSearchRequest(mx = 2)

        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: $DLNA_MULTICAST_ADDRESS:$DLNA_MULTICAST_PORT\r\n"))
        assertTrue(request.contains("MX: 2\r\n"))
        assertTrue(request.contains("ST: $DLNA_MEDIA_RENDERER_SEARCH_TARGET\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `a 200 response yields its location and usn`() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.168.1.50:8080/description.xml\r\n" +
            "USN: uuid:1234::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        val response = assertNotNull(parseSsdpResponse(raw))

        assertEquals("http://192.168.1.50:8080/description.xml", response.location)
        assertEquals("uuid:1234::urn:schemas-upnp-org:device:MediaRenderer:1", response.usn)
    }

    @Test
    fun `header matching is case insensitive since devices vary in casing`() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "location: http://192.168.1.50:8080/desc.xml\r\n\r\n"

        val response = assertNotNull(parseSsdpResponse(raw))
        assertEquals("http://192.168.1.50:8080/desc.xml", response.location)
    }

    @Test
    fun `usn falls back to location when the header is missing`() {
        val raw = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.50/desc.xml\r\n\r\n"

        val response = assertNotNull(parseSsdpResponse(raw))
        assertEquals(response.location, response.usn)
    }

    @Test
    fun `a non-200 status line is not a usable response`() {
        val raw = "HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.50/desc.xml\r\n\r\n"
        assertNull(parseSsdpResponse(raw))
    }

    @Test
    fun `a 200 response without a location header is unusable`() {
        val raw = "HTTP/1.1 200 OK\r\nUSN: uuid:1234\r\n\r\n"
        assertNull(parseSsdpResponse(raw))
    }

    @Test
    fun `the search target is carried through when the device echoes it`() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.168.1.50/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

        assertEquals(DLNA_MEDIA_RENDERER_SEARCH_TARGET, assertNotNull(parseSsdpResponse(raw)).searchTarget)
    }

    @Test
    fun `every round searches both the renderer type and ssdp all`() {
        // MediaRenderer:1 alone misses TVs that only answer ssdp:all.
        assertTrue(DLNA_MEDIA_RENDERER_SEARCH_TARGET in DLNA_SEARCH_TARGETS)
        assertTrue(SSDP_ALL_SEARCH_TARGET in DLNA_SEARCH_TARGETS)
    }

    @Test
    fun `the search target is what ends up in the request`() {
        assertTrue(buildSsdpSearchRequest(SSDP_ALL_SEARCH_TARGET).contains("ST: ssdp:all\r\n"))
    }
}

class SsdpNotifyTest {

    @Test
    fun `an alive announcement carries the location to resolve`() {
        val raw = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NTS: ssdp:alive\r\n" +
            "USN: uuid:1234::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "LOCATION: http://192.168.1.50:8080/desc.xml\r\n\r\n"

        val notification = assertNotNull(parseSsdpNotify(raw))

        assertTrue(notification.isAlive)
        assertEquals("http://192.168.1.50:8080/desc.xml", notification.location)
        assertEquals("uuid:1234::urn:schemas-upnp-org:device:MediaRenderer:1", notification.usn)
    }

    @Test
    fun `a byebye announcement is a departure and carries no location`() {
        val raw = "NOTIFY * HTTP/1.1\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "USN: uuid:1234::upnp:rootdevice\r\n\r\n"

        val notification = assertNotNull(parseSsdpNotify(raw))

        assertFalse(notification.isAlive)
        assertNull(notification.location)
    }

    @Test
    fun `an update is treated as still-alive, not a departure`() {
        val raw = "NOTIFY * HTTP/1.1\r\n" +
            "NTS: ssdp:update\r\n" +
            "USN: uuid:1234\r\n" +
            "LOCATION: http://192.168.1.50/desc.xml\r\n\r\n"

        assertTrue(assertNotNull(parseSsdpNotify(raw)).isAlive)
    }

    @Test
    fun `an alive announcement without a location cannot be acted on`() {
        val raw = "NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\nUSN: uuid:1234\r\n\r\n"
        assertNull(parseSsdpNotify(raw))
    }

    @Test
    fun `a search response is not mistaken for an announcement`() {
        val raw = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.50/desc.xml\r\nUSN: uuid:1234\r\n\r\n"
        assertNull(parseSsdpNotify(raw))
    }

    @Test
    fun `an announcement with an unrecognised NTS is ignored`() {
        val raw = "NOTIFY * HTTP/1.1\r\nNTS: ssdp:something-else\r\nUSN: uuid:1234\r\n\r\n"
        assertNull(parseSsdpNotify(raw))
    }
}

class DeviceDescriptionTest {

    private val xml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room TV</friendlyName>
            <modelName>Some &amp; Renderer</modelName>
            <UDN>uuid:abcd-1234</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>RenderingControl1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `friendly name, model, udn and services are all extracted`() {
        val description = assertNotNull(parseDeviceDescription(xml, "http://192.168.1.50:8080/description.xml"))

        assertEquals("Living Room TV", description.friendlyName)
        assertEquals("Some & Renderer", description.modelName)
        assertEquals("uuid:abcd-1234", description.udn)
        assertEquals(2, description.services.size)
    }

    @Test
    fun `control urls are resolved against the device description location`() {
        val description = assertNotNull(parseDeviceDescription(xml, "http://192.168.1.50:8080/description.xml"))

        val avTransport = assertNotNull(description.service(AV_TRANSPORT_SERVICE_TYPE))
        assertEquals("http://192.168.1.50:8080/upnp/control/AVTransport1", avTransport.controlUrl)

        val renderingControl = assertNotNull(description.service(RENDERING_CONTROL_SERVICE_TYPE))
        assertEquals("http://192.168.1.50:8080/RenderingControl1", renderingControl.controlUrl)
    }

    @Test
    fun `a lookup for a service the renderer does not advertise returns null`() {
        val description = assertNotNull(parseDeviceDescription(xml, "http://192.168.1.50:8080/description.xml"))
        assertNull(description.service(CONNECTION_MANAGER_SERVICE_TYPE))
    }

    @Test
    fun `missing udn makes the whole document unusable`() {
        val withoutUdn = xml.replace("<UDN>uuid:abcd-1234</UDN>", "")
        assertNull(parseDeviceDescription(withoutUdn, "http://192.168.1.50/description.xml"))
    }

    @Test
    fun `missing friendly name makes the whole document unusable`() {
        val withoutName = xml.replace("<friendlyName>Living Room TV</friendlyName>", "")
        assertNull(parseDeviceDescription(withoutName, "http://192.168.1.50/description.xml"))
    }

    @Test
    fun `model name is optional`() {
        val withoutModel = xml.replace("<modelName>Some &amp; Renderer</modelName>", "")
        val description = assertNotNull(parseDeviceDescription(withoutModel, "http://192.168.1.50/description.xml"))
        assertNull(description.modelName)
    }
}

class ResolveUrlTest {

    @Test
    fun `an already-absolute reference is returned unchanged`() {
        assertEquals(
            "http://elsewhere.example/x",
            resolveUrl("http://192.168.1.50:8080/description.xml", "http://elsewhere.example/x"),
        )
    }

    @Test
    fun `a root-relative reference replaces the whole path`() {
        assertEquals(
            "http://192.168.1.50:8080/upnp/control",
            resolveUrl("http://192.168.1.50:8080/desc/description.xml", "/upnp/control"),
        )
    }

    @Test
    fun `a bare reference is resolved against the location's directory`() {
        assertEquals(
            "http://192.168.1.50:8080/desc/control1",
            resolveUrl("http://192.168.1.50:8080/desc/description.xml", "control1"),
        )
    }

    @Test
    fun `a bare reference against a root location has no extra slash`() {
        assertEquals(
            "http://192.168.1.50:8080/control1",
            resolveUrl("http://192.168.1.50:8080/description.xml", "control1"),
        )
    }
}

class DidlLiteTest {

    @Test
    fun `a video content type is classed as a video item`() {
        val didl = buildDidlLite("My Movie", "http://192.168.1.10:8899/stream.mp4", "video/mp4")

        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("<dc:title>My Movie</dc:title>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/mp4:*\""))
        assertTrue(didl.contains(">http://192.168.1.10:8899/stream.mp4<"))
    }

    @Test
    fun `an audio content type is classed as an audio item`() {
        val didl = buildDidlLite("My Song", "http://192.168.1.10:8899/stream.mp3", "audio/mpeg")
        assertTrue(didl.contains("<upnp:class>object.item.audioItem</upnp:class>"))
    }

    @Test
    fun `titles with xml metacharacters are escaped`() {
        val didl = buildDidlLite("Tom & Jerry <1940>", "http://x/y.mp4", "video/mp4")
        assertTrue(didl.contains("<dc:title>Tom &amp; Jerry &lt;1940&gt;</dc:title>"))
        assertFalse(didl.contains("Tom & Jerry <1940>"))
    }

    @Test
    fun `no subtitle means no subtitle elements at all`() {
        val didl = buildDidlLite("My Movie", "http://x/y.mp4", "video/mp4")

        assertFalse(didl.contains("CaptionInfo"))
        assertFalse(didl.contains("subtitleFileUri"))
        // Exactly one res element: the media itself.
        assertEquals(1, Regex("<res ").findAll(didl).count())
    }

    @Test
    fun `a subtitle is offered under every convention renderers actually read`() {
        val didl = buildDidlLite(
            title = "My Movie",
            contentUrl = "http://192.168.1.10:8899/stream.mp4",
            contentType = "video/mp4",
            subtitleUrl = "http://192.168.1.10:8899/subs.vtt",
        )

        // Samsung reads sec:CaptionInfo/CaptionInfoEx, others pv:subtitleFileUri, others a
        // second res entry. No single one of these works everywhere.
        assertTrue(didl.contains("<sec:CaptionInfoEx sec:type=\"vtt\">http://192.168.1.10:8899/subs.vtt</sec:CaptionInfoEx>"))
        assertTrue(didl.contains("<sec:CaptionInfo sec:type=\"vtt\">"))
        assertTrue(didl.contains("<pv:subtitleFileUri>http://192.168.1.10:8899/subs.vtt</pv:subtitleFileUri>"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:text/vtt:*\""))
        // Both namespaces have to be declared or the document is malformed.
        assertTrue(didl.contains("xmlns:sec=\"http://www.sec.co.kr/\""))
        assertTrue(didl.contains("xmlns:pv=\"http://www.pv.com/pvns/\""))
    }

    @Test
    fun `the subtitle extension is taken from the url`() {
        val didl = buildDidlLite("M", "http://x/y.mp4", "video/mp4", subtitleUrl = "http://x/subs.srt")
        assertTrue(didl.contains("sec:type=\"srt\""))
    }

    @Test
    fun `a subtitle url without an extension still produces a valid document`() {
        val didl = buildDidlLite("M", "http://x/y.mp4", "video/mp4", subtitleUrl = "http://x/subtitles")
        assertTrue(didl.contains("sec:type=\"srt\""))
        assertTrue(didl.contains("<pv:subtitleFileUri>http://x/subtitles</pv:subtitleFileUri>"))
    }
}

class XmlHelperTest {

    @Test
    fun `extractTag reads a plain tag`() {
        assertEquals("hello", extractTag("<a><foo>hello</foo></a>", "foo"))
    }

    @Test
    fun `extractTag reads a namespaced tag`() {
        assertEquals("hello", extractTag("<a><dc:foo>hello</dc:foo></a>", "foo"))
    }

    @Test
    fun `extractTag unescapes entities and trims whitespace`() {
        assertEquals("Tom & Jerry", extractTag("<foo>\n  Tom &amp; Jerry \n</foo>", "foo"))
    }

    @Test
    fun `extractTag returns null when the tag is absent`() {
        assertNull(extractTag("<a><bar>hello</bar></a>", "foo"))
    }

    @Test
    fun `escaping then unescaping is the identity`() {
        val original = "<Tom & Jerry> \"quoted\" 'text'"
        assertEquals(original, unescapeXml(escapeXml(original)))
    }
}

class SoapTest {

    @Test
    fun `setAvTransportUri embeds the content url and didl-lite metadata`() {
        val call = DlnaActions.setAvTransportUri("http://192.168.1.10:8899/stream.mp4", "video/mp4", "My Movie")

        assertEquals(AV_TRANSPORT_SERVICE_TYPE, call.serviceType)
        assertEquals("\"$AV_TRANSPORT_SERVICE_TYPE#SetAVTransportURI\"", call.soapActionHeader)
        assertTrue(call.body.contains("<CurrentURI>http://192.168.1.10:8899/stream.mp4</CurrentURI>"))
        assertTrue(call.body.contains("&lt;DIDL-Lite"))
        assertTrue(call.body.contains("<u:SetAVTransportURI xmlns:u=\"$AV_TRANSPORT_SERVICE_TYPE\">"))
    }

    @Test
    fun `seek formats the target as an upnp time`() {
        val call = DlnaActions.seek(3_725_000)
        assertTrue(call.body.contains("<Target>01:02:05</Target>"))
        assertTrue(call.body.contains("<Unit>REL_TIME</Unit>"))
    }

    @Test
    fun `setVolume clamps out-of-range percentages`() {
        assertTrue(DlnaActions.setVolume(150).body.contains("<DesiredVolume>100</DesiredVolume>"))
        assertTrue(DlnaActions.setVolume(-10).body.contains("<DesiredVolume>0</DesiredVolume>"))
    }

    @Test
    fun `setMute encodes booleans as 1 or 0`() {
        assertTrue(DlnaActions.setMute(true).body.contains("<DesiredMute>1</DesiredMute>"))
        assertTrue(DlnaActions.setMute(false).body.contains("<DesiredMute>0</DesiredMute>"))
    }

    @Test
    fun `a successful action response round-trips its arguments`() {
        val xml = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><u:GetPositionInfoResponse xmlns:u=\"$AV_TRANSPORT_SERVICE_TYPE\">" +
            "<Track>1</Track><RelTime>00:05:30</RelTime><TrackDuration>01:30:00</TrackDuration>" +
            "</u:GetPositionInfoResponse></s:Body></s:Envelope>"

        val args = parseSoapResponse(xml, "GetPositionInfo")

        assertEquals("00:05:30", args["RelTime"])
        assertEquals("01:30:00", args["TrackDuration"])
    }

    @Test
    fun `a soap fault yields no arguments rather than throwing`() {
        val fault = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<s:Body><s:Fault><faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring></s:Fault></s:Body></s:Envelope>"

        assertTrue(parseSoapResponse(fault, "GetPositionInfo").isEmpty())
    }

    @Test
    fun `a response for a different action is not matched`() {
        val xml = "<s:Body><u:PlayResponse xmlns:u=\"$AV_TRANSPORT_SERVICE_TYPE\"></u:PlayResponse></s:Body>"
        assertTrue(parseSoapResponse(xml, "GetPositionInfo").isEmpty())
    }
}

class SoapFaultTest {

    private val fault = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
        "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
        "<errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>" +
        "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"

    @Test
    fun `the upnp error code and description are what explain a refusal`() {
        assertEquals("701 Transition not available", parseSoapFault(fault))
    }

    @Test
    fun `a fault carrying only a faultstring still says something`() {
        val bare = "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring></s:Fault>"
        assertEquals("UPnPError", parseSoapFault(bare))
    }

    @Test
    fun `a successful response is not a fault`() {
        val ok = "<s:Envelope><s:Body><u:PlayResponse></u:PlayResponse></s:Body></s:Envelope>"
        assertNull(parseSoapFault(ok))
    }

    @Test
    fun `an empty body is not a fault`() {
        assertNull(parseSoapFault(""))
    }
}

class UpnpTimeTest {

    @Test
    fun `parsing and formatting round-trip whole seconds`() {
        val ms = 3_725_000L // 1h 2m 5s
        assertEquals("01:02:05", formatMsAsUpnpTime(ms))
        assertEquals(ms, parseUpnpTimeToMs(formatMsAsUpnpTime(ms)))
    }

    @Test
    fun `fractional seconds in the source are truncated on parse`() {
        assertEquals(5_000L, parseUpnpTimeToMs("00:00:05.123"))
    }

    @Test
    fun `blank or missing time is not a position`() {
        assertNull(parseUpnpTimeToMs(null))
        assertNull(parseUpnpTimeToMs(""))
        assertNull(parseUpnpTimeToMs("   "))
    }

    @Test
    fun `a malformed time is not a position`() {
        assertNull(parseUpnpTimeToMs("not-a-time"))
        assertNull(parseUpnpTimeToMs("01:02"))
    }
}

class CapabilitiesFromProtocolInfoTest {

    private val gen12Floor = capabilitiesFor(CastReceiverProfile.GEN_1_2)

    @Test
    fun `a null sink falls back to the gen 1-2 floor`() {
        assertEquals(gen12Floor, capabilitiesFromProtocolInfo(null))
    }

    @Test
    fun `a blank sink falls back to the gen 1-2 floor`() {
        assertEquals(gen12Floor, capabilitiesFromProtocolInfo("   "))
    }

    @Test
    fun `a sink with no parseable mime types falls back to the floor`() {
        assertEquals(gen12Floor, capabilitiesFromProtocolInfo("garbage-with-no-colons"))
    }

    @Test
    fun `matroska support widens the floor's containers only`() {
        val capabilities = capabilitiesFromProtocolInfo("http-get:*:video/x-matroska:*")

        assertTrue(CastContainer.MATROSKA in capabilities.containers)
        // Everything else about the conservative floor is untouched.
        assertEquals(gen12Floor.videoCodecs, capabilities.videoCodecs)
        assertEquals(gen12Floor.audioCodecs, capabilities.audioCodecs)
        assertEquals(gen12Floor.maxWidth, capabilities.maxWidth)
        assertEquals(gen12Floor.maxHeight, capabilities.maxHeight)
    }

    @Test
    fun `multiple sink entries are all considered`() {
        val capabilities = capabilitiesFromProtocolInfo(
            "http-get:*:video/x-matroska:*,http-get:*:audio/flac:*,http-get:*:video/mp4:*",
        )

        assertTrue(CastContainer.MATROSKA in capabilities.containers)
        assertTrue(CastAudioCodec.FLAC in capabilities.audioCodecs)
        assertTrue(CastContainer.MP4 in capabilities.containers)
    }

    @Test
    fun `the floor is never narrowed, only widened`() {
        // A sink that only claims one container/codec still keeps every floor entry.
        val capabilities = capabilitiesFromProtocolInfo("http-get:*:video/x-matroska:*")

        assertTrue(gen12Floor.containers.all { it in capabilities.containers })
        assertTrue(gen12Floor.audioCodecs.all { it in capabilities.audioCodecs })
        assertTrue(gen12Floor.videoCodecs.all { it in capabilities.videoCodecs })
    }
}
