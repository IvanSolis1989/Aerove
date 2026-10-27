package dev.aerove.lite.vpn

import dev.aerove.lite.VpnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceLifecycleTraceTest {
    @Test fun capturedCommandCauseDoesNotInheritTheLatestReceipt() {
        assertEquals("schema=2 event=StartSubmitted processInstance=2 latestReceivedStartId=9 " +
            "latestReceivedCommand=Stop causeStartId=3 causeCommand=Start causeIntentGeneration=5 owner=4 " +
            "intentGeneration=6 sessionGeneration=0 phase=Stopped visiblePhase=Stopped " +
            "planIntentGeneration=5 scopeActive=true coreScopeActive=false",
            VpnServiceLifecycleTrace.format(VpnServiceLifecycleTrace.Event.StartSubmitted, 2, 9,
                VpnServiceLifecycleTrace.Command.Stop, 3, VpnServiceLifecycleTrace.Command.Start, 5,
                4, 6, 0, VpnPhase.Stopped, VpnPhase.Stopped, 5, true, false))
    }

    @Test fun asynchronousEventKeepsItsUnknownCauseNullEvenAfterAnUnknownCommand() {
        val row = VpnServiceLifecycleTrace.format(VpnServiceLifecycleTrace.Event.StatePublished, 1, 7,
            VpnServiceLifecycleTrace.Command.Other, null, null, null, 1, 8, null, null,
            VpnPhase.Stopped, null, true, true)
        assertTrue(row.contains("latestReceivedStartId=7 latestReceivedCommand=Other"))
        assertTrue(row.contains("causeStartId=null causeCommand=null causeIntentGeneration=null"))
        assertTrue(row.contains("sessionGeneration=null phase=null"))
        assertTrue(VpnServiceLifecycleTrace.nextInstance() < VpnServiceLifecycleTrace.nextInstance())
    }

    @Test fun schemaUsesFixedEnumsAndCallsCancellationARequest() {
        val keys = setOf("schema", "event", "processInstance", "latestReceivedStartId", "latestReceivedCommand",
            "causeStartId", "causeCommand", "causeIntentGeneration", "owner", "intentGeneration",
            "sessionGeneration", "phase", "visiblePhase", "planIntentGeneration", "scopeActive", "coreScopeActive")
        for (event in VpnServiceLifecycleTrace.Event.entries) for (command in VpnServiceLifecycleTrace.Command.entries) {
            val row = VpnServiceLifecycleTrace.format(event, 1, 2, command, null, null, null, 3, 4, 6,
                VpnPhase.Starting, VpnPhase.Stopped, 5, true, false)
            val fields = row.split(' ').map { it.split('=') }
            assertEquals(keys, fields.map { it[0] }.toSet())
            assertTrue(fields.all { it.size == 2 && it[1].matches(Regex("[A-Za-z0-9]+")) })
        }
        assertEquals("ScopesCancelRequested", VpnServiceLifecycleTrace.Event.ScopesCancelRequested.name)
        assertTrue(VpnServiceLifecycleTrace.Event.entries.none { it.name == "ScopesCancelled" })
    }
}
