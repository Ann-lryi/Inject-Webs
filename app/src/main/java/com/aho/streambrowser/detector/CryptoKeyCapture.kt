package com.aho.streambrowser.detector

data class CryptoKeyCapture(
    val algorithm: String,
    val key:       String,
    val iv:        String  = "",
    val pageUrl:   String  = "",
    val timestamp: Long    = System.currentTimeMillis()
)
