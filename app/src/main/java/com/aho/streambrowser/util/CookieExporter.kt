package com.aho.streambrowser.util

import android.webkit.CookieManager

/** B5: Export cookies for a URL in multiple formats */
object CookieExporter {

    fun getRaw(url: String): String = CookieManager.getInstance().getCookie(url) ?: ""

    fun toCurlFlag(url: String): String {
        val c = getRaw(url)
        return if (c.isBlank()) "" else """-b "$c""""
    }

    fun toKotlinMap(url: String): String {
        val c = getRaw(url).takeIf { it.isNotBlank() } ?: return "emptyMap()"
        val entries = c.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .joinToString(",\n    ") {
                val key = it.substringBefore("=").trim()
                val val_ = it.substringAfter("=").trim()
                """"$key" to "$val_""""
            }
        return "mapOf(\n    $entries\n)"
    }

    fun toHeaderLine(url: String): String {
        val c = getRaw(url)
        return if (c.isBlank()) "" else """"Cookie" to "$c""""
    }

    fun toDocument(url: String): String = getRaw(url)

    /** Parse cookie string → map */
    fun parse(raw: String): Map<String, String> =
        raw.split(";").filter { it.contains("=") }.associate {
            it.substringBefore("=").trim() to it.substringAfter("=").trim()
        }
}
