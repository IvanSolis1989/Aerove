package dev.aerove.lite.controllerprefs

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalControllerPreferencesTest {
    @Test fun defaultsAndWireRoundTrip() {
        assertEquals(ExternalControllerPreferences(), decodeExternalController(null))
        assertEquals(ExternalControllerPreferences(), decodeExternalController(JSONObject()))
        val value = ExternalControllerPreferences().withEnabled(true).withSecret("  密钥Abc123  ")
        assertEquals("密钥Abc123", value.secret)
        assertEquals(value, decodeExternalController(JSONObject(encodeExternalController(value).toString())))
    }

    @Test fun enablingEnsuresAndDisablingClearsSecret() {
        val first = ExternalControllerPreferences().withEnabled(true)
        assertTrue(first.enabled)
        assertTrue(first.secret.matches(Regex("[a-zA-Z0-9]{32}")))
        assertEquals(first, first.withEnabled(true))
        val disabled = first.withEnabled(false)
        assertEquals(ExternalControllerPreferences(), disabled)
        assertNotEquals(first.secret, disabled.withEnabled(true).secret)
    }

    @Test fun effectiveSafeModeDoesNotMutateSavedPreference() {
        val saved = ExternalControllerPreferences(true, "user-secret")
        assertFalse(saved.effective(true).enabled)
        assertEquals("", saved.effective(true).secret)
        assertNull(saved.effective(true).reservedPort)
        assertTrue(saved.enabled)
        assertEquals("127.0.0.1:9090", saved.effective(false).address)
        assertEquals(9090, saved.effective(false).reservedPort)
        assertFalse(ExternalControllerPreferences(true, "").effective(false).enabled)
    }

    @Test fun strictWireAndInputBoundaries() {
        listOf("{\"enabled\":null}", "{\"enabled\":1}", "{\"enabled\":\"true\"}",
            "{\"secret\":null}", "{\"secret\":2}").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { decodeExternalController(JSONObject(text)) }
        }
        val value = ExternalControllerPreferences(true)
        assertEquals(512, value.withSecret("x".repeat(512)).secret.length)
        listOf("", "  ", "x".repeat(513)).forEach { input ->
            val failure = assertThrows(IllegalArgumentException::class.java) { value.withSecret(input) }
            if (input.length > 512) assertFalse(failure.message.orEmpty().contains(input))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeExternalController(JSONObject().put("secret", "x".repeat(513)))
        }
    }

    @Test fun diagnosticsRedactAndReadbackRejectsWrongOwnerOrRoutes() {
        val saved = ExternalControllerPreferences(true, "never-log-this")
        assertFalse(saved.toString().contains(saved.secret))
        val plan = saved.effective(false)
        assertFalse(plan.toString().contains(saved.secret))
        val readback = UserControllerReadback(4, true, true, true)
        assertSame(readback, readback.requireMatches(4, plan))
        assertThrows(IllegalStateException::class.java) { readback.requireMatches(5, plan) }
        assertThrows(IllegalStateException::class.java) { readback.copy(authenticated = false).requireMatches(4, plan) }
        assertThrows(IllegalStateException::class.java) { readback.copy(privateRoutesExcluded = false).requireMatches(4, plan) }
        assertThrows(IllegalStateException::class.java) { readback.copy(enabled = false).requireMatches(4, plan) }
    }
}
