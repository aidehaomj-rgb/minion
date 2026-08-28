package com.minion.core.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SecretRedactorTest {
    @Test public void redactsKeysBearerAndUrlPassword() {
        String out = SecretRedactor.redact("apiKey=abc123 token:xyz789 Authorization: Bearer token123 https://u:pass@host/db");
        assertFalse(out.contains("abc123"));
        assertFalse(out.contains("xyz789"));
        assertFalse(out.contains("token123"));
        assertFalse(out.contains(":pass@"));
        assertTrue(out.contains("<redacted>"));
    }
}
