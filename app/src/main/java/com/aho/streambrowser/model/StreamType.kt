package com.aho.streambrowser.model

enum class StreamType {
    HLS,
    MP4,
    DASH,
    FLV,
    WEBM,      // WebM format
    WEBSOCKET, // WebSocket streaming
    RTMP,      // RTMP streams
    OTHER      // Unknown/other
}
