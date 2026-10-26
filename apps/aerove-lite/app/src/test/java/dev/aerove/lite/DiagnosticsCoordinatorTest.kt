package dev.aerove.lite

import dev.aerove.lite.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsCoordinatorTest {
    private fun answer(target: ServiceTarget, epoch: ULong = 1u, version: ULong = 0u) = ServiceCheck(target,
        ServiceProbeStatus.Available, "US", 42, listOf("node", "group"), 1234, epoch, version,
        listOf(RouteHit("${target.id}.example:443", exit="node")))
    private suspend fun until(condition: () -> Boolean) = withTimeout(3000) { while(!condition()) delay(5) }

    @Test fun visibleTargetsAreMergedAndCachedUntilTheirRouteMoves() = runBlocking<Unit> {
        val calls = mutableListOf<ServiceTarget>()
        val coordinator = DiagnosticsCoordinator<Int>(this, { _, targets -> calls += targets; targets.map { answer(it) } },
            { MemorySnapshot(12u, null) }, { true })
        try {
            coordinator.settings(LiteSettings());coordinator.bind(1);coordinator.route(1u,0u,mapOf("group" to "node"));coordinator.watch(setOf("google"))
            delay(100);assertTrue(calls.isEmpty()) // The reset has a real 300ms settle window.
            until{calls.size==1 && !coordinator.state.value.serviceLoading};assertEquals(listOf(ServiceTarget.Google),calls)
            coordinator.watch(setOf("google"));coordinator.refresh(setOf("google"));coordinator.refresh(setOf("google"))
            until{calls.size==2 && !coordinator.state.value.serviceLoading};assertEquals(2,calls.size)
            coordinator.route(1u,1u,mapOf("group" to "other"),mapOf("google" to false))
            assertTrue(coordinator.state.value.services.first().status!="available")
            delay(100);assertEquals(2,calls.size)
        }finally{coordinator.close()}
    }
    @Test fun obsoleteEndpointResponseCannotOverwriteCurrentSession() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();var calls=0
        val coordinator=DiagnosticsCoordinator<Int>(this,{key,targets->calls++;if(key==1)withContext(NonCancellable){entered.complete(Unit);held.await()};targets.map{answer(it,epoch=key.toULong())}},
            {MemorySnapshot(null,null)},{true})
        try{
            coordinator.settings(LiteSettings());coordinator.bind(1);coordinator.route(1u,0u,emptyMap());coordinator.watch(setOf("google"));withTimeout(3000){entered.await()}
            coordinator.bind(2);coordinator.route(2u,0u,emptyMap());delay(100);assertEquals(1,calls)
            held.complete(Unit);until{coordinator.state.value.services.first().status=="available"}
            assertEquals(2,calls);assertEquals(2uL,coordinator.state.value.services.first().coreEpoch)
        }finally{held.complete(Unit);coordinator.close()}
    }
    @Test fun settingsPreserveAllServicesAndNeverDisableEveryTarget() {
        assertEquals(ServiceTarget.entries.map{it.id},serviceTargetIds)
        val normalized=LiteSettings(serviceOrder=listOf("netflix","bad","netflix"),disabledServices=serviceTargetIds.toSet()).normalized()
        assertEquals("netflix",normalized.serviceOrder.first());assertEquals(13,normalized.serviceOrder.size);assertEquals(13,normalized.enabledServiceIds().size)
    }
    @Test fun unrelatedSelectionKeepsActualExitCacheAndOlderSnapshotCannotRevertIt() = runBlocking<Unit> {
        var calls=0
        val coordinator=DiagnosticsCoordinator<Int>(this,{_,targets->calls++;targets.map{answer(it)}},{MemorySnapshot(null,null)},{true})
        try{
            coordinator.bind(1);coordinator.route(1u,0u,mapOf("group" to "node"));coordinator.watch(setOf("google"));until{calls==1 && !coordinator.state.value.serviceLoading}
            coordinator.awaitingRoute();coordinator.route(1u,1u,mapOf("group" to "node","other" to "new"),mapOf("google" to true))
            coordinator.route(1u,0u,mapOf("group" to "old"));delay(350)
            assertEquals(1,calls);assertEquals("available",coordinator.state.value.services.first().status);assertEquals(1uL,coordinator.state.value.services.first().routeVersion)
        }finally{coordinator.close()}
    }
    @Test fun failedResultRetriesOnlyOnExplicitRefreshOrRealForegroundResume() = runBlocking<Unit> {
        var calls=0
        val coordinator=DiagnosticsCoordinator<Int>(this,{_,targets->calls++;targets.map{answer(it).copy(status=ServiceProbeStatus.Timeout)}},{MemorySnapshot(null,null)},{true})
        try{
            coordinator.bind(1);coordinator.route(1u,0u,emptyMap());coordinator.watch(setOf("google"));until{calls==1 && !coordinator.state.value.serviceLoading}
            coordinator.watch(setOf("google"));coordinator.watch(emptySet());coordinator.watch(setOf("google"));delay(350);assertEquals(1,calls)
            coordinator.refresh(setOf("google"));until{calls==2 && !coordinator.state.value.serviceLoading}
            coordinator.foreground(false);delay(100);assertEquals(2,calls);coordinator.foreground(true)
            // Automatic resume respects the existing 5s per-target throttle; manual refresh above bypassed it.
            withTimeout(6000){while(calls!=3 || coordinator.state.value.serviceLoading)delay(5)};assertEquals(3,calls)
        }finally{coordinator.close()}
    }
    @Test fun emptyDemandCancelsPendingCallAndDiscardsEvenNonCancellableCompletion() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();var completed=false
        val coordinator=DiagnosticsCoordinator<Int>(this,{_,targets->withContext(NonCancellable){entered.complete(Unit);held.await();completed=true};targets.map{answer(it)}},{MemorySnapshot(null,null)},{true})
        try{
            coordinator.bind(1);coordinator.route(1u,0u,emptyMap());coordinator.watch(setOf("google"));withTimeout(3000){entered.await()}
            coordinator.watch(emptySet());held.complete(Unit);until{completed}
            assertFalse(coordinator.state.value.serviceLoading);assertEquals("pending",coordinator.state.value.services.first().status)
        }finally{held.complete(Unit);coordinator.close()}
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


    @Test fun refreshWhileRouteIsUnsyncedWaitsForSnapshot() = runBlocking<Unit> {
        var calls=0
        val coordinator=DiagnosticsCoordinator<Int>(this,{_,targets->calls++;targets.map{answer(it)}},{MemorySnapshot(null,null)},{true})
        try{
            coordinator.bind(1);coordinator.refresh(setOf("netflix"));delay(350);assertEquals(0,calls)
            coordinator.route(1u,0u,emptyMap());until{calls==1 && !coordinator.state.value.serviceLoading}
            assertEquals("available",coordinator.state.value.services.first{it.id=="netflix"}.status)
        }finally{coordinator.close()}
    }
    @Test fun routeChangeReissuesExplicitOffCardRefreshWithoutAcceptingOldAnswer() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val held=CompletableDeferred<Unit>();var calls=0
        val coordinator=DiagnosticsCoordinator<Int>(this,{_,targets->val call=++calls;if(call==1)withContext(NonCancellable){entered.complete(Unit);held.await()};targets.map{answer(it,epoch=if(call==1)1u else 2u)}},{MemorySnapshot(null,null)},{true})
        try{
            coordinator.bind(1);coordinator.route(1u,0u,emptyMap());coordinator.refresh(setOf("netflix"));withTimeout(3000){entered.await()}
            coordinator.route(2u,0u,emptyMap());delay(100);assertEquals(1,calls)
            held.complete(Unit);until{calls==2 && !coordinator.state.value.serviceLoading}
            assertEquals(2uL,coordinator.state.value.services.first{it.id=="netflix"}.coreEpoch)
        }finally{held.complete(Unit);coordinator.close()}
    }
}
