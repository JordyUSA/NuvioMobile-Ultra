package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Liveness detection for adaptive manifests.
 *
 * The direction of an error matters here and is asymmetric: a Cast receiver told a stream is
 * live hides its scrubber entirely, so calling VOD "live" costs seeking outright, while calling
 * a live stream "VOD" merely shows an optimistic timeline.
 */
class HlsLivenessTest {

    @Test
    fun `a playlist ending in ENDLIST is a finished recording`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            segment0.ts
            #EXTINF:10.0,
            segment1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertFalse(hlsPlaylistIsLive(playlist))
    }

    @Test
    fun `an explicit VOD playlist type is a finished recording`() {
        val playlist = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:10.0,\nsegment0.ts"
        assertFalse(hlsPlaylistIsLive(playlist))
    }

    @Test
    fun `a media playlist with no end marker is still being written`() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nsegment42.ts"
        assertTrue(hlsPlaylistIsLive(playlist))
    }

    @Test
    fun `an EVENT playlist is live because it is still growing`() {
        val playlist = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:6.0,\nsegment0.ts"
        assertTrue(hlsPlaylistIsLive(playlist))
    }

    @Test
    fun `tag matching is case insensitive`() {
        assertFalse(hlsPlaylistIsLive("#EXTM3U\n#ext-x-endlist"))
    }
}

class HlsMasterPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=720x480
        low/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1920x1080
        high/index.m3u8
    """.trimIndent()

    @Test
    fun `a master playlist is recognised as one`() {
        assertTrue(hlsIsMasterPlaylist(master))
    }

    @Test
    fun `a media playlist is not a master`() {
        assertFalse(hlsIsMasterPlaylist("#EXTM3U\n#EXTINF:10.0,\nsegment0.ts"))
    }

    @Test
    fun `the first variant is resolved against the master's own location`() {
        assertEquals(
            "https://example.com/hls/low/index.m3u8",
            assertNotNull(hlsFirstVariantUrl(master, "https://example.com/hls/master.m3u8")),
        )
    }

    @Test
    fun `an absolute variant url is used as-is`() {
        val absolute = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example.net/v/index.m3u8"
        assertEquals(
            "https://cdn.example.net/v/index.m3u8",
            hlsFirstVariantUrl(absolute, "https://example.com/hls/master.m3u8"),
        )
    }

    @Test
    fun `comment lines between the tag and the url are skipped`() {
        val withComment = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n#EXT-X-SOMETHING\nlow.m3u8"
        assertEquals(
            "https://example.com/hls/low.m3u8",
            hlsFirstVariantUrl(withComment, "https://example.com/hls/master.m3u8"),
        )
    }

    @Test
    fun `a playlist with no variants yields nothing`() {
        assertNull(hlsFirstVariantUrl("#EXTM3U\n#EXTINF:10.0,\nseg.ts", "https://example.com/a.m3u8"))
    }

    @Test
    fun `a query string on the master does not become part of the base path`() {
        assertEquals(
            "https://example.com/hls/low/index.m3u8",
            hlsFirstVariantUrl(master, "https://example.com/hls/master.m3u8?token=abc"),
        )
    }
}

class DashLivenessTest {

    @Test
    fun `a dynamic manifest is live`() {
        val manifest = """<?xml version="1.0"?><MPD type="dynamic" minimumUpdatePeriod="PT2S"></MPD>"""
        assertTrue(dashManifestIsLive(manifest))
    }

    @Test
    fun `a static manifest is a finished recording`() {
        val manifest = """<?xml version="1.0"?><MPD type="static" mediaPresentationDuration="PT1H"></MPD>"""
        assertFalse(dashManifestIsLive(manifest))
    }

    @Test
    fun `an absent type attribute defaults to static`() {
        assertFalse(dashManifestIsLive("""<?xml version="1.0"?><MPD profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"></MPD>"""))
    }

    @Test
    fun `single quotes and spacing are tolerated`() {
        assertTrue(dashManifestIsLive("<MPD type = 'dynamic'></MPD>"))
    }
}
