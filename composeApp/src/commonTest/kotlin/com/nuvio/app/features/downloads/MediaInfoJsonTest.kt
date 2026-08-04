package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is the whole of the MediaInfo integration that can go wrong in a way a build catches
 * nothing of: the native side either loads or does not, but a renamed key or a value that arrives
 * as an array instead of a string silently blanks a row in the sheet.
 *
 * The fixtures are trimmed copies of real MediaInfoLib output, keeping its quirks — every value a
 * string, a single track still wrapped in an array, HDR reported under two different keys.
 */
class MediaInfoJsonTest {

    @Test
    fun `reads a Dolby Vision remux with several audio and subtitle tracks`() {
        val info = assertNotNull(parseMediaInfoJson(DOLBY_VISION_MKV))

        assertEquals("MATROSKA", info.container)
        assertEquals(7_326_000L, info.durationMs)

        val video = assertNotNull(info.video)
        assertEquals("HEVC", video.codec)
        assertEquals(3840, video.width)
        assertEquals(2160, video.height)
        assertEquals(23.976f, video.frameRate)
        assertEquals(58_000_000L, video.bitrateBitsPerSecond)
        assertEquals(10, video.bitDepth)
        assertEquals("Main 10@L5.1@High", video.profile)
        // The commercial name is what a release calls it, and it wins over the raw HDR_Format.
        assertEquals("Dolby Vision, Version 1.0, dvhe.08.06, BL+RPU, HDR10 compatible", video.dynamicRange)

        assertEquals(2, info.audioTracks.size)
        val first = info.audioTracks[0]
        assertEquals("TRUEHD", first.codec)
        assertEquals(8, first.channelCount)
        assertEquals(48_000, first.sampleRateHz)
        assertEquals("en", first.language)
        assertTrue(first.isDefault)
        assertEquals("EAC3", info.audioTracks[1].codec)

        assertEquals(2, info.subtitleTracks.size)
        assertEquals("English SDH", info.subtitleTracks[0].label)
        assertEquals("en", info.subtitleTracks[0].language)
        assertTrue(info.subtitleTracks[1].isBitmap)
    }

    @Test
    fun `reads a plain web-dl with one of everything`() {
        val info = assertNotNull(parseMediaInfoJson(SIMPLE_MP4))

        assertEquals("MP4", info.container)
        val video = assertNotNull(info.video)
        assertEquals("H264", video.codec)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
        assertEquals(8, video.bitDepth)
        // No HDR_Format and an SDR transfer function.
        assertEquals("SDR", video.dynamicRange)

        assertEquals(1, info.audioTracks.size)
        assertEquals("AAC", info.audioTracks[0].codec)
        assertEquals(2, info.audioTracks[0].channelCount)
        // A lone audio track is the one that plays, whether or not the container says so.
        assertTrue(info.audioTracks[0].isDefault)
        assertTrue(info.subtitleTracks.isEmpty())
    }

    @Test
    fun `infers HDR10 from the transfer function when HDR_Format is absent`() {
        val info = assertNotNull(parseMediaInfoJson(HDR10_NO_HDR_FORMAT))
        assertEquals("HDR10", assertNotNull(info.video).dynamicRange)
    }

    @Test
    fun `distinguishes DTS-HD Master Audio from its core`() {
        val info = assertNotNull(parseMediaInfoJson(DTS_HD))
        assertEquals("DTS_HD", info.audioTracks[0].codec)
    }

    @Test
    fun `passes an unmapped codec through rather than collapsing it to unknown`() {
        val info = assertNotNull(parseMediaInfoJson(EXOTIC_CODEC))
        assertEquals("ProRes", assertNotNull(info.video).codec)
    }

    @Test
    fun `treats blank values as absent`() {
        val info = assertNotNull(parseMediaInfoJson(BLANK_FIELDS))
        val video = assertNotNull(info.video)
        assertNull(video.frameRate)
        assertNull(video.profile)
        assertNull(info.durationMs)
        assertNull(info.audioTracks[0].language)
        // The documented default, not a value read from the file.
        assertEquals(8, video.bitDepth)
    }

    @Test
    fun `survives output that is not what it expects`() {
        assertNull(parseMediaInfoJson(""))
        assertNull(parseMediaInfoJson("not json at all"))
        assertNull(parseMediaInfoJson("""{"media":{}}"""))
        // No General track means MediaInfo did not actually read a file.
        assertNull(parseMediaInfoJson("""{"media":{"track":[{"@type":"Video","Width":"1920"}]}}"""))
    }

    @Test
    fun `accepts a single track written as an object`() {
        val info = assertNotNull(
            parseMediaInfoJson("""{"media":{"track":{"@type":"General","Format":"Matroska"}}}"""),
        )
        assertEquals("MATROSKA", info.container)
        assertNull(info.video)
    }
}

private const val DOLBY_VISION_MKV = """
{
  "creatingLibrary": {"name": "MediaInfoLib", "version": "25.04"},
  "media": {
    "@ref": "/data/user/0/com.nuvio.app/files/downloads/movie.mkv",
    "track": [
      {
        "@type": "General",
        "VideoCount": "1",
        "AudioCount": "2",
        "TextCount": "2",
        "Format": "Matroska",
        "FileSize": "54000000000",
        "Duration": "7326.000"
      },
      {
        "@type": "Video",
        "Format": "HEVC",
        "Format_Profile": "Main 10@L5.1@High",
        "Width": "3840",
        "Height": "2160",
        "FrameRate": "23.976",
        "BitRate": "58000000",
        "BitDepth": "10",
        "HDR_Format": "Dolby Vision / SMPTE ST 2086",
        "HDR_Format_Commercial": "Dolby Vision, Version 1.0, dvhe.08.06, BL+RPU, HDR10 compatible",
        "transfer_characteristics": "PQ"
      },
      {
        "@type": "Audio",
        "Format": "MLP FBA",
        "Format_Commercial_IfAny": "Dolby TrueHD with Dolby Atmos",
        "Channels": "8",
        "SamplingRate": "48000",
        "BitRate": "4500000",
        "Language": "en",
        "Default": "Yes"
      },
      {
        "@type": "Audio",
        "Format": "E-AC-3",
        "Channels": "6",
        "SamplingRate": "48000",
        "BitRate": "640000",
        "Language": "es",
        "Default": "No"
      },
      {
        "@type": "Text",
        "Format": "UTF-8",
        "Title": "English SDH",
        "Language": "en"
      },
      {
        "@type": "Text",
        "Format": "PGS",
        "Language": "fr"
      }
    ]
  }
}
"""

private const val SIMPLE_MP4 = """
{
  "media": {
    "track": [
      {"@type": "General", "Format": "MPEG-4", "Duration": "2712.400"},
      {
        "@type": "Video",
        "Format": "AVC",
        "Format_Profile": "High@L4",
        "Width": "1920",
        "Height": "1080",
        "FrameRate": "24.000",
        "BitRate": "4500000",
        "transfer_characteristics": "BT.709"
      },
      {"@type": "Audio", "Format": "AAC", "Channels": "2", "SamplingRate": "48000"}
    ]
  }
}
"""

private const val HDR10_NO_HDR_FORMAT = """
{
  "media": {
    "track": [
      {"@type": "General", "Format": "Matroska"},
      {"@type": "Video", "Format": "HEVC", "Width": "3840", "Height": "2160",
       "BitDepth": "10", "transfer_characteristics": "PQ"}
    ]
  }
}
"""

private const val DTS_HD = """
{
  "media": {
    "track": [
      {"@type": "General", "Format": "Matroska"},
      {"@type": "Audio", "Format": "DTS",
       "Format_Commercial_IfAny": "DTS-HD Master Audio", "Channels": "8"}
    ]
  }
}
"""

private const val EXOTIC_CODEC = """
{
  "media": {
    "track": [
      {"@type": "General", "Format": "QuickTime"},
      {"@type": "Video", "Format": "ProRes", "Width": "1920", "Height": "1080"}
    ]
  }
}
"""

private const val BLANK_FIELDS = """
{
  "media": {
    "track": [
      {"@type": "General", "Format": "Matroska", "Duration": ""},
      {"@type": "Video", "Format": "HEVC", "Width": "1920", "Height": "1080",
       "FrameRate": "", "Format_Profile": "", "BitDepth": ""},
      {"@type": "Audio", "Format": "AAC", "Channels": "2", "Language": ""}
    ]
  }
}
"""
