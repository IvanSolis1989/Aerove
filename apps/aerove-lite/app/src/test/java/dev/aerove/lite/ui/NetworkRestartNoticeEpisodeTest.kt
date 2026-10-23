package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test

class NetworkRestartNoticeEpisodeTest {
    @Test fun initialRequiredShowsAndRecompositionDoesNotRearmDismissedEpisode() {
        val shown = NetworkRestartNoticeEpisode().observe(true)
        assertTrue(shown.visible)
        val dismissed = shown.dismiss()
        assertFalse(dismissed.visible)
        repeat(100) { assertEquals(dismissed, dismissed.observe(true)) }
    }

    @Test fun actualFalseClosesAndNewEpisodeCanNotifyAgain() {
        val closed = NetworkRestartNoticeEpisode().observe(true).dismiss().observe(false)
        assertFalse(closed.visible)
        assertFalse(closed.required)
        assertTrue(closed.observe(true).visible)
    }

    @Test fun actualFalseAlsoClosesAnUndismissedWarning() {
        assertFalse(NetworkRestartNoticeEpisode().observe(true).observe(false).visible)
    }
}
