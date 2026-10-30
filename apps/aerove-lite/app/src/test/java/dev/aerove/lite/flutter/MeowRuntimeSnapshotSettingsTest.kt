package dev.aerove.lite.flutter

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.FindProcessMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.controllerprefs.ExternalControllerPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowRuntimeSnapshotSettingsTest {
    private fun settings() = LiteSettings(mode = ProxyMode.Global, ipv6 = true,
        appFilterMode = AppFilterMode.Include, packages = linkedSetOf("app.first", "app.second"),
        unifiedDelay = false, tcpConcurrent = false, findProcessMode = FindProcessMode.Off,
        externalController = ExternalControllerPreferences(true, "codec-test-secret"), autoStart = true)

    @Test fun runtimeSchemaAndRoundTripCoverEveryProjectedField() {
        val fields = setOf("network", "mode", "ipv6", "appFilterMode", "packages", "requests", "backgroundPolicy",
            "unifiedDelay", "tcpConcurrent", "findProcessMode", "externalController", "dnsPreferences", "geoResources", "autoStart")
        for (settings in listOf(LiteSettings(), settings())) {
            val encoded = MeowRuntimeSnapshotSettings.encode(settings)
            assertEquals(fields, encoded.keys().asSequence().toSet())
            assertEquals(settings, MeowRuntimeSnapshotSettings.decode(encoded.toString()))
        }
    }

    @Test fun serviceProjectionUpdatesSettingsWithoutDiscardingRuntimeMetadata() {
        val oldRuntime = MeowRuntimeSnapshotSettings.encode(LiteSettings())
            .put("flutterListeningRequested", false).put("futureMetadata", JSONObject().put("revision", 42))
            .put("flutterSetup", JSONObject().put("old", true)).put("flutterUpdate", JSONObject().put("mixed-port", 7890))
        val previous = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "source", "revision", "source-hash", "yaml", "yaml-hash",
            "{}", oldRuntime.toString(), "{}", "{}")
        val settings = settings()
        val next = meowServiceProjection(previous, settings, null, null, null, "https://probe.test/check")
        val runtime = JSONObject(next.runtimeSettingsJson)
        assertEquals(settings, MeowRuntimeSnapshotSettings.decode(runtime.toString()))
        assertFalse(runtime.getBoolean("flutterListeningRequested"))
        assertEquals(42, runtime.getJSONObject("futureMetadata").getInt("revision"))
        assertTrue(meowJsonEqual(oldRuntime.getJSONObject("flutterSetup"), runtime.getJSONObject("flutterSetup")))
        assertTrue(meowJsonEqual(oldRuntime.getJSONObject("flutterUpdate"), runtime.getJSONObject("flutterUpdate")))
        assertEquals("https://probe.test/check", runtime.getString("flutterDefaultTestUrl"))
        assertEquals(previous.sourceSha256, next.sourceSha256)
        assertEquals(previous.compiledYaml, next.compiledYaml)
        assertEquals(previous.setupRecordJson, next.setupRecordJson)
        assertEquals(previous.vpnOptionsJson, next.vpnOptionsJson)
        assertEquals(previous.notificationJson, next.notificationJson)
        assertTrue(oldRuntime.getBoolean("unifiedDelay"))
    }
}
