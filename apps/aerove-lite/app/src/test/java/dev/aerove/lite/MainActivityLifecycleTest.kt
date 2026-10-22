package dev.aerove.lite

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MainActivityLifecycleTest {
    @Test fun visibilityStillFollowsOnlyResumeAndPause() {
        val events = mutableListOf<String>()
        val owner = owner(events)
        owner.onCreate()
        owner.onResume()
        owner.onPause()
        owner.onStop(changingConfigurations = false)
        owner.onDestroy(changingConfigurations = false)
        assertEquals(listOf("visible:true", "visible:false"), events.filter { it.startsWith("visible:") })
        assertEquals(listOf("state:Resumed", "state:Inactive", "state:Paused", "state:Detached"), events.filter { it.startsWith("state:") })
    }

    @Test fun configurationRecreationDoesNotSendBackgroundOrDetachedAndKeepsTheController() {
        val events = mutableListOf<String>()
        val transitions = mutableListOf<AuroraLifecycleState>()
        var schedules = 0
        var checks = 0
        val controller = ControllerAuroraRotation(
            settings = { LiteSettings() },
            onCheck = { _, eligible -> if (eligible()) checks++ },
            schedule = { _, _ -> schedules++; AuroraRotationHandle {} },
            clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
        )
        fun activity() = ActivityAuroraLifecycle(
            initialize = { controller.initialize(AuroraLifecycleState.Inactive) },
            transition = { transitions += it; controller.setLifecycle(it) },
            visible = {},
            registerTimeReceiver = { events += "register" },
            unregisterTimeReceiver = { events += "unregister" },
            checkTime = { events += "check" },
        )
        val first = activity()
        first.onCreate()
        first.onResume()
        first.onPause()
        first.onStop(changingConfigurations = true)
        first.onDestroy(changingConfigurations = true)
        val scheduledBeforeRecreation = schedules
        val recreated = activity()
        recreated.onCreate()
        assertEquals(scheduledBeforeRecreation, schedules)
        recreated.onResume()
        assertEquals(0, checks)
        assertEquals(listOf(AuroraLifecycleState.Resumed, AuroraLifecycleState.Inactive, AuroraLifecycleState.Resumed), transitions)
        assertEquals(listOf("register", "unregister", "register"), events)
        recreated.onPause()
        recreated.onStop(changingConfigurations = false)
        assertEquals(AuroraLifecycleState.Paused, transitions.last())
        assertEquals(1, checks)
        recreated.onDestroy(changingConfigurations = false)
        controller.dispose()
    }

    @Test fun eachActivityUnregistersOnlyItsOwnReceiverOnceAndIgnoresLateBroadcasts() {
        val firstEvents = mutableListOf<String>()
        val secondEvents = mutableListOf<String>()
        val first = owner(firstEvents)
        val second = owner(secondEvents)
        first.onTimeChanged()
        first.onCreate()
        first.onCreate()
        first.onTimeChanged()
        first.onDestroy(changingConfigurations = true)
        first.onDestroy(changingConfigurations = true)
        second.onCreate()
        first.onTimeChanged()
        second.onTimeChanged()
        assertEquals(1, firstEvents.count { it == "register" })
        assertEquals(1, firstEvents.count { it == "unregister" })
        assertEquals(1, firstEvents.count { it == "check" })
        assertEquals(0, secondEvents.count { it == "unregister" })
        assertEquals(1, secondEvents.count { it == "check" })
        second.onDestroy(changingConfigurations = false)
        assertEquals(1, secondEvents.count { it == "unregister" })
    }

    private fun owner(events: MutableList<String>) = ActivityAuroraLifecycle(
        initialize = { events += "initialize" },
        transition = { events += "state:$it" },
        visible = { events += "visible:$it" },
        registerTimeReceiver = { events += "register" },
        unregisterTimeReceiver = { events += "unregister" },
        checkTime = { events += "check" },
    )
}
