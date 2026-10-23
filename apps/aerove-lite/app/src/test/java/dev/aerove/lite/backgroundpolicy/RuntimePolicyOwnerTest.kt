package dev.aerove.lite.backgroundpolicy

import dev.aerove.lite.vpn.*
import org.junit.Assert.*
import org.junit.Test

class RuntimePolicyOwnerTest {
    private val target = RuntimePolicyTarget(true, "product-agent", true, true, true, false, "", "always")
    private class Commands : RuntimePolicyCommands {
        val events = mutableListOf<String>()
        var probe = ProbeDialReadback(true, true, "123")
        var controller = UserControllerReadback(false, null, false, true, "123")
        var afterProbe: () -> Unit = {}
        var process = ProcessModeReadback("always", "123")
        var wrongProcessReadback = false
        override fun statistics(enabled: Boolean): String { events += "statistics"; return "123" }
        override fun http(userAgent: String, checkCertificate: Boolean) { events += "http" }
        override fun probe(unifiedDelay: Boolean, tcpConcurrent: Boolean) {
            events += "probe"; probe = probe.copy(unifiedDelay = unifiedDelay, tcpConcurrent = tcpConcurrent); afterProbe()
        }
        override fun probeStatus(): ProbeDialReadback { events += "probe-read"; return probe }
        override fun controller(enabled: Boolean, secret: String): UserControllerReadback {
            events += "controller"; controller = controller.copy(enabled = enabled, address = if (enabled) "127.0.0.1:9090" else null,
                authenticated = enabled)
            return controller
        }
        override fun controllerStatus(): UserControllerReadback { events += "controller-read"; return controller }
        override fun processMode(mode: String) { events += "process"; if (!wrongProcessReadback) process = process.copy(mode = mode) }
        override fun processStatus(): ProcessModeReadback { events += "process-read"; return process }
    }

    @Test fun identicalPoliciesHaveNoCommandsAndProbeChangeDoesNotReconfigureOtherPolicies() {
        val commands = Commands(); val owner = RuntimePolicyOwner(commands)
        assertTrue(owner.apply(target, "123") { true }); commands.events.clear()
        assertTrue(owner.apply(target, "123") { true }); assertTrue(commands.events.isEmpty())
        assertTrue(owner.apply(target.copy(unifiedDelay = false), "123") { true })
        assertEquals(listOf("probe", "probe-read"), commands.events)
    }

    @Test fun enabledControllerNeedsAuthenticationAndCannotExposePrivateRoutes() {
        val commands = Commands(); val owner = RuntimePolicyOwner(commands)
        owner.apply(target, "123") { true }; commands.events.clear()
        assertTrue(owner.apply(target.copy(controllerEnabled = true, controllerSecret = "not-for-logs"), "123") { true })
        assertEquals(listOf("controller", "controller-read"), commands.events)
        assertFalse(target.copy(controllerSecret = "not-for-logs").toString().contains("not-for-logs"))
        commands.controller = commands.controller.copy(privateRoutesExcluded = false)
        assertThrows(IllegalStateException::class.java) {
            owner.apply(target.copy(controllerEnabled = true, controllerSecret = "rotated-secret"), "123") { true }
        }
    }

    @Test fun emptyEnabledSecretIsAnErrorBeforeAnyNativeCommand() {
        val commands = Commands()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePolicyOwner(commands).apply(target.copy(controllerEnabled = true), "123") { true }
        }
        assertTrue(commands.events.isEmpty())
    }

    @Test fun staleEpochCannotCommitAndNextRetryPerformsTheTransaction() {
        val commands = Commands(); commands.probe = commands.probe.copy(coreEpoch = "122")
        val owner = RuntimePolicyOwner(commands)
        assertThrows(IllegalStateException::class.java) { owner.apply(target, "123") { true } }
        commands.probe = commands.probe.copy(coreEpoch = "123"); commands.events.clear()
        assertTrue(owner.apply(target, "123") { true })
        assertEquals(listOf("statistics", "http", "probe", "probe-read", "controller", "controller-read", "process", "process-read"), commands.events)
    }

    @Test fun supersededAcceptedCommandDoesNotEstablishTheDiffBaseline() {
        val commands = Commands(); val owner = RuntimePolicyOwner(commands); var current = true
        commands.afterProbe = { current = false }
        assertFalse(owner.apply(target, "123") { current })
        assertFalse(commands.events.contains("controller"))
        commands.afterProbe = {}; current = true; commands.events.clear()
        assertTrue(owner.apply(target.copy(tcpConcurrent = false), "123") { current })
        assertEquals(listOf("statistics", "http", "probe", "probe-read", "controller", "controller-read", "process", "process-read"), commands.events)
    }
    @Test fun configRevisionsAdvanceWithinTheSameOwnerAndRejectNoncanonicalReadback() {
        val commands = Commands(); val owner = RuntimePolicyOwner(commands)
        commands.probe = commands.probe.copy(coreEpoch = "124")
        commands.controller = commands.controller.copy(coreEpoch = "125")
        commands.process = commands.process.copy(coreEpoch = "126")
        assertTrue(owner.apply(target, "123") { true })
        assertEquals("126", owner.lastEpoch)
        assertThrows(IllegalStateException::class.java) { owner.apply(target, "124") { true } }
        assertThrows(IllegalArgumentException::class.java) { requireConfigEpoch("001") }
        assertThrows(IllegalArgumentException::class.java) { requireConfigEpoch("18446744073709551616") }
        assertEquals("18446744073709551615", requireConfigEpoch("18446744073709551615", "125"))
    }

    @Test fun privateRandomControllerNeverTakesTheHotEnableUserControllerPort() {
        val offered = java.util.ArrayDeque(listOf(9090, 55000))
        val closed = mutableListOf<Int>()
        val selected = selectTunnelControlPort(VpnPlan("id", dev.aerove.lite.LiteSettings())) {
            val value = offered.removeFirst()
            object : dev.aerove.lite.networksettings.BoundControlPort {
                override val port: Int = value
                override fun close() { closed += value }
            }
        }
        assertEquals(55000, selected)
        assertEquals(setOf(9090, 55000), closed.toSet())
    }

    @Test fun processModeIsHotDiffedAndRequiresActualMatchingReadback() {
        val commands = Commands(); val owner = RuntimePolicyOwner(commands)
        owner.apply(target, "123") { true }; commands.events.clear()
        assertTrue(owner.apply(target.copy(findProcessMode = "strict"), "123") { true })
        assertEquals(listOf("process", "process-read"), commands.events)
        commands.events.clear(); commands.wrongProcessReadback = true
        assertThrows(IllegalStateException::class.java) { owner.apply(target.copy(findProcessMode = "off"), "123") { true } }
        commands.wrongProcessReadback = false
        assertTrue(owner.apply(target.copy(findProcessMode = "off"), "123") { true })
        assertEquals("off", commands.process.mode)
    }

    @Test fun invalidProcessModeIsRejectedWithoutAnyNativeCommand() {
        val commands = Commands()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimePolicyOwner(commands).apply(target.copy(findProcessMode = "guess"), "123") { true }
        }
        assertTrue(commands.events.isEmpty())
    }

}
