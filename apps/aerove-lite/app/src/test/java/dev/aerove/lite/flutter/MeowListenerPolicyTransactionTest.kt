package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyActor
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences
import dev.aerove.lite.backgroundpolicy.ListenerPolicyIntent
import dev.aerove.lite.data.SystemDnsSnapshot
import dev.aerove.lite.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class MeowListenerPolicyTransactionTest {
    @Test fun queuedActorCallbackCannotDeadlockDecisionWaitingBehindRuntimeGate() = runBlocking {
        val gate = Mutex()
        val entered = CompletableDeferred<Unit>()
        val queue = CompletableDeferred<Unit>()
        val attemptingGate = CompletableDeferred<Unit>()
        lateinit var actor: BackgroundPolicyActor
        var callbacks = 0
        actor = BackgroundPolicyActor(this, { intent ->
            if (++callbacks == 1) { entered.complete(Unit); queue.await(); attemptingGate.complete(Unit) }
            gate.withLock { if (actor.isCurrent(intent)) Unit }
        }, { throw it })
        actor.update(BackgroundPolicyPreferences(), true, false, null)
        entered.await()
        try {
            val completed = withTimeoutOrNull(1000) {
                meowWithListenerPolicyDecision(gate, request = {
                    queue.complete(Unit); attemptingGate.await()
                    Unit to actor.update(BackgroundPolicyPreferences(), true, false, null)
                }, decision = actor::latestDecision, isCurrent = actor::isCurrent) { _, intent -> intent.revision }
            }
            assertNotNull("Actor is waiting for runtime gate while setter waits for the next decision", completed)
            assertEquals(2L, completed)
        } finally { actor.close() }
    }

    private class Session : ListenerPolicySession {
        override val endpoint = CoreEndpoint(12345, "test-secret")
        var stops = 0
        val listening = mutableListOf<Boolean>()
        override suspend fun awaitReady() = Unit
        override suspend fun awaitExit(): Unit = awaitCancellation()
        override suspend fun stop() { stops++ }
        override suspend fun configurePolicies(settings: LiteSettings) = Unit
        override suspend fun configureNetworkDns(snapshot: SystemDnsSnapshot) = Unit
        override suspend fun setListening(enabled: Boolean): ListenerReadback {
            listening += enabled
            return ListenerReadback(enabled, enabled, enabled, "1")
        }
    }

    @Test fun supersededPolicyIsNotAcknowledgedOrStoppedAndActualCoordinatorCanAutoResume() = runBlocking {
        val session = Session()
        val coordinator = VpnCoordinator(this, { session })
        coordinator.start(VpnPlan("profile", LiteSettings()))
        coordinator.state.first { it.phase == VpnPhase.Running }
        val endpoint = coordinator.state.value.endpoint
        val gate = Mutex()
        val preferences = BackgroundPolicyPreferences(excludeSSIDs = listOf("Home"))
        var requested = true
        lateinit var actor: BackgroundPolicyActor
        actor = BackgroundPolicyActor(this, { policy ->
            gate.withLock {
                if (actor.isCurrent(policy)) coordinator.setListenerPolicy(policy.listening) { actor.isCurrent(policy) }
            }
        }, { throw it })
        val r1 = actor.update(preferences, requested, false, null)
        actor.latestDecision()
        var acknowledged = false
        var failure: Throwable? = null
        try {
            meowWithListenerPolicyDecision(gate, request = { Unit to r1 }, decision = actor::latestDecision,
                isCurrent = actor::isCurrent) { _, policy ->
                // The current policy changes after admission, before Coordinator checks its predicate.
                actor.update(preferences, requested, false, "Home")
                try {
                    coordinator.setListenerPolicy(true) { actor.isCurrent(policy) }
                    meowRequireListenerPolicyCurrent(actor.isCurrent(policy))
                    acknowledged = true
                } catch (error: Throwable) {
                    if (meowListenerFailureRequiresStop(error, actor.isCurrent(policy))) {
                        requested = false
                        coordinator.stopIfCurrent(checkNotNull(endpoint), "listener failed")
                    }
                    throw error
                }
            }
        } catch (error: Throwable) { failure = error }
        assertTrue(failure is MeowListenerPolicySuperseded)
        assertFalse(acknowledged)
        assertTrue("Normal policy supersession must preserve the user's running intent", requested)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.state.first { it.suspended }
        assertTrue(requested); assertEquals(0, session.stops)
        actor.update(preferences, requested, false, "Outside")
        coordinator.state.first { it.listening }
        assertSame(endpoint, coordinator.state.value.endpoint)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        assertEquals(0, session.stops)
        actor.close(); coordinator.close()
    }

    @Test fun anotherPolicyRevisionCannotBecomeTheRequestedAcknowledgement() = runBlocking {
        val gate = Mutex()
        val policy = ListenerPolicyIntent(8, true, false)
        var committed = false
        val error = runCatching { meowWithListenerPolicyDecision(gate, { Unit to 7L }, { policy }, { true }) { _, _ -> committed = true } }.exceptionOrNull()
        assertTrue(error is MeowListenerPolicySuperseded)
        assertFalse(committed)
        assertFalse(meowListenerFailureRequiresStop(CancellationException(), true))
        assertTrue(meowListenerFailureRequiresStop(IllegalStateException("native apply failed"), true))
    }
}
