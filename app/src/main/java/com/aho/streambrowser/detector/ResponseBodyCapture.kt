package com.aho.streambrowser.detector

data class ResponseBodyCapture(
    val url:         String,
    val statusCode:  Int,
    val contentType: String,
    val body:        String,
    val timestamp:   Long = System.currentTimeMillis()
)
