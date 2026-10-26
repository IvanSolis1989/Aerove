package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MaintenanceBackoffEpochTest {
    @Test fun oldSuccessAndFailureCannotMutateNewEpochBackoff() {
        val backoff = MaintenanceBackoff({ 1000L })
        val old = backoff.ticket("p")
        backoff.reset()
        backoff.failedIfCurrent("p", "source", 86_400_000, old)
        assertTrue(backoff.ready("p", "source"))
        val fresh = backoff.ticket("p")
        backoff.failedIfCurrent("p", "source", 86_400_000, fresh)
        assertFalse(backoff.ready("p", "source"))
        val deadline = backoff.deadline("p", "source")
        backoff.succeededIfCurrent("p", old)
        backoff.failedIfCurrent("p", "source", 86_400_000, old)
        assertEquals(deadline, backoff.deadline("p", "source"))
        backoff.succeededIfCurrent("p", fresh)
        assertTrue(backoff.ready("p", "source"))
    }

    @Test fun oneKeyResetInvalidatesOnlyThatKeysTicket() {
        val backoff = MaintenanceBackoff({ 1000L })
        val first = backoff.ticket("one"); val second = backoff.ticket("two")
        backoff.reset("one")
        backoff.failedIfCurrent("one", "source", 86_400_000, first)
        backoff.failedIfCurrent("two", "source", 86_400_000, second)
        assertTrue(backoff.ready("one", "source"))
        assertFalse(backoff.ready("two", "source"))
    }
}
