package dev.aerove.lite.profilebuilder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderRemoteRefreshTest {
    @get:Rule val directory = TemporaryFolder()
    private fun icon(name: String, url: String) = BuilderIconSet(name, url, listOf(BuilderIcon("old", "https://example.com/old.png")))
    private suspend fun responseGate(expectedRequests: Int = 2, block: suspend (String, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit) {
        val started = CountDownLatch(expectedRequests); val release = CountDownLatch(1); val count = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(request.method == "GET")
                val number = count.incrementAndGet(); started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                val body = """{"name":"download","icons":[{"name":"new$number","url":"https://example.com/$number.png"}]}"""
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        try { block(server.url("/").toString().trimEnd('/'), started, release, count) }
        finally { release.countDown(); server.close() }
    }
    @Test fun differentIdsBothStartHttpAndMergeWithoutLosingEitherResult() = runBlocking<Unit> {
        responseGate { url, started, release, count ->
            val library = BuilderResourceLibrary(directory.root)
            val saved = library.replace(library.snapshot(), BuilderResources(iconSets = listOf(icon("one", "$url/one"), icon("two", "$url/two"))))
            coroutineScope {
                val updates = saved.resources.iconSets.map { set -> async(Dispatchers.IO) { library.syncIconSet(saved, set.id) } }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) }); release.countDown()
                updates.awaitAll()
            }
            val actual = library.snapshot().resources.iconSets
            assertEquals(2, count.get()); assertEquals(saved.resources.iconSets.map { it.id }, actual.map { it.id })
            assertTrue(actual.all { it.icons.single().name.startsWith("new") }); assertEquals(2, actual.map { it.icons.single().url }.distinct().size)
        }
    }
    @Test fun sameIdUrlEditAndReturnRejectsLateResponseEvenWhenEntryMatchesAgain() = runBlocking<Unit> {
        responseGate(1) { url, started, release, count ->
            val library = BuilderResourceLibrary(directory.root)
            val saved = library.replace(library.snapshot(), BuilderResources(iconSets = listOf(icon("one", "$url/one"))))
            coroutineScope {
                val update = async(Dispatchers.IO) { runCatching { library.syncIconSet(saved, saved.resources.iconSets.single().id) } }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                val changed = library.replace(saved, saved.resources.copy(iconSets = listOf(saved.resources.iconSets.single().copy(url = "$url/changed"))))
                val returned = library.replace(changed, saved.resources)
                release.countDown()
                assertTrue(update.await().exceptionOrNull() is IllegalArgumentException)
                assertEquals(returned, library.snapshot()); assertEquals(1, count.get())
            }
        }
    }
    @Test fun maintenanceEpochRejectsStagedResponseEvenWhenRestoredEntryIsIdentical() = runBlocking<Unit> {
        responseGate(1) { url, started, release, _ ->
            val library = BuilderResourceLibrary(directory.root)
            val saved = library.replace(library.snapshot(), BuilderResources(iconSets = listOf(icon("one", "$url/one"))))
            coroutineScope {
                val update = async(Dispatchers.IO) { runCatching { library.syncIconSet(saved, saved.resources.iconSets.single().id) } }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                library.invalidateRemoteRefreshes(); release.countDown()
                assertTrue(update.await().exceptionOrNull() is IllegalArgumentException)
                assertEquals(saved, library.snapshot())
            }
        }
    }
    @Test fun competingSameIdRequestsCannotBothCommit() = runBlocking<Unit> {
        responseGate { url, started, release, count ->
            val library = BuilderResourceLibrary(directory.root)
            val saved = library.replace(library.snapshot(), BuilderResources(iconSets = listOf(icon("one", "$url/one"))))
            val outcomes = coroutineScope {
                val pending = (0..1).map { async(Dispatchers.IO) { runCatching { library.syncIconSet(saved, saved.resources.iconSets.single().id) } } }
                assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) }); release.countDown(); pending.awaitAll()
            }
            assertEquals(2, count.get()); assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is IllegalArgumentException)
        }
    }

}
