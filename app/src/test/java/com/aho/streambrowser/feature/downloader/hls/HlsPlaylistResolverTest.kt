package com.aho.streambrowser.feature.downloader.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistResolverTest {
    @Test fun `master playlist selects highest bandwidth variant`() {
        val parsed = HlsPlaylistResolver.parse("""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2400000
            hd/index.m3u8
        """.trimIndent(), "https://cdn.example/master.m3u8")
        assertEquals("https://cdn.example/hd/index.m3u8", parsed.variants.first().url)
        assertEquals("2400000", parsed.variants.first().bandwidth.toString())
    }

    @Test fun `media playlist resolves init map and segments`() {
        val parsed = HlsPlaylistResolver.parse("""
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2,
            media/001.m4s
            #EXTINF:2,
            media/002.m4s
        """.trimIndent(), "https://cdn.example/path/list.m3u8")
        assertTrue(parsed.variants.isEmpty())
        assertEquals("https://cdn.example/path/init.mp4", parsed.initSegmentUrl)
        assertEquals(listOf("https://cdn.example/path/media/001.m4s", "https://cdn.example/path/media/002.m4s"), parsed.segmentUrls)
    }

    @Test fun `AES-128 key is parsed with resolved URI and explicit IV propagated to segments`() {
        val parsed = HlsPlaylistResolver.parse("""
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn/keys/enc.key",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:2,
            seg1.ts
            #EXTINF:2,
            seg2.ts
        """.trimIndent(), "https://cdn.example/path/list.m3u8")

        assertNull(parsed.unsupportedReason)
        assertEquals(2, parsed.segments.size)
        val first = parsed.segments[0]
        assertNotNull(first.key)
        assertEquals("AES-128", first.key!!.method)
        assertEquals("https://cdn/keys/enc.key", first.key!!.uri)
        // Explicit IV is preserved on every segment under that key.
        assertArrayEquals(
            (0..15).map { it.toByte() }.toByteArray(),
            first.key!!.iv
        )
    }

    @Test fun `AES-128 without IV derives big-endian IV from media sequence per segment`() {
        val parsed = HlsPlaylistResolver.parse("""
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
            #EXTINF:2,
            seg1.ts
            #EXTINF:2,
            seg2.ts
        """.trimIndent(), "https://cdn.example/path/list.m3u8")

        val iv1 = parsed.segments[0].key!!.iv!!
        val iv2 = parsed.segments[1].key!!.iv!!
        // Last byte holds the sequence number (5 then 6); all other bytes are zero.
        assertEquals(5.toByte(), iv1[15])
        assertEquals(0, iv1.dropLast(1).sum())
        assertEquals(6.toByte(), iv2[15])
        assertEquals(5L, parsed.segments[0].mediaSequence)
    }

    @Test fun `EXT-X-MAP with BYTERANGE is captured on the init segment`() {
        val parsed = HlsPlaylistResolver.parse("""
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="820@0"
            #EXTINF:2,
            media/001.m4s
        """.trimIndent(), "https://cdn.example/path/list.m3u8")
        // BYTERANGE="820@0" -> HTTP Range header "bytes=0-819"
        assertEquals("bytes=0-819", parsed.initSegment!!.byteRange)
        assertNull(parsed.segments.single().byteRange)
    }

    @Test fun `SAMPLE-AES is explicitly rejected`() {
        val reason = HlsPlaylistResolver.parse(
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"sk://key\"",
            "https://x/a.m3u8"
        ).unsupportedReason
        assertNotNull(reason)
        assertTrue(reason!!.contains("SAMPLE-AES"))
    }
}
