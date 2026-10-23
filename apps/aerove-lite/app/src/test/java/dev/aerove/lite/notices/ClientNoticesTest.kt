package dev.aerove.lite.notices

import org.junit.Assert.*
import org.junit.Test

class ClientNoticesTest {
    private var now = 0L
    private fun owner() = ClientNotices { now }.also { it.setForeground(true) }
    private fun ClientNotices.visible() = state.value.entries.filter { it.visible }.map { it.notice }
    private fun ClientNotices.at(time: Long) { now = time; advance() }

    @Test fun levelsKeepStandardOrderDurationsAndActionMinimum() {
        assertEquals(listOf("info", "success", "warning", "error"), NoticeLevel.entries.map { it.wireName })
        assertEquals(listOf(3000L, 3000L, 5000L, 6000L), NoticeLevel.entries.map { it.durationMillis })
        val q = owner(); q.post("info", action = ClientNoticeAction("retry") {})
        assertEquals(6000L, q.delayUntilNextEvent()); q.at(5999); assertEquals(1, q.visible().size)
        q.at(6000); assertTrue(q.visible().isEmpty())
    }
    @Test fun visibleAndBufferBoundsDropOldestNonErrorFirst() {
        val q = owner(); q.post("a", NoticeLevel.Error); q.post("b", NoticeLevel.Error)
        repeat(8) { q.post("normal$it") }; q.post("queued error", NoticeLevel.Error)
        assertEquals(2, q.visible().size); assertEquals(8, q.state.value.buffered.size)
        assertFalse(q.state.value.buffered.any { it.text == "normal0" })
        assertTrue(q.state.value.buffered.any { it.text == "queued error" })
    }
    @Test fun allErrorBufferDropsOldestWithoutExceedingEight() {
        val q = owner(); repeat(11) { q.post("error$it", NoticeLevel.Error) }
        assertEquals(8, q.state.value.buffered.size); assertFalse(q.state.value.buffered.any { it.text == "error2" })
        assertEquals("error3", q.state.value.buffered.first().text)
    }
    @Test fun duplicateVisibleReusesIdentityReplacesActionAndResetsDuration() {
        val q = owner(); var old = 0; var replacement = 0
        val id = q.post("duplicate", action = ClientNoticeAction("old") { old++ })!!
        now = 2000; assertEquals(id, q.post("duplicate", action = ClientNoticeAction("new") { replacement++ }))
        assertEquals(6000L, q.delayUntilNextEvent()); assertEquals("new", q.visible().single().actionLabel)
        assertTrue(q.performAction(id)); assertFalse(q.performAction(id)); assertEquals(0, old); assertEquals(1, replacement)
    }
    @Test fun duplicateBufferKeepsOriginalQueuePositionAndIdentity() {
        val q = owner(); q.post("a"); q.post("b"); val id = q.post("queued")
        assertEquals(id, q.post("queued")); assertEquals(1, q.state.value.buffered.size)
        assertNotEquals(id, q.post("queued", NoticeLevel.Warning))
    }
    @Test fun errorPreemptsOldestNonErrorAndDepartingCardsStayBounded() {
        val q = owner(); q.post("old"); q.post("new", NoticeLevel.Warning); q.post("error", NoticeLevel.Error)
        assertEquals(listOf("error", "new"), q.visible().map { it.text })
        assertFalse(q.state.value.entries.first { it.notice.text == "old" }.visible)
        repeat(50) { q.post("burst$it", NoticeLevel.Error) }
        assertTrue(q.state.value.entries.size <= 4); assertEquals(2, q.visible().size); assertTrue(q.state.value.buffered.size <= 8)
    }
    @Test fun cancelBufferedAndVisibleNeverInvokesActionAndFillsFreeSlot() {
        val q = owner(); var calls = 0; val first = q.post("a", action = ClientNoticeAction("run") { calls++ })!!
        q.post("b"); val queued = q.post("queued")!!; q.post("next")
        q.cancel(queued); q.cancel(first)
        assertEquals(listOf("next", "b"), q.visible().map { it.text }); assertEquals(0, calls)
    }
    @Test fun foregroundPausePreservesRemainingAndQueuesBoundedlyWithoutDeadline() {
        val q = owner(); q.post("a"); now = 1000; q.setForeground(false)
        assertNull(q.delayUntilNextEvent()); repeat(20) { q.post("hidden$it") }
        now = 50000; q.advance(); assertEquals(1, q.visible().size); assertEquals(8, q.state.value.buffered.size)
        q.setForeground(true); assertEquals(2000L, q.delayUntilNextEvent()); q.at(51999)
        assertTrue(q.visible().any { it.text == "a" }); q.at(52000); assertFalse(q.visible().any { it.text == "a" })
    }
    @Test fun dragPausesEveryVisibleTimerAndQueueThenResetsLikeStandard() {
        val q = owner(); val id = q.post("a")!!; q.post("b"); now = 2900; q.setDragging(id, true)
        assertNull(q.delayUntilNextEvent()); q.post("error", NoticeLevel.Error); now = 20000; q.advance()
        assertEquals(listOf("b", "a"), q.visible().map { it.text }); q.setDragging(id, false)
        assertEquals(listOf("error", "b"), q.visible().map { it.text }); q.at(20300)
        assertEquals(2700L, q.delayUntilNextEvent())
    }
    @Test fun oneNearestDeadlineCoversExpiryAndCollapseAndStopsWhenEmpty() {
        val q = owner(); q.post("a"); assertEquals(3000L, q.delayUntilNextEvent()); q.at(3000)
        assertEquals(300L, q.delayUntilNextEvent()); q.at(3300)
        assertTrue(q.state.value.entries.isEmpty()); assertNull(q.delayUntilNextEvent())
    }
    @Test fun reducedMotionRemovesExitWithoutSchedulingHiddenAnimations() {
        val q = owner(); q.setReducedMotion(true); val id = q.post("a")!!; q.cancel(id, true)
        assertTrue(q.state.value.entries.isEmpty()); assertNull(q.delayUntilNextEvent())
    }
    @Test fun clearAndTerminalCloseReleasePendingActionsAndRejectNewMessages() {
        val q = owner(); var calls = 0; val id = q.post("a", action = ClientNoticeAction("run") { calls++ })!!
        q.clear(); assertFalse(q.performAction(id)); assertNull(q.delayUntilNextEvent())
        q.post("b"); q.close(); assertNull(q.post("c")); q.setForeground(true); q.advance()
        assertTrue(q.state.value.entries.isEmpty()); assertTrue(q.state.value.buffered.isEmpty()); assertEquals(0, calls)
    }
}
