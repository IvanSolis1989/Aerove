package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.networksettings.NetworkRuntimeStatus
import dev.aerove.lite.networksettings.NetworkRuntimeRequest
import dev.aerove.lite.networksettings.NetworkRuntimeOutcome
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.networksettings.ProxyAuthentication
import dev.aerove.lite.networksettings.effectiveVpnNetworkOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnCoordinatorTest {
    private class Session(private val ready: CompletableDeferred<Unit> = CompletableDeferred()) : TunnelSession {
        override val endpoint = CoreEndpoint(1234, "test-secret")
        var stopped = false
        override suspend fun awaitReady() { ready.await() }
        override suspend fun awaitExit(): Unit = awaitCancellation()
        override suspend fun stop() { stopped = true }
        fun ready() { ready.complete(Unit) }
    }

    @Test fun maintenanceStopCannotAcknowledgeBeforeOwnedSessionJoin() = runTest {
        val joined = CompletableDeferred<Unit>()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            object : TunnelSession {
                override val endpoint = CoreEndpoint(1234, "test-secret")
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() { joined.await() }
            }
        })
        coordinator.start(VpnPlan("profile", LiteSettings())); runCurrent()
        var acknowledged = false
        val stop = launch { coordinator.stopAndJoin(); acknowledged = true }
        runCurrent()
        assertEquals(VpnPhase.Stopping, coordinator.state.value.phase)
        assertTrue(!acknowledged)
        joined.complete(Unit); runCurrent()
        assertTrue(acknowledged)
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        stop.join(); coordinator.close()
    }

    @Test
    fun stoppingAnUnreadyStartCannotPublishRunning() = runTest {
        val session = Session()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { session })
        coordinator.start(VpnPlan("profile", LiteSettings()))
        runCurrent()
        assertEquals(VpnPhase.Starting, coordinator.state.value.phase)
        coordinator.stop()
        session.ready()
        runCurrent()
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertTrue(session.stopped)
        coordinator.close()
    }

    @Test
    fun replacingProfileJoinsPreviousOwnerBeforeOpeningAnother() = runTest {
        val previous = Session().apply { ready() }
        val next = Session().apply { ready() }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) previous else {
                assertTrue(previous.stopped)
                next
            }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("second", LiteSettings()))
        runCurrent()
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        assertEquals(2, opened)
        coordinator.close()
        assertTrue(next.stopped)
    }

    @Test
    fun replacingSessionWithdrawsItsEndpointBeforeWaitingForJoin() = runTest {
        val joined = CompletableDeferred<Unit>()
        val previous = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop() { joined.await() }
        }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) previous else Session().apply { ready() }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        assertEquals(VpnPhase.Starting, coordinator.state.value.phase)
        assertNull(coordinator.state.value.endpoint)
        runCurrent()
        assertEquals(1, opened)
        joined.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test
    fun cleanupFailureAfterJoinDoesNotBlockTheNextInstance() = runTest {
        val events = mutableListOf<String>()
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            opened++
            object : TunnelSession {
                override val endpoint = CoreEndpoint(1234, "test-secret")
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() = stopNativeSession(
                    join = { events += "joined" },
                    cleanup = { error("disk cleanup failed") },
                    warn = { events += it.message.orEmpty() },
                )
            }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        runCurrent()
        assertEquals(listOf("joined", "disk cleanup failed"), events)
        assertEquals(2, opened)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test
    fun unconfirmedJoinPreservesResourcesAndReportsFailure() = runTest {
        var released = false
        var reported = false
        try {
            stopNativeSession(
                join = { error("join failed") },
                cleanup = { released = true },
                warn = { reported = true },
            )
            error("An unconfirmed join must fail")
        } catch (failure: IllegalStateException) {
            assertEquals("join failed", failure.message)
        }
        assertEquals(false, released)
        assertEquals(false, reported)
    }

    @Test
    fun failedTerminationDoesNotPermitAnotherInstance() = runTest {
        var opened = 0
        val broken = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop(): Unit = error("join failed")
        }
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opened++; broken })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        runCurrent()
        assertEquals(1, opened)
        assertEquals(VpnPhase.Failed, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test fun runningNetworkBaselineComesFromReadyPlanAndIgnoresOtherEndpointReceipts() = runTest {
        val session = Session()
        val initial = NetworkSettings(ports = UserProxyPorts(7900))
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { session })
        coordinator.start(VpnPlan("profile", LiteSettings(network = initial, ipv6 = true)))
        runCurrent()
        assertNull(coordinator.state.value.initialNetwork)
        session.ready(); runCurrent()
        assertEquals(initial, coordinator.state.value.initialNetwork)
        assertEquals(true, coordinator.state.value.initialIpv6)
        val applied = initial.copy(ports = UserProxyPorts(7901))
        val request = NetworkRuntimeRequest(2, applied, true, NetworkRuntimeOutcome.Applied)
        coordinator.recordNetworkStatus(NetworkRuntimeStatus(CoreEndpoint(1234, "test-secret"), applied, true, request = request))
        assertEquals(initial, coordinator.state.value.appliedNetwork)
        assertNull(coordinator.state.value.networkRequest)
        coordinator.recordNetworkStatus(NetworkRuntimeStatus(session.endpoint, initial, true))
        assertTrue(coordinator.state.value.networkApplying)
        coordinator.recordNetworkStatus(NetworkRuntimeStatus(session.endpoint, applied, false, request = request))
        assertEquals(applied, coordinator.state.value.appliedNetwork)
        assertEquals(request, coordinator.state.value.networkRequest)
        assertEquals(initial, coordinator.state.value.initialNetwork)
        coordinator.close()
        coordinator.recordNetworkStatus(NetworkRuntimeStatus(session.endpoint, initial, true))
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertNull(coordinator.state.value.endpoint)
    }

    @Test fun hostNetworkCleanupCompletesBeforeNativeStopAndReplacement() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val previous = Session().apply { ready() }
        val next = Session().apply { ready() }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) previous else {
                assertTrue(previous.stopped)
                next
            }
        }, beforeSessionStop = { endpoint -> if (endpoint === previous.endpoint) cleanup.await() })
        coordinator.start(VpnPlan("first", LiteSettings())); runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings())); runCurrent()
        assertEquals(1, opened)
        assertEquals(false, previous.stopped)
        assertNull(coordinator.state.value.endpoint)
        cleanup.complete(Unit); runCurrent()
        assertTrue(previous.stopped)
        assertEquals(2, opened)
        coordinator.close()
    }

    @Test fun closeJoinsNetworkBarrierAndIsTerminal() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val session = Session().apply { ready() }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opened++; session },
            beforeSessionStop = { cleanup.await() })
        coordinator.start(VpnPlan("profile", LiteSettings())); runCurrent()
        val close = launch { coordinator.close() }; runCurrent()
        assertEquals(false, close.isCompleted)
        assertEquals(false, session.stopped)
        cleanup.complete(Unit); runCurrent()
        assertTrue(close.isCompleted)
        assertTrue(session.stopped)
        coordinator.start(VpnPlan("ignored", LiteSettings())); coordinator.reconnect(); runCurrent()
        assertEquals(1, opened)
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
    }

    @Test fun internalReconnectUsesSavedInboundIntentWithoutConfirmingBuilderChanges() = runTest {
        for (authenticatedAtStart in listOf(false, true)) {
            val startup = LiteSettings(network = NetworkSettings(bypassDomains = listOf("*.startup"),
                authentication = ProxyAuthentication(authenticatedAtStart, "startup", "password")), fontFamily = "startup-font")
            var saved = startup
            val plans = mutableListOf<VpnPlan>()
            val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { plan ->
                plans += plan
                Session().apply { ready() }
            }, latestSavedSettings = { saved })
            coordinator.start(VpnPlan("profile", startup)); runCurrent()
            saved = startup.copy(network = NetworkSettings(systemProxy = false, allowBypass = false,
                bypassDomains = listOf("*.pending"), ports = UserProxyPorts(7900, 7901, 7902), allowLan = true,
                authentication = ProxyAuthentication(!authenticatedAtStart, "latest", "latest-password")),
                ipv6 = true, fontFamily = "pending-font")
            coordinator.reconnect(); runCurrent()
            val implicit = plans.last()
            assertEquals(saved.network, implicit.settings.network)
            assertTrue(implicit.settings.ipv6)
            assertEquals(startup.fontFamily, implicit.settings.fontFamily)
            assertEquals(startup.network, implicit.vpnNetwork)
            assertEquals(startup.ipv6, implicit.vpnIpv6)
            assertEquals(startup.network, coordinator.state.value.initialNetwork)
            assertEquals(startup.ipv6, coordinator.state.value.initialIpv6)
            assertEquals(saved.network, coordinator.state.value.appliedNetwork)
            assertEquals(saved.ipv6, coordinator.state.value.appliedIpv6)
            assertEquals(effectiveVpnNetworkOptions(startup.network),
                effectiveVpnNetworkOptions(checkNotNull(coordinator.state.value.initialNetwork)))
            coordinator.start(VpnPlan("profile", saved)); runCurrent()
            assertNull(plans.last().vpnNetwork)
            assertNull(plans.last().vpnIpv6)
            assertEquals(saved.network, coordinator.state.value.initialNetwork)
            assertEquals(saved.ipv6, coordinator.state.value.initialIpv6)
            coordinator.close()
        }
    }

    @Test fun reconnectReadsLatestSavedInboundAfterTheOldStopBarrier() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val previous = Session().apply { ready() }
        val startup = LiteSettings()
        var saved = startup
        var sourceReads = 0
        val plans = mutableListOf<VpnPlan>()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { plan ->
            plans += plan
            if (plans.size == 1) previous else {
                assertTrue(previous.stopped)
                Session().apply { ready() }
            }
        }, beforeSessionStop = { if (it === previous.endpoint) stopped.await() }, latestSavedSettings = {
            sourceReads++
            saved
        })
        coordinator.start(VpnPlan("profile", startup)); runCurrent()
        saved = startup.copy(network = startup.network.copy(ports = UserProxyPorts(7900)))
        coordinator.reconnect(); runCurrent()
        assertEquals(1, plans.size)
        assertEquals(0, sourceReads)
        saved = saved.copy(network = saved.network.copy(ports = UserProxyPorts(7902), allowLan = true))
        coordinator.reconnect(); runCurrent()
        stopped.complete(Unit); runCurrent()
        assertEquals(2, plans.size)
        assertEquals(1, sourceReads)
        assertEquals(saved.network, plans.last().settings.network)
        assertEquals(startup.network, plans.last().vpnNetwork)
        coordinator.close()
    }
}
