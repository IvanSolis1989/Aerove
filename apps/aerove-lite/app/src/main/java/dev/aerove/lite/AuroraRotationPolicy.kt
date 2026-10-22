package dev.aerove.lite

import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.random.Random

fun auroraRotationDayOf(now: ZonedDateTime): Long {
    val day = if (now.hour < 5) now.toLocalDate().minusDays(1) else now.toLocalDate()
    return day.year.toLong() * 10000 + day.monthValue * 100 + day.dayOfMonth
}

fun shuffleAuroraWallpaper(
    settings: LiteSettings,
    now: ZonedDateTime = ZonedDateTime.now(),
    random: Random = Random.Default,
): LiteSettings {
    if (!settings.aurora) return settings
    return settings.copy(
        auroraSeed = Math.floorMod(settings.auroraSeed + 1L + random.nextInt(0x3ffffffe), 0x7fffffffL),
        auroraRotationDay = maxOf(settings.auroraRotationDay, auroraRotationDayOf(now)),
    )
}

fun rotateAuroraForDay(
    settings: LiteSettings,
    now: ZonedDateTime,
    random: Random = Random.Default,
): LiteSettings {
    if (!settings.aurora || auroraRotationDayOf(now) <= settings.auroraRotationDay) return settings
    return shuffleAuroraWallpaper(settings, now, random)
}

enum class AuroraLifecycleState { Resumed, Inactive, Hidden, Paused, Detached }

fun interface AuroraRotationHandle { fun cancel() }

// Lifecycle calls and the injected one-shot callback share the owner's thread.
class AuroraDailyRotation(
    private val onCheck: (ZonedDateTime) -> Unit,
    private val schedule: (Duration, () -> Unit) -> AuroraRotationHandle,
    private val clock: Clock? = null,
) {
    private var timer: AuroraRotationHandle? = null
    private var background = false
    private var backgroundReady = false
    private var detached = false
    private var disposed = false
    private var generation = 0L

    fun initialize(state: AuroraLifecycleState?) {
        if (disposed) return
        updateLifecycle(state)
        backgroundReady = false
        check()
    }

    fun setLifecycle(state: AuroraLifecycleState?) {
        if (disposed) return
        val previousBackground = background
        updateLifecycle(state)
        if (!background) backgroundReady = false else if (!previousBackground) backgroundReady = true
        check()
    }

    fun check() {
        if (disposed) return
        cancelTimer()
        if (detached) return
        val token = generation
        val now = if (clock == null) ZonedDateTime.now() else ZonedDateTime.now(clock)
        if (background && backgroundReady) onCheck(now)
        if (disposed || detached || generation != token) return
        val today = now.toLocalDate().atTime(5, 0).atZone(now.zone)
        val next = if (now.isBefore(today)) today else now.toLocalDate().plusDays(1).atTime(5, 0).atZone(now.zone)
        val handle = schedule(Duration.between(now.toInstant(), next.toInstant())) {
            if (!disposed && !detached && generation == token) {
                if (background) backgroundReady = true
                check()
            }
        }
        if (disposed || detached || generation != token) handle.cancel() else timer = handle
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        background = false
        backgroundReady = false
        cancelTimer()
    }

    private fun updateLifecycle(state: AuroraLifecycleState?) {
        background = state == AuroraLifecycleState.Hidden || state == AuroraLifecycleState.Paused
        detached = state == AuroraLifecycleState.Detached
    }

    private fun cancelTimer() {
        generation++
        val previous = timer
        timer = null
        previous?.cancel()
    }
}
