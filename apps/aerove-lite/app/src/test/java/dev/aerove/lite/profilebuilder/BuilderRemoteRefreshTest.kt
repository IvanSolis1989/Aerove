package dev.aerove.lite.profilebuilder

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderRemoteRefreshTest {
    @get:Rule val directory = TemporaryFolder()
    private fun icon(name: String, url: String) = BuilderIconSet(name, url, listOf(BuilderIcon("old", "https://example.com/old.png")))
    private suspend fun responseGate(expectedRequests: Int = 2, block: suspend (String, CountDownLatch, CountDownLatch, AtomicInteger) -> Unit) {
        val started = CountDownLatch(expectedRequests); val release = CountDownLatch(1); val count = AtomicInteger()
        val executor = Executors.newCachedThreadPool(); val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            val number = count.incrementAndGet(); started.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                val bytes = """{"name":"download","icons":[{"name":"new$number","url":"https://example.com/$number.png"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}", started, release, count) }
        finally { release.countDown(); server.stop(0); executor.shutdownNow() }
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
