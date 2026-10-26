package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MaintenanceBackoffTest {
    @Test fun sharedTwentyMinuteScheduleCapsAtOwnIntervalAndResets() {
        var now = 0L
        val backoff = MaintenanceBackoff({ now })
        val interval = 3 * 60 * 60_000L
        for (minutes in listOf(20, 40, 80, 160, 180, 180)) {
            backoff.failed("one", "source", interval)
            assertFalse(backoff.ready("one", "source"))
            now += minutes * 60_000L - 1
            assertFalse(backoff.ready("one", "source"))
            now++
            assertTrue(backoff.ready("one", "source"))
        }
        backoff.failed("one", "source", interval)
        assertTrue(backoff.ready("one", "edited-source"))
        backoff.failed("one", "edited-source", interval)
        backoff.reset("one")
        assertTrue(backoff.ready("one", "edited-source"))
        backoff.failed("one", "source", interval)
        backoff.reset()
        assertTrue(backoff.ready("one", "source"))
    }

    @Test fun providerStartsAtTenSecondsAndFailuresAreIndependent() {
        var now = 0L
        val backoff = MaintenanceBackoff({ now }, initialMillis = 10_000)
        for (seconds in listOf(10, 20, 40, 60, 60)) {
            backoff.failed("one", "source", 60_000)
            assertTrue(backoff.ready("two", "source"))
            now += seconds * 1000L
            assertTrue(backoff.ready("one", "source"))
        }
    }
}
