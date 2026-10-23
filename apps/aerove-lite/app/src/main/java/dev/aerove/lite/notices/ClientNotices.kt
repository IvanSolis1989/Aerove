package dev.aerove.lite.notices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Standard MessageLevel.values order and duration. */
enum class NoticeLevel(val wireName: String, val durationMillis: Long) {
    Info("info", 3000), Success("success", 3000), Warning("warning", 5000), Error("error", 6000),
}

data class ClientNoticeAction(val label: String, val onClick: () -> Unit)
data class ClientNotice(val id: Long, val text: String, val level: NoticeLevel, val actionLabel: String?)
data class ClientNoticeEntry(val notice: ClientNotice, val visible: Boolean, val swiped: Boolean = false)
data class ClientNoticesState(
    val entries: List<ClientNoticeEntry> = emptyList(),
    val buffered: List<ClientNotice> = emptyList(),
    val nextDeadlineMillis: Long? = null,
)

/** One retained client queue. No scope, ticker, logging or platform ownership. */
class ClientNotices(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
    private data class Record(
        val notice: ClientNotice, val action: ClientNoticeAction?,
        val duration: Long, var remaining: Long = duration, var deadline: Long? = null,
        var visible: Boolean = true, var swiped: Boolean = false,
    )
    private val entries = mutableListOf<Record>()
    private val buffer = ArrayDeque<Record>()
    private val mutableState = MutableStateFlow(ClientNoticesState())
    val state: StateFlow<ClientNoticesState> = mutableState.asStateFlow()
    private var sequence = 0L
    private var foreground = false
    private var reducedMotion = false
    private var dragging: Long? = null
    private var closed = false

    @Synchronized fun post(text: String, level: NoticeLevel = NoticeLevel.Info, action: ClientNoticeAction? = null): Long? {
        if (closed || text.isEmpty()) return null
        val duration = maxOf(level.durationMillis, if (action != null) 6000 else 0)
        val existing = entries.firstOrNull { it.visible && it.notice.text == text && it.notice.level == level }
        if (existing != null) {
            val index = entries.indexOf(existing)
            entries[index] = Record(existing.notice.copy(actionLabel = action?.label), action, duration,
                deadline = if (timersActive()) clock() + duration else null)
            publish()
            return existing.notice.id
        }
        buffer.firstOrNull { it.notice.text == text && it.notice.level == level }?.let { return it.notice.id }
        val notice = ClientNotice(++sequence, text, level, action?.label)
        buffer.addLast(Record(notice, action, duration))
        while (buffer.size > 8) buffer.remove(buffer.firstOrNull { it.notice.level != NoticeLevel.Error } ?: buffer.first())
        processQueue()
        publish()
        return notice.id
    }

    /** Dismiss actions are consumed under the lock, callbacks run outside it exactly once. */
    fun performAction(id: Long): Boolean {
        val action = synchronized(this) {
            if (closed || !foreground) return false
            val record = entries.firstOrNull { it.visible && it.notice.id == id } ?: return false
            val callback = record.action ?: return false
            hide(record, false)
            if (dragging == id) { dragging = null; resetVisibleTimers() }
            processQueue(); publish()
            callback
        }
        action.onClick()
        return true
    }

    @Synchronized fun cancel(id: Long, swiped: Boolean = false) {
        if (closed) return
        buffer.removeAll { it.notice.id == id }
        entries.firstOrNull { it.visible && it.notice.id == id }?.let { hide(it, swiped) }
        if (dragging == id) { dragging = null; resetVisibleTimers() }
        processQueue(); publish()
    }

    @Synchronized fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        val now = clock()
        if (!value) {
            entries.filter { it.visible }.forEach { record ->
                record.remaining = record.deadline?.let { (it - now).coerceAtLeast(0) } ?: record.remaining
                record.deadline = null
            }
            entries.removeAll { !it.visible }
            dragging = null
        }
        foreground = value
        if (value) entries.filter { it.visible }.forEach { it.deadline = now + it.remaining }
        processQueue(); publish()
    }

    @Synchronized fun setReducedMotion(value: Boolean) {
        if (closed || reducedMotion == value) return
        reducedMotion = value
        if (value) entries.removeAll { !it.visible }
        publish()
    }

    @Synchronized fun setDragging(id: Long, value: Boolean) {
        if (closed || !foreground) return
        if (value) {
            if (dragging == id || entries.none { it.visible && it.notice.id == id }) return
            dragging = id
            entries.filter { it.visible }.forEach { it.remaining = it.duration; it.deadline = null }
        } else {
            if (dragging != id) return
            dragging = null
            resetVisibleTimers()
            processQueue()
        }
        publish()
    }

    /** Invoked only by the host's single nearest-deadline delay. */
    @Synchronized fun advance() {
        if (closed || !foreground) return
        val now = clock()
        entries.filter { it.visible && it.deadline?.let { deadline -> deadline <= now } == true }
            .toList().forEach { hide(it, false) }
        entries.removeAll { !it.visible && it.deadline?.let { deadline -> deadline <= now } == true }
        processQueue(); publish()
    }

    @Synchronized fun delayUntilNextEvent(): Long? = mutableState.value.nextDeadlineMillis?.let { (it - clock()).coerceAtLeast(0) }
    @Synchronized fun clear() { entries.clear(); buffer.clear(); dragging = null; publish() }
    @Synchronized override fun close() { clear(); closed = true; foreground = false }

    private fun timersActive() = foreground && dragging == null
    private fun resetVisibleTimers() {
        val now = clock()
        entries.filter { it.visible }.forEach { it.remaining = it.duration; it.deadline = if (timersActive()) now + it.duration else null }
    }
    private fun hide(record: Record, swiped: Boolean) {
        record.visible = false; record.swiped = swiped
        record.deadline = if (foreground && !reducedMotion) clock() + if (swiped) 200 else 300 else clock()
        if (reducedMotion || !foreground) entries.remove(record)
        // A burst of errors must not retain an unbounded list of departing cards.
        while (entries.count { !it.visible } > 2) entries.remove(entries.last { !it.visible })
    }
    private fun processQueue() {
        if (!foreground || dragging != null) return
        while (buffer.isNotEmpty()) {
            val visible = entries.filter { it.visible }
            val next = if (visible.size < 2) buffer.removeFirst() else {
                val error = buffer.firstOrNull { it.notice.level == NoticeLevel.Error } ?: return
                val preempted = visible.lastOrNull { it.notice.level != NoticeLevel.Error } ?: return
                hide(preempted, false); buffer.remove(error); error
            }
            next.deadline = clock() + next.remaining
            entries.add(0, next)
        }
    }
    private fun publish() {
        mutableState.value = ClientNoticesState(entries.map { ClientNoticeEntry(it.notice, it.visible, it.swiped) },
            buffer.map { it.notice }, if (foreground) entries.mapNotNull { it.deadline }.minOrNull() else null)
    }
}
