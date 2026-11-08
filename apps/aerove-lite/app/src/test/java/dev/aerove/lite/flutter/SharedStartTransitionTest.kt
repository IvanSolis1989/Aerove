package dev.aerove.lite.flutter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedStartTransitionTest {
    @Test fun live_graph_releases_lifecycle_before_start_and_revalidates_every_boundary() = runTest {
        val gate = Mutex(); val lifecycle = Mutex(); val graphOwner = Any()
        lifecycle.lock(graphOwner)
        var checks = 0; var retired = 0
        val start = launch {
            assertEquals(7, sharedStartTransition(gate, lifecycle, { checks++; 7 }, Int::equals,
                { retired++; assertTrue(lifecycle.holdsLock(graphOwner)); lifecycle.unlock(graphOwner) },
                { assertTrue(lifecycle.isLocked); it }))
        }
        runCurrent()
        try {
            assertTrue("Start must not wait on the graph's own lifecycle lock", start.isCompleted)
            start.join()
        } finally {
            start.cancelAndJoin()
            if (lifecycle.holdsLock(graphOwner)) lifecycle.unlock(graphOwner)
        }
        assertEquals(3, checks); assertEquals(1, retired)
        assertFalse(gate.isLocked); assertFalse(lifecycle.isLocked)
    }
    @Test fun ineligible_or_already_active_autostart_never_waits_or_retires() = runTest {
        val gate = Mutex(locked=true); val lifecycle = Mutex(locked=true)
        assertNull(sharedStartTransition<Int,Int>(gate,lifecycle,{null},Int::equals,
            { error("ineligible request retired an existing owner") },{error("committed")}))
        assertTrue(gate.isLocked);assertTrue(lifecycle.isLocked)
        gate.unlock();lifecycle.unlock()
    }
    @Test fun explicit_stop_or_changed_revision_during_join_cannot_start() = runTest {
        for (stop in listOf(false,true)) {
            val lifecycle=Mutex(locked=true);var eligible=true;var revision=1;var committed=false
            try {
                val result=sharedStartTransition(Mutex(),lifecycle,{if(eligible)revision else null},Int::equals,
                    { if(stop) eligible=false else revision++; lifecycle.unlock() },{committed=true;it})
                assertTrue(stop);assertNull(result)
            } catch (expected:IllegalStateException) { assertFalse(stop);assertEquals("shared_start_superseded",expected.message) }
            assertFalse(committed);assertFalse(lifecycle.isLocked)
        }
    }
    @Test fun new_graph_waits_until_start_commit_and_cancellation_does_not_steal_owners() = runTest {
        val gate=Mutex();val lifecycle=Mutex(locked=true);val joining=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var committed=false;var newGraph=false
        val start=launch { sharedStartTransition(gate,lifecycle,{1},Int::equals,
            { joining.complete(Unit);release.await();lifecycle.unlock() },{committed=true;it}) }
        joining.await()
        val graph=launch { gate.withLock { assertTrue(committed);newGraph=true } }
        runCurrent();assertFalse(newGraph)
        release.complete(Unit);start.join();graph.join();assertTrue(newGraph)
        lifecycle.lock()
        val blocked=launch { sharedStartTransition(gate,lifecycle,{1},Int::equals,{}, {error("foreign owner entered")}) }
        runCurrent();blocked.cancelAndJoin();assertTrue(lifecycle.isLocked);assertFalse(gate.isLocked);lifecycle.unlock()
    }
    @Test fun actual_active_owner_policy_rejects_manual_reentry_without_stopping_it() {
        for (phase in dev.aerove.lite.VpnPhase.values()) {
            if (phase == dev.aerove.lite.VpnPhase.Stopped || phase == dev.aerove.lite.VpnPhase.Failed) {
                assertTrue(sharedStartOwnerAvailable(false, phase))
                assertTrue(sharedStartOwnerAvailable(true, phase))
            } else {
                assertFalse(sharedStartOwnerAvailable(true, phase))
                try { sharedStartOwnerAvailable(false, phase); fail("manual reentry accepted") }
                catch (expected: IllegalStateException) { assertEquals("shared_service_owner_active", expected.message) }
            }
        }
    }
    @Test fun source_permit_or_permission_rejection_after_retirement_never_commits() = runTest {
        for (reason in listOf("source_changed", "permit_revoked", "permission_revoked")) {
            var retired=false;var commits=0;val lifecycle=Mutex(locked=true);val gate=Mutex()
            try {
                sharedStartTransition(gate,lifecycle,{ check(!retired) { reason }; 1 },Int::equals,
                    { retired=true;lifecycle.unlock() },{commits++;it})
                fail("changed qualification accepted")
            } catch(expected:IllegalStateException) { assertEquals(reason,expected.message) }
            assertEquals(0,commits);assertFalse(gate.isLocked);assertFalse(lifecycle.isLocked)
        }
    }

    @Test fun cancelling_gate_waiter_does_not_retire_commit_or_unlock_initializer() = runTest {
        val initializer=Any();val gate=Mutex();gate.lock(initializer)
        val lifecycle=Mutex();var retired=0;var committed=0
        val start=launch { sharedStartTransition(gate,lifecycle,{1},Int::equals,{retired++},{committed++;it}) }
        runCurrent();start.cancelAndJoin()
        assertTrue(gate.holdsLock(initializer));assertEquals(0,retired);assertEquals(0,committed)
        assertFalse(lifecycle.isLocked);gate.unlock(initializer)
    }
    @Test fun second_start_cannot_retire_after_first_commit_changes_intent() = runTest {
        val gate=Mutex();val lifecycle=Mutex();var epoch=1;var retired=0;var committed=0
        val joining=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=launch { sharedStartTransition(gate,lifecycle,{epoch},Int::equals,
            { retired++;joining.complete(Unit);release.await() },{committed++;epoch++;it}) }
        joining.await()
        var rejected=false
        val second=launch {
            try { sharedStartTransition(gate,lifecycle,{epoch},Int::equals,{retired++},{committed++;it});fail("stale second start committed") }
            catch(expected:IllegalStateException) { assertEquals("shared_start_superseded",expected.message);rejected=true }
        }
        runCurrent();release.complete(Unit);first.join();second.join()
        assertTrue(rejected);assertEquals(1,retired);assertEquals(1,committed)
        assertFalse(gate.isLocked);assertFalse(lifecycle.isLocked)
    }

}
