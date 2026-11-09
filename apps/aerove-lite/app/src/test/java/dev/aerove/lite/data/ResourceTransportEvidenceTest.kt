package dev.aerove.lite.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceTransportEvidenceTest {
    @Test fun nativeTransportCategoriesSurviveWithoutPrivateMessages() {
        for (category in listOf("tls", "proxy-dial", "direct-connect", "read-timeout", "http-status", "body-limit", "url", "header", "redirect", "io", "response-or-transport")) {
            val evidence = "Resource transport failed: $category"
            assertEquals(evidence, ResourceBundleManager.safeError(IOException(evidence)))
        }
        for (message in listOf("Resource transport failed: tls secret", "Resource transport failed: https://private.example", "HTTP 404 private response")) {
            assertEquals("network or file I/O error", ResourceBundleManager.safeError(IOException(message)))
        }
        assertEquals("invalid resource request", ResourceBundleManager.safeError(IllegalArgumentException("private input")))
    }
}
