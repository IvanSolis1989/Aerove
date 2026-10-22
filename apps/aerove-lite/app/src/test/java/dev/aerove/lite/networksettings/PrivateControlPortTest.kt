package dev.aerove.lite.networksettings

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PrivateControlPortTest {
    private class Reservation(override val port: Int, private val failingClose: Boolean = false) : BoundControlPort {
        var closed = false
        override fun close() { closed = true; if (failingClose) throw IOException("release failed") }
    }

    @Test fun conflictingUserPortsStayBoundUntilASeparatePrivatePortIsSelected() {
        val reservations = listOf(Reservation(7890), Reservation(7891), Reservation(7892), Reservation(59000))
        var index = 0
        val selected = selectPrivateControlPort(NetworkSettings(ports = UserProxyPorts(7890, 7891, 7892))) {
            assertTrue(reservations.take(index).all { !it.closed })
            reservations[index++]
        }
        assertEquals(59000, selected); assertEquals(4, index); assertTrue(reservations.all { it.closed })
    }

    @Test fun failedBindReleasesEarlierReservationsAndDoesNotProduceAPort() {
        val held = Reservation(7890)
        var calls = 0
        assertThrows(IOException::class.java) {
            selectPrivateControlPort(NetworkSettings()) { if (calls++ == 0) held else throw IOException("bind failed") }
        }
        assertTrue(held.closed)
    }

    @Test fun releaseFailureStillClosesEveryReservationAndCannotReportSuccess() {
        val reservations = listOf(Reservation(7890, true), Reservation(59000, true)); var index = 0
        val error = assertThrows(IOException::class.java) { selectPrivateControlPort(NetworkSettings()) { reservations[index++] } }
        assertTrue("allocated=$index closed=${reservations.map { it.closed }} error=${error.message}", reservations.all { it.closed })
        assertEquals(1, error.suppressed.size)
    }

    @Test fun invalidSettingsAreRejectedBeforeAnySocketIsRequested() {
        var bound = false
        assertThrows(NetworkSettingsException::class.java) {
            selectPrivateControlPort(NetworkSettings(ports = UserProxyPorts(7890, 7890))) { bound = true; Reservation(59000) }
        }
        assertFalse(bound)
    }

    @Test fun cleanupFailureDoesNotReplaceTheOriginalBindError() {
        val held = Reservation(7890, true); var calls = 0
        val error = assertThrows(IOException::class.java) {
            selectPrivateControlPort(NetworkSettings()) { if (calls++ == 0) held else throw IOException("original bind failure") }
        }
        assertEquals("original bind failure", error.message); assertEquals(1, error.suppressed.size)
    }
}
