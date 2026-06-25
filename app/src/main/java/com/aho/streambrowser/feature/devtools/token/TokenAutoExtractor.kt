package com.aho.streambrowser.feature.devtools.token

import com.aho.streambrowser.model.NetworkRequest
import java.util.regex.Pattern

data class ExtractedToken(
    val type: String, // "Bearer", "API-Key", "Session", "JWT"
    val value: String,
    val sourceUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)

object TokenAutoExtractor {
    
    private val extractedTokens = mutableSetOf<ExtractedToken>()
    
    // Regex siêu nhạy bắt Bearer, JWT và API Key
    private val JWT_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")
    private val API_KEY_PATTERN = Pattern.compile("(?i)(api[-_]?key|auth[-_]?token|x[-_]api[-_]key)\\s*[:=]\\s*([a-zA-Z0-9_-]{20,})")

    fun inspectRequest(request: NetworkRequest) {
        // 1. Quét Headers
        request.headers.forEach { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true)) {
                if (value.startsWith("Bearer ")) {
                    addToken("Bearer", value.substring(7), request.url)
                } else if (value.startsWith("Basic ")) {
                    addToken("Basic", value.substring(6), request.url)
                }
            } else if (key.lowercase().contains("api-key") || key.lowercase().contains("token")) {
                addToken("API-Key", value, request.url)
            }
            
            // Tìm JWT rải rác trong headers
            val jwtMatcher = JWT_PATTERN.matcher(value)
            if (jwtMatcher.find()) {
                addToken("JWT", jwtMatcher.group(), request.url)
            }
        }
        
        // 2. Quét URL Parameters
        request.parseQueryParams().forEach { (key, value) ->
            if (key.lowercase().contains("token") || key.lowercase().contains("apikey") || key.lowercase().contains("sig")) {
                addToken("URL-Token", value, request.url)
            }
            val jwtMatcher = JWT_PATTERN.matcher(value)
            if (jwtMatcher.find()) {
                addToken("JWT", jwtMatcher.group(), request.url)
            }
        }
    }

    private fun addToken(type: String, value: String, url: String) {
        if (value.length > 10) { // Bỏ qua rác
            extractedTokens.add(ExtractedToken(type, value, url))
        }
    }

    fun getAllTokens(): List<ExtractedToken> = extractedTokens.toList()
    
    fun clear() = extractedTokens.clear()
}
