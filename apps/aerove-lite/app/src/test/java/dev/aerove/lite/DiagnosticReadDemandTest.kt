package dev.aerove.lite

import dev.aerove.lite.data.*
import dev.aerove.lite.telemetry.OutboundAnswer
import dev.aerove.lite.telemetry.OutboundProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReadDemandTest {
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    private fun answer(target: ServiceTarget, epoch: ULong = 1u, version: ULong = 0u) = ServiceCheck(target,
        ServiceProbeStatus.Available, "US", 42, listOf("node"), 1234, epoch, version,
        listOf(RouteHit("${target.id}.example:443", exit = "node")))
    @Test fun automaticFailureDoesNotRetryForFocusButExplicitRequestStillRuns() = runBlocking<Unit> {
        var calls = 0
        val owner = DiagnosticsCoordinator<Int>(this, { _, targets -> calls++; targets.map { answer(it).copy(status = ServiceProbeStatus.Timeout) } },
            { MemorySnapshot(null, null) }, { true })
        try {
            owner.bind(1); owner.route(1u, 0u, emptyMap()); owner.watch(setOf("google"))
            until { calls == 1 && !owner.state.value.serviceLoading }
            repeat(3) { owner.readDemand(false); owner.readDemand(true) }; delay(350); assertEquals(1, calls)
            owner.readDemand(false); owner.refresh(setOf("google"))
            until { calls == 2 && !owner.state.value.serviceLoading }; assertEquals("timeout", owner.state.value.services.first().status)
        } finally { owner.close() }
    }
    @Test fun explicitRefreshAdoptsRunningRequestAndSurvivesFocusAndWatchLoss() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = DiagnosticsCoordinator<Int>(this, { _, targets -> calls++; release.await(); targets.map { answer(it) } },
            { MemorySnapshot(null, null) }, { true })
        try {
            owner.bind(1); owner.route(1u, 0u, emptyMap()); owner.watch(setOf("google")); until { calls == 1 }
            owner.refresh(setOf("google")); assertTrue(owner.explicitPending)
            owner.readDemand(false); owner.watch(emptySet()); release.complete(Unit)
            until { !owner.state.value.serviceLoading }; assertEquals("available", owner.state.value.services.first().status)
            assertEquals(1, calls); assertFalse(owner.explicitPending)
        } finally { release.complete(Unit); owner.close() }
    }
    @Test fun verifiedCompletingServiceAdvancesRouteWithoutCancelOrExtraProbe() = runBlocking<Unit> {
        lateinit var owner: DiagnosticsCoordinator<Int>; var calls = 0
        owner = DiagnosticsCoordinator(this, { _, targets ->
            calls++; owner.route(2u, 7u, emptyMap(), verifiedServices = targets.mapTo(mutableSetOf()) { it.id })
            targets.map { answer(it, 2u, 7u) }
        }, { MemorySnapshot(null, null) }, { true })
        try {
            owner.bind(1); owner.route(1u, 0u, emptyMap()); owner.readDemand(false); owner.refresh(setOf("google"))
            until { !owner.state.value.serviceLoading }
            assertEquals(1, calls); assertEquals(2uL, owner.state.value.services.first().coreEpoch)
            assertEquals(7uL, owner.state.value.services.first().routeVersion); assertEquals("available", owner.state.value.services.first().status)
        } finally { owner.close() }
    }
    @Test fun memoryReadCancellationJoinsBeforeResumeAndDropsLateSample() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = DiagnosticsCoordinator<Int>(this, { _, targets -> targets.map { answer(it) } }, {
            val n = ++calls; if (n == 1) withContext(NonCancellable) { release.await() }
            MemorySnapshot(n.toULong(), null)
        }, { true })
        try {
            owner.bind(1); owner.memory(true); until { calls == 1 }
            owner.readDemand(false); owner.readDemand(true); delay(20); assertEquals(1, calls)
            release.complete(Unit); until { owner.state.value.memoryInUseBytes == 2L }; assertEquals(2, calls)
            owner.readDemand(false); delay(20); assertEquals(2L, owner.state.value.memoryInUseBytes)
        } finally { release.complete(Unit); owner.close() }
    }
    private fun outbound(epoch: ULong = 1u, version: ULong = 0u) = OutboundAnswer(
        OutboundIdentity("1.1.1.1", "US", null, "unknown"), listOf("node"),
        listOf(RouteHit("example:443", exit = "node")), epoch, version)
    @Test fun outboundFailureKeepsCooldownAcrossFocusAndManualAdoptsLiveRead() = runBlocking<Unit> {
        var calls = 0; val release = CompletableDeferred<Unit>()
        val owner = OutboundProbe<Int>(this, { calls++; if (calls == 1) error("offline") else { release.await(); outbound() } }, { true })
        try {
            owner.bind(1); owner.setActive(true); owner.synchronize(emptyMap(), 1u, 0u)
            until { owner.state.value.error != null }
            repeat(3) { owner.readDemand(false); owner.readDemand(true) }; delay(350); assertEquals(1, calls)
            owner.refresh(); until { calls == 2 }; owner.readDemand(false); release.complete(Unit)
            until { !owner.state.value.loading }; assertEquals("1.1.1.1", owner.state.value.identity?.address)
            assertEquals(2, calls); assertFalse(owner.explicitPending)
        } finally { release.complete(Unit); owner.close() }
    }
    @Test fun verifiedCompletingOutboundAdvancesStampWithoutSelfCancellation() = runBlocking<Unit> {
        lateinit var owner: OutboundProbe<Int>; var calls = 0
        owner = OutboundProbe(this, { calls++; owner.confirmRoute(emptyMap(), 2u, 7u); outbound(2u, 7u) }, { true })
        try {
            owner.bind(1); owner.readDemand(false); owner.setActive(true); owner.synchronize(emptyMap(), 1u, 0u); owner.refresh()
            until { !owner.state.value.loading && owner.state.value.identity != null }
            assertEquals(1, calls); assertFalse(owner.explicitPending)
        } finally { owner.close() }
    }
}
