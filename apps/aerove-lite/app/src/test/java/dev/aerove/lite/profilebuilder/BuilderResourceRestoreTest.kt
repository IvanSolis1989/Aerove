package dev.aerove.lite.profilebuilder

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BuilderResourceRestoreTest {
    @Test fun collisionsRenameImportedResourcesAndOnlyImportedBindings() = runBlocking<Unit> {
        val existing = BuilderResources(proxies = listOf(BuilderEntry("node", mapOf("type" to "direct"))),
            filters = listOf(BuilderFilter("region", "JP")))
        val imported = BuilderResources(proxies = listOf(BuilderEntry("node", mapOf("type" to "reject"))),
            filters = listOf(BuilderFilter("region", "US")))
        val document = imported.bindProxy("node", ProfileBuilderDocument.empty())
        val source = document.yaml()
        val merged = existing.mergeForRestore(imported)
        assertEquals("node 2", merged.proxyNames["node"])
        assertEquals("region 2", merged.filterNames["region"])
        assertEquals(listOf("node", "node 2"), merged.resources.proxies.map { it.name })
        val rewritten = ProfileBuilderDocument.parse(merged.rewriteSource(source))
        assertEquals(mapOf("node" to "node 2"), rewritten.bindings.proxies)
        assertEquals("node", rewritten.entries(BuilderSection.Proxies).single().name)
        assertEquals(mapOf("node" to "node"), ProfileBuilderDocument.parse(source).bindings.proxies)
        val resolved = ProfileBuilderDocument.parse(merged.resources.resolve(rewritten.yaml()))
        assertEquals("reject", resolved.entries(BuilderSection.Proxies).single().fields["type"])
    }

    @Test fun equalResourcesAreReusedAndIncomingNamesAreReserved() {
        val existing = BuilderResources(filters = listOf(BuilderFilter("a", "a")))
        assertEquals(existing, existing.mergeForRestore(existing).resources)
        val imported = BuilderResources(filters = listOf(BuilderFilter("a", "b"), BuilderFilter("a 2", "c")))
        val merged = existing.mergeForRestore(imported)
        assertEquals("a 3", merged.filterNames["a"])
        assertEquals("a 2", merged.filterNames["a 2"])
        assertEquals(3, merged.resources.filters.size)
    }

    @Test fun unboundSourcePassesThroughByteForByte() = runBlocking<Unit> {
        val source = "# keep comment\r\nmode: direct\r\nrules: ['MATCH,DIRECT']\r\n"
        assertEquals(source, BuilderResources().resolve(source))
        assertEquals(source, BuilderResources().mergeForRestore(BuilderResources()).rewriteSource(source))
    }

    @Test fun profilesOnlyResourceSelectionCarriesBoundDefinitions() {
        val resources = BuilderResources(proxies = listOf(BuilderEntry("used", mapOf("type" to "direct")),
            BuilderEntry("other", mapOf("type" to "reject"))),
            filters = listOf(BuilderFilter("unused", "a")))
        val source = resources.bindProxy("used", ProfileBuilderDocument.empty()).yaml()
        val selected = resources.referencedBy(listOf(source))
        assertEquals(listOf("used"), selected.proxies.map { it.name })
        assertTrue(selected.filters.isEmpty())
    }
}
