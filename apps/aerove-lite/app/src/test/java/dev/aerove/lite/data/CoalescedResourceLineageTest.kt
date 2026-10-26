package dev.aerove.lite.data

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.integration.CoalescedSourceApply
import dev.aerove.lite.integration.ResourceRuntimeApply
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CoalescedResourceLineageTest {
    @Test fun oneActualReconfigureSettlesEachWaiterAgainstItsOwnSavedSourceLineage() = runBlocking<Unit> {
        val secret = "0123456789abcdef0123456789abcdef"
        val current = PreparedProfile("profile", "mode: rule", "mode: rule", "current-source", "dynamic-generation", null, null)
        val obsolete = current.copy(revision = "old-source", generation = "old-resource")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"core-epoch":"2","route-version":"1","picks":{}}""")); server.start()
            val initial = CoreEndpoint(9091, secret, AppliedConfigSnapshot("profile", File("unused")), "previous", "previous")
            val ready = CoreEndpoint(server.port, secret, AppliedConfigSnapshot("profile", File("unused")), current.revision, current.generation)
            val snapshots = MutableStateFlow(VpnSnapshot(VpnPhase.Running, initial)); val starts = AtomicInteger()
            val applier = ResourceRuntimeApply(snapshots, { "profile" }, { current },
                { starts.incrementAndGet(); snapshots.value = VpnSnapshot(VpnPhase.Running, ready) },
                { endpoint -> CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot() } }, { true })
            val owner = CoalescedSourceApply(scope, { true }, { applier.apply(current) }, { throw AssertionError(it) })
            try {
                val first = async { runCatching { applier.verifyReceipt(obsolete, owner.requestAndAwait()) } }
                val second = async { runCatching { applier.verifyReceipt(current, owner.requestAndAwait()) } }
                val oldResult = withTimeout(5000) { first.await() }; val latestResult = withTimeout(5000) { second.await() }
                assertTrue(oldResult.exceptionOrNull() is IllegalStateException)
                assertEquals("dynamic-generation", latestResult.getOrThrow().appliedGeneration)
                assertEquals(1, starts.get()); assertEquals(1, server.requestCount)
                assertEquals("Bearer $secret", server.takeRequest().getHeader("Authorization"))
            } finally { owner.quiesce(); owner.close(); scope.cancel() }
        }
    }
}
