package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ConfigYaml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderResourceLibraryTest {
    @Before fun exactEngine() { dev.aerove.lite.regexp2.ExactFilterRuntime.install(RealGoFilterExecutor()) }
    @get:Rule val temporary = TemporaryFolder()
    private val gallery = """{"name":"Network","icons":[{"name":"One","url":"https://example.com/one.png"},{"url":"file:///secret"},{"url":"https://example.com/one.png"},{"url":"https://example.com/two.png"}]}"""

    @Test fun iconGalleryMatchesStandardFilteringAndKeepsActualSourceMetrics() {
        val set = BuilderResources.parseIconSet(gallery, "https://example.com/gallery.json", now = 1234)
        assertEquals(listOf("One", "two.png"), set.icons.map { it.name })
        assertEquals(1234L, set.updatedAt)
        assertEquals(gallery.toByteArray().size.toLong(), set.sourceBytes)
        val item = BuilderResources(iconSets = listOf(set)).enumerate().single()
        assertEquals(2, item.count)
        assertEquals(set.url, item.url)
    }

    @Test fun savedLibraryReopensAndStaleWriterCannotOverwrite() = runBlocking<Unit> {
        val first = BuilderResourceLibrary(temporary.root)
        val empty = first.snapshot()
        val resources = BuilderResources(filters = listOf(BuilderFilter("Asia", "JP|SG")),
            iconSets = listOf(BuilderResources.parseIconSet(gallery, now = 1234)))
        val saved = first.replace(empty, resources)
        assertEquals(resources, BuilderResourceLibrary(temporary.root).snapshot().resources)
        assertNotNull(saved.encoded)
        val failure = runCatching { first.replace(empty, BuilderResources()) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(saved, first.snapshot())
    }

    @Test fun invalidRegexOrDuplicateNameNeverChangesSavedLibrary() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(temporary.root)
        val snapshot = library.snapshot()
        assertTrue(runCatching { library.replace(snapshot, BuilderResources(filters = listOf(BuilderFilter("bad", "[")))) }.isFailure)
        val duplicate = BuilderResources(filters = listOf(BuilderFilter("a", "a"), BuilderFilter("a", "b")))
        assertTrue(runCatching { library.replace(snapshot, duplicate) }.isFailure)
        assertEquals(snapshot, library.snapshot())
    }

    @Test fun corruptSavedLibraryIsNotTreatedAsEmpty() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(temporary.root)
        library.replace(library.snapshot(), BuilderResources())
        val file = java.io.File(temporary.root, "settings/builder-resources.yaml")
        file.writeText("schema: 500\n")
        assertTrue(runCatching { library.snapshot() }.isFailure)
        assertEquals("schema: 500\n", file.readText())
    }

    @Test fun boundNodeReadsLatestGlobalFieldsEachTimeAndKeepsSourceImmutable() = runBlocking<Unit> {
        val original = BuilderEntry("global", mapOf("type" to "socks5", "server" to "old.example", "port" to 1080))
        val resources = BuilderResources(proxies = listOf(original))
        val source = resources.bindProxy(requireNotNull(resources.proxies.single().id), ProfileBuilderDocument.empty()).yaml()
        assertTrue(source.startsWith("# aerove-builder-bindings:"))
        val updated = resources.copy(proxies = listOf(resources.proxies.single().copy(fields = original.fields + ("server" to "new.example"))))
        val resolved = ProfileBuilderDocument.parse(updated.resolve(source))
        assertEquals("new.example", resolved.entries(BuilderSection.Proxies).single().fields["server"])
        assertEquals("old.example", ProfileBuilderDocument.parse(source).entries(BuilderSection.Proxies).single().fields["server"])
        assertEquals(BuilderResourceBindings(), resolved.bindings)
        assertEquals("Missing proxy resource ID: ${resources.proxies.single().id}",
            runCatching { BuilderResources().resolve(source) }.exceptionOrNull()!!.message)
    }

    @Test fun filterResolutionRecomputesMatchesRatherThanKeepingStaleMembers() = runBlocking<Unit> {
        var document = ProfileBuilderDocument.empty()
        for (name in listOf("JP", "US")) document = document.put(BuilderSection.Proxies, null, name, mapOf("type" to "direct"))
        document = document.put(BuilderSection.Groups, null, "group", mapOf("type" to "select", "proxies" to listOf("DIRECT", "JP")))
        val filter = BuilderFilter("region", "^JP$")
        val bindings = BuilderResourceBindings(filters = mapOf("group" to BuilderFilterBinding(listOf(filter.id), listOf("DIRECT"))))
        val source = document.withBindings(bindings).yaml()
        val updated = BuilderResources(filters = listOf(filter.copy(regex = "^US$")))
        val resolved = ProfileBuilderDocument.parse(updated.resolve(source))
        assertEquals(listOf("DIRECT", "US"), resolved.entries(BuilderSection.Groups).single().fields["proxies"])
    }

    @Test fun iconAndRuleProviderBindingsResolveAndDeletedIconsGiveExactPath() = runBlocking<Unit> {
        val set = BuilderResources.parseIconSet(gallery, now = 1234)
        val provider = BuilderEntry("rules", mapOf("type" to "http", "url" to "https://example.com/rules.yaml", "path" to "rules.yaml", "behavior" to "domain"))
        val resources = BuilderResources(ruleProviders = listOf(provider), iconSets = listOf(set))
        var document = resources.bindRuleProvider(requireNotNull(resources.ruleProviders.single().id), ProfileBuilderDocument.empty())
        document = document.put(BuilderSection.Groups, null, "group", mapOf("type" to "select", "proxies" to listOf("DIRECT")))
        document = document.withBindings(document.bindings.copy(icons = mapOf("group" to BuilderIconBinding(set.id, set.icons[0].url))))
        val source = document.yaml()
        val resolved = ProfileBuilderDocument.parse(resources.resolve(source))
        assertEquals(set.icons[0].url, resolved.entries(BuilderSection.Groups).single().fields["icon"])
        val missingSet = runCatching { resources.copy(iconSets = emptyList()).resolve(source) }.exceptionOrNull()
        assertEquals("Missing icon set resource ID: ${set.id}", missingSet!!.message)
        val missingIcon = runCatching { resources.copy(iconSets = listOf(set.copy(icons = emptyList()))).resolve(source) }.exceptionOrNull()
        assertEquals("$.proxy-groups.group.icon: Missing icon ${set.icons[0].url}", missingIcon!!.message)
    }

    @Test fun editingBoundItemMakesItLocalAndMetadataSurvivesReordering() {
        val resources = BuilderResources(proxies = listOf(BuilderEntry("global", mapOf("type" to "direct"))))
        val document = resources.bindProxy(requireNotNull(resources.proxies.single().id), ProfileBuilderDocument.empty())
        val reopened = ProfileBuilderDocument.parse(document.move(BuilderSection.Proxies, 0, 0).yaml())
        assertEquals(mapOf("global" to requireNotNull(resources.proxies.single().id)), reopened.bindings.proxies)
        val edited = reopened.put(BuilderSection.Proxies, 0, "local", mapOf("type" to "direct"))
        assertTrue(edited.bindings.proxies.isEmpty())
        assertFalse(ConfigYaml.parse(edited.yaml()).containsKey("aerove-builder-bindings"))
    }

    @Test fun malformedAndDuplicateBindingHeadersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProfileBuilderDocument.parse("# aerove-builder-bindings: ?\nrules: ['MATCH,DIRECT']") }
        val header = BuilderResourceBindings(proxies = mapOf("node" to java.util.UUID.randomUUID().toString())).header()
        assertThrows(IllegalArgumentException::class.java) { ProfileBuilderDocument.parse(header + header + "rules: ['MATCH,DIRECT']") }
    }

    @Test fun standardBacktickSeparatedFilterPartsMatchAsAlternatives() = runBlocking<Unit> {
        var document = ProfileBuilderDocument.empty()
        for (name in listOf("JP node", "US node", "SG node")) document = document.put(BuilderSection.Proxies, null, name, mapOf("type" to "direct"))
        val resources = BuilderResources(filters = listOf(BuilderFilter("regions", "^JP`^SG")))
        assertEquals(listOf("JP node", "SG node"), resources.matching(resources.filters.single(), document))
        assertEquals(listOf("SG node", "JP node"), resources.matching(BuilderFilter("reordered", "^SG`^JP`^SG"), document))
        assertThrows(IllegalArgumentException::class.java) { BuilderResources(filters = listOf(BuilderFilter("empty", ""))).validate() }
    }
}
