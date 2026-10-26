package dev.aerove.lite.vpn

import dev.aerove.lite.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceScheduleWakeupTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun resetJoinsFailedWaitThenRetriesDueKeyAndPreservesHealthyInterval() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            var now = 1_700_000_000_000L
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val repo = ProfileRepository(temporary.root, validator, clock = { now }, resources = ResourceBundleManager(
                runtime, validator, NativeResourceValidator { _, _, _, _ -> null }, clock = { now }))
            val body = "proxies:\n - {name: node, type: socks5, server: 127.0.0.1, port: 1080}\n"
            server.enqueue(MockResponse().setBody(body))
            val id = repo.importText("Main", """
                proxy-providers:
                  remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml, interval: 60}
                proxy-groups: [{name: Auto, type: select, use: [remote]}]
                rules: ['MATCH,Auto']
            """.trimIndent()).id
            val before = repo.prepareForStart(id)
            val manifest = File(before.resourceDirectory!!, "manifest.json").readBytes()
            val endpoint = CoreEndpoint(9000, "secret", resourceGeneration = before.generation)
            val endpoints = MutableStateFlow<CoreEndpoint?>(endpoint)
            val failedWait = CompletableDeferred<Unit>()
            val failedWaitJoined = CompletableDeferred<Unit>()
            val healthyWait = CompletableDeferred<Unit>()
            val healthyResetWait = CompletableDeferred<Unit>()
            val stoppedWait = CompletableDeferred<Unit>()
            var positiveWaits = 0
            now += 60_000
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(body))
            val owner = launch {
                collectResourceRefreshes(endpoints, repo) { currentEndpoint ->
                    refreshRuntimeResources(repo, id, currentEndpoint, { endpoints.value === endpoint },
                        { _, _ -> throw AssertionError("Same payload must not apply") },
                        { throw AssertionError("Same payload must not reconnect") }, {}, { now }, { delay ->
                            when {
                                delay == 0L -> if (failedWait.isCompleted) assertTrue(failedWaitJoined.isCompleted)
                                ++positiveWaits == 1 -> {
                                    assertEquals(10_000L, delay)
                                    failedWait.complete(Unit)
                                    try { awaitCancellation() } finally { failedWaitJoined.complete(Unit) }
                                }
                                positiveWaits == 2 -> {
                                    assertEquals(60_000L, delay)
                                    healthyWait.complete(Unit)
                                    awaitCancellation()
                                }
                                else -> {
                                    assertEquals(59_000L, delay)
                                    healthyResetWait.complete(Unit)
                                    try { awaitCancellation() } finally { stoppedWait.complete(Unit) }
                                }
                            }
                        })
                }
            }
            try {
                withTimeout(10_000) { failedWait.await() }
                now += 1_000
                repo.resetResourceBackoff()
                withTimeout(10_000) { healthyWait.await() }
                assertEquals(before.generation, repo.refreshSchedule(id).generation)
                assertArrayEquals(manifest, File(repo.refreshSchedule(id).resourceDirectory!!, "manifest.json").readBytes())
                val row = JSONObject(File(temporary.root, "profiles/index.json").readText()).getJSONArray("profiles").getJSONObject(0)
                assertEquals(now, row.getJSONObject("resourceCheckedAt").getLong("$.proxy-providers.remote"))
                assertEquals(now + 60_000, repo.refreshSchedule(id).nextRefreshAt)
                now += 1_000
                repo.resetResourceBackoff()
                withTimeout(10_000) { healthyResetWait.await() }
                assertEquals(3, server.requestCount)
                assertEquals(now + 59_000, repo.refreshSchedule(id).nextRefreshAt)
                endpoints.value = null
                withTimeout(10_000) { stoppedWait.await() }
            } finally { owner.cancelAndJoin() }
            assertTrue(owner.isCompleted)
            assertEquals(2L, repo.resourceScheduleRevision.value)
        } finally { server.shutdown() }
    }
}
