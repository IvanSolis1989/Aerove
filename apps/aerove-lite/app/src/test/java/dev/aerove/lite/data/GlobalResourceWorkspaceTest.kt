package dev.aerove.lite.data

import dev.aerove.lite.integration.GlobalResourceWorkspace
import dev.aerove.lite.profilebuilder.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlobalResourceWorkspaceTest {
    private class Fixture {
        val root = Files.createTempDirectory("global-workspace").toFile()
        val library = BuilderResourceLibrary(root)
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        // This boundary supplies parser receipts; the test verifies real shared IO/CAS/headers, not JNI.
        val inspector = object : NativeRulePayloadInspector {
            override suspend fun inspect(path: String) = RulePayloadInspection("text", "domain", File(path).readLines().size)
            override suspend fun dump(path: String, behavior: String, format: String) = File(path).readText()
        }
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, offlineRules = inspector)
        val profiles = ProfileRepository(root, ProfileValidator {}, resources = manager, globalResources = library)
        val payloads = GlobalRulePayloadRepository(root, library, manager, inspector, profiles)
        var changes = 0
        var validations = 0
        val workspace = GlobalResourceWorkspace(library, profiles, payloads, { validations++; null }, { changes++ })
    }
    @Test fun realGlobalNodeDeletionDetachesProfileHeaderAndKeepsBody() = runBlocking<Unit> {
        val f = Fixture()
        try {
            assertTrue(f.workspace.prepare())
            assertTrue(f.workspace.session.putProxy(null, "shared", mapOf("type" to "direct"), f.workspace.proxyLinks::validate))
            assertEquals(1, f.validations)
            val id = f.library.snapshot().resources.proxies.single().id!!
            val body = "proxies: [{name: local, type: direct}]\nrules: [MATCH,DIRECT]\n"
            val saved = f.profiles.importText("profile", BuilderResourceBindings(proxies = mapOf("local" to id)).header() + body)
            assertTrue(f.workspace.session.remove(BuilderResourceKind.Proxy, setOf(id)))
            val source = f.profiles.documentSnapshot(saved.id).yaml
            assertTrue(BuilderResourceBindings.parse(source).proxies.isEmpty())
            assertEquals(body, source.lineSequence().filterNot { it.startsWith("# aerove-builder-bindings:") }.joinToString("\n"))
            assertTrue(BuilderResourceLibrary(f.root).snapshot().resources.proxies.isEmpty())
            assertEquals(2, f.changes)
            f.workspace.quiesce()
            assertFalse(f.workspace.prepare())
            f.workspace.resumeAfterMaintenance()
            assertTrue(f.workspace.prepare())
        } finally { f.workspace.closeAndJoin(); f.root.deleteRecursively() }
    }
    @Test fun ruleAdapterPersistsActualPayloadByStableIdAndReadsItBack() = runBlocking<Unit> {
        val f = Fixture()
        try {
            assertTrue(f.workspace.prepare())
            assertTrue(f.workspace.session.perform { expected -> f.workspace.rules.saveText(expected!!, null, "rules", "example.com\n") })
            val saved = f.workspace.session.state.value.snapshot!!
            val id = saved.resources.ruleProviders.single().id!!
            assertEquals("example.com\n", f.workspace.rules.preview(saved, id).text)
            assertEquals(1, f.workspace.rules.info(saved, id).count)
            val renamed = f.workspace.rules.options(saved, id, "renamed", "")
            assertEquals(id, renamed.resources.ruleProviders.single().id)
            assertEquals("renamed", BuilderResourceLibrary(f.root).snapshot().resources.ruleProviders.single().name)
            assertTrue(f.workspace.rules.delete(renamed, id).resources.ruleProviders.isEmpty())
        } finally { f.workspace.closeAndJoin(); f.root.deleteRecursively() }
    }
}
