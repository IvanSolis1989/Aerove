package dev.aerove.lite.data

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProfileMaintenanceResetEpochTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oldHttpFailureCannotRestoreBackoffAfterReset() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val second = CompletableDeferred<Unit>(); val requests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (requests.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setResponseCode(503)
                } else { second.complete(Unit); MockResponse().setBody("healthy") }
            }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sweeps = Channel<Unit>(Channel.UNLIMITED)
            val downloader = SubscriptionDownloader()
            val maintenance = ProfileMaintenance(scope, { downloader.download(server.url("/profile").toString()); 2_000_000_000L },
                now = { 2_000_000_000L }, waitMillis = { awaitCancellation() }, afterAutomaticSweep = { sweeps.send(Unit) })
            try {
                maintenance.replacePolicies(listOf(ProfileMaintenancePolicy("p", "source", true, intervalMillis = 86_400_000, lastUpdatedAtMillis = 0)))
                maintenance.setForeground(true); maintenance.checkDue()
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                maintenance.resetAutomaticBackoff()
                assertTrue("reset must not cancel the foreground flight", "p" in maintenance.runningProfileIds.value)
                release.countDown(); withTimeout(5000) { sweeps.receive() }
                maintenance.checkDue(); withTimeout(3000) { second.await(); sweeps.receive() }
                assertEquals(2, requests.get())
                assertEquals(2_000_000_000L, maintenance.snapshot().cachedUpdatedAtMillis["p"])
                maintenance.checkDue(); withTimeout(5000) { sweeps.receive() }
                assertEquals(2, requests.get())
            } finally { release.countDown(); maintenance.close(); scope.coroutineContext[Job]!!.cancelAndJoin(); sweeps.close() }
        }
    }

    @Test fun resetKeepsManualAndForegroundSuccessAndNormalInterval() = runBlocking<Unit> {
        for (manual in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val entered = CompletableDeferred<ProfileMaintenanceRequest>(); val release = CompletableDeferred<Unit>()
            val sweeps = Channel<Unit>(Channel.UNLIMITED); var calls = 0
            val now = 2_000_000_000L
            val maintenance = ProfileMaintenance(scope, { request -> calls++; entered.complete(request); release.await(); now },
                now = { now }, waitMillis = { awaitCancellation() }, dispatcher = Dispatchers.Unconfined,
                afterAutomaticSweep = { sweeps.send(Unit) })
            try {
                maintenance.replacePolicies(listOf(ProfileMaintenancePolicy("p", "source", true, intervalMillis = 86_400_000,
                    lastUpdatedAtMillis = if (manual) now else 0)))
                maintenance.setForeground(true)
                val normalCheck = maintenance.snapshot().nextCheckAtMillis
                val work = if (manual) async { maintenance.refresh("p") } else { maintenance.checkDue(); null }
                val request = entered.await(); maintenance.resetAutomaticBackoff()
                assertTrue(request.isCurrent()); assertTrue("p" in maintenance.runningProfileIds.value)
                release.complete(Unit)
                if (manual) assertTrue(withTimeout(5000) { checkNotNull(work).await() } is ProfileMaintenanceResult.Updated)
                else withTimeout(5000) { sweeps.receive() }
                assertEquals(now, maintenance.snapshot().cachedUpdatedAtMillis["p"])
                assertEquals(normalCheck, maintenance.snapshot().nextCheckAtMillis)
                maintenance.checkDue(); withTimeout(5000) { sweeps.receive() }
                assertEquals(1, calls)
            } finally { release.complete(Unit); maintenance.close(); scope.coroutineContext[Job]!!.cancelAndJoin(); sweeps.close() }
        }
    }

    @Test fun subscriptionOnlyFacadeResetDoesNotResetProviderSchedule() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ProfileRepository(File(temporary.root, "profiles-owner"), ProfileValidator { })
        val facade = ProfileMaintenanceFacade(repository, scope)
        try {
            val revision = repository.resourceScheduleRevision.value
            facade.resetSubscriptionBackoff()
            assertEquals(revision, repository.resourceScheduleRevision.value)
            facade.resetAutomaticBackoff()
            assertEquals(revision + 1, repository.resourceScheduleRevision.value)
        } finally { facade.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
