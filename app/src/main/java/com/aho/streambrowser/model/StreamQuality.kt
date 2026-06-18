package com.aho.streambrowser.model

enum class StreamQuality {
    P240, P360, P480, P720, P1080, P1440, P4K, UNKNOWN;

    companion object {
        fun fromUrl(url: String): StreamQuality {
            val lower = url.lowercase()
            return when {
                lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> P4K
                lower.contains("1440") || lower.contains("qhd")                          -> P1440
                lower.contains("1080") || lower.contains("fhd")                          -> P1080
                lower.contains("720")  || lower.contains("hd")                            -> P720
                lower.contains("480")  || lower.contains("sd")                            -> P480
                lower.contains("360")                                                     -> P360
                lower.contains("240")                                                     -> P240
                else                                                                     -> UNKNOWN
            }
        }
    }
}
