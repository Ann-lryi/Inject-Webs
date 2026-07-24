package com.aho.streambrowser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPrivacyTest {
    @Test fun `redacts bearer jwt and query secrets`() {
        val raw = "Bearer abc.def-123 https://x.test/p?token=secret eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature"
        val clean = SnapshotPrivacy.redactText(raw)
        assertTrue(clean.contains("Bearer [REDACTED]"))
        assertTrue(clean.contains("token=[REDACTED]"))
        assertTrue(clean.contains("[REDACTED_JWT]"))
        assertFalse(clean.contains("secret"))
    }

    @Test fun `redacts sensitive header values only`() {
        assertEquals("[REDACTED]", SnapshotPrivacy.redactHeaderValue("Authorization", "Bearer a"))
        assertEquals("ok", SnapshotPrivacy.redactHeaderValue("Accept", "ok"))
    }
}
