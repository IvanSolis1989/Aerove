package dev.aerove.lite.backgroundpolicy

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.networksettings.NetworkRuntimeCoordinator
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ListenerLifecycleTest {
    @Test fun emptyIncludeCannotFallThroughToAllApplicationsAndOwnPackageIsNeverExcluded() {
        assertEquals(setOf("dev.aerove.lite"), vpnApplicationPolicy(dev.aerove.lite.AppFilterMode.Include,
            emptySet(), "dev.aerove.lite").allowed)
        assertEquals(emptySet<String>(), vpnApplicationPolicy(dev.aerove.lite.AppFilterMode.Exclude,
            setOf("dev.aerove.lite"), "dev.aerove.lite").disallowed)
        assertNull(vpnApplicationPolicy(dev.aerove.lite.AppFilterMode.All, setOf("other"), "dev.aerove.lite").allowed)
    }

    @Test fun privateBypassCannotRemoveTheActualIpv6SyntheticDnsGateway() {
        val reserved = vpnReservedRanges(emptyList(), true, true)
        val routes = vpnBuilderNetworkPlan(VpnPlan("id", LiteSettings(ipv6 = true)), 55555, reserved).routes
        val expected = java.net.InetAddress.getByName("fdfe:dcba:9876::2")
        assertTrue(routes.any { route -> route.substringAfter('/') == "128" &&
            java.net.InetAddress.getByName(route.substringBefore('/')) == expected })
        assertTrue(vpnReservedRanges(emptyList(), false, true).isEmpty())
    }
    private class Session(val parkGate: CompletableDeferred<Unit>? = null,
        override val builderReservedFakeIpRanges: List<String> = emptyList(),
        override val coreReservedFakeIpRanges: List<String> = builderReservedFakeIpRanges,
        override val builderDnsEnabled: Boolean = true,
        override val coreDnsEnabled: Boolean = builderDnsEnabled,
        private val readyGate: CompletableDeferred<Unit>? = null,
        override val initialListenerReadback: ListenerReadback? = null,
        private val plannedBuilder: LiteSettings? = null,
    ) : ListenerPolicySession {
        override val endpoint = CoreEndpoint(12345, "secret")
        override var establishedBuilderSettings: LiteSettings? = null
        val events = mutableListOf<String>()
        var configured: LiteSettings? = null
        val waitingForExit = CompletableDeferred<Unit>()
        override suspend fun awaitReady() { readyGate?.await() }
        override suspend fun awaitExit() { waitingForExit.complete(Unit); awaitCancellation() }
        override suspend fun stop() { events += "stop"; parkGate?.complete(Unit) }
        override suspend fun configurePolicies(settings: LiteSettings) { configured = settings }
        override suspend fun configureNetworkDns(snapshot: dev.aerove.lite.data.SystemDnsSnapshot) = Unit
        override suspend fun setListening(enabled: Boolean): ListenerReadback {
            events += if (enabled) "resume" else "park"
            if (enabled) establishedBuilderSettings = plannedBuilder
            if (!enabled) parkGate?.await()
            return ListenerReadback(enabled, enabled, enabled, "1")
        }
    }

    @Test fun parksAndResumesSameCoreWhileRunIntentAndBuilderRemain() = runBlocking {
        val session = Session()
        var opens = 0
        val coordinator = VpnCoordinator(this, { opens++; session })
        val plan = VpnPlan("old", LiteSettings())
        coordinator.start(plan); coordinator.state.first { it.phase == VpnPhase.Running }
        val endpoint = coordinator.state.value.endpoint
        coordinator.setListenerPolicy(false)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.suspended)
        assertFalse(coordinator.state.value.listening)
        assertNotNull(coordinator.requestedBuilderSettings())
        coordinator.setListenerPolicy(true)
        assertSame(endpoint, coordinator.state.value.endpoint)
        assertEquals(1, opens)
        assertEquals(listOf("park", "resume"), session.events)
        coordinator.close()
    }

    @Test fun stopCancelsAcceptedParkWithoutWaitingForPolicyMutex() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val session = Session(gate)
        val coordinator = VpnCoordinator(this, { session })
        coordinator.start(VpnPlan("id", LiteSettings())); coordinator.state.first { it.phase == VpnPhase.Running }
        val parking = launch { coordinator.setListenerPolicy(false) }
        yield(); assertEquals(listOf("park"), session.events)
        withTimeout(1000) { coordinator.stopAndJoin() }
        parking.join()
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertEquals(listOf("park", "stop"), session.events)
        coordinator.close()
    }

    @Test fun supersededParkStillPublishesActualStateSoLatestResumeIsNotSkipped() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val session = Session(gate)
        val coordinator = VpnCoordinator(this, { session })
        coordinator.start(VpnPlan("id", LiteSettings())); coordinator.state.first { it.phase == VpnPhase.Running }
        var current = true
        val parking = launch { coordinator.setListenerPolicy(false) { current } }
        yield(); current = false; gate.complete(Unit); parking.join()
        assertTrue(coordinator.state.value.suspended)
        coordinator.setListenerPolicy(true)
        assertTrue(coordinator.state.value.listening)
        assertEquals(listOf("park", "resume"), session.events)
        coordinator.close()
    }

    @Test fun networkPostAndReadbackFinishBeforeParkAndNewSavesWaitForResume() = runBlocking {
        val initial = NetworkSettings()
        val accepted = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val network = NetworkRuntimeCoordinator(this, { _, _, _ ->
            events += "post"
            accepted.await()
            events += "readback"
            if (events.count { it == "readback" } == 2) resumed.complete(Unit)
        })
        val endpoint = CoreEndpoint(12345, "secret")
        network.settings(initial, false)
        network.bind(endpoint, initial, false)
        network.settings(initial.copy(ports = UserProxyPorts(8001)), false); yield()
        val park = launch { network.parkBarrier(endpoint); events += "park" }
        yield()
        network.settings(initial.copy(ports = UserProxyPorts(8002)), false)
        assertEquals(listOf("post"), events)
        accepted.complete(Unit); park.join(); yield()
        assertEquals(listOf("post", "readback", "park"), events)
        network.resume(endpoint); resumed.await()
        assertEquals(listOf("post", "readback", "park", "post", "readback"), events)
        network.close()
    }

    @Test fun detachedNetworkCancelsBlockedReadbackAndReleasesParkBarrier() = runBlocking {
        val initial = NetworkSettings()
        val entered = CompletableDeferred<Unit>()
        val network = NetworkRuntimeCoordinator(this, { _, _, _ -> entered.complete(Unit); awaitCancellation() })
        val endpoint = CoreEndpoint(12345, "secret")
        network.settings(initial, false); network.bind(endpoint, initial, false)
        network.settings(initial.copy(ports = UserProxyPorts(8001)), false)
        entered.await()
        val park = launch { network.parkBarrier(endpoint) }; yield()
        withTimeout(1000) { network.detachAndJoin(endpoint); park.join() }
        network.close()
    }

    @Test fun maintenanceCapturesActualBuilderAndPolicyUpdatesNeverReopenCore() = runBlocking {
        val session = Session()
        var opens = 0
        val coordinator = VpnCoordinator(this, { opens++; session })
        val baseline = LiteSettings()
        coordinator.start(VpnPlan("old", baseline)); coordinator.state.first { it.phase == VpnPhase.Running }
        val desired = baseline.copy(network = baseline.network.copy(vpnEnabled = false),
            backgroundPolicy = BackgroundPolicyPreferences(onlyStatisticsProxy = false))
        coordinator.configurePolicies(desired)
        assertEquals(desired, session.configured)
        val receipt = coordinator.maintenanceContext(true)
        assertTrue(receipt.userRequested)
        assertEquals(baseline, receipt.builderSettings)
        assertEquals("old", receipt.previousPlan?.profileId)
        assertEquals(1, opens)
        coordinator.close()
    }

    @Test fun reconfigureAndRestoreReceiptRetainActualDnsAndPrivateFakePoolBuilderBaseline() = runBlocking {
        val opened = mutableListOf<VpnPlan>()
        val oldRange = listOf("10.20.0.0/16")
        val newRange = listOf("172.20.0.0/16")
        val coordinator = VpnCoordinator(this, { plan ->
            opened += plan
            Session(builderReservedFakeIpRanges = plan.vpnReservedFakeIpRanges ?: oldRange,
                coreReservedFakeIpRanges = if (opened.size == 1) oldRange else newRange,
                builderDnsEnabled = plan.vpnDnsEnabled ?: true, coreDnsEnabled = opened.size == 1)
        })
        val baseline = LiteSettings()
        coordinator.start(VpnPlan("old", baseline)); coordinator.state.first { it.phase == VpnPhase.Running }
        assertFalse(requiresVpnRestart(baseline, coordinator.state.value))
        coordinator.reconfigure(VpnPlan("new", baseline))
        val actual = coordinator.state.first { it.phase == VpnPhase.Running }
        assertEquals(oldRange, actual.initialReservedFakeIpRanges)
        assertEquals(newRange, actual.appliedReservedFakeIpRanges)
        assertEquals(true, actual.initialDnsEnabled)
        assertEquals(false, actual.appliedDnsEnabled)
        assertTrue(requiresVpnRestart(baseline, actual))
        val receipt = coordinator.maintenanceContext(true)
        assertEquals(oldRange, receipt.previousPlan?.vpnReservedFakeIpRanges)
        assertEquals(true, receipt.previousPlan?.vpnDnsEnabled)
        coordinator.close()
    }

    @Test fun internalReconnectUsesLatestCorePolicyAndRetainsInstalledBuilder() = runBlocking {
        val baseline = LiteSettings()
        val desired = baseline.copy(mode = dev.aerove.lite.ProxyMode.Global,
            network = baseline.network.copy(vpnEnabled = false, mtu = 1400))
        val opened = mutableListOf<VpnPlan>()
        val coordinator = VpnCoordinator(this, { plan -> opened += plan; Session() }, latestSavedSettings = { desired })
        coordinator.start(VpnPlan("id", baseline)); coordinator.state.first { it.phase == VpnPhase.Running }
        coordinator.reconnect(); coordinator.state.first { it.phase == VpnPhase.Running }
        assertEquals(dev.aerove.lite.ProxyMode.Global, opened.last().settings.mode)
        assertFalse(opened.last().settings.network.vpnEnabled)
        assertEquals(baseline.network, opened.last().builderSettings().network)
        coordinator.close()
    }

    @Test fun anObsoletePreStartupExclusionCannotParkTheNewReadySession() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val session = Session(readyGate = ready)
        val coordinator = VpnCoordinator(this, { session })
        var oldObservationCurrent = true
        coordinator.setListenerPolicy(false) { oldObservationCurrent }
        coordinator.start(VpnPlan("id", LiteSettings()))
        oldObservationCurrent = false
        ready.complete(Unit)
        session.waitingForExit.await()
        assertTrue(session.events.isEmpty())
        coordinator.setListenerPolicy(true)
        assertTrue(coordinator.state.value.listening)
        assertFalse(coordinator.state.value.suspended)
        coordinator.close()
    }
    @Test fun initiallyParkedHasNoActualBuilderUntilFirstResumeAndRetainsCoreOwner() = runBlocking {
        val planned = LiteSettings()
        val session = Session(initialListenerReadback = ListenerReadback(false, false, false, "1"), plannedBuilder = planned)
        var opens = 0
        val coordinator = VpnCoordinator(this, { opens++; session })
        coordinator.setListenerPolicy(false)
        coordinator.start(VpnPlan("id", planned))
        val parked = coordinator.state.first { it.phase == VpnPhase.Running }
        assertTrue(parked.suspended)
        assertFalse(parked.listening)
        assertNull(parked.initialSettings)
        assertNull(parked.initialNetwork)
        assertNull(parked.initialIpv6)
        assertEquals(planned, parked.plannedBuilderSettings)
        assertFalse(requiresVpnRestart(planned, parked))
        assertTrue(requiresVpnRestart(planned.copy(network = planned.network.copy(mtu = 1400)), parked))
        assertTrue(requiresVpnRestart(planned.copy(network = planned.network.copy(vpnEnabled = false)), parked))
        assertTrue(session.events.isEmpty())
        assertNull(coordinator.maintenanceContext(true).builderSettings)
        assertEquals(planned, coordinator.maintenanceContext(true).previousPlan?.builderSettings())
        coordinator.setListenerPolicy(true)
        val resumed = coordinator.state.value
        assertSame(parked.endpoint, resumed.endpoint)
        assertEquals(planned, resumed.initialSettings)
        assertTrue(resumed.listening)
        assertEquals(listOf("resume"), session.events)
        assertEquals(1, opens)
        coordinator.close()
    }

    @Test fun initiallyParkedNetworkBindingKeepsLatestDesiredUntilResume() = runBlocking {
        val applied = mutableListOf<Int>()
        val completed = CompletableDeferred<Unit>()
        val actor = NetworkRuntimeCoordinator(this, { _, settings, _ -> applied += settings.ports.mixed; completed.complete(Unit) })
        val initial = NetworkSettings()
        val endpoint = CoreEndpoint(12345, "secret")
        actor.bind(endpoint, initial, false, initiallyParked = true)
        actor.settings(initial.copy(ports = UserProxyPorts(8001)), false)
        actor.settings(initial.copy(ports = UserProxyPorts(8002)), false)
        yield()
        assertTrue(applied.isEmpty())
        actor.resume(endpoint)
        withTimeout(1000) { completed.await() }
        assertEquals(listOf(8002), applied)
        actor.close()
    }

}
