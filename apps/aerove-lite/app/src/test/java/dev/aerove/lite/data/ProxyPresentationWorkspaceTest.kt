package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.integration.ProxyPresentationWorkspace
import dev.aerove.lite.proxies.ProxyPresentationPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
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
}
