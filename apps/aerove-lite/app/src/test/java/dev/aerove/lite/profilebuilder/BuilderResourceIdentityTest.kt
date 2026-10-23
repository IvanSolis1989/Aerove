package dev.aerove.lite.profilebuilder

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderResourceIdentityTest {
    @get:Rule val directory = TemporaryFolder()
    private val body = "# preserve downloaded bytes\r\nproxies:\r\n  - {name: local, type: direct}\r\nproxy-groups:\r\n  - {name: group, type: select, proxies: [local]}\r\nrules: [\"MATCH,local\"]\r\n"
    private fun icons(name: String, suffix: String) = BuilderIconSet(name, icons = listOf(BuilderIcon(suffix, "https://example.com/$suffix.png")))
    private fun legacy() = """schema: 1
proxies:
  - {name: node, type: direct}
rule-providers: {}
filters: []
icon-sets: []
"""
    @Test fun duplicateIconNamesBindSecondAndRenameRetainsReference() = runBlocking<Unit> {
        val first = icons("same", "one"); val second = icons("same", "two")
        val resources = BuilderResources(iconSets = listOf(first, second))
        val source = BuilderResourceBindings(icons = mapOf("group" to BuilderIconBinding(second.id, second.icons.single().url))).header() + body
        val renamed = resources.copy(iconSets = listOf(first.copy(name = "renamed"), second.copy(name = "renamed")))
        val decoded = BuilderResources.parse(renamed.yaml())
        assertEquals(listOf(first.id, second.id), decoded.iconSets.map { it.id })
        assertEquals(source, decoded.migrateSourceHeader(source))
        assertTrue(decoded.resolve(source).contains(second.icons.single().url))
        assertEquals(listOf(second.id), decoded.referencedBy(listOf(source)).iconSets.map { it.id })
    }
    @Test fun proxyRenameRetainsIdAndRuntimeResolvesChangedFields() = runBlocking<Unit> {
        val original = BuilderResources(proxies = listOf(BuilderEntry("node", mapOf("type" to "direct"))))
        val id = original.proxies.single().id!!
        val source = BuilderResourceBindings(proxies = mapOf("local" to id)).header() + body
        val updated = original.copy(proxies = listOf(original.proxy(id).copy(name = "renamed", fields = mapOf("type" to "socks5", "server" to "changed.example", "port" to 443L))))
        assertEquals(id, BuilderResources.parse(updated.yaml()).proxies.single().id)
        val actual = ProfileBuilderDocument.parse(updated.resolve(source)).entries(BuilderSection.Proxies).single()
        assertEquals("local", actual.name); assertEquals("changed.example", actual.fields["server"])
    }
    @Test fun legacyMigrationIsDeterministicHeaderOnlyAndReadDoesNotWrite() = runBlocking<Unit> {
        val file = File(directory.root, "settings/builder-resources.yaml"); file.parentFile.mkdirs(); file.writeText(legacy())
        val library = BuilderResourceLibrary(directory.root); val snapshot = library.snapshot()
        assertEquals(1, snapshot.schemaVersion); assertEquals(legacy(), file.readText())
        assertEquals(snapshot.resources, BuilderResources.parse(legacy()))
        val oldHeader = BuilderResourceBindings(proxies = mapOf("local" to "node"), schema = 1).header().replace("\n", "\r\n")
        val source = "\uFEFF" + oldHeader + body
        val plan = snapshot.planIdentityMigration(mapOf("profile" to source))
        assertEquals(body, plan.sources.getValue("profile").substringAfter("\r\n"))
        assertTrue(plan.sources.getValue("profile").startsWith("\uFEFF#"))
        assertEquals(snapshot.resources.proxies.single().id, BuilderResourceBindings.parse(plan.sources.getValue("profile")).proxies["local"])
        try { library.replace(snapshot, snapshot.resources); fail("Legacy mutation must be journal-gated") } catch (_: IllegalArgumentException) {}
        val saved = library.commitIdentityMigration(snapshot, plan)
        assertEquals(2, saved.schemaVersion); assertEquals(snapshot.resources, saved.resources)
        assertEquals(saved.encoded, library.snapshot().encoded)
    }
    @Test fun missingAmbiguousLegacyAndInvalidStableReferencesFailExplicitly() {
        val resources = BuilderResources(iconSets = listOf(icons("same", "one"), icons("same", "two")))
        fun rejects(action: () -> Unit) { try { action(); fail("Expected explicit rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }
        rejects { resources.migrateBindings(BuilderResourceBindings(icons = mapOf("g" to BuilderIconBinding("same", "url")), schema = 1)) }
        rejects { resources.migrateBindings(BuilderResourceBindings(proxies = mapOf("n" to "missing"), schema = 1)) }
        rejects { resources.migrateSourceHeader(BuilderResourceBindings(proxies = mapOf("n" to java.util.UUID.randomUUID().toString())).header() + body) }
        rejects { BuilderResourceBindings(proxies = mapOf("n" to "bad-id")).header() }
        rejects { resources.copy(iconSets = listOf(resources.iconSets[0], resources.iconSets[1].copy(id = resources.iconSets[0].id))).yaml() }
    }
    @Test fun restoreRemapsCollidingIdsAndKeepsDuplicateNamesWithCorrectPayload() = runBlocking<Unit> {
        val first = icons("same", "existing"); val incoming = icons("same", "incoming").copy(id = first.id)
        val existing = BuilderResources(iconSets = listOf(first)); val imported = BuilderResources(iconSets = listOf(incoming))
        val source = BuilderResourceBindings(icons = mapOf("group" to BuilderIconBinding(incoming.id, incoming.icons.single().url))).header().replace("\n", "\r\n") + body
        val merged = existing.mergeForRestore(imported)
        val rewritten = merged.rewriteSource(source)
        val id = BuilderResourceBindings.parse(rewritten).icons.getValue("group").set
        assertNotEquals(first.id, id); assertEquals(2, merged.resources.iconSets.count { it.name == "same" })
        assertEquals(incoming.icons, merged.resources.iconSet(id).icons)
        assertEquals(body, rewritten.substringAfter("\r\n"))
        assertTrue(merged.resources.resolve(rewritten).contains(incoming.icons.single().url))
    }
    @Test fun legacyBackupNameCollisionMapsToImportedResourceWithoutRewritingBody() {
        val imported = BuilderResources.parse(legacy())
        val original = imported.proxies.single()
        val existing = BuilderResources(proxies = listOf(original.copy(fields = mapOf("type" to "reject"))))
        val merged = existing.mergeForRestore(imported)
        val source = BuilderResourceBindings(proxies = mapOf("local" to "node"), schema = 1).header() + body
        val rewritten = merged.rewriteSource(source)
        val id = BuilderResourceBindings.parse(rewritten).proxies.getValue("local")
        assertNotEquals(original.id, id); assertEquals("direct", merged.resources.proxy(id).fields["type"])
        assertEquals(body, rewritten.substringAfter('\n'))
        val missing = BuilderResourceBindings(proxies = mapOf("local" to java.util.UUID.randomUUID().toString())).header() + body
        try { merged.rewriteSource(missing); fail("Missing restore ID") } catch (_: IllegalStateException) {}
    }
    @Test fun detachRemovesOnlyChosenIdAndPreservesStaticBody() {
        val one = icons("same", "one"); val two = icons("same", "two")
        val resources = BuilderResources(iconSets = listOf(one, two))
        val bindings = BuilderResourceBindings(icons = mapOf("g1" to BuilderIconBinding(one.id, one.icons.single().url), "g2" to BuilderIconBinding(two.id, two.icons.single().url)))
        val source = bindings.header().replace("\n", "\r\n") + body
        val detached = resources.detachReferences(source, BuilderResourceKind.IconSet, setOf(one.id))
        assertEquals(setOf("g2"), BuilderResourceBindings.parse(detached).icons.keys)
        assertEquals(body, detached.substringAfter("\r\n"))
    }

    @Test fun boundChainRenameProjectsGlobalReferencesToLocalProfileNames() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(directory.root)
        val session = GlobalResourceLibrarySession(library, GlobalResourceDeletion { expected, _, _, next -> library.replace(expected, next) })
        session.load()
        assertTrue(session.putProxy(null, "A", mapOf("type" to "direct")))
        assertTrue(session.putProxy(null, "B", mapOf("type" to "socks5", "server" to "node.example", "port" to 443L, "dialer-proxy" to "A", "connect-via" to "A", "password" to "A")))
        val resources = library.snapshot().resources
        val source = BuilderResourceBindings(proxies = resources.proxies.associate { it.name to it.id!! }).header() +
            "proxies: [{name: A, type: direct}, {name: B, type: direct}]\nrules: [\"MATCH,B\"]\n"
        assertTrue(session.putProxy(resources.proxies.single { it.name == "A" }.id, "renamed", mapOf("type" to "direct")))
        val renamed = library.snapshot().resources
        assertEquals("renamed", renamed.proxies.single { it.name == "B" }.fields["dialer-proxy"])
        val actual = ProfileBuilderDocument.parse(renamed.resolve(source))
        val b = actual.entries(BuilderSection.Proxies).single { it.name == "B" }
        assertEquals("A", b.fields["dialer-proxy"]); assertEquals("A", b.fields["connect-via"])
        assertEquals("A", b.fields["password"]); assertTrue(actual.issues().isEmpty())
        assertEquals(source, renamed.migrateSourceHeader(source))
    }
    @Test fun restoreCollisionProjectsIncomingChainWithoutChangingExistingOrDeduplicatingWrongTarget() = runBlocking<Unit> {
        for (reverse in listOf(false, true)) {
            val a = BuilderEntry("A", mapOf("type" to "direct"))
            val b = BuilderEntry("B", mapOf("type" to "socks5", "dialer-proxy" to "A", "connect-via" to "A", "password" to "A"))
            val existing = BuilderResources(proxies = listOf(a, b))
            val incoming = BuilderResources(proxies = (if (reverse) listOf(b, a.copy(fields = mapOf("type" to "reject"))) else listOf(a.copy(fields = mapOf("type" to "reject")), b)))
            val merged = existing.mergeForRestore(incoming)
            val importedA = merged.resources.proxy(merged.proxyIds.getValue(incoming.proxies.single { it.name == "A" }.id!!))
            val importedB = merged.resources.proxy(merged.proxyIds.getValue(incoming.proxies.single { it.name == "B" }.id!!))
            assertNotEquals("A", importedA.name); assertNotEquals("B", importedB.name)
            assertEquals(importedA.name, importedB.fields["dialer-proxy"]); assertEquals(importedA.name, importedB.fields["connect-via"])
            assertEquals("A", importedB.fields["password"])
            assertEquals("A", merged.resources.proxies.single { it.id == existing.proxies.single { it.name == "B" }.id }.fields["dialer-proxy"])
            val source = BuilderResourceBindings(proxies = incoming.proxies.associate { it.name to it.id!! }).header() +
                "proxies: [{name: A, type: direct}, {name: B, type: direct}]\nrules: [\"MATCH,B\"]\n"
            val restored = ProfileBuilderDocument.parse(merged.resources.resolve(merged.rewriteSource(source)))
            assertEquals("A", restored.entries(BuilderSection.Proxies).single { it.name == "B" }.fields["dialer-proxy"])
            assertTrue(restored.issues().isEmpty())
        }
    }

}
