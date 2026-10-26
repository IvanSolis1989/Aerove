package dev.aerove.lite.telemetry

import dev.aerove.lite.*
import dev.aerove.lite.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RouteOwnerRegressionTest {
    private suspend fun until(condition:()->Boolean) = withTimeout(4000) { while(!condition()) delay(5) }
    @Test fun constructorIsIdleHealthIsTrailingAndDelayBatchHasOneSupplementalRead() = runBlocking<Unit> {
        var reads=0;var health=0
        val session=TelemetrySession(this){true}
        val groups=GroupRefresh(session,{++reads},{throw it},{},{false},healthUpdate={health++})
        try {
            delay(40);assertEquals(0,reads)
            groups.initial();groups.initial();until{reads==1}
            assertTrue(groups.changed(1uL,7uL));until{reads==2}
            groups.testing(true)
            repeat(40){assertFalse(groups.changed((it+2).toULong(),7uL))}
            delay(350);assertEquals(2,reads)
            groups.testing(false);until{reads==3};assertEquals(1,health)
            assertTrue(groups.changed(43uL,8uL));until{reads==4}
            groups.changed(44uL,8uL);delay(180);groups.changed(45uL,8uL);delay(180)
            assertEquals(4,reads);until{reads==5};assertEquals(2,health)
            delay(350);assertEquals(5,reads)
        } finally {session.close()}
    }
    @Test fun outboundKeepsHeldExitAndManualRefreshBypassesPendingMoveThrottle() = runBlocking<Unit> {
        var reads=0;var version=1uL
        val owner=OutboundProbe(this,{_:String->reads++;OutboundAnswer(OutboundIdentity("1.1.1.1"),listOf("A","PICK"),
            listOf(RouteHit("origin.example:443",exit="actual")),1uL,version)},{true})
        try {
            owner.setActive(true);owner.bind("runtime");owner.synchronize(mapOf("PICK" to "A"),1uL,version)
            until{owner.state.value.identity!=null};assertEquals(1,reads)
            version=2uL;owner.synchronize(mapOf("PICK" to "unrelated-leaf"),1uL,version,true)
            delay(350);assertEquals(1,reads);assertEquals("1.1.1.1",owner.state.value.identity?.address)
            version=3uL;owner.synchronize(mapOf("PICK" to "new-leaf"),1uL,version,false)
            delay(100);assertEquals(1,reads);assertEquals("1.1.1.1",owner.state.value.identity?.address)
            owner.refresh();until{reads==2 && owner.state.value.identity!=null}
            assertEquals(2,reads)
        } finally {owner.close()}
    }
    @Test fun servicesRetainSameEndpointAcrossClientsAndOnlyResumeRetriesFailure() = runBlocking<Unit> {
        data class Binding(val endpoint:Any,val client:Int)
        val endpoint=Any();var binding=Binding(endpoint,1);var reads=0
        val owner=DiagnosticsCoordinator(this,{_:Binding,targets:List<ServiceTarget>->reads++;targets.map { target->
            ServiceCheck(target,ServiceProbeStatus.Failed,null,0,emptyList(),123,1uL,1uL,listOf(RouteHit("failed.example:443",exit="same")))
        }},{MemorySnapshot(0uL,null)},{it==binding},sameSession={a,b->a.endpoint===b.endpoint})
        try {
            owner.settings(LiteSettings(disabledServices=ServiceTarget.entries.filter{it!=ServiceTarget.Google}.map{it.id}.toSet()))
            owner.bind(binding);owner.watch(setOf("google"));owner.route(1uL,1uL,emptyMap());until{reads==1 && !owner.state.value.serviceLoading}
            owner.watch(emptySet());owner.watch(setOf("google"));delay(350);assertEquals(1,reads)
            owner.bind(null);binding=Binding(endpoint,2);owner.bind(binding);owner.route(1uL,1uL,emptyMap());delay(350);assertEquals(1,reads)
            owner.foreground(false);owner.bind(null);binding=Binding(endpoint,3);owner.bind(binding);owner.foreground(true);owner.route(1uL,1uL,emptyMap())
            owner.refresh(setOf("google"));until{reads==2 && !owner.state.value.serviceLoading}
            owner.route(1uL,2uL,emptyMap(),mapOf("google" to true));delay(350);assertEquals(2,reads)
            assertEquals("failed",owner.state.value.services.single().status)
            owner.runtimeChanged();assertEquals("pending",owner.state.value.services.single().status)
        } finally {owner.close()}
    }

    @Test fun cancelledProbeJoinsOldIoBeforeStartingTheLatestRoute() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val owner=OutboundProbe(this,{_:String->
            val call=++calls
            if(call==1)withContext(NonCancellable){entered.complete(Unit);release.await()}
            OutboundAnswer(OutboundIdentity("1.1.1.$call"),listOf("A","PICK"),listOf(RouteHit("example:443",exit="same")),1uL,call.toULong())
        },{true})
        try {
            owner.setActive(true);owner.bind("runtime");owner.synchronize(emptyMap(),1uL,1uL)
            withTimeout(3000){entered.await()}
            owner.synchronize(emptyMap(),1uL,2uL,false);owner.refresh();delay(100);assertEquals(1,calls)
            release.complete(Unit);until{calls==2 && owner.state.value.identity?.address=="1.1.1.2"}
            assertEquals(2,calls)
        }finally{release.complete(Unit);owner.close()}
    }

    @Test fun serviceSuccessorRetainsOriginalJoinAcrossRefreshDuringRouteSettle() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val owner=DiagnosticsCoordinator(this,{_:String,targets:List<ServiceTarget>->
            val call=++calls
            if(call==1)withContext(NonCancellable){entered.complete(Unit);release.await()}
            targets.map{ServiceCheck(it,ServiceProbeStatus.Failed,null,0,emptyList(),123,1uL,if(call==1)1uL else 2uL,listOf(RouteHit("example:443",exit="same")))}
        },{MemorySnapshot(0uL,null)},{true})
        try{
            owner.settings(LiteSettings(disabledServices=ServiceTarget.entries.filter{it!=ServiceTarget.Google}.map{it.id}.toSet()))
            owner.bind("runtime");owner.watch(setOf("google"));owner.route(1uL,1uL,emptyMap());withTimeout(3000){entered.await()}
            owner.route(1uL,2uL,emptyMap(),mapOf("google" to false));owner.refresh(setOf("google"));delay(100);assertEquals(1,calls)
            release.complete(Unit);until{calls==2 && !owner.state.value.serviceLoading};assertEquals(2,calls)
        }finally{release.complete(Unit);owner.close()}
    }
}
