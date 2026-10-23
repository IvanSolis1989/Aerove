package dev.aerove.lite.backgroundpolicy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun settingsRoundTripPreservesExactSsidAndOrder() {
        val settings = BackgroundPolicyPreferences(autoRun = true, hidden = true, excludeSSIDs = listOf(" café ", "Office"), showNotificationStopAction = false)
        assertEquals(settings, BackgroundPolicyJson.decode(JSONObject(BackgroundPolicyJson.encode(settings).toString())))
        assertEquals(listOf("Office", " café "), settings.moveSsid(0, 1).excludeSSIDs)
        assertEquals(listOf("Office", "Home"), settings.editSsid(" café ", "Home").excludeSSIDs)
        assertSame(settings, settings.editSsid(" café ", " café "))
        assertEquals(listOf("Office"), settings.deleteSsids(setOf(" café ")).excludeSSIDs)
        assertEquals(BackgroundPolicyPreferences(), BackgroundPolicyJson.decode(null))
    }

    @Test fun malformedRestoresCannotSilentlyEnableOrDiscardPolicy() {
        listOf("{\"autoRun\":\"true\"}", "{\"hidden\":null}", "{\"excludeSSIDs\":[1]}",
            "{\"excludeSSIDs\":[\"x\",\"x\"]}", "{\"excluded\":[]}", "{\"showNotificationStopAction\":1}").forEach { raw ->
            assertTrue(raw, runCatching { BackgroundPolicyJson.decode(JSONObject(raw)) }.isFailure)
        }
        assertFalse(BackgroundPolicyPreferences.validSsid("网".repeat(11)))
        assertTrue(BackgroundPolicyPreferences.validSsid("网".repeat(10)))
        assertEquals(" Office ", BackgroundPolicyPreferences.ssidFromPlatform("\" Office \""))
        assertNull(BackgroundPolicyPreferences.ssidFromPlatform("<unknown ssid>"))
    }

    @Test fun excludedNetworkParksListenerAndLeavingResumesWithoutChangingIntent() = runBlocking {
        val calls = mutableListOf<ListenerPolicyIntent>()
        val actor = BackgroundPolicyActor(this, { calls += it }, { throw it })
        val policy = BackgroundPolicyPreferences(excludeSSIDs = listOf("Home"))
        actor.update(policy, true, false, "Home"); yield()
        assertEquals(false to true, calls.last().let { it.listening to it.suspended })
        actor.update(policy, true, false, "home"); yield()
        assertEquals(true to false, calls.last().let { it.listening to it.suspended })
        actor.update(policy, true, false, null); yield()
        assertEquals(2, calls.size)
        actor.update(policy, false, false, "Home"); yield()
        assertEquals(false to false, calls.last().let { it.listening to it.suspended })
        actor.update(policy, true, true, "Home"); yield()
        assertEquals(3, calls.size)
        actor.close()
    }

    @Test fun pendingNetworkStormConflatesAndLatestIntentRemainsAuthoritative() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<ListenerPolicyIntent>()
        val actor = BackgroundPolicyActor(this, { calls += it; if (calls.size == 1) gate.await() }, { throw it })
        val policy = BackgroundPolicyPreferences(excludeSSIDs = listOf("Home"))
        actor.update(policy, true, false, null); yield()
        repeat(100) { actor.update(policy, true, false, if (it % 2 == 0) "Home" else null) }
        actor.update(policy, false, false, "Home")
        assertFalse(actor.isCurrent(calls.single()))
        gate.complete(Unit); yield()
        assertEquals(2, calls.size)
        assertFalse(calls.last().listening)
        assertFalse(calls.last().suspended)
        actor.close()
    }

    @Test fun failedTransitionIsReportedAndOnlyFreshEventRetries() = runBlocking {
        var attempts = 0
        val failures = mutableListOf<Throwable>()
        val actor = BackgroundPolicyActor(this, { attempts++; if (attempts == 1) error("FGS denied") }, failures::add)
        actor.update(BackgroundPolicyPreferences(), true, false, null); yield()
        yield(); assertEquals(1, attempts); assertEquals("FGS denied", failures.single().message)
        actor.update(BackgroundPolicyPreferences(), true, false, null); yield()
        assertEquals(2, attempts)
        actor.close()
    }

    @Test fun skippedObsoleteTransitionCannotSuppressNewIdenticalIntent() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<ListenerPolicyIntent>()
        val applied = mutableListOf<ListenerPolicyIntent>()
        lateinit var actor: BackgroundPolicyActor
        actor = BackgroundPolicyActor(this, { intent ->
            started += intent
            if (started.size == 1) gate.await()
            if (actor.isCurrent(intent)) applied += intent
        }, { throw it })
        actor.update(BackgroundPolicyPreferences(), true, false, null); yield()
        actor.update(BackgroundPolicyPreferences(), true, false, null)
        gate.complete(Unit); yield()
        assertEquals(2, started.size)
        assertEquals(1, applied.size)
        assertTrue(actor.isCurrent(applied.single()))
        actor.close()
    }
}
