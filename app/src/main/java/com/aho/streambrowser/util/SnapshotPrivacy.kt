package com.aho.streambrowser.util

/** Pure, conservative redaction used by private export metadata and tests. */
object SnapshotPrivacy {
    private val bearer = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+""")
    private val querySecret = Regex(
        """(?i)([?&](?:access_token|token|auth|authorization|cookie|session|password|secret|api[_-]?key)=)[^&#\s]+"""
    )
    private val sensitiveHeader = Regex("""(?i)^(authorization|cookie|set-cookie|x-api-key|x-auth-token)$""")

    fun redactText(text: String): String = text
        .replace(bearer, "${'$'}1[REDACTED]")
        .replace(jwt, "[REDACTED_JWT]")
        .replace(querySecret, "${'$'}1[REDACTED]")

    fun redactHeaderValue(name: String, value: String): String =
        if (sensitiveHeader.matches(name)) "[REDACTED]" else redactText(value)
}
