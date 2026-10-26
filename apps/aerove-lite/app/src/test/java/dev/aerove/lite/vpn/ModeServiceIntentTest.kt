package dev.aerove.lite.vpn

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModeServiceIntentTest {
    @Test fun pendingStartAcceptedBeforeCleanupKeepsItsIntentAndCompletesAfterProfileRead() = runBlocking<Unit> {
        val old = CoreEndpoint(1234, "old")
        val fresh = CoreEndpoint(1234, "new")
        var endpoint: CoreEndpoint? = old
        var generation = 1L
        var planOwner: Long? = 1L
        var requested = true
        var stoppedSelf = false
        val profilesRead = CompletableDeferred<Unit>()
        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            val own = ++generation
            profilesRead.await()
            if (own == generation) { planOwner = own; endpoint = fresh }
        }
        assertEquals(2L, generation)
        assertTrue(stopModeSessionForService(old, "mode failed", { ModeServiceIntentReceipt(endpoint, planOwner, generation) },
            { it() }, { actual, _ -> assertSame(old, actual); endpoint = null; true },
            { generation++; requested = false; planOwner = null; stoppedSelf = true }))
        profilesRead.complete(Unit)
        start.join()
        assertEquals(2L, generation)
        assertEquals(2L, planOwner)
        assertSame(fresh, endpoint)
        assertTrue(requested)
        assertFalse(stoppedSelf)
    }
    @Test fun startAcceptedDuringOldSessionJoinAlsoWins() = runBlocking<Unit> {
        val old = CoreEndpoint(1234, "old")
        var endpoint: CoreEndpoint? = old
        var generation = 1L
        var planOwner: Long? = 1L
        var cleared = false
        val joining = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            stopModeSessionForService(old, "mode failed", { ModeServiceIntentReceipt(endpoint, planOwner, generation) },
                { it() }, { _, _ -> joining.complete(Unit); joined.await(); endpoint = null; true }, { cleared = true })
        }
        joining.await(); generation++
        joined.complete(Unit)
        assertTrue(cleanup.await())
        assertFalse(cleared)
    }
    @Test fun unchangedOwnerClearsOnlyAfterJoinButForeignEndpointNeverStops() = runBlocking<Unit> {
        val old = CoreEndpoint(1234, "old")
        var endpoint: CoreEndpoint? = old
        var cleared = false
        assertTrue(stopModeSessionForService(old, "mode failed", { ModeServiceIntentReceipt(endpoint, 1L, 1L) },
            { it() }, { _, _ -> assertFalse(cleared); endpoint = null; true }, { cleared = true }))
        assertTrue(cleared)
        val fresh = CoreEndpoint(1234, "fresh")
        assertFalse(stopModeSessionForService(old, "stale", { ModeServiceIntentReceipt(fresh, 2L, 2L) },
            { it() }, { _, _ -> error("old endpoint reached stop") }, { error("new owner cleared") }))
    }
}
