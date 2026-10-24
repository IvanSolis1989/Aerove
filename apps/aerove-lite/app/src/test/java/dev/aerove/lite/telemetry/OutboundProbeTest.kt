package dev.aerove.lite.telemetry

import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.aerove.lite.data.RouteSnapshot
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundProbeTest {
    private fun answer(node: String = "A", ip: String = "1.1.1.1") =
        OutboundAnswer(OutboundIdentity(ip, "AU", null, "unknown"), listOf(node, "Auto", "Pick"))

    private fun groups(node: String = "A", unrelated: String = "X", delay: Int = 30) = listOf(
        ProxyGroup("Pick", "Selector", "Auto", listOf(ProxyNode("Auto", "URLTest"))),
        ProxyGroup("Auto", "URLTest", node, listOf(ProxyNode("A", "Shadowsocks", delay), ProxyNode("B", "Shadowsocks", delay))),
        ProxyGroup("Other", "Selector", unrelated, listOf(ProxyNode("X", "Direct"), ProxyNode("Y", "Direct"))),
    )

    private class Fixture(scope: CoroutineScope, nonCooperative: Boolean = false) {
        var key = "core-1/profile-1/rule/config-1"
        val requests = mutableListOf<Pair<String, CompletableDeferred<OutboundAnswer>>>()
        val probe = OutboundProbe(scope, { requested: String ->
            val result = CompletableDeferred<OutboundAnswer>()
            requests += requested to result
            if (nonCooperative) withContext(NonCancellable) { result.await() } else result.await()
        }, { it == key })

        fun enter(picks: Map<String, String>, epoch: ULong? = null) {
            probe.bind(key)
            probe.setActive(true)
            probe.synchronize(picks, epoch)
        }

        fun changeKey(value: String) {
            key = value
            probe.bind(value)
        }
    }

    @Test fun successfulRouteSurvivesRepeatedPauseResumeAndNeverPolls() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        repeat(4) {
            fixture.probe.setActive(false)
            fixture.probe.setActive(true)
            fixture.probe.synchronize(groups().routePicks())
            runCurrent()
        }
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        assertFalse(fixture.probe.state.value.loading)
        fixture.probe.close()
    }

    @Test fun unrelatedSelectionAndDelaySnapshotsDoNotInvalidateActualChain() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.synchronize(groups(unrelated = "Y", delay = 4).routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.synchronize(groups(unrelated = "Y", delay = 4).routePicks())
        val selection = fixture.probe.beginSelection()
        fixture.probe.endSelection(selection, "Other", "X", succeeded = true)
        fixture.probe.synchronize(groups(delay = 80).routePicks())
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals(answer().identity, fixture.probe.state.value.identity)
        fixture.probe.close()
    }

    @Test fun automaticEffectiveHopChangeClearsOldIdentityAndQueriesOnce() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.synchronize(groups(node = "B").routePicks())
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        fixture.requests.last().second.complete(answer("B", "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun manualRefreshKeepsValueAndCoalescesInFlightClicks() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        repeat(10) { fixture.probe.refresh() }
        runCurrent()
        assertEquals(1, fixture.requests.size)
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.refresh()
        runCurrent()
        repeat(10) { fixture.probe.refresh() }
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.probe.state.value.loading)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun pauseDoesNotCancelInFlightAndResumeWaitsForNewGroupSnapshot() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.probe.setActive(false)
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertNull(fixture.probe.state.value.identity)
        fixture.probe.setActive(true)
        runCurrent()
        assertEquals(1, fixture.requests.size)
        fixture.probe.synchronize(groups().routePicks())
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun staleManualAnswerSchedulesOneReplacementEvenWhenTheOldCacheWasFresh() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.refresh()
        runCurrent()
        fixture.requests.last().second.complete(answer().copy(chains = listOf("Z", "Other")))
        runCurrent()
        assertEquals(3, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun changedHopDuringFirstLookupDiscardsOldAnswerBeforeOneReplacement() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        val selection = fixture.probe.beginSelection()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertNull(fixture.probe.state.value.identity)
        fixture.probe.endSelection(selection, "Auto", "B", succeeded = true)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        fixture.requests.last().second.complete(answer("B", "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun lateNonCooperativeOldProfileResultCannotOverwriteNewScope() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.changeKey("core-2/profile-2/global/config-2")
        fixture.probe.synchronize(groups(node = "B").routePicks())
        runCurrent()
        fixture.requests.last().second.complete(answer("B", "8.8.8.8"))
        runCurrent()
        fixture.requests.first().second.complete(answer())
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        assertFalse(fixture.probe.state.value.loading)
        fixture.probe.close()
    }

    @Test fun lateManualRefreshResultCannotOverwriteNewEffectiveRoute() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.first().second.complete(answer())
        runCurrent()
        fixture.probe.refresh()
        runCurrent()
        fixture.probe.synchronize(groups(node = "B").routePicks())
        runCurrent()
        fixture.requests.last().second.complete(answer("B", "8.8.8.8"))
        runCurrent()
        fixture.requests[1].second.complete(answer(ip = "9.9.9.9"))
        runCurrent()
        assertEquals(3, fixture.requests.size)
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun failedResultDoesNotLoopOnDelayEventsButManualOrResumeRetriesOnce() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.first().second.completeExceptionally(IOException("unavailable"))
        runCurrent()
        repeat(3) { fixture.probe.synchronize(groups(delay = it).routePicks()) }
        runCurrent()
        assertEquals(1, fixture.requests.size)
        fixture.probe.refresh()
        runCurrent()
        fixture.requests.last().second.completeExceptionally(IOException("unavailable"))
        runCurrent()
        fixture.probe.setActive(false)
        fixture.probe.setActive(true)
        fixture.probe.synchronize(groups().routePicks())
        runCurrent()
        assertEquals(3, fixture.requests.size)
        fixture.probe.close()
    }

    @Test fun repeatedStaleChainStopsAfterOneReplacementInsteadOfLooping() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups(node = "B").routePicks())
        runCurrent()
        fixture.requests.first().second.complete(answer())
        runCurrent()
        fixture.requests.last().second.complete(answer())
        runCurrent()
        fixture.probe.synchronize(groups(node = "B", unrelated = "Y").routePicks())
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        assertNotNull(fixture.probe.state.value.error)
        fixture.probe.close()
    }

    @Test fun loadBalancerWithoutSinglePickDoesNotInvalidateKnownChain() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(mapOf("Pick" to "Balanced"))
        runCurrent()
        fixture.requests.single().second.complete(answer().copy(chains = listOf("A", "Balanced", "Pick")))
        runCurrent()
        fixture.probe.synchronize(mapOf("Pick" to "Balanced", "Other" to "Y"))
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun changingTheReturnedStaleChainDoesNotCreateAnUnlimitedRetryLoop() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups(node = "B").routePicks())
        runCurrent()
        fixture.requests.first().second.complete(answer())
        runCurrent()
        fixture.requests.last().second.complete(answer().copy(chains = listOf("Z", "Other")))
        runCurrent()
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        assertNotNull(fixture.probe.state.value.error)
        fixture.probe.close()
    }

    @Test fun duplicateTelemetryRevisionCannotLeaveManualRefreshWaitingForANonexistentRead() = runTest {
        val fixture = Fixture(backgroundScope)
        val session = TelemetrySession(backgroundScope) { true }
        var reads = 0
        val refresh = GroupRefresh(session, { reads++; groups() }, { throw it }, { fixture.probe.synchronize(it.routePicks()) })
        fixture.probe.bind(fixture.key)
        fixture.probe.setActive(true)
        fun event(revision: ULong) {
            if (refresh.changed(revision)) fixture.probe.awaitingGroups()
        }
        event(0uL)
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        event(0uL)
        fixture.probe.refresh()
        runCurrent()
        assertEquals(1, reads)
        assertEquals(2, fixture.requests.size)
        fixture.probe.close()
        session.close()
    }

    @Test fun changedCoreProfileModeOrConfigurationCannotReusePreviousScope() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        val keys = listOf("core-2", "profile-2", "direct", "config-2")
        keys.forEach { changed ->
            fixture.changeKey(changed)
            assertNull(fixture.probe.state.value.identity)
            fixture.probe.synchronize(groups().routePicks())
            runCurrent()
            fixture.requests.last().second.complete(answer())
            runCurrent()
        }
        assertEquals(1 + keys.size, fixture.requests.size)
        fixture.probe.close()
    }

    @Test fun failedGroupSynchronizationShowsAnErrorAndRecoveryReusesTheProvenCache() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.enter(groups().routePicks())
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.groupsFailed(IOException("groups unavailable"))
        assertFalse(fixture.probe.state.value.loading)
        assertNotNull(fixture.probe.state.value.error)
        fixture.probe.synchronize(groups().routePicks())
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        assertNull(fixture.probe.state.value.error)
        fixture.probe.close()
    }
    @Test fun sameNamesAtNewCoreEpochClearCachedIdentityButRepeatedEpochNeverPolls() = runTest {
        val fixture = Fixture(backgroundScope)
        val picks = mapOf("Pick" to "Balanced")
        fixture.enter(picks, 10uL)
        runCurrent()
        fixture.requests.single().second.complete(answer().copy(chains = listOf("A", "Balanced", "Pick")))
        runCurrent()
        repeat(4) { fixture.probe.synchronize(picks, 10uL) }
        runCurrent()
        assertEquals(1, fixture.requests.size)
        fixture.probe.awaitingGroups()
        fixture.probe.synchronize(picks, 11uL)
        assertNull(fixture.probe.state.value.identity)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8").copy(chains = listOf("A", "Balanced", "Pick")))
        runCurrent()
        fixture.probe.synchronize(picks, 10uL)
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun lateUncancellableAnswerAtOldEpochCannotOverwriteSameNamedNewGraph() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        val picks = groups().routePicks()
        fixture.enter(picks, 10uL)
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.synchronize(picks, 11uL)
        runCurrent()
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        fixture.requests.first().second.complete(answer(ip = "9.9.9.9"))
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        assertFalse(fixture.probe.state.value.loading)
        fixture.probe.close()
    }

    @Test fun newBindingMayRestartEpochAndRejectsOldGenerationAnswer() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        val picks = groups().routePicks()
        fixture.enter(picks, 20uL)
        runCurrent()
        fixture.changeKey("core-2/profile-2/global/config-2")
        fixture.probe.synchronize(picks, 1uL)
        runCurrent()
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        fixture.requests.first().second.complete(answer())
        runCurrent()
        assertEquals(2, fixture.requests.size)
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun groupUpdateThenOrdinaryVpnBindCannotReplayOldTicketOrReleaseWaitingAnswer() = runTest {
        val fixture = Fixture(backgroundScope)
        val picks = groups().routePicks()
        fixture.enter(picks, 10uL)
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.probe.bind(fixture.key)
        fixture.probe.replayConfirmedRoute(picks, 10uL)
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertNull(fixture.probe.state.value.identity)
        assertEquals(1, fixture.requests.size)
        repeat(3) {
            fixture.probe.bind(fixture.key)
            fixture.probe.replayConfirmedRoute(picks, 10uL)
        }
        runCurrent()
        assertNull(fixture.probe.state.value.identity)
        assertEquals(1, fixture.requests.size)
        fixture.probe.synchronize(picks, 11uL)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun pendingRouteSurvivesVpnKeyChangeAndPauseResumeUntilAuthoritativeSnapshot() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        val picks = groups().routePicks()
        fixture.enter(picks, 10uL)
        runCurrent()
        fixture.probe.awaitingGroups()
        fixture.changeKey("core-1/profile-1/rule/config-2")
        fixture.probe.setActive(false)
        fixture.probe.setActive(true)
        fixture.probe.replayConfirmedRoute(picks, 10uL)
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertNull(fixture.probe.state.value.identity)
        fixture.probe.synchronize(picks, 11uL)
        runCurrent()
        assertEquals(2, fixture.requests.size)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun confirmedTicketCanResumeWithoutAnUpdatePendingOrAnotherLookup() = runTest {
        val fixture = Fixture(backgroundScope)
        val picks = groups().routePicks()
        fixture.enter(picks, 10uL)
        runCurrent()
        fixture.requests.single().second.complete(answer())
        runCurrent()
        fixture.probe.setActive(false)
        fixture.probe.setActive(true)
        fixture.probe.bind(fixture.key)
        fixture.probe.replayConfirmedRoute(picks, 10uL)
        runCurrent()
        assertEquals(1, fixture.requests.size)
        assertEquals("1.1.1.1", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun preEventRouteFetchCannotReleasePendingAnswerThroughAnEqualOldStamp() = runTest {
        val fixture = Fixture(backgroundScope)
        val picks = groups().routePicks()
        val demand = RouteSnapshotDemand()
        val oldRoute = CompletableDeferred<RouteSnapshot>()
        var accepted = 0
        fixture.enter(picks, 10uL)
        runCurrent()
        val fetch = backgroundScope.launch {
            demand.read({ withContext(NonCancellable) { oldRoute.await() } }) { route ->
                accepted++
                fixture.probe.synchronize(route.picks, route.coreEpoch)
            }
        }
        runCurrent()
        demand.invalidate()
        fixture.probe.awaitingGroups()
        fixture.probe.bind(fixture.key)
        fixture.probe.replayConfirmedRoute(picks, 10uL)
        oldRoute.complete(RouteSnapshot(10uL, 1uL, picks))
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertTrue(fetch.isCompleted)
        assertEquals(0, accepted)
        assertNull(fixture.probe.state.value.identity)
        assertEquals(1, fixture.requests.size)
        demand.read({ RouteSnapshot(11uL, 1uL, picks) }) { route ->
            accepted++
            fixture.probe.synchronize(route.picks, route.coreEpoch)
        }
        runCurrent()
        assertEquals(1, accepted)
        assertEquals(2, fixture.requests.size)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

    @Test fun serviceRouteReadUnderExistingMutexRejectsOldDemandAcrossBindingChange() = runTest {
        val fixture = Fixture(backgroundScope, nonCooperative = true)
        val picks = groups().routePicks()
        val demand = RouteSnapshotDemand()
        val readGate = Mutex()
        val oldRoute = CompletableDeferred<RouteSnapshot>()
        var accepted = 0
        fixture.enter(picks, 20uL)
        runCurrent()
        val serviceFetch = backgroundScope.launch {
            readGate.withLock {
                demand.read({ withContext(NonCancellable) { oldRoute.await() } }) { route ->
                    accepted++
                    fixture.probe.synchronize(route.picks, route.coreEpoch)
                }
            }
        }
        runCurrent()
        demand.invalidate()
        fixture.probe.awaitingGroups()
        fixture.changeKey("core-2/profile-2/global/config-2")
        demand.invalidate()
        fixture.probe.replayConfirmedRoute(picks, 20uL)
        oldRoute.complete(RouteSnapshot(20uL, 1uL, picks))
        fixture.requests.single().second.complete(answer())
        runCurrent()
        assertTrue(serviceFetch.isCompleted)
        assertEquals(0, accepted)
        assertNull(fixture.probe.state.value.identity)
        assertEquals(1, fixture.requests.size)
        readGate.withLock {
            demand.read({ RouteSnapshot(1uL, 1uL, picks) }) { route ->
                accepted++
                fixture.probe.synchronize(route.picks, route.coreEpoch)
            }
        }
        runCurrent()
        assertEquals(1, accepted)
        assertEquals(2, fixture.requests.size)
        fixture.requests.last().second.complete(answer(ip = "8.8.8.8"))
        runCurrent()
        assertEquals("8.8.8.8", fixture.probe.state.value.identity?.address)
        fixture.probe.close()
    }

}
