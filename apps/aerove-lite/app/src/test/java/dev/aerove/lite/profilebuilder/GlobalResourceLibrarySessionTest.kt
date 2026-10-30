package dev.aerove.lite.profilebuilder

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlobalResourceLibrarySessionTest {
    @get:Rule val directory = TemporaryFolder()
    private fun library() = BuilderResourceLibrary(directory.root)
    // These cases have no profiles; production must supply the Host source-header journal adapter.
    private fun session(library: BuilderResourceLibrary) = GlobalResourceLibrarySession(library,
        GlobalResourceDeletion { expected, _, _, replacement -> library.replace(expected, replacement) })
    @Test fun proxyCrudCommitsAndReopensWithoutSecondStore() = runBlocking<Unit> {
        val library = library(); val session = session(library); session.load()
        assertTrue(session.putProxy(null, "first", mapOf("type" to "direct")))
        assertTrue(session.putProxy(library.snapshot().resources.proxies.single().id, "renamed", mapOf("type" to "direct", "udp" to true)))
        val reopened = session(library); reopened.load()
        assertEquals(listOf("renamed"), reopened.state.value.snapshot!!.resources.proxies.map { it.name })
        assertTrue(reopened.remove(BuilderResourceKind.Proxy, reopened.state.value.snapshot!!.resources.enumerate().map { it.id }.toSet()))
        assertTrue(library.snapshot().resources.proxies.isEmpty())
    }
    @Test fun sortOrderPersistsAndInvalidPermutationNeverWrites() = runBlocking<Unit> {
        val library = library(); val session = session(library); session.load()
        for (name in listOf("a", "b", "c")) assertTrue(session.putProxy(null, name, mapOf("type" to "direct")))
        assertTrue(session.reorder(BuilderResourceKind.Proxy, listOf("c", "a", "b").map { label -> session.state.value.snapshot!!.resources.proxies.single { it.name == label }.id!! }))
        val saved = library.snapshot()
        assertEquals(listOf("c", "a", "b"), saved.resources.proxies.map { it.name })
        assertFalse(session.reorder(BuilderResourceKind.Proxy, listOf("a", "a", "b")))
        assertEquals(saved, library.snapshot())
    }
    @Test fun staleWriterPreservesOtherSessionAndReloadRecovers() = runBlocking<Unit> {
        val library = library(); val first = session(library); val second = session(library)
        first.load(); second.load()
        assertTrue(first.putProxy(null, "a", mapOf("type" to "direct")))
        assertFalse(second.putProxy(null, "b", mapOf("type" to "direct")))
        assertNotNull(second.state.value.error)
        second.load()
        assertTrue(second.putProxy(null, "b", mapOf("type" to "direct")))
        assertEquals(listOf("a", "b"), library.snapshot().resources.proxies.map { it.name })
    }
    @Test fun formCancellationAndNavigationDoNotSaveDrafts() = runBlocking<Unit> {
        val library = library(); val session = session(library); session.load()
        val opened = library.snapshot()
        session.open(BuilderResourceKind.Proxy, "missing")
        assertEquals("missing", session.state.value.target!!.id)
        session.clearTarget(); session.query("any"); session.toggleSelected("missing")
        assertTrue(session.back()); assertTrue(session.back()); assertTrue(session.back()); assertFalse(session.back())
        assertEquals(opened, library.snapshot())
    }
    @Test fun iconsImportRenameReorderDeleteKeepsContentAndMetrics() = runBlocking<Unit> {
        val library = library(); val session = session(library); session.load()
        val json = """{"name":"icons","icons":[{"name":"A","url":"https://example.com/a.png"}]}"""
        assertTrue(session.importIcons(GlobalLibraryFile("icons.json", json.toByteArray())))
        assertTrue(session.editIcons(library.snapshot().resources.iconSets.single().id, "renamed", ""))
        val actual = library.snapshot().resources.iconSets.single()
        assertEquals("renamed", actual.name); assertEquals(json.toByteArray().size.toLong(), actual.sourceBytes)
        assertEquals(listOf(BuilderIcon("A", "https://example.com/a.png")), actual.icons)
        assertTrue(session.remove(BuilderResourceKind.IconSet, session.state.value.snapshot!!.resources.enumerate().map { it.id }.toSet()))
        assertTrue(library.snapshot().resources.iconSets.isEmpty())
    }
    @Test fun searchUsesAllWhitespaceSeparatedTermsAcrossFields() = runBlocking<Unit> {
        val session = session(library()); session.load()
        session.putProxy(null, "Japan", mapOf("type" to "socks5", "server" to "edge.example", "port" to 443L))
        session.open(BuilderResourceKind.Proxy); session.query("JAPAN   edge")
        assertEquals(listOf("Japan"), session.visible().map { it.name })
        session.query("Japan other"); assertTrue(session.visible().isEmpty())
    }
    @Test fun quickEditRejectsDuplicatesAndStaleContentWithoutChangingSource() = runBlocking<Unit> {
        val library = library(); val session = session(library); session.load()
        session.putProxy(null, "a", mapOf("type" to "direct"))
        val initial = library.snapshot()
        val duplicate = listOf(BuilderEntry("same", mapOf("type" to "direct")), BuilderEntry("same", mapOf("type" to "direct")))
        assertFalse(session.replaceProxies(duplicate, initial.encoded)); assertEquals(initial, library.snapshot())
        session.putProxy(null, "b", mapOf("type" to "direct"))
        assertFalse(session.replaceProxies(emptyList(), initial.encoded))
        assertEquals(listOf("a", "b"), library.snapshot().resources.proxies.map { it.name })
    }
    @Test fun filterCrudUsesRealExactEngineAndInvalidSyntaxPreservesSavedData() = runBlocking<Unit> {
        dev.aerove.lite.regexp2.ExactFilterRuntime.install(RealGoFilterExecutor())
        val library = library(); val session = session(library); session.load()
        assertTrue(session.putFilter(null, "consonants", "^[a-z-[aeiou]]$"))
        val saved = library.snapshot()
        assertFalse(session.putFilter(saved.resources.filters.single().id, "broken", "["))
        assertEquals(saved, library.snapshot())
        assertTrue(session.putFilter(saved.resources.filters.single().id, "renamed", "(?i)jp|sg"))
        assertEquals("renamed", library.snapshot().resources.filters.single().label)
        assertEquals(saved.resources.filters.single().id, library.snapshot().resources.filters.single().id)
        assertTrue(session.remove(BuilderResourceKind.Filter, session.state.value.snapshot!!.resources.enumerate().map { it.id }.toSet()))
    }
    @Test fun remoteIconsHonorExplicitNameAndSameUrlUpdatesExistingResource() = runBlocking<Unit> {
        val content = """{"name":"From JSON","icons":[{"name":"one","url":"https://example.com/one.png"}]}""".toByteArray()
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody(content.decodeToString())) }
            server.start()
            val library = library(); val session = session(library); session.load()
            val address = server.url("/icons.json").toString()
            assertTrue(session.importIconsUrl("Requested", address))
            assertEquals("Requested", library.snapshot().resources.iconSets.single().name)
            assertTrue(session.importIconsUrl("Updated", address))
            val actual = library.snapshot().resources.iconSets.single()
            assertEquals("Updated", actual.name); assertEquals(address, actual.url); assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("GET", request.method); assertEquals("/icons.json", request.path)
            }
        }
    }

    @Test fun quickEditRenameUsesDeletionTransactionAndRetainsUnchangedIdentity() = runBlocking<Unit> {
        val library = library(); var detached = emptySet<String>()
        val session = GlobalResourceLibrarySession(library, GlobalResourceDeletion { expected, kind, ids, replacement ->
            assertEquals(BuilderResourceKind.Proxy, kind); detached = ids
            library.replace(expected, replacement)
        })
        session.load(); session.putProxy(null, "keep", mapOf("type" to "direct")); session.putProxy(null, "old", mapOf("type" to "direct"))
        val before = library.snapshot(); val keep = before.resources.proxies.single { it.name == "keep" }.id
        val removed = before.resources.proxies.single { it.name == "old" }.id
        assertTrue(session.replaceProxies(listOf(BuilderEntry("keep", mapOf("type" to "direct", "udp" to true)), BuilderEntry("new", mapOf("type" to "direct"))), before.encoded))
        val actual = library.snapshot().resources
        assertEquals(setOf(removed), detached); assertEquals(keep, actual.proxies.single { it.name == "keep" }.id)
        assertNotEquals(removed, actual.proxies.single { it.name == "new" }.id)
    }

}
