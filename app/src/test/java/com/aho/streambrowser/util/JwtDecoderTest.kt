package com.aho.streambrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [JwtDecoder.isJwt] and [JwtDecoder.findInText] only — both are pure regex/string
 * operations with no Android-framework dependency, so they run correctly as plain JVM unit
 * tests (no device/emulator, no Robolectric needed).
 *
 * [JwtDecoder.decode] is NOT covered here: it calls android.util.Base64, which under this
 * module's `testOptions.unitTests.isReturnDefaultValues = true` returns an empty byte array
 * rather than actually decoding — a test against it would assert on stub behavior, not real
 * decoding, which would be misleading rather than useful. Needs Robolectric (or an injectable
 * Base64 abstraction) to test for real; left as a follow-up rather than added blind.
 */
class JwtDecoderTest {

    // ── isJwt ────────────────────────────────────────────────────────────────

    @Test
    fun `isJwt accepts a realistic 3-segment token`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        assertTrue(JwtDecoder.isJwt(token))
    }

    @Test
    fun `isJwt trims surrounding whitespace before matching`() {
        assertTrue(JwtDecoder.isJwt("  eyJhbGc.eyJzdWI.sig  "))
    }

    @Test
    fun `isJwt accepts an empty third segment`() {
        // Regex third group is zero-or-more — a token with no signature segment is still shaped like a JWT
        assertTrue(JwtDecoder.isJwt("eyJhbGc.eyJzdWI."))
    }

    @Test
    fun `isJwt rejects a string with only one segment`() {
        assertFalse(JwtDecoder.isJwt("notajwt"))
    }

    @Test
    fun `isJwt rejects a string with only two segments`() {
        assertFalse(JwtDecoder.isJwt("only.two"))
    }

    @Test
    fun `isJwt rejects characters outside base64url charset`() {
        assertFalse(JwtDecoder.isJwt("has.invalid!.chars"))
    }

    @Test
    fun `isJwt rejects an empty string`() {
        assertFalse(JwtDecoder.isJwt(""))
    }

    // ── findInText ───────────────────────────────────────────────────────────

    @Test
    fun `findInText extracts a single embedded token`() {
        val text = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc123 end of line"
        val found = JwtDecoder.findInText(text)
        assertEquals(1, found.size)
        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc123", found[0])
    }

    @Test
    fun `findInText returns empty list when there is no token`() {
        assertTrue(JwtDecoder.findInText("plain text, no token here").isEmpty())
    }

    @Test
    fun `findInText deduplicates identical repeated tokens`() {
        val text = "first eyJhbGc.eyJzdWI.sig then again eyJhbGc.eyJzdWI.sig"
        val found = JwtDecoder.findInText(text)
        assertEquals(1, found.size)
    }

    @Test
    fun `findInText only matches tokens starting with the eyJ prefix`() {
        // Same 3-segment shape as a real JWT, but missing the eyJ prefix findInText requires
        val text = "abcDEF123.eyJzdWI.sig"
        assertTrue(JwtDecoder.findInText(text).isEmpty())
    }
}
