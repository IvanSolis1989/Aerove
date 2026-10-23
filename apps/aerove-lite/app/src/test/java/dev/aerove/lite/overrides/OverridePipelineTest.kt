package dev.aerove.lite.overrides

import dev.aerove.lite.data.ConfigYaml
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OverridePipelineTest {
    @Test fun standard_order_global_defaults_and_match_placeholder() = runBlocking {
        val repo = OverrideRepository(Files.createTempDirectory("overrides").toFile())
        repo.setGlobalRules(listOf(OverrideRule("g", "DOMAIN,global.example,DIRECT")))
        repo.setProfile(ProfileOverride("p", addedRules = listOf("DOMAIN,local.example,MATCH", "AND,((NETWORK,TCP),(DOMAIN,a.example)),MATCH")))
        val actual = OverridePipeline(repo) { _, _ -> error("unused") }.resolve("p", "rules: ['MATCH,PROXY']")
        assertEquals(listOf("DOMAIN,local.example,PROXY", "AND,((NETWORK,TCP),(DOMAIN,a.example)),PROXY", "DOMAIN,global.example,DIRECT", "MATCH,PROXY"), ConfigYaml.parse(actual.yaml)["rules"])
        assertEquals(64, actual.fingerprint.length)
        assertEquals(listOf("DOMAIN,global.example,DIRECT", "MATCH,DIRECT"), ConfigYaml.parse(OverridePipeline(repo) { _, _ -> "{}" }.transform("new", "rules: ['MATCH,DIRECT']"))["rules"])
    }

    @Test fun script_mode_does_not_prepend_standard_rules_and_failure_keeps_source() = runBlocking {
        val repo = OverrideRepository(Files.createTempDirectory("scripts").toFile())
        repo.putScript(ScriptResource("s", "Local", content = "function main(c){return c}"))
        repo.setGlobalRules(listOf(OverrideRule("g", "MATCH,DIRECT")))
        repo.setProfile(ProfileOverride("p", OverrideMode.Script, "s", listOf("MATCH,REJECT")))
        val source = "rules: ['MATCH,PROXY']"
        val pipeline = OverridePipeline(repo) { script, config -> assertTrue(script.contains("main")); config }
        val first = pipeline.resolve("p", source)
        assertEquals(listOf("MATCH,PROXY"), ConfigYaml.parse(first.yaml)["rules"])
        repo.putScript(ScriptResource("other", "Unrelated", content = "throw 1"))
        assertEquals(first.fingerprint, pipeline.resolve("p", source).fingerprint)
        repo.putScript(ScriptResource("s", "Local", content = "function main(c){return {...c}}"))
        assertNotEquals(first.fingerprint, pipeline.resolve("p", source).fingerprint)
        val beforeFailure = repo.snapshot()
        try { OverridePipeline(repo) { _, _ -> throw ScriptExecutionException("timeout") }.transform("p", source); fail() }
        catch (expected: ScriptExecutionException) { assertEquals("timeout", expected.code) }
        assertEquals(beforeFailure, repo.snapshot())
        try { OverridePipeline(repo) { _, _ -> "[]" }.transform("p", source); fail() } catch (_: java.io.IOException) { }
    }

    @Test fun atomic_script_compare_and_set_and_delete_selection() = runBlocking {
        val repo = OverrideRepository(Files.createTempDirectory("compare").toFile())
        val old = ScriptResource("s", "Script", content = "old")
        repo.putScript(old)
        repo.setProfile(ProfileOverride("p", OverrideMode.Script, "s"))
        repo.putScript(old.copy(content = "edited"))
        assertFalse(repo.replaceScriptIfCurrent(old, old.copy(content = "downloaded")))
        assertEquals("edited", repo.snapshot().scripts.single().content)
        repo.deleteScript("s")
        assertNull(repo.snapshot().profiles.single().scriptId)
    }

    @Test fun restore_remaps_collisions_and_implicit_global_rules_without_changing_live_profile() {
        val current = OverrideSnapshot(listOf(ScriptResource("s", "Name", content = "live")), globalRules = listOf(OverrideRule("g", "DOMAIN,live,DIRECT")))
        val incoming = OverrideSnapshot(listOf(ScriptResource("s", "Name", content = "import")),
            listOf(ProfileOverride("explicit", OverrideMode.Script, "s")), listOf(OverrideRule("g", "DOMAIN,import,DIRECT")))
        val restored = OverrideRepository.mergeRestore(current, incoming, mapOf("explicit" to "e", "implicit" to "i"), false, false, setOf("live"))
        assertEquals(2, restored.scripts.map { it.id }.distinct().size)
        assertEquals(2, restored.scripts.map { it.name }.distinct().size)
        assertNotEquals("s", restored.profiles.single { it.profileId == "e" }.scriptId)
        assertEquals(listOf(restored.globalRules.last().id), restored.profiles.single { it.profileId == "live" }.disabledGlobalRuleIds)
        assertEquals(listOf("g"), restored.profiles.single { it.profileId == "i" }.disabledGlobalRuleIds)
        val profilesOnly = OverrideRepository.mergeRestore(current, incoming, mapOf("implicit" to "i"), false, true, setOf("live"))
        assertEquals(current.globalRules, profilesOnly.globalRules)
        assertEquals(listOf("DOMAIN,import,DIRECT"), profilesOnly.profiles.single { it.profileId == "i" }.addedRules)
        assertEquals(current.scripts, profilesOnly.scripts)
        assertEquals(restored, OverrideRepository.decode(OverrideRepository.encode(restored)))
        val dropped = OverrideRepository.mergeRestore(current.copy(profiles = listOf(ProfileOverride("removed"))), incoming,
            mapOf("implicit" to "i"), false, true, setOf("i"))
        assertFalse(dropped.profiles.any { it.profileId == "removed" })
    }

    @Test fun shared_script_editor_refuses_stale_save_and_keeps_latest_metadata() = runBlocking {
        val repo = OverrideRepository(Files.createTempDirectory("editor").toFile())
        val script = ScriptResource("s", "Script", content = "original")
        repo.putScript(script)
        val editor = scriptEditorSession(repo, "s", script.name)
        editor.open()
        repo.putScript(script.copy(name = "Renamed"))
        editor.contentChanged()
        assertTrue(editor.save { "edited" })
        assertEquals("Renamed", repo.snapshot().scripts.single().name)
        assertEquals("edited", repo.snapshot().scripts.single().content)
        editor.contentChanged()
        repo.replaceContent("s", "edited", "downloaded")
        assertFalse(editor.save { "stale edit" })
        assertEquals("downloaded", repo.snapshot().scripts.single().content)
        assertTrue(editor.state.value.dirty)
    }
}
