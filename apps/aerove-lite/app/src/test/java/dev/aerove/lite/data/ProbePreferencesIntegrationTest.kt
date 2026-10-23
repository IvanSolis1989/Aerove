package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.controllerprefs.ExternalControllerPreferences
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbePreferencesIntegrationTest {
    @Test fun persistedProbeFlagsAndControllerReceiptSurviveFreshSettingsOwner() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("probe-preferences").toFile()
        try {
            val settings = LiteSettings(unifiedDelay = false, tcpConcurrent = false,
                externalController = ExternalControllerPreferences().withEnabled(true))
            LiteSettingsStore(directory).save(settings)
            val actual = LiteSettingsStore(directory).read()
            assertFalse(actual.unifiedDelay); assertFalse(actual.tcpConcurrent)
            assertEquals(settings.externalController, actual.externalController)
            assertEquals(32, actual.externalController.secret.length)
        } finally { directory.deleteRecursively() }
    }
    @Test fun missingFlagsMigrateToActualDefaultsButPresentNonBooleansAreRejected() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("probe-legacy").toFile()
        try {
            LiteSettingsStore(directory).save(LiteSettings())
            val file = directory.resolve("settings/settings.json")
            val original = JSONObject(file.readText())
            file.writeText(JSONObject(original.toString()).apply { remove("unifiedDelay"); remove("tcpConcurrent") }.toString())
            assertTrue(LiteSettingsStore(directory).read().unifiedDelay)
            assertTrue(LiteSettingsStore(directory).read().tcpConcurrent)
            for (key in listOf("unifiedDelay", "tcpConcurrent")) {
                file.writeText(JSONObject(original.toString()).put(key, "true").toString())
                try { LiteSettingsStore(directory).read(); fail("Present invalid boolean must not silently become default") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { directory.deleteRecursively() }
    }
}
