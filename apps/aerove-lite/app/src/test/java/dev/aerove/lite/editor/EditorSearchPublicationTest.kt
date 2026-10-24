package dev.aerove.lite.editor

import org.junit.Assert.*
import org.junit.Test

class EditorSearchPublicationTest {
    private class Queue {
        val tasks = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        val owner = EditorSearchPublication({ tasks += it; true }, { removed += it })
        fun drain() { tasks.toList().also { tasks.clear() }.forEach { it.run() } }
    }

    @Test fun synchronousSearchEventReadsZeroButOneShotReadsAfterPublisherClearsWorker() {
        val queue = Queue()
        var workerAlive = true
        fun count() = if (workerAlive) 0 else 3
        val synchronous = count()
        var posted: Int? = null
        queue.owner.schedule({ true }) { posted = count() }
        assertEquals(0, synchronous)
        assertNull(posted)
        assertEquals(1, queue.tasks.size)
        workerAlive = false
        queue.drain()
        assertEquals(3, posted)
        queue.drain()
        assertEquals(3, posted)
    }

    @Test fun supersededQueryCannotPublishEvenIfRemovedCallbackStillRuns() {
        val queue = Queue(); val published = mutableListOf<String>()
        queue.owner.schedule({ true }) { published += "old" }
        queue.owner.schedule({ true }) { published += "new" }
        assertEquals(1, queue.removed.size)
        queue.drain()
        assertEquals(listOf("new"), published)
    }

    @Test fun staleDeliveryGuardIsEvaluatedAfterPosting() {
        val queue = Queue(); var current = true; var deliveries = 0
        queue.owner.schedule({ current }) { deliveries++ }
        current = false
        queue.drain()
        assertEquals(0, deliveries)
    }

    @Test fun contentOrQueryInvalidationAndReleaseCancelPendingPublication() {
        val queue = Queue(); var deliveries = 0
        queue.owner.schedule({ true }) { deliveries++ }
        queue.owner.invalidate(); queue.drain()
        queue.owner.schedule({ true }) { deliveries++ }
        queue.owner.close(); queue.drain()
        queue.owner.schedule({ true }) { deliveries++ }
        assertEquals(0, deliveries)
        assertEquals(2, queue.removed.size)
        assertTrue(queue.tasks.isEmpty())
    }

    @Test fun rejectedPostDoesNotKeepAStalePendingCallback() {
        val tasks = mutableListOf<Runnable>(); var deliveries = 0; var removals = 0
        val owner = EditorSearchPublication({ tasks += it; false }, { removals++ })
        owner.schedule({ true }) { deliveries++ }
        tasks.single().run(); owner.invalidate()
        assertEquals(0, deliveries); assertEquals(0, removals)
    }
}
