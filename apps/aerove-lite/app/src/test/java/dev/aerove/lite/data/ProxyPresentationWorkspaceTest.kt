package dev.aerove.lite.data

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import dev.aerove.lite.proxies.ProxyPresentationPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProxyPresentationWorkspaceTest {
    private fun profile(group: String? = null, unfolded: Set<String> = emptySet()) =
        ProfileSummary("profile", "name", null, 0, currentProxyGroup = group, unfoldedProxyGroups = unfolded)
    @Test fun immutableAppliedSourceIsReadOnceAcrossTabVisitsAndMetadataIsActuallyReadBack() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        val saved = CompletableDeferred<ProfileSummary>()
        val client = CoreApiClient(ControlEndpoint(9091, "0123456789abcdef0123456789abcdef"))
        val workspace = ProxyPresentationWorkspace(scope, { ProxyPresentationPreferences() },
            { _, group, unfolded -> profile(group, unfolded) }, { saved.complete(it) }, { true })
        try {
            workspace.profile(profile()); assertTrue(workspace.state.value.unfolded.isEmpty())
            workspace.bind(client, "profile", client, { reads.incrementAndGet(); "proxy-groups:\n  - name: group\n    type: select\n    url: https://example.com/test\n    icon: https://example.com/icon.svg?rev=2\n    proxies: [DIRECT]" }, { true })
            workspace.active(true)
            withTimeout(3000) { while (!workspace.state.value.ready) yield() }
            assertEquals("https://example.com/test", workspace.state.value.groupUrls["group"])
            assertEquals("https://example.com/icon.svg?rev=2", workspace.state.value.groupIcons["group"])
            workspace.active(false); workspace.active(true)
            assertTrue(workspace.state.value.ready); assertEquals(1, reads.get())
            workspace.toggleGroup("group")
            assertEquals(setOf("group"), withTimeout(3000) { saved.await() }.unfoldedProxyGroups)
        } finally { workspace.closeAndJoin(); client.close(); scope.cancel() }
    }
    @Test fun maintenanceWaitsForMetadataAlreadyInsideAnUncancellableCommitAndRejectsNewIntent() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val published = AtomicInteger()
        val workspace = ProxyPresentationWorkspace(scope, { ProxyPresentationPreferences() }, { _, group, unfolded ->
            withContext(NonCancellable) { entered.complete(Unit); release.await(); profile(group, unfolded) }
        }, { published.incrementAndGet() }, { true })
        try {
            workspace.profile(profile()); workspace.selectGroup("group")
            withTimeout(3000) { entered.await() }
            val barrier = async { workspace.quiesce() }
            yield(); assertFalse(barrier.isCompleted)
            workspace.toggleGroup("must-not-save")
            release.complete(Unit); withTimeout(3000) { barrier.await() }
            val count = published.get(); workspace.selectGroup("ignored")
            assertEquals("group", workspace.state.value.currentGroup); assertEquals(count, published.get())
            workspace.resumeAfterMaintenance()
        } finally { release.complete(Unit); workspace.closeAndJoin(); scope.cancel() }
    }
    @Test fun sameNamedGraphEpochClearsDelaysWithoutAutomaticRequestsAndOldEpochCannotEraseNewResult() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef"))
            val reads = AtomicInteger()
            val workspace = ProxyPresentationWorkspace(scope, { ProxyPresentationPreferences() },
                { _, group, unfolded -> profile(group, unfolded) }, {}, { true })
            try {
                workspace.profile(profile())
                workspace.bind(client, "profile", client, { reads.incrementAndGet(); "proxy-groups: []" }, { true })
                workspace.groups(listOf(ProxyGroup("group", "Selector", "A", listOf(ProxyNode("A", "Direct")))))
                workspace.active(true)
                withTimeout(3000) { workspace.state.first { it.ready } }
                workspace.routeEpoch(client, 10uL)
                server.enqueue(MockResponse().setBody("{\"delay\":42}"))
                workspace.testNode("group", "A")
                withTimeout(3000) { workspace.state.first { it.delay.results.values.singleOrNull() == 42 } }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                repeat(4) { workspace.routeEpoch(client, 10uL) }
                assertEquals(42, workspace.state.value.delay.results.values.single())
                workspace.routeEpoch(client, 11uL)
                assertTrue(workspace.state.value.delay.results.isEmpty())
                assertEquals(1, server.requestCount)
                assertEquals(1, reads.get())
                server.enqueue(MockResponse().setBody("{\"delay\":7}"))
                workspace.testNode("group", "A")
                withTimeout(3000) { workspace.state.first { it.delay.results.values.singleOrNull() == 7 } }
                workspace.routeEpoch(client, 10uL)
                assertEquals(7, workspace.state.value.delay.results.values.single())
                assertEquals(2, server.requestCount)
            } finally { workspace.closeAndJoin(); client.close(); scope.cancel() }
        }
    }

    @Test fun epochChangeCancelsActualDelayAndForeignBindingCannotInvalidateReplacement() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef"))
            val workspace = ProxyPresentationWorkspace(scope, { ProxyPresentationPreferences() },
                { _, group, unfolded -> profile(group, unfolded) }, {}, { true })
            val oldBinding = Any()
            val newBinding = Any()
            try {
                workspace.profile(profile())
                workspace.bind(oldBinding, "profile", client, { "proxy-groups: []" }, { true })
                workspace.groups(listOf(ProxyGroup("group", "Selector", "A", listOf(ProxyNode("A", "Direct")))))
                workspace.active(true)
                withTimeout(3000) { workspace.state.first { it.ready } }
                workspace.routeEpoch(oldBinding, 20uL)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                workspace.testNode("group", "A")
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                workspace.routeEpoch(oldBinding, 21uL)
                assertTrue(workspace.state.value.delay.pending.isEmpty())
                workspace.bind(newBinding, "profile", client, { "proxy-groups: []" }, { true })
                withTimeout(3000) { workspace.state.first { it.ready } }
                workspace.routeEpoch(newBinding, 1uL)
                server.enqueue(MockResponse().setBody("{\"delay\":7}"))
                workspace.testNode("group", "A")
                withTimeout(3000) { workspace.state.first { it.delay.results.values.singleOrNull() == 7 } }
                workspace.routeEpoch(oldBinding, 22uL)
                assertEquals(7, workspace.state.value.delay.results.values.single())
                assertEquals(2, server.requestCount)
            } finally { workspace.closeAndJoin(); client.close(); scope.cancel() }
        }
    }

}
