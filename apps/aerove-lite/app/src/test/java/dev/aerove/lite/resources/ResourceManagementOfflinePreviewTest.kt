package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementOfflinePreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = "$.rule-providers.Rules"
    private suspend fun fixture(server: MockWebServer): Pair<ProfileRepository, String> {
        server.enqueue(MockResponse().setBody("native-mrs-boundary-fixture"))
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val profiles = ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(runtime,
            validator, NativeResourceValidator { _, path, behavior, format ->
                assertEquals("native-mrs-boundary-fixture", File(path).readText())
                assertEquals("domain", behavior); assertEquals("mrs", format); null
            }))
        val id = profiles.importText("Fixture", """
            proxies: []
            proxy-groups: []
            rule-providers:
              Rules:
                type: http
                url: ${server.url("/rules.mrs")}
                path: rules.mrs
                behavior: domain
                format: mrs
            rules: ['RULE-SET,Rules,DIRECT', 'MATCH,DIRECT']
        """.trimIndent()).id
        return profiles to id
    }
    private fun inspector(action: (File) -> String) = object : NativeRulePayloadInspector {
        override fun inspect(path: String): RulePayloadInspection = error("Preview must call full offline dump")
        override fun dump(path: String, behavior: String, format: String): String {
            assertEquals("domain", behavior); assertEquals("mrs", format)
            return action(File(path))
        }
    }

    @Test fun fullDumpUsesActualLeasedFileOffCallerThreadAndReleasesAfterSuccess() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); val (profiles, id) = fixture(server)
            val caller = Thread.currentThread()
            val expected = (0..600).joinToString("\n", postfix = "\n") { "rule$it.example.com" }
            lateinit var file: File
            val repository = ResourceManagementRepository(profiles, offlineRules = inspector { target ->
                file = target; assertNotEquals(caller, Thread.currentThread())
                assertEquals("native-mrs-boundary-fixture", target.readText())
                runBlocking { profiles.delete(id); profiles.collectGarbage() }
                assertTrue("Lease protects deleted profile payload until dump returns", target.isFile)
                expected
            })
            assertEquals(expected, repository.preview(id, key))
            assertEquals(601, expected.lines().count { it.isNotEmpty() })
            profiles.collectGarbage(); assertFalse(file.exists())
        }
    }

    @Test fun offlineFailureReleasesLeaseAndPreservesOriginalError() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); val (profiles, id) = fixture(server)
            val failure = IOException("fixture dump failed")
            lateinit var file: File
            val repository = ResourceManagementRepository(profiles, inspector { target ->
                file = target
                runBlocking { profiles.delete(id); profiles.collectGarbage() }
                assertTrue(target.exists()); throw failure
            })
            try { repository.preview(id, key); fail("Expected dump failure") } catch (error: IOException) { assertSame(failure, originalFailure(error)) }
            profiles.collectGarbage(); assertFalse(file.exists())
        }
    }

    // kotlinx.coroutines 1.10.2 recovery keeps the original as the cause of a same-type copy.
    private fun originalFailure(error: Throwable): Throwable {
        val cause = error.cause
        return if (cause != null && cause.javaClass == error.javaClass &&
            error.stackTrace.any { it.className.startsWith("_COROUTINE.") }) cause else error
    }

    @Test fun cancellationKeepsLeaseThroughBlockingDumpThenReleasesWithoutLateResult() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); val (profiles, id) = fixture(server)
            val entered = CountDownLatch(1); val finish = CountDownLatch(1)
            lateinit var file: File
            var published = false
            val repository = ResourceManagementRepository(profiles, inspector { target ->
                file = target; entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS)); assertTrue(target.isFile)
                "late result\n"
            })
            val job = launch(Dispatchers.Default) { repository.preview(id, key); published = true }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                job.cancel(); profiles.delete(id); profiles.collectGarbage()
                assertTrue(file.exists()); assertFalse(job.isCompleted)
            } finally { finish.countDown(); job.join() }
            assertTrue(job.isCancelled); assertFalse(published)
            profiles.collectGarbage(); assertFalse(file.exists())
        }
    }
}
