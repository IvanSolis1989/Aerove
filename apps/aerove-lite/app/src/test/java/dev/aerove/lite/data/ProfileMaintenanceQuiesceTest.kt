package dev.aerove.lite.data

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileMaintenanceQuiesceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = "rules: ['MATCH,DIRECT']"

    @Test fun facadeWaitsForAnAlreadyEnteredApplyCallbackAndBlocksNewRefreshUntilExplicitResume() = runBlocking<Unit> {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(source))
            server.enqueue(MockResponse().setBody(source))
            val repo = ProfileRepository(temporary.root, ProfileValidator { })
            val profile = repo.importText("Plan", source, server.url("/profile").toString())
            val facade = ProfileMaintenanceFacade(repo, owner, onUpdated = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                exited.complete(Unit)
            })
            try {
                facade.reload()
                val refresh = async(Dispatchers.Default) { facade.refresh(profile.id) }
                withTimeout(5_000) { entered.await() }
                facade.cancel(profile.id)
                assertEquals(ProfileMaintenanceResult.Cancelled, withTimeout(5_000) { refresh.await() })
                assertFalse(exited.isCompleted)
                val drain = async(Dispatchers.Default) { facade.quiesce() }
                assertNull(withTimeoutOrNull(100) { drain.await(); true })
                assertEquals(ProfileMaintenanceResult.Unavailable, facade.refresh(profile.id))
                facade.setForeground(true); facade.checkDue()
                assertEquals(1, server.requestCount)
                release.complete(Unit)
                withTimeout(5_000) { drain.await() }
                assertTrue(exited.isCompleted)
                assertEquals(ProfileMaintenanceResult.Unavailable, facade.refresh(profile.id))
                facade.resumeAfterMaintenance(false)
                assertTrue(withTimeout(5_000) { facade.refresh(profile.id) } is ProfileMaintenanceResult.Updated)
                assertEquals(2, server.requestCount)
            } finally { release.complete(Unit); facade.close(); owner.cancel() }
        }
    }

    @Test fun removedPolicyCannotHideItsStillRunningJobFromQuiesce() = runBlocking<Unit> {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
        val actor = ProfileMaintenance(owner, update = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            exited.complete(Unit)
            1234L
        })
        try {
            actor.replacePolicies(listOf(ProfileMaintenancePolicy("profile", "source", true)))
            val request = async(Dispatchers.Default) { actor.refresh("profile") }
            withTimeout(5_000) { entered.await() }
            actor.replacePolicies(emptyList())
            assertEquals(ProfileMaintenanceResult.Cancelled, request.await())
            val drain = async(Dispatchers.Default) { actor.quiesce() }
            assertNull(withTimeoutOrNull(100) { drain.await(); true })
            assertFalse(exited.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { drain.await() }
            assertTrue(exited.isCompleted)
            assertTrue(actor.snapshot().runningProfileIds.isEmpty())
        } finally { release.complete(Unit); actor.close(); owner.cancel() }
    }

    @Test fun detachedTimerCleanupIsJoinedWithoutSchedulingAReplacementDuringMaintenance() = runBlocking<Unit> {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
        val actor = ProfileMaintenance(owner, update = { error("No update expected") }, waitMillis = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            exited.complete(Unit)
        })
        try {
            actor.setForeground(true)
            withTimeout(5_000) { entered.await() }
            actor.setForeground(false)
            val drain = async(Dispatchers.Default) { actor.quiesce() }
            assertNull(withTimeoutOrNull(100) { drain.await(); true })
            release.complete(Unit)
            withTimeout(5_000) { drain.await() }
            assertTrue(exited.isCompleted)
            assertFalse(actor.snapshot().foreground)
            actor.setForeground(true); actor.checkDue()
            assertFalse(actor.snapshot().foreground)
            actor.resumeAfterMaintenance(false)
            assertFalse(actor.snapshot().foreground)
        } finally { release.complete(Unit); actor.close(); owner.cancel() }
    }
}
