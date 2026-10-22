package dev.aerove.lite

import dev.aerove.lite.data.MemorySnapshot
import dev.aerove.lite.data.ServiceCheck
import dev.aerove.lite.data.ServiceProbeStatus
import dev.aerove.lite.data.ServiceTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsCoordinatorTest {
    private fun answer(target: ServiceTarget, epoch: ULong = 1u, picks: ULong = 0u,
        chains: List<String> = listOf("node", "group")) = ServiceCheck(target,
        ServiceProbeStatus.Available, "US", 42, chains, 1234, epoch, picks)

    @Test fun visibleTargetsAreMergedAndCachedUntilTheirRouteMoves() = runBlocking {
        val calls = mutableListOf<ServiceTarget>()
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            calls += targets; targets.map { answer(it) }
        }, { MemorySnapshot(12u, null) }, { true })
        coordinator.settings(LiteSettings())
        coordinator.bind(1)
        coordinator.route(1u, 0u, mapOf("group" to "node"))
        coordinator.watch(setOf("google"))
        repeat(6) { yield() }
        assertEquals(listOf(ServiceTarget.Google), calls)
        coordinator.watch(setOf("google"))
        coordinator.refresh(setOf("google"))
        coordinator.refresh(setOf("google"))
        repeat(6) { yield() }
        assertEquals(2, calls.size)
        coordinator.route(1u, 1u, mapOf("group" to "other"))
        repeat(6) { yield() }
        assertTrue(coordinator.state.value.services.first().status != "available")
        coordinator.close()
    }

    @Test fun obsoleteEndpointResponseCannotOverwriteCurrentSession() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val coordinator = DiagnosticsCoordinator<Int>(this, { key, targets ->
            if (key == 1) withContext(NonCancellable) { held.await() }
            targets.map { answer(it, epoch = key.toULong()) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.settings(LiteSettings())
        coordinator.bind(1); coordinator.route(1u, 0u, emptyMap()); coordinator.watch(setOf("google"))
        yield()
        coordinator.bind(2); coordinator.route(2u, 0u, emptyMap())
        repeat(6) { yield() }
        held.complete(Unit)
        repeat(6) { yield() }
        assertEquals("available", coordinator.state.value.services.first().status)
        assertEquals(2uL, coordinator.state.value.services.first().coreEpoch)
        coordinator.close()
    }

    @Test fun settingsPreserveAllServicesAndNeverDisableEveryTarget() {
        assertEquals(ServiceTarget.entries.map { it.id }, serviceTargetIds)
        val normalized = LiteSettings(serviceOrder = listOf("netflix", "bad", "netflix"),
            disabledServices = serviceTargetIds.toSet()).normalized()
        assertEquals("netflix", normalized.serviceOrder.first())
        assertEquals(13, normalized.serviceOrder.size)
        assertEquals(13, normalized.enabledServiceIds().size)
    }

    @Test fun unrelatedSelectionKeepsActualChainCacheAndOlderSnapshotCannotRevertIt() = runBlocking {
        var calls = 0
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            calls++; targets.map { answer(it) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.bind(1); coordinator.route(1u, 0u, mapOf("group" to "node")); coordinator.watch(setOf("google"))
        repeat(6) { yield() }
        coordinator.awaitingRoute(); coordinator.route(1u, 1u, mapOf("group" to "node", "other" to "new"))
        coordinator.route(1u, 0u, mapOf("group" to "old"))
        repeat(6) { yield() }
        assertEquals(1, calls)
        assertEquals("available", coordinator.state.value.services.first().status)
        coordinator.close()
    }

    @Test fun failedResultRetriesOnlyOnExplicitRefreshOrNewWatch() = runBlocking {
        var calls = 0
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            calls++; targets.map { answer(it).copy(status = ServiceProbeStatus.Timeout) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.bind(1); coordinator.route(1u, 0u, emptyMap()); coordinator.watch(setOf("google"))
        repeat(6) { yield() }
        coordinator.watch(setOf("google"))
        repeat(6) { yield() }
        assertEquals(1, calls)
        coordinator.watch(emptySet()); coordinator.watch(setOf("google"))
        repeat(6) { yield() }
        assertEquals(2, calls)
        coordinator.close()
    }

    @Test fun emptyDemandCancelsPendingCallAndDiscardsEvenNonCancellableCompletion() = runBlocking {
        val held = CompletableDeferred<Unit>()
        var completed = false
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            withContext(NonCancellable) { held.await(); completed = true }
            targets.map { answer(it) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.bind(1); coordinator.route(1u, 0u, emptyMap()); coordinator.watch(setOf("google"))
        yield(); coordinator.watch(emptySet()); held.complete(Unit)
        repeat(6) { yield() }
        assertTrue(completed)
        assertFalse(coordinator.state.value.serviceLoading)
        assertEquals("pending", coordinator.state.value.services.first().status)
        coordinator.close()
    }

    @Test fun memoryWaitsTwoSecondsAfterCompletionAndStopsWhenHidden() = runBlocking {
        var calls = 0
        val held = CompletableDeferred<Unit>()
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets -> targets.map { answer(it) } }, {
            calls++
            if (calls == 1) held.await()
            MemorySnapshot(123u, null)
        }, { true })
        coordinator.bind(1); coordinator.memory(true)
        yield(); coordinator.memory(true)
        delay(50)
        assertEquals(1, calls)
        held.complete(Unit); repeat(6) { yield() }
        assertEquals(123L, coordinator.state.value.memoryInUseBytes)
        assertEquals("process-rss", coordinator.state.value.memoryScope)
        delay(100)
        assertEquals(1, calls)
        delay(2_000)
        assertEquals(2, calls)
        coordinator.memory(false)
        delay(2_100)
        assertEquals(2, calls)
        assertFalse(coordinator.state.value.memoryLoading)
        coordinator.close()
    }

    @Test fun refreshWhileRouteIsUnsyncedWaitsForSnapshot() = runBlocking {
        var calls = 0
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            calls++; targets.map { answer(it) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.bind(1); coordinator.refresh(setOf("netflix")); repeat(3) { yield() }
        assertEquals(0, calls)
        coordinator.route(1u, 0u, emptyMap()); repeat(6) { yield() }
        assertEquals(1, calls)
        assertEquals("available", coordinator.state.value.services.first { it.id == "netflix" }.status)
        coordinator.close()
    }

    @Test fun routeChangeReissuesExplicitOffCardRefreshWithoutAcceptingOldAnswer() = runBlocking {
        val held = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets ->
            calls++
            if (calls == 1) withContext(NonCancellable) { held.await() }
            targets.map { answer(it, epoch = if (calls == 1) 1u else 2u) }
        }, { MemorySnapshot(null, null) }, { true })
        coordinator.bind(1); coordinator.route(1u, 0u, emptyMap()); coordinator.refresh(setOf("netflix"))
        yield(); coordinator.route(2u, 0u, emptyMap())
        repeat(6) { yield() }
        held.complete(Unit); repeat(6) { yield() }
        assertEquals(2, calls)
        assertEquals(2uL, coordinator.state.value.services.first { it.id == "netflix" }.coreEpoch)
        coordinator.close()
    }
}
