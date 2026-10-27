package dev.aerove.lite.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopCommandTest {
    private class FrameworkStartIds(var lastEnqueuedId: Int) {
        val requests = mutableListOf<Int>()
        var destroyed = false
        fun stop(id: Int) {
            requests += id
            if (id == lastEnqueuedId) destroyed = true
        }
    }

    @Test fun newStartEnqueuedButNotDeliveredRejectsTheCapturedOldStopId() {
        val framework = FrameworkStartIds(3)
        assertTrue(requestVpnServiceStop(2, 2, 2, false, false, framework::stop))
        assertEquals(listOf(2), framework.requests)
        assertFalse(framework.destroyed)
    }

    @Test fun newStartAcceptedBeforeProfileReadInvalidatesOldCompletion() {
        val framework = FrameworkStartIds(3)
        assertFalse(requestVpnServiceStop(2, 2, 3, false, false, framework::stop))
        assertTrue(framework.requests.isEmpty())
        assertFalse(framework.destroyed)
    }

    @Test fun currentExplicitOrRevokeStopStillRequestsTheOwnedIdAfterJoin() {
        for (generation in listOf(2L, 5L)) {
            val framework = FrameworkStartIds(2)
            assertTrue(requestVpnServiceStop(2, generation, generation, false, false, framework::stop))
            assertEquals(listOf(2), framework.requests)
            assertTrue(framework.destroyed)
        }
    }

    @Test fun repeatedStopKeepsOnlyItsNewestIntentAndNeverAdoptsItsIdForTheOldStop() {
        val framework = FrameworkStartIds(4)
        assertFalse(requestVpnServiceStop(2, 2, 4, false, false, framework::stop))
        assertTrue(requestVpnServiceStop(4, 4, 4, false, false, framework::stop))
        assertEquals(listOf(4), framework.requests)
    }

    @Test fun coldServiceActivePlanAndMaintenanceCannotRequestAnUnownedStop() {
        val requests = mutableListOf<Int>()
        for (invalid in listOf(-1, 0)) assertFalse(requestVpnServiceStop(invalid, 0, 0, false, false, requests::add))
        assertFalse(requestVpnServiceStop(2, 2, 2, true, false, requests::add))
        assertFalse(requestVpnServiceStop(2, 2, 2, false, true, requests::add))
        assertTrue(requests.isEmpty())
    }
}
