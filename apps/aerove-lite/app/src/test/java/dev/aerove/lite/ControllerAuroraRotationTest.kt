package dev.aerove.lite

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ControllerAuroraRotationTest {
    @Test fun initializationIsOnceForTheRetainedController() {
        val harness = Harness()
        harness.owner.initialize(AuroraLifecycleState.Hidden)
        harness.owner.initialize(AuroraLifecycleState.Resumed)
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        assertTrue(harness.checks.isEmpty())
        harness.owner.setLifecycle(AuroraLifecycleState.Resumed)
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        assertEquals(1, harness.checks.size)
        harness.owner.dispose()
    }

    @Test fun inactiveIsNotBackgroundAndDisabledAuroraNeverRequestsStore() {
        val harness = Harness()
        harness.owner.initialize(AuroraLifecycleState.Inactive)
        harness.owner.setLifecycle(AuroraLifecycleState.Resumed)
        harness.owner.setLifecycle(AuroraLifecycleState.Inactive)
        assertTrue(harness.checks.isEmpty())
        harness.settings = harness.settings.copy(aurora = false)
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        harness.owner.check()
        assertTrue(harness.checks.isEmpty())
        harness.settings = harness.settings.copy(aurora = true)
        harness.owner.check()
        assertEquals(1, harness.checks.size)
        harness.owner.dispose()
    }

    @Test fun oldEligibilityCannotReenterAResumedOrNewBackgroundSession() {
        val harness = Harness()
        harness.owner.initialize(AuroraLifecycleState.Resumed)
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        val first = harness.checks.single()
        assertTrue(first())
        harness.owner.setLifecycle(AuroraLifecycleState.Resumed)
        assertFalse(first())
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        val second = harness.checks.last()
        assertTrue(second())
        assertFalse(first())
        harness.settings = harness.settings.copy(aurora = false)
        assertFalse(second())
        harness.owner.dispose()
    }

    @Test fun clockCheckInvalidatesOldRequestsAndDisposalCancelsOnlyItsHandle() {
        val harness = Harness()
        harness.owner.initialize(AuroraLifecycleState.Resumed)
        harness.owner.setLifecycle(AuroraLifecycleState.Hidden)
        val old = harness.checks.last()
        harness.owner.check()
        assertFalse(old())
        val current = harness.checks.last()
        assertTrue(current())
        val count = harness.tasks.size
        harness.owner.dispose()
        harness.tasks.last().callback()
        harness.owner.check()
        harness.owner.setLifecycle(AuroraLifecycleState.Paused)
        assertFalse(current())
        assertTrue(harness.tasks.all { it.canceled })
        assertEquals(count, harness.tasks.size)
    }

    @Test fun handlerDelayRoundsPositiveFractionsUpToAvoidAnEarlyZeroDelayLoop() {
        assertEquals(1L, auroraHandlerDelayMillis(Duration.ofNanos(1)))
        assertEquals(1L, auroraHandlerDelayMillis(Duration.ofMillis(1)))
        assertEquals(2L, auroraHandlerDelayMillis(Duration.ofMillis(1).plusNanos(1)))
        assertEquals(90_000_000L, auroraHandlerDelayMillis(Duration.ofHours(25)))
    }

    private class Harness {
        var settings = LiteSettings()
        val tasks = mutableListOf<Task>()
        val checks = mutableListOf<() -> Boolean>()
        val owner = ControllerAuroraRotation(
            settings = { settings },
            onCheck = { _, eligible -> checks += eligible },
            schedule = { _, callback -> Task(callback).also { tasks += it } },
            clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
        )
    }

    private class Task(val callback: () -> Unit) : AuroraRotationHandle {
        var canceled = false
        override fun cancel() { canceled = true }
    }
}
