package dev.aerove.lite.integration

import dev.aerove.lite.data.ProfileMaintenance
import dev.aerove.lite.data.ProfileMaintenancePolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResourceMaintenanceLifecycleTest {
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    @Test fun sameTwentyMinuteProfileClockRunsSubscriptionsBeforeResources() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val events = mutableListOf<String>(); val timers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        var now = 0L; var core: Any? = null
        lateinit var lifecycle: ResourceMaintenanceLifecycle<Any>
        val profiles = ProfileMaintenance(scope, { events += "profile"; now }, now = { now },
            waitMillis = { ms -> val gate = CompletableDeferred<Unit>(); timers += ms to gate; gate.await() },
            dispatcher = Dispatchers.Unconfined, afterAutomaticSweep = { lifecycle.afterAutomaticSweep(it) })
        lifecycle = ResourceMaintenanceLifecycle(scope, { core }, { true },
            { full, current, available -> assertTrue(current()); assertEquals(core != null, available()); events += if (full) "resources" else "skipped" },
            {}, 0, "stopped", { throw it })
        try {
            profiles.replacePolicies(listOf(ProfileMaintenancePolicy("p", "source", true, intervalMillis = 1000, lastUpdatedAtMillis = 0)))
            profiles.setForeground(true); lifecycle.foreground(true); lifecycle.ready()
            until { events == listOf("resources") && timers.isNotEmpty() }
            assertEquals(1, timers.size)
            assertEquals(20 * 60 * 1000L, timers.last().first)
            now = 20 * 60 * 1000L; timers.last().second.complete(Unit)
            until { events.size == 3 }; assertEquals(listOf("resources", "profile", "resources"), events)
            core = Any(); lifecycle.runtime("first-running", core)
            until { events.size == 4 }; assertEquals("resources", events.last())
            assertEquals(2, timers.size) // endpoint does not restart the existing profile clock
            core = Any(); lifecycle.runtime("second-running", core)
            until { events.size == 5 }; assertEquals("skipped", events.last())
            assertEquals(1, events.count { it == "profile" })
        } finally { lifecycle.close(); profiles.close(); scope.cancel() }
    }
    @Test fun firstAvailableEndpointAndLaterEndpointUseDifferentExistingPaths() = runBlocking<Unit> {
        var core: Any? = Any(); val modes = mutableListOf<Boolean>(); var resets = 0
        val owner = ResourceMaintenanceLifecycle(this, { core }, { true },
            { full, current, available -> assertTrue(current()); assertTrue(available()); modes += full },
            { resets++ }, 3, "running-A", { throw it })
        try {
            owner.foreground(true); owner.ready(); until { modes == listOf(true) }
            owner.runtime("running-A", core); owner.network(3); assertEquals(0, resets)
            core = Any(); owner.runtime("running-B", core); until { modes.size == 2 }
            assertEquals(listOf(true, false), modes); assertEquals(1, resets)
            owner.network(4); assertEquals(2, resets); owner.network(4); assertEquals(2, resets)
        } finally { owner.close() }
    }
    @Test fun backgroundCancelsButSuccessorWaitsForTheUncancellableTail() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>(); var calls = 0; var errors = 0
        val paths = mutableListOf<Boolean>()
        val core = Any()
        val owner = ResourceMaintenanceLifecycle(this, { core }, { true }, { full, current, _ ->
            paths += full
            if (++calls == 1) withContext(NonCancellable) { release.await() }
            if (calls == 1) assertFalse(current()) else assertTrue(current())
        }, {}, 0, "running", { errors++ })
        try {
            owner.foreground(true); owner.ready()
            until { calls == 1 }
            owner.foreground(false); owner.foreground(true)
            delay(20); assertEquals(1, calls)
            release.complete(Unit); until { calls == 2 }
            assertEquals(0, errors); assertEquals(listOf(true, true), paths)
        } finally { release.complete(Unit); owner.close() }
    }
    @Test fun appMaintenanceQuiesceJoinsAndResumeRetainsTheOwner() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = ResourceMaintenanceLifecycle<Any>(this, { null }, { true }, { _, _, _ ->
            if (++calls == 1) withContext(NonCancellable) { release.await() }
        }, {}, 0, "stopped", { throw it })
        try {
            owner.foreground(true); owner.ready()
            until { calls == 1 }
            val drain = async { owner.quiesce() }; delay(20); assertFalse(drain.isCompleted)
            release.complete(Unit); drain.await()
            owner.afterAutomaticSweep { true }; assertEquals(1, calls)
            owner.resumeAfterMaintenance(); until { calls == 2 }
            owner.afterAutomaticSweep { true }; assertEquals(3, calls)
        } finally { release.complete(Unit); owner.close() }
    }
    @Test fun unavailableCoreAndCanceledUpstreamDoNotInventCoreAvailabilityOrFailure() = runBlocking<Unit> {
        var calls = 0; var errors = 0
        val owner = ResourceMaintenanceLifecycle<Any>(this, { null }, { true }, { _, current, available ->
            calls++; assertTrue(current()); assertFalse(available()); throw CancellationException("real cancel")
        }, {}, 0, "stopped", { errors++ })
        try {
            owner.foreground(true); owner.ready()
            until { calls == 1 }
            owner.afterAutomaticSweep { false }; assertEquals(1, calls)
            val task = async { owner.afterAutomaticSweep { true } }; runCatching { task.await() }
            assertEquals(2, calls); assertEquals(0, errors)
        } finally { owner.close() }
    }
    @Test fun networkResetCannotLetTheOldSubscriptionFailureRestoreBackoff() = runBlocking<Unit> {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        lateinit var owner: ResourceMaintenanceLifecycle<Any>
        val profiles = ProfileMaintenance(scope, {
            if (++calls == 1) { entered.complete(Unit); release.await(); throw java.io.IOException("old network 503") }
            2000L
        }, now = { 2000L }, waitMillis = { awaitCancellation() }, dispatcher = Dispatchers.Unconfined,
            afterAutomaticSweep = { owner.afterAutomaticSweep(it) })
        owner = ResourceMaintenanceLifecycle(scope, { null }, { true },
            { _, _, _ -> }, profiles::resetAutomaticBackoff, 0, "stopped", { throw it })
        try {
            profiles.replacePolicies(listOf(ProfileMaintenancePolicy("p", "source", true, intervalMillis = 1000, lastUpdatedAtMillis = 0)))
            profiles.setForeground(true); owner.foreground(true); owner.ready(); profiles.checkDue(); entered.await()
            owner.network(1); release.complete(Unit)
            until { profiles.runningProfileIds.value.isEmpty() }
            profiles.checkDue()
            until { calls == 2 }
            assertEquals(2, calls)
        } finally { release.complete(Unit); owner.close(); profiles.close(); scope.cancel() }
    }
    @Test fun realSessionReceiptDistinguishesReusedEndpointAndUptimeButNotParkOrCopies() = runBlocking<Unit> {
        val endpoint = dev.aerove.lite.vpn.CoreEndpoint(24001, "session-test")
        val first = dev.aerove.lite.vpn.VpnSnapshot(phase = dev.aerove.lite.VpnPhase.Running,
            endpoint = endpoint, runningSinceNanos = 100, sessionGeneration = 1)
        val next = first.copy(sessionGeneration = 2)
        assertEquals(first.runningSinceNanos, next.runningSinceNanos)
        assertSame(first.endpoint, next.endpoint)
        assertNotEquals(maintenanceRuntimeIdentity(first), maintenanceRuntimeIdentity(next))
        assertEquals(maintenanceRuntimeIdentity(first), maintenanceRuntimeIdentity(first.copy(suspended = true, listenerError = "notice")))
        var resets = 0; val paths = mutableListOf<Boolean>()
        val owner = ResourceMaintenanceLifecycle(this, { endpoint }, { true }, { full, current, _ -> assertTrue(current()); paths += full },
            { resets++ }, 0, maintenanceRuntimeIdentity(first), { throw it })
        try {
            owner.foreground(true); owner.ready(); until { paths.size == 1 }
            owner.runtime(maintenanceRuntimeIdentity(first.copy(suspended = true)), endpoint); assertEquals(0, resets)
            owner.runtime(maintenanceRuntimeIdentity(next), endpoint); until { paths.size == 2 }
            assertEquals(1, resets); assertEquals(listOf(true, false), paths)
        } finally { owner.close() }
    }
}
