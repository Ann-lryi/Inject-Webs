package com.aho.streambrowser.detector

data class WebSocketMessage(
    val direction: String,   // "open" | "send" | "recv"
    val wsUrl:     String,
    val data:      String,
    val timestamp: Long = System.currentTimeMillis()
)
