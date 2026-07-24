package com.aho.streambrowser.feature.downloader.hls

import org.junit.Assert.assertEquals
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

    @Test fun `encrypted and byte range playlists are explicitly rejected`() {
        assertTrue(HlsPlaylistResolver.parse("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"", "https://x/a.m3u8").unsupportedReason!!.isNotBlank())
        assertTrue(HlsPlaylistResolver.parse("#EXT-X-BYTERANGE:100@0", "https://x/a.m3u8").unsupportedReason!!.isNotBlank())
    }
}
