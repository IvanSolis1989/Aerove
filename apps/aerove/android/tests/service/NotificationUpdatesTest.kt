package com.follow.clash.service

import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.modules.changesAfter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationUpdatesTest {
    private val started = NotificationParams(title = "AEROVE")
    private val renamed = NotificationParams(title = "Profile")

    @Test
    fun `the notification posted at start is not posted again`() = runTest {
        val params = MutableStateFlow(started)
        val posted = mutableListOf<NotificationParams>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            params.changesAfter(started).toList(posted)
        }

        assertEquals(emptyList<NotificationParams>(), posted)

        params.value = renamed
        params.value = started

        assertEquals(listOf(renamed, started), posted)
    }

    @Test
    fun `a change made before the collector subscribes is still posted`() = runTest {
        val params = MutableStateFlow(started)
        val initial = params.value
        params.value = renamed
        val posted = mutableListOf<NotificationParams>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            params.changesAfter(initial).toList(posted)
        }

        assertEquals(listOf(renamed), posted)
    }
}
