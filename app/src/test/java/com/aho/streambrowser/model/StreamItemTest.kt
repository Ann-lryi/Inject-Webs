package com.aho.streambrowser.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamItemTest {

    @Test fun `explicit extensions win over heuristics`() {
        assertEquals(StreamType.HLS, StreamItem.detectType("https://cdn/x/audio.m3u8?token=abc"))
        assertEquals(StreamType.DASH, StreamItem.detectType("https://cdn/x/manifest.mpd"))
        assertEquals(StreamType.MP4, StreamItem.detectType("https://cdn/x/movie.mp4"))
        assertEquals(StreamType.WEBM, StreamItem.detectType("https://cdn/x/clip.webm"))
        assertEquals(StreamType.M3U9, StreamItem.detectType("https://cdn/x/p.m3u9"))
        assertEquals(StreamType.WEBSOCKET, StreamItem.detectType("wss://live/ws"))
        assertEquals(StreamType.RTMP, StreamItem.detectType("rtmp://s/app"))
    }

    @Test fun `path-based HLS and DASH detection`() {
        assertEquals(StreamType.HLS, StreamItem.detectType("https://cdn.com/hls/live/stream"))
        assertEquals(StreamType.DASH, StreamItem.detectType("https://cdn.com/dash/stream/manifest"))
    }

    @Test fun `heuristics no longer flag arbitrary video API URLs as MP4`() {
        // The old detector flagged anything with both "videos/" and ".com" as MP4,
        // which caught unrelated REST/tracking URLs.
        assertNull(StreamItem.detectType("https://api.example.com/v1/videos/metadata"))
        assertNull(StreamItem.detectType("https://tracker.example.com/pixel?path=/video/123"))
        assertNull(StreamItem.detectType("https://example.com/users/123/edit"))
    }

    @Test fun `heuristics still catch tokenized segment or playlist endpoints`() {
        assertEquals(
            StreamType.HLS,
            StreamItem.detectType("https://cdn.example.com/stream/play?token=abc&bitrate=high")
        )
        assertEquals(
            StreamType.HLS,
            StreamItem.detectType("https://cdn.example.com/media/segment_1.ts"))
    }
}
