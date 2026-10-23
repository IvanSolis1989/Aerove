package dev.aerove.lite.data

import dev.aerove.lite.*
import dev.aerove.lite.appaccess.*
import dev.aerove.lite.integration.withAppAccess
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformPreferencesIntegrationTest {
    @Test fun bothCollectionsSurviveSwitchingDisablingEmptyIncludeAndFreshStoreReadback() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("access-settings").toFile()
        try {
            val store = LiteSettingsStore(directory)
            val preferences = AppAccessPreferences(enabled = true, mode = AppAccessMode.Include,
                includePackages = emptySet(), excludePackages = setOf("com.example.excluded"), hideSystem = false)
            val included = store.save(LiteSettings().withAppAccess(preferences))
            assertEquals(AppFilterMode.Include, included.appFilterMode)
            assertTrue(included.packages.isEmpty())
            assertEquals(preferences, LiteSettingsStore(directory).read().appAccess)
            val excluded = store.save(included.withAppAccess(preferences.copy(mode = AppAccessMode.Exclude)))
            assertEquals(preferences.excludePackages, excluded.packages)
            val disabled = store.save(excluded.withAppAccess(excluded.appAccess.copy(enabled = false)))
            assertEquals(AppFilterMode.All, disabled.appFilterMode)
            assertTrue(disabled.packages.isEmpty())
            assertEquals(preferences.excludePackages, LiteSettingsStore(directory).read().appAccess.excludePackages)
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacySingleCollectionMigratesAndResetReadsFreshDefaultsWithoutCarryingOldPrivatePreferences() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("legacy-access").toFile()
        try {
            val store = LiteSettingsStore(directory)
            store.save(LiteSettings(appFilterMode = AppFilterMode.Exclude, packages = setOf("com.old.client"),
                developerMode = true, acceptedDisclaimerVersion = 1, auroraSeed = 987654))
            val file = File(directory, "settings/settings.json")
            file.writeText(JSONObject(file.readText()).apply { remove("appAccess") }.toString())
            val migrated = LiteSettingsStore(directory).read()
            assertTrue(migrated.appAccess.enabled)
            assertEquals(AppAccessMode.Exclude, migrated.appAccess.mode)
            assertEquals(setOf("com.old.client"), migrated.appAccess.excludePackages)
            store.deferPublication { store.reset() }
            assertEquals(LiteSettings().normalized(), LiteSettingsStore(directory).read())
            assertFalse(store.changes.value!!.developerMode)
            assertEquals(0, store.changes.value!!.acceptedDisclaimerVersion)
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedPresentDisclaimerVersionAndAppAccessNeverCoerceToAcceptedDefaults() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("invalid-consent").toFile()
        try {
            val store = LiteSettingsStore(directory)
            store.save(LiteSettings())
            val file = File(directory, "settings/settings.json")
            val original = file.readText()
            listOf<Any>("1", -1, JSONObject.NULL).forEach { invalid ->
                file.writeText(JSONObject(original).put("acceptedDisclaimerVersion", invalid).toString())
                try { LiteSettingsStore(directory).read(); fail("Accepted malformed disclaimer version") }
                catch (_: IllegalArgumentException) { }
            }
            file.writeText(JSONObject(original).put("appAccess", JSONObject.NULL).toString())
            try { LiteSettingsStore(directory).read(); fail("Accepted null app access") }
            catch (_: IllegalArgumentException) { }
        } finally { directory.deleteRecursively() }
    }
}
