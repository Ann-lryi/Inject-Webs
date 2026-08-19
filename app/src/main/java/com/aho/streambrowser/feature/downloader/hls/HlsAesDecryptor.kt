package com.aho.streambrowser.feature.downloader.hls

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts AES-128-CBC HLS segments (RFC 8216 §4.3.2.4) using the JCE provider already on
 * Android — no third-party/native crypto dependency.
 *
 * Each EXT-X-KEY URI is fetched once and cached for the lifetime of a download. The key is
 * fetched with the same Referer/Cookie/Origin headers as the playlist/segments so key servers
 * that gate on those headers keep working.
 */
class HlsAesDecryptor(
    private val client: OkHttpClient,
    private val requestHeaders: Headers
) {
    // Segments are downloaded in parallel; HashMap is not safe for concurrent getOrPut.
    private val keyCache = ConcurrentHashMap<String, ByteArray>()

    /** Decrypt a fully-downloaded segment in place of its bytes. Returns plaintext. */
    fun decrypt(segment: HlsPlaylistResolver.Segment, ciphertext: ByteArray): ByteArray {
        val keyInfo = segment.key ?: return ciphertext
        require(keyInfo.method.equals("AES-128", ignoreCase = true)) {
            "Unsupported HLS key method: ${keyInfo.method}"
        }
        val key = keyCache.getOrPut(keyInfo.uri) { fetchKey(keyInfo.uri) }
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }

        val iv = keyInfo.iv ?: run {
            // Fall back to the implicit sequence-number IV; resolver normally sets this already.
            val iv = ByteArray(16)
            var v = segment.mediaSequence
            for (i in 15 downTo 0) { iv[i] = (v and 0xFF).toByte(); v = v ushr 8 }
            iv
        }
        require(iv.size == 16) { "AES IV must be 16 bytes" }

        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return cipher.doFinal(ciphertext)
    }

    fun hasKey(segment: HlsPlaylistResolver.Segment): Boolean = segment.key != null

    private fun fetchKey(uri: String): ByteArray {
        client.newCall(Request.Builder().url(uri).headers(requestHeaders).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Không tải được khóa AES (HTTP ${resp.code})")
            val bytes = resp.body?.bytes() ?: throw Exception("Nội dung khóa AES trống")
            if (bytes.size != 16) throw Exception("Khóa AES phải dài đúng 16 byte, nhận ${bytes.size}")
            return bytes
        }
    }
}
