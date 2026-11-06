package dev.aerove.lite.flutter

import dev.aerove.lite.data.MeowProviderCompatibility
import dev.aerove.lite.data.sha256
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowProviderReportsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reportUsesActualRelocationReceiptAndRejectsRetiredRevisionOrChangedBytes() {
        val home = temporary.root.canonicalFile
        val store = File(home, "runtime-handoffs/resources/${UUID.randomUUID()}").apply { mkdirs() }
        val runtime = File(home, "runtime/meow-read/${UUID.randomUUID()}").apply { mkdirs() }
        val original = "proxies: [{name: removed,type: ssh},{name: kept,type: http}]\n"
        val source = File(store, "assets/source.bin").apply { parentFile.mkdirs(); writeText(original) }
        val derived = File(store, "assets/prepared.yaml").apply { writeText("proxies: [{name: kept,type: http,ca: '${File(store, "assets/ca.pem").path.replace('\\', '/')}'}]\n") }
        val relocated = File(runtime, "assets/prepared.yaml").apply {
            parentFile.mkdirs(); writeText(derived.readText().replace(store.path.replace('\\', '/'), runtime.path.replace('\\', '/')))
        }
        assertNotEquals(sha256(derived), sha256(relocated))
        val compatibility = MeowProviderCompatibility(sha256(source), sha256(derived), 1, mapOf("ssh" to 1))
        val manifest = File(store, "manifest.json").apply { writeText(JSONObject().put("records", JSONArray()
            .put(JSONObject().put("key", "$.proxy-providers.feed").put("kind", "source-proxy").put("file", "assets/source.bin").put("sha256", sha256(source)))
            .put(JSONObject().put("key", "$.proxy-providers.feed.prepared").put("kind", "proxy-provider").put("file", "assets/prepared.yaml")
                .put("sha256", sha256(derived)).put("compatibility", compatibility.json()))).toString()) }
        val yaml = "proxy-providers: {feed: {type: file,path: '${relocated.path.replace('\\', '/')}'}}\n"
        val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "source", "revision", sha256(original), yaml, sha256(yaml), "{}", "{}", "{}", "{}",
            listOf(source, derived, manifest).map { file -> RuntimeHandoffResource(store.toPath().relativize(file.toPath()).joinToString("/"), file.path, sha256(file)) })
        val revision = UUID.randomUUID().toString()
        val snapshot = MeowRuntimeSnapshot(revision, 1, false, handoff, store)
        val receipt = JSONObject().put("revision", revision).put("compiledSha256", handoff.compiledSha256)
            .put("records", JSONArray().put(JSONObject().put("file", "assets/prepared.yaml").put("sourceSha256", sha256(derived)).put("runtimeSha256", sha256(relocated))))
        val receiptFile = File(runtime, "relocation-receipt.json").apply { writeText(receipt.toString()) }
        val raw = mapOf<String, Any?>("proxies" to listOf(mapOf("name" to "kept")))
        val metadata = meowProviderMetadata(home, snapshot, "feed", "proxies", raw, yaml)
        val report = metadata.compatibility!!
        assertEquals(source.path, metadata.path); assertEquals(revision, metadata.runtimeRevision)
        assertEquals(sha256(derived), report["derivedSha256"]); assertEquals(1, report["retained"])
        receiptFile.writeText(receipt.put("revision", UUID.randomUUID().toString()).toString())
        try { meowProviderMetadata(home, snapshot, "feed", "proxies", raw, yaml); fail("Retired relocation must fail") } catch (_: IllegalArgumentException) { }
        receiptFile.writeText(receipt.put("revision", revision).toString())
        relocated.appendText("\n")
        try { meowProviderMetadata(home, snapshot, "feed", "proxies", raw, yaml); fail("Changed runtime bytes must fail") } catch (_: IllegalArgumentException) { }
    }

    @Test fun ruleProviderWithoutCompatibilityGetsItsVerifiedReadableSourceAndActualRevision() {
        val home = temporary.root.canonicalFile
        val store = File(home, "runtime-handoffs/resources/${UUID.randomUUID()}").apply { mkdirs() }
        val runtime = File(home, "runtime/meow-read/${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(store, "assets/rules.yaml").apply { parentFile.mkdirs(); writeText("payload: [example.com]\n") }
        val relocated = File(runtime, "assets/rules.yaml").apply { parentFile.mkdirs(); writeText(source.readText()) }
        val config = File(store, "config.yaml").apply { writeText("rule-providers: {feed: {type: file,path: '${source.path.replace('\\', '/')}'}}\n") }
        val manifest = File(store, "manifest.json").apply { writeText(JSONObject().put("configSha256", sha256(config)).put("records", JSONArray().put(JSONObject()
            .put("key", "$.rule-providers.feed").put("kind", "ruleset").put("file", "assets/rules.yaml").put("sha256", sha256(source)))).toString()) }
        val yaml = "rule-providers: {feed: {type: file,path: '${relocated.path.replace('\\', '/')}'}}\n"
        val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "source", "revision", sha256(source), yaml, sha256(yaml), "{}", "{}", "{}", "{}",
            listOf(source, config, manifest).map { file -> RuntimeHandoffResource(store.toPath().relativize(file.toPath()).joinToString("/"), file.path, sha256(file)) })
        val snapshot = MeowRuntimeSnapshot(UUID.randomUUID().toString(), 1, false, handoff, store)
        File(runtime, "relocation-receipt.json").writeText(JSONObject().put("revision", snapshot.revision).put("compiledSha256", sha256(config))
            .put("records", JSONArray().put(JSONObject().put("file", "assets/rules.yaml").put("sourceSha256", sha256(source)).put("runtimeSha256", sha256(relocated)))).toString())
        val metadata = meowProviderMetadata(home, snapshot, "feed", "rules", emptyMap(), yaml)
        assertEquals(source.path, metadata.path); assertEquals(snapshot.revision, metadata.runtimeRevision); assertNull(metadata.compatibility)
    }
}
