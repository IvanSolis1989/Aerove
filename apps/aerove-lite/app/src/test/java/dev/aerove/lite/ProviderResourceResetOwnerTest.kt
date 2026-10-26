package dev.aerove.lite

import dev.aerove.lite.data.*
import dev.aerove.lite.platform.*
import dev.aerove.lite.vpn.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ProviderResourceResetOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val now = AtomicLong(1_700_000_000_000L)
    private val body = "proxies:\n - {name: node, type: socks5, server: 127.0.0.1, port: 1080}\n"
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun repository(): ProfileRepository {
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        return ProfileRepository(temporary.root, validator, clock = now::get, resources = ResourceBundleManager(
            runtime, validator, NativeResourceValidator { _, _, _, _ -> null }, clock = now::get))
    }
    private suspend fun initial(repo: ProfileRepository): PreparedProfile {
        server.enqueue(MockResponse().setBody(body))
        val id = repo.importText("Main", """
            proxy-providers:
              remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml, interval: 60}
            proxy-groups: [{name: Auto, type: select, use: [remote]}]
            rules: ['MATCH,Auto']
        """.trimIndent()).id
        return repo.prepareForStart(id)
    }

    @Test fun physicalProducerResetsExistingRepositoryBeforePublishingRevisionWithoutController() = runBlocking<Unit> {
        var initialized = 0
        val profiles = lazy { initialized++; repository() }
        val reset = ProviderResourceResetOwner(profiles)
        lateinit var events: PhysicalNetworkEvents<String, Boolean, String>
        val driver = object : PhysicalNetworkDriver<String, Boolean, String> {
            override val activeNetwork = "wifi"
            override fun seed() = ObservedPhysicalNetworks(setOf("wifi"), mapOf("wifi" to true), mapOf("wifi" to "1.1.1.1"))
            override fun register(includeLocation: Boolean, observer: PhysicalNetworkEvents<String, Boolean, String>): AutoCloseable {
                assertFalse(includeLocation)
                events = observer
                return AutoCloseable { }
            }
        }
        lateinit var physical: LeasedPhysicalNetworks<String, Boolean, String>
        var observedOrdering = false
        physical = LeasedPhysicalNetworks(driver, { true }, { it }, { network, dns -> "$network:$dns" }, { "10.0.0.2" }) { revision ->
            if (revision == 2L) assertEquals(1L, physical.revision.value)
            reset.network(revision)
            if (revision == 2L) {
                assertEquals(1L, profiles.value.resourceScheduleRevision.value)
                observedOrdering = true
            }
        }
        val serviceOnly = physical.acquire()
        try {
            assertEquals(0, initialized)
            val repo = profiles.value
            val before = initial(repo)
            now.addAndGet(60_000)
            server.enqueue(MockResponse().setResponseCode(503))
            try { repo.refreshIfDue(before.profileId); fail("failure required") } catch (_: java.io.IOException) { }
            assertEquals(now.get() + 10_000, repo.refreshSchedule(before.profileId).nextRefreshAt)
            events.links("wifi", "9.9.9.9")
            assertTrue(observedOrdering)
            assertEquals(now.get(), repo.refreshSchedule(before.profileId).nextRefreshAt)
            assertEquals(1L, repo.resourceScheduleRevision.value)
            events.links("wifi", "9.9.9.9")
            reset.network(2L)
            assertEquals(1L, repo.resourceScheduleRevision.value)
            server.enqueue(MockResponse().setBody(body))
            assertNull(repo.refreshIfDue(before.profileId))
            assertEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
            assertEquals(now.get() + 60_000, repo.refreshSchedule(before.profileId).nextRefreshAt)
            events.links("wifi", "8.8.8.8")
            assertEquals(2L, repo.resourceScheduleRevision.value)
            assertEquals(now.get() + 60_000, repo.refreshSchedule(before.profileId).nextRefreshAt)
            assertEquals(3, server.requestCount)
        } finally { serviceOnly.close() }
    }

    @Test fun actualCoordinatorReceiptDistinguishesSameEndpointNewSessionDespitePreservedUptime() = runBlocking<Unit> {
        val repo = repository()
        val profiles = lazyOf(repo)
        val reset = ProviderResourceResetOwner(profiles)
        val endpoint = CoreEndpoint(9000, "secret")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stopped = 0
        var opened = 0
        fun backend() = TunnelBackend {
            if (opened > 0) assertEquals(opened, stopped)
            opened++
            object : TunnelSession {
                override val endpoint = endpoint
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() { stopped++ }
            }
        }
        val first = VpnCoordinator(scope, backend(), onSessionReady = { reset.runtime(7L, it) }, nanoTime = { 100L })
        try {
            first.start(VpnPlan("profile", LiteSettings()))
            val a = withTimeout(5_000) { first.state.first { it.phase == VpnPhase.Running } }
            assertEquals(1L, a.sessionGeneration)
            assertEquals(1L, repo.resourceScheduleRevision.value)
            reset.runtime(7L, a.copy(suspended = true, listening = false))
            reset.runtime(7L, a.copy(phase = VpnPhase.Starting))
            first.recordListenerError("readback")
            reset.runtime(7L, first.state.value)
            assertEquals(a.sessionGeneration, first.state.value.sessionGeneration)
            assertEquals(1L, repo.resourceScheduleRevision.value)
            first.start(VpnPlan("profile", LiteSettings()), 200L)
            val b = withTimeout(5_000) { first.state.first { it.phase == VpnPhase.Running && it.sessionGeneration != a.sessionGeneration } }
            assertSame(a.endpoint, b.endpoint)
            assertEquals(a.runningSinceNanos, b.runningSinceNanos)
            assertNotEquals(a.sessionGeneration, b.sessionGeneration)
            assertEquals(2L, repo.resourceScheduleRevision.value)
            first.close()
            val nextOwner = VpnCoordinator(scope, backend(), onSessionReady = { reset.runtime(8L, it) }, nanoTime = { 100L })
            try {
                nextOwner.start(VpnPlan("profile", LiteSettings()))
                val c = withTimeout(5_000) { nextOwner.state.first { it.phase == VpnPhase.Running } }
                assertSame(endpoint, c.endpoint)
                assertEquals(a.sessionGeneration, c.sessionGeneration)
                assertEquals(3L, repo.resourceScheduleRevision.value)
                reset.runtime(8L, c)
                assertEquals(3L, repo.resourceScheduleRevision.value)
            } finally { nextOwner.close() }
        } finally { first.close(); scope.cancel() }
    }

    @Test fun unknownSessionAndOrdinaryPhasesCannotResetHealthyInterval() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        val reset = ProviderResourceResetOwner(lazyOf(repo))
        val endpoint = CoreEndpoint(9000, "secret")
        reset.runtime(1L, VpnSnapshot(VpnPhase.Running, endpoint))
        reset.runtime(1L, VpnSnapshot(VpnPhase.Starting, endpoint, sessionGeneration = 1L))
        assertEquals(0L, repo.resourceScheduleRevision.value)
        val next = repo.refreshSchedule(before.profileId).nextRefreshAt
        now.addAndGet(10_000)
        reset.network(1L)
        reset.network(1L)
        assertEquals(1L, repo.resourceScheduleRevision.value)
        assertEquals(next, repo.refreshSchedule(before.profileId).nextRefreshAt)
        assertEquals(1, server.requestCount)
    }
}
