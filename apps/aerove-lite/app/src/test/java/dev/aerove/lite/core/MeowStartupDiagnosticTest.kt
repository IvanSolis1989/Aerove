package dev.aerove.lite.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowStartupDiagnosticTest {
    private fun row() = JSONObject("""{"schema":1,"generation":"7","phase":"failed","coreReady":false,"tunReady":false,"userListenersReady":false,"startup":{"stage":"configLoad","completed":false,"primary":{"stage":"configLoad","code":"providerConfigRejected","ioKind":"other"},"cleanup":"complete"}}""")
    @Test fun optionalCauseTypeIsFiniteAndLegacyReceiptsRemainReadable() {
        assertNull(MeowStartupCodec.decode(row().toString()).primary!!.causeType)
        for (cause in listOf("serdeYaml", "io", "join", "unknown", "password https://private.invalid")) {
            val json = row().apply { getJSONObject("startup").getJSONObject("primary").put("causeType", cause) }
            val snapshot = MeowStartupCodec.decode(json.toString())
            assertEquals(if (cause.startsWith("password")) MeowStartupCauseType.unknown else MeowStartupCauseType.valueOf(cause), snapshot.primary!!.causeType)
            val log = meowStartupFailureDiagnostic(7, Result.success(snapshot), Result.success(snapshot))
            assertFalse(log.contains("password")); assertFalse(log.contains("private.invalid"))
            assertTrue(log.contains("cleanup=complete")); assertTrue(log.contains("configLoad/providerConfigRejected/other"))
        }
        for (value in listOf<Any>(true, 3, JSONObject.NULL, JSONObject().put("secret", "private"))) {
            val json = row().apply { getJSONObject("startup").getJSONObject("primary").put("causeType", value) }
            assertThrows(Exception::class.java) { MeowStartupCodec.decode(json.toString()) }
        }
    }
    @Test fun strictCodecAndFormatterExposeOnlyFiniteClassifications() {
        val snapshot = MeowStartupCodec.decode(row().toString())
        val log = meowStartupFailureDiagnostic(7, Result.success(snapshot), Result.failure(IllegalStateException("password https://private.invalid /secret")))
        assertTrue(log.contains("configLoad/providerConfigRejected/other")); assertTrue(log.contains("after=[unavailable]"))
        for (secret in listOf("password", "private.invalid", "/secret", "IllegalStateException")) assertFalse(log.contains(secret))
        assertTrue(meowStartupFailureDiagnostic(8, Result.success(snapshot), Result.success(snapshot)).contains("before=[unavailable]"))
    }
    @Test fun unknownFieldsEnumsTypesAndGenerationAreRejected() {
        val invalid = listOf(row().put("error", "private"), row().put("schema", "1"), row().put("generation", "07"),
            row().put("coreReady", "false"), row().put("phase", "arbitrary"),
            row().apply { getJSONObject("startup").getJSONObject("primary").put("code", "passwordSecret") },
            row().apply { getJSONObject("startup").getJSONObject("primary").put("reason", "private") })
        invalid.forEach { assertThrows(Exception::class.java) { MeowStartupCodec.decode(it.toString()) } }
    }
    @Test fun allCleanupOutcomesDecodeAndBothFailuresRemainVisible() {
        val expected = listOf(MeowStartupCleanup.complete, MeowStartupCleanup.runtimeCleanupFailed,
            MeowStartupCleanup.loadJoinFailed, MeowStartupCleanup.runtimeAndLoadJoinFailed)
        expected.forEach { cleanup ->
            val json = row().apply { getJSONObject("startup").put("cleanup", cleanup.name) }
            val snapshot = MeowStartupCodec.decode(json.toString())
            assertEquals(cleanup, snapshot.cleanup)
            assertEquals(MeowStartupCode.providerConfigRejected, snapshot.primary!!.code)
            val log = meowStartupFailureDiagnostic(7, Result.success(snapshot), Result.success(snapshot))
            assertTrue(log.contains("cleanup=${cleanup.name}"))
        }
        val invalid = row().apply { getJSONObject("startup").put("cleanup", "runtimeAndLoadJoinFailure") }
        assertThrows(Exception::class.java) { MeowStartupCodec.decode(invalid.toString()) }
    }
}
