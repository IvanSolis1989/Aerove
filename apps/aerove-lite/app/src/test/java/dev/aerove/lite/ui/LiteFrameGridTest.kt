package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test

class LiteFrameGridTest {
    @Test fun lateJoiningClockSharesTheExistingDeadlineAndCannotResetItsEpoch() {
        val a = Any(); val b = Any()
        LiteFrameGrid.join(a)
        try {
            assertEquals(66_666_667L, LiteFrameGrid.next(a, 0, 15))
            LiteFrameGrid.join(b)
            try {
                assertEquals(66_666_667L, LiteFrameGrid.next(b, 20_000_000, 15))
                assertEquals(100_000_000L, LiteFrameGrid.next(b, 30_000_000, 10))
                assertEquals(66_666_667L, LiteFrameGrid.next(a, 40_000_000, 15))
            } finally { LiteFrameGrid.leave(b) }
        } finally { LiteFrameGrid.leave(a) }
    }
    @Test fun lastClockReleasesEpochAndSingleClockMayRebaseWhenPaceChanges() {
        val token = Any(); LiteFrameGrid.join(token)
        assertEquals(1_066_666_667L, LiteFrameGrid.next(token, 1_000_000_000L, 15))
        assertEquals(1_140_000_000L, LiteFrameGrid.next(token, 1_040_000_000L, 10))
        LiteFrameGrid.leave(token); LiteFrameGrid.join(token)
        assertEquals(2_066_666_667L, LiteFrameGrid.next(token, 2_000_000_000L, 15))
        LiteFrameGrid.leave(token)
    }
    @Test fun balancedLabelsKeepWholeWordsAndExplicitCjkPhrases() {
        assertEquals("局域网\n代理", dashboardBalancedLabel(dashboardControlLabel("局域网代理")) { it.length.toFloat() }?.first)
        assertNull(dashboardBalancedLabel("Глобальный") { it.length.toFloat() })
        assertEquals("Разрешить\nLAN", dashboardBalancedLabel("Разрешить LAN") { it.length.toFloat() }?.first)
        assertTrue(dashboardLabelBreaks("e\u0301LAN 許可").none { it == 1 })
    }
    @Test fun phraseBreaksDoNotSplitLatinOrCyrillicWords() {
        assertEquals("局域网\u200B代理", dashboardControlLabel("局域网代理"))
        assertEquals("LAN\u200Bプロキシ", dashboardControlLabel("LANプロキシ"))
        for (label in listOf("Глобальный", "Разрешить LAN", "Allow LAN", "Permitir LAN", "Autoriser le LAN"))
            assertEquals(label, dashboardControlLabel(label))
    }
}
