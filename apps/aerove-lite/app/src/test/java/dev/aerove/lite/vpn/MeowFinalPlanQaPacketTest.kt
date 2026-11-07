package dev.aerove.lite.vpn

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Packet framing only; these unit strings are never real Composer/Native input evidence. */
class MeowFinalPlanQaPacketTest {
    @get:Rule val temp = TemporaryFolder()
    // Generated from fixed production --workflow template and verify_apk.py validator; this is not a real BUILD_INFO receipt.
    private fun producerWorkflow() = javaClass.classLoader.getResourceAsStream("qa-final-plan-build-info-workflow.json")!!.use {
        JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).getString("workflow")
    }
    private fun hash(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    private inner class Input {
        val root = temp.newFolder()
        val store = File(root, "store").apply { mkdirs() }
        val authority = File(root, "read").apply { mkdirs() }
        val read = File(authority, "leased").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs(); File(this, "empty-child").mkdirs() }
        val raw = byteArrayOf(0, -1, 1, 2)
        val file = File(store, "binary.bin").apply { writeBytes(raw) }
        val applied = File(root, "applied").apply { writeText("unit-engine") }
        val sha = hash(raw); val compiled = hash("unit-compiled".toByteArray())
        val handoff = JSONObject().put("compiledSha256", compiled).put("resources", JSONArray().put(JSONObject()
            .put("relativePath", "binary.bin").put("sourcePath", file.path).put("sha256", sha)))
        val settings = JSONObject().put("flutterSetup", JSONObject().put("data-source-proof", JSONObject()
            .put("schema", 3).put("configSha256", "b".repeat(64)).put("tables", JSONObject().put("profiles", JSONObject().put("rows", 0)))))
        val source = MeowFinalPlanQaSources("revision", 3, "flutter:revision", handoff.toString(), settings.toString(), store, read, authority, "{}", applied, false, "{}", "{}", "{}", "unit-compiled")
        val plan = JSONObject().put("runtimeMode", "proxy").put("initiallyParked", true).put("profileId", source.profileId)
            .put("revision", source.revision).put("resourceGeneration", compiled).put("resourceReadRoot", read.path).put("cacheRoot", cache.path)
            .put("engineYaml", "unit-engine").put("hostPolicyYaml", "unit-policy").put("inbounds", JSONObject().put("configYaml", "unit-inbounds")).toString()
        val output = File(root, "packet")
        init {
            File(read, "binary.bin").writeBytes(raw)
            File(read, "relocation-receipt.json").writeText(JSONObject().put("revision", source.revision).put("compiledSha256", compiled)
                .put("records", JSONArray().put(JSONObject().put("file", "binary.bin").put("sourceSha256", sha).put("runtimeSha256", sha))).toString())
        }
        fun identity() = JSONObject().put("syntheticQA", true).put("original312Input", false).put("selector", "freshDefaultFinalPlanCapture")
            .put("source", "a".repeat(40)).put("tree", "b".repeat(40)).put("apkSha256", "c".repeat(64))
            .put("meowOverlaySha256", "d".repeat(64)).put("meowFileManifestSha256", "e".repeat(64))
            .put("hostOwnerSha256", "f".repeat(64)).put("signerSha256", "0".repeat(64))
            .put("workflow", producerWorkflow()).put("runAttempt", "1").put("executorRun", "2").put("executorAttempt", "1")
            .put("qaToken", "unit").put("uid", 10000).put("firstInstallTime", 1)
        fun capture(after: () -> Unit = {}) = MeowFinalPlanQaPacket.write(output, identity(),
            plan, -1, source, { true }, false, false, after)
    }
    @Test fun binaryBytesAndEmptyDirectoryArePreservedWithoutNativeSuccessClaim() {
        val f = Input(); val row = JSONObject(f.capture().readText())
        assertArrayEquals(f.raw, File(f.output, "closure/read-root/binary.bin").readBytes())
        assertTrue(File(f.output, "closure/cache-baseline/empty-child").isDirectory)
        assertFalse(row.getBoolean("cacheBaselineEmpty")); assertFalse(row.getBoolean("accepted"))
        assertFalse(row.getBoolean("original312Input")); assertFalse(row.getBoolean("hostReplayReady"))
        assertEquals("https://github.com/IvanSolis1989/Aerove/actions/runs/123", row.getJSONObject("identity").getString("workflow"))
        assertEquals("unit-engine", File(f.output, "engine.yaml").readText())
        val entries = row.getJSONArray("files")
        assertEquals(f.sha, (0 until entries.length()).map(entries::getJSONObject).single { it.getString("path") == "closure/read-root/binary.bin" }.getString("sha256"))
    }
    @Test fun sourceMutationBeforeFinalReadbackCannotLeaveCompletedManifest() {
        val f = Input()
        try { f.capture { f.file.writeBytes(byteArrayOf(9)) }; fail("changed closure must fail") }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(f.output, "capture-manifest.json").exists())
        assertTrue(File(f.output, "capture-failed.json").isFile)
    }
    @Test fun leaseRevalidationFailureIsTheExactPrimaryAndNoCompletedManifestRemains() {
        val f = Input(); val primary = java.io.IOException("unit controlled boundary")
        try { f.capture { throw primary }; fail("must reject") }
        catch (failure: java.io.IOException) { assertSame(primary, failure) }
        assertFalse(File(f.output, "capture-manifest.json").exists())
    }
    @Test fun missingOrMalformedCoreIdentityCannotCreateRawOutput() {
        for (key in listOf("meowOverlaySha256", "meowFileManifestSha256")) {
            val f = Input(); val missing = f.identity().apply { remove(key) }
            try { MeowFinalPlanQaPacket.write(f.output, missing, f.plan, -1, f.source, { true }, false, false) {}; fail("must reject missing identity") }
            catch (_: org.json.JSONException) { }
            assertFalse(f.output.exists())
            val malformed = f.identity().put(key, "not-a-digest")
            try { MeowFinalPlanQaPacket.write(f.output, malformed, f.plan, -1, f.source, { true }, false, false) {}; fail("must reject malformed identity") }
            catch (_: IllegalArgumentException) { }
            assertFalse(f.output.exists())
        }
    }
    @Test fun numericOrForeignOrNonCanonicalWorkflowCannotCreateRawOutput() {
        for (workflow in listOf("123", "https://github.com/IvanSolis1989/Other/actions/runs/123",
            "https://github.com/IvanSolis1989/Aerove/actions/runs/123?x=1", "https://github.com/IvanSolis1989/Aerove/actions/runs/not-a-run")) {
            val f = Input(); val identity = f.identity().put("workflow", workflow)
            try { MeowFinalPlanQaPacket.write(f.output, identity, f.plan, -1, f.source, { true }, false, false) {}; fail("must reject invalid workflow URL") }
            catch (_: IllegalArgumentException) { }
            assertFalse(f.output.exists())
        }
    }
}
