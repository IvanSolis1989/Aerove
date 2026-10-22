package dev.aerove.lite

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AuroraDailyRotationTest {
    @Test fun initialDetachedHasNoTimerOrRotation() {
        val harness = Harness("2026-10-19T08:00+09:00[Asia/Tokyo]")
        harness.rotation.initialize(AuroraLifecycleState.Detached)
        harness.rotation.check()
        assertTrue(harness.tasks.isEmpty())
        assertTrue(harness.checked.isEmpty())
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        assertEquals(1, harness.activeTasks())
        assertTrue(harness.checked.isEmpty())
        harness.rotation.dispose()
    }

    @Test fun initialHiddenAndPausedWaitForATrueBackgroundTransition() {
        listOf(AuroraLifecycleState.Hidden, AuroraLifecycleState.Paused).forEach { initial ->
            val harness = Harness("2026-10-19T08:00+09:00[Asia/Tokyo]")
            harness.rotation.initialize(initial)
            harness.rotation.check()
            harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
            harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
            assertTrue(harness.checked.isEmpty())
            harness.rotation.setLifecycle(AuroraLifecycleState.Inactive)
            harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
            assertTrue(harness.checked.isEmpty())
            harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
            assertEquals(1, harness.changes)
            assertEquals(20261019L, harness.settings.auroraRotationDay)
            harness.rotation.dispose()
        }
    }

    @Test fun foregroundTimerInactiveAndResumeNeverShuffle() {
        val harness = Harness("2026-12-31T04:59:59+09:00[Asia/Tokyo]")
        harness.rotation.initialize(null)
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        assertEquals(Duration.ofSeconds(1), harness.tasks.last().delay)
        harness.clock.value = ZonedDateTime.parse("2026-12-31T05:00+09:00[Asia/Tokyo]")
        harness.tasks.last().fire()
        assertTrue(harness.checked.isEmpty())
        assertEquals(Duration.ofDays(1), harness.tasks.last().delay)
        harness.rotation.setLifecycle(AuroraLifecycleState.Inactive)
        harness.rotation.check()
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        assertEquals(0, harness.changes)
        harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
        assertEquals(1, harness.changes)
        harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
        harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
        harness.rotation.check()
        assertEquals(1, harness.changes)
        assertEquals(1, harness.activeTasks())
        harness.rotation.dispose()
    }

    @Test fun initialBackgroundTimerCanRotateAndCatchUpOnlyOncePerPeriod() {
        listOf(AuroraLifecycleState.Hidden, AuroraLifecycleState.Paused).forEach { initial ->
            val harness = Harness("2026-10-19T04:59:59+09:00[Asia/Tokyo]")
            harness.rotation.initialize(initial)
            assertTrue(harness.checked.isEmpty())
            harness.clock.value = ZonedDateTime.parse("2026-10-19T05:00+09:00[Asia/Tokyo]")
            harness.tasks.last().fire()
            assertEquals(20261019L, harness.settings.auroraRotationDay)
            assertEquals(1, harness.changes)
            harness.rotation.check()
            assertEquals(1, harness.changes)
            harness.clock.value = harness.clock.value.plusDays(1)
            harness.tasks.last().fire()
            assertEquals(2, harness.changes)
            assertEquals(20261020L, harness.settings.auroraRotationDay)
            assertEquals(1, harness.activeTasks())
            harness.rotation.dispose()
        }
    }

    @Test fun enablingAuroraInForegroundWaitsForBackground() {
        val harness = Harness("2026-10-19T08:00+09:00[Asia/Tokyo]", LiteSettings(aurora = false, material = MaterialTier.Solid))
        harness.rotation.initialize(AuroraLifecycleState.Resumed)
        harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
        assertEquals(0, harness.changes)
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        harness.settings = harness.settings.copy(aurora = true)
        harness.rotation.check()
        harness.rotation.setLifecycle(AuroraLifecycleState.Inactive)
        assertEquals(0, harness.changes)
        harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
        assertEquals(1, harness.changes)
        assertEquals(MaterialTier.Solid, harness.settings.material)
        harness.rotation.dispose()
    }

    @Test fun canceledQueuedTimerCannotSetBackgroundReadyOrScheduleMoreWork() {
        val harness = Harness("2026-10-19T08:00+09:00[Asia/Tokyo]")
        harness.rotation.initialize(AuroraLifecycleState.Hidden)
        val stale = harness.tasks.last()
        harness.rotation.check()
        assertTrue(stale.canceled)
        val count = harness.tasks.size
        stale.callback()
        assertTrue(harness.checked.isEmpty())
        assertEquals(count, harness.tasks.size)
        assertEquals(1, harness.activeTasks())
        harness.rotation.dispose()
    }

    @Test fun detachedAndDisposedCancelPendingChecksIncludingQueuedCallbacks() {
        val harness = Harness("2026-10-19T08:00+09:00[Asia/Tokyo]")
        harness.rotation.initialize(AuroraLifecycleState.Hidden)
        val stale = harness.tasks.last()
        harness.rotation.setLifecycle(AuroraLifecycleState.Detached)
        stale.callback()
        assertTrue(harness.checked.isEmpty())
        assertEquals(0, harness.activeTasks())
        harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
        assertEquals(1, harness.changes)
        val pending = harness.tasks.last()
        harness.rotation.dispose()
        val count = harness.tasks.size
        pending.callback()
        harness.rotation.initialize(AuroraLifecycleState.Paused)
        harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
        harness.rotation.check()
        harness.rotation.dispose()
        assertEquals(count, harness.tasks.size)
        assertEquals(1, harness.changes)
        assertEquals(0, harness.activeTasks())
    }

    @Test fun clockRollbackAndForwardJumpRearmWithoutForegroundRotation() {
        val original = LiteSettings(auroraRotationDay = 20270102L)
        val harness = Harness("2027-01-02T12:00+09:00[Asia/Tokyo]", original)
        harness.rotation.initialize(AuroraLifecycleState.Resumed)
        assertEquals(Duration.ofHours(17), harness.tasks.last().delay)
        harness.clock.value = ZonedDateTime.parse("2027-01-01T23:00+09:00[Asia/Tokyo]")
        harness.rotation.setLifecycle(AuroraLifecycleState.Hidden)
        assertEquals(Duration.ofHours(6), harness.tasks.last().delay)
        assertEquals(original, harness.settings)
        harness.clock.value = ZonedDateTime.parse("2027-01-03T06:00+09:00[Asia/Tokyo]")
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        assertEquals(original, harness.settings)
        harness.rotation.setLifecycle(AuroraLifecycleState.Paused)
        assertEquals(20270103L, harness.settings.auroraRotationDay)
        harness.clock.value = ZonedDateTime.parse("2027-01-03T06:00-05:00[America/New_York]")
        harness.rotation.setLifecycle(AuroraLifecycleState.Resumed)
        assertEquals(Duration.ofHours(23), harness.tasks.last().delay)
        assertEquals(1, harness.changes)
        assertEquals(1, harness.activeTasks())
        harness.rotation.dispose()
    }

    @Test fun nextLocalFiveAmUsesDstCalendarDaysRatherThanFixedTwentyFourHours() {
        val cases = mapOf(
            "2026-03-07T12:00-05:00[America/New_York]" to 16L,
            "2026-10-31T12:00-04:00[America/New_York]" to 18L,
            "2026-03-07T05:00-05:00[America/New_York]" to 23L,
            "2026-10-31T05:00-04:00[America/New_York]" to 25L,
        )
        cases.forEach { (now, hours) ->
            val harness = Harness(now)
            harness.rotation.initialize(AuroraLifecycleState.Resumed)
            assertEquals(now, Duration.ofHours(hours), harness.tasks.last().delay)
            harness.rotation.dispose()
        }
    }

    @Test fun disposalInsideOnCheckDoesNotRecreateATimer() {
        val tasks = mutableListOf<Task>()
        lateinit var rotation: AuroraDailyRotation
        rotation = AuroraDailyRotation(
            onCheck = { rotation.dispose() },
            schedule = { delay, callback -> Task(delay, callback).also { tasks += it } },
            clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
        )
        rotation.initialize(AuroraLifecycleState.Resumed)
        rotation.setLifecycle(AuroraLifecycleState.Hidden)
        assertEquals(1, tasks.size)
        assertTrue(tasks.single().canceled)
    }

    private class Harness(now: String, initial: LiteSettings = LiteSettings(auroraRotationDay = 20261017L)) {
        val clock = MutableClock(ZonedDateTime.parse(now))
        val tasks = mutableListOf<Task>()
        val checked = mutableListOf<ZonedDateTime>()
        var settings = initial
        var changes = 0
        val rotation = AuroraDailyRotation(
            onCheck = { date ->
                checked += date
                val next = rotateAuroraForDay(settings, date)
                if (next != settings) changes++
                settings = next
            },
            schedule = { delay, callback -> Task(delay, callback).also { tasks += it } },
            clock = clock,
        )
        fun activeTasks(): Int = tasks.count { !it.canceled && !it.fired }
    }

    private class MutableClock(var value: ZonedDateTime) : Clock() {
        override fun getZone(): ZoneId = value.zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(value.withZoneSameInstant(zone))
        override fun instant(): Instant = value.toInstant()
    }

    private class Task(val delay: Duration, val callback: () -> Unit) : AuroraRotationHandle {
        var canceled = false
        var fired = false
        override fun cancel() { canceled = true }
        fun fire() {
            check(!canceled && !fired)
            fired = true
            callback()
        }
    }
}
