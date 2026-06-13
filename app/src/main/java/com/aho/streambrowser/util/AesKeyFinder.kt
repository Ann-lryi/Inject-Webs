package com.aho.streambrowser.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** C1: Find AES keys in loaded JavaScript files */
object AesKeyFinder {

    data class FoundKey(
        val jsUrl:     String,
        val keyValue:  String,
        val keyType:   String,   // "hex32", "hex48", "hex64", "string", "base64"
        val context:   String    // surrounding code
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    // Patterns for common AES key presentations
    private val KEY_PATTERNS = listOf(
        // 32-char hex (AES-128 key)
        Regex("""["'`]([0-9a-fA-F]{32})["'`]"""),
        // 48-char hex (AES-192 key)
        Regex("""["'`]([0-9a-fA-F]{48})["'`]"""),
        // 64-char hex (AES-256 key)
        Regex("""["'`]([0-9a-fA-F]{64})["'`]"""),
        // Variable named key/secret/pass
        Regex("""(?:key|secret|pass(?:word)?|aes|cipher)\s*[=:]\s*["'`]([^"'`\s]{8,64})["'`]""", RegexOption.IGNORE_CASE),
        // CryptoJS.enc.Utf8.parse("...")
        Regex("""Utf8\.parse\(["'`]([^"'`]{8,64})["'`]\)"""),
        // Base64 keys (32+ chars, ends with =)
        Regex("""["'`]([A-Za-z0-9+/]{24,}={0,2})["'`]""")
    )

    private val KEY_TYPE = mapOf(32 to "hex32", 48 to "hex48", 64 to "hex64")

    suspend fun scanJsFiles(jsUrls: List<String>, referer: String): List<FoundKey> {
        val found = mutableListOf<FoundKey>()
        jsUrls.take(15).forEach { url ->
            try {
                val resp = client.newCall(
                    Request.Builder().url(url)
                        .addHeader("Referer", referer)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute().use { it.body?.string() ?: "" }

                if (resp.length < 5_000_000) {  // skip huge files
                    found.addAll(scanContent(resp, url))
                }
            } catch (_: Exception) {}
        }
        return found.distinctBy { it.keyValue }
    }

    private fun scanContent(js: String, sourceUrl: String): List<FoundKey> {
        val results = mutableListOf<FoundKey>()
        KEY_PATTERNS.forEach { pattern ->
            pattern.findAll(js).take(10).forEach { match ->
                val value  = match.groupValues[1]
                if (isLikelyKey(value)) {
                    val start   = (match.range.first - 40).coerceAtLeast(0)
                    val end     = (match.range.last  + 40).coerceAtMost(js.length)
                    val context = js.substring(start, end).replace('\n', ' ').trim()
                    val type = KEY_TYPE[value.length] ?: when {
                        value.matches(Regex("[0-9a-fA-F]+")) -> "hex${value.length}"
                        value.endsWith("=")                  -> "base64"
                        else                                 -> "string"
                    }
                    results.add(FoundKey(sourceUrl, value, type, context))
                }
            }
        }
        return results
    }

    private fun isLikelyKey(value: String): Boolean {
        if (value.length < 8) return false
        // Skip obvious non-keys: URLs, common strings, all same char
        if (value.contains("://")) return false
        if (value.all { it == value[0] }) return false
        // Skip very common JS strings
        val skipList = listOf("undefined","function","prototype","constructor","innerHTML")
        if (value in skipList) return false
        return true
    }
}
