package com.follow.clash.service.modules

import com.follow.clash.service.models.NotificationParams
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUpdatesTest {
    @Test
    fun `repeated unlocks repost static background notifications without starting speed ticks`() = runTest {
        val screen = MutableSharedFlow<Boolean>(replay = 1)
        val active = MutableStateFlow(false)
        val params = MutableStateFlow(NotificationParams())
        val updates = mutableListOf<Pair<Any, Boolean>>()
        screen.emit(true)
        backgroundScope.launch {
            notificationUpdates(screen, active, params)
                .map { it.session to it.showSpeed }
                .distinctUntilChanged()
                .collect { updates.add(it) }
        }
        runCurrent()
        assertEquals(1, updates.size)
        repeat(2) {
            screen.emit(true)
            runCurrent()
            assertEquals(it + 2, updates.size)
            assertFalse(updates.last().second)
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, updates.size)
    }

    @Test
    fun `background and screen off cancel speed ticks while notification actions stay current`() = runTest {
        val screen = MutableStateFlow(true)
        val active = MutableStateFlow(false)
        val params = MutableStateFlow(NotificationParams())
        val updates = mutableListOf<NotificationUpdate>()
        backgroundScope.launch {
            notificationUpdates(screen, active, params).collect { updates.add(it) }
        }
        runCurrent()
        assertFalse(updates.last().showSpeed)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, updates.size)

        active.value = true
        runCurrent()
        assertTrue(updates.last().showSpeed)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, updates.size)

        active.value = false
        runCurrent()
        val pausedCount = updates.size
        params.value = NotificationParams(stopText = "Halt", showStopAction = false)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(pausedCount + 1, updates.size)
        assertFalse(updates.last().showSpeed)
        assertEquals("Halt", updates.last().params.stopText)
        assertFalse(updates.last().params.showStopAction)

        screen.value = false
        active.value = true
        runCurrent()
        val screenOffCount = updates.size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(screenOffCount, updates.size)
        screen.value = true
        runCurrent()
        assertTrue(updates.last().showSpeed)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(screenOffCount + 2, updates.size)
    }
}
