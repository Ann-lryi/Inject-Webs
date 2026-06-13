package com.aho.streambrowser.util

import android.util.Base64
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** C3: JWT token decoder — no external library needed */
object JwtDecoder {

    data class JwtInfo(
        val header:    String,
        val payload:   String,
        val isExpired: Boolean,
        val expTime:   String,
        val subject:   String,
        val issuer:    String
    )

    fun isJwt(token: String): Boolean =
        token.trim().matches(Regex("""[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"""))

    fun decode(token: String): JwtInfo? {
        return try {
            val parts = token.trim().split(".")
            if (parts.size < 2) return null
            val header  = decodeBase64(parts[0])
            val payload = decodeBase64(parts[1])
            val payObj  = JSONObject(payload)
            val exp     = if (payObj.has("exp")) payObj.getLong("exp") else -1L
            val isExp   = exp > 0 && exp < System.currentTimeMillis() / 1000
            val expStr  = if (exp > 0) SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(Date(exp * 1000)) else "no exp"
            JwtInfo(
                header    = prettyJson(header),
                payload   = prettyJson(payload),
                isExpired = isExp,
                expTime   = expStr,
                subject   = runCatching { payObj.getString("sub") }.getOrElse { "" },
                issuer    = runCatching { payObj.getString("iss") }.getOrElse { "" }
            )
        } catch (_: Exception) { null }
    }

    /** Find all JWTs in a text block (request body, response, etc.) */
    fun findInText(text: String): List<String> =
        Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")
            .findAll(text).map { it.value }.distinct().toList()

    private fun decodeBase64(s: String): String {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
    }

    private fun prettyJson(raw: String) = try { JSONObject(raw).toString(2) }
                                          catch (_: Exception) { raw }
}
