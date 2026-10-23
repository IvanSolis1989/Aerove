package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementAbandonedCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.put(block) }
        fun runNext() { checkNotNull(queue.poll(5, TimeUnit.SECONDS)) { "Expected dispatched continuation" }.run() }
    }

    @Test fun quiesceWaitsForAbandonedInputCloseAfterParentCancellation() = abandonedCloseBarrier(false)
    @Test fun terminalCloseAndJoinWaitsForAbandonedInputCloseAfterParentCancellation() = abandonedCloseBarrier(true)

    @Test fun cleanupFailureDoesNotReturnBeforeOtherRegisteredCleanupCompletes() = runBlocking<Unit> {
        val (profiles, id) = fixture()
        val dispatcher = QueuedDispatcher()
        val parent = SupervisorJob()
        val session = ResourceManagementSession(ResourceManagementRepository(profiles), CoroutineScope(parent + dispatcher), {})
        val expected = IOException("Expected abandoned input close failure")
        val failedClose = CountDownLatch(1); val closeEntered = CountDownLatch(1); val closeRelease = CountDownLatch(1)
        try {
            session.load(id)
            while (session.state.value.snapshot == null || session.state.value.busy.isNotEmpty()) dispatcher.runNext()
            session.sideload("first-not-entered", object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { failedClose.countDown(); throw expected }
            })
            session.cancel(); dispatcher.runNext()
            assertTrue(failedClose.await(5, TimeUnit.SECONDS))
            session.sideload("second-not-entered", object : ByteArrayInputStream(byteArrayOf(2)) {
                override fun close() {
                    closeEntered.countDown()
                    check(closeRelease.await(5, TimeUnit.SECONDS))
                    super.close()
                }
            })
            session.cancel(); dispatcher.runNext()
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val barrier = async(Dispatchers.Default) { started.complete(Unit); runCatching { session.quiesce() } }
            started.await()
            assertNull("A failed receipt must not skip the other blocked close", withTimeoutOrNull(250) { barrier.await() })
            closeRelease.countDown()
            assertSame(expected, withTimeout(5000) { barrier.await() }.exceptionOrNull())
        } finally {
            closeRelease.countDown()
            runCatching { session.closeAndJoin() }
            parent.cancelAndJoin()
        }
    }

    private suspend fun fixture(): Pair<ProfileRepository, String> {
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val profiles = ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null }))
        val id = profiles.importText("Fixture", "proxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n").id
        return profiles to id
    }

    private fun abandonedCloseBarrier(terminal: Boolean) = runBlocking<Unit> {
        val (profiles, id) = fixture()
        val dispatcher = QueuedDispatcher()
        val parent = SupervisorJob()
        val session = ResourceManagementSession(ResourceManagementRepository(profiles), CoroutineScope(parent + dispatcher), {})
        val closeEntered = CountDownLatch(1); val closeRelease = CountDownLatch(1); val closeExited = CountDownLatch(1)
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() {
                closeEntered.countDown()
                check(closeRelease.await(5, TimeUnit.SECONDS)) { "Close release not received" }
                super.close(); closeExited.countDown()
            }
        }
        var completing: Thread? = null
        try {
            session.load(id)
            while (session.state.value.snapshot == null || session.state.value.busy.isNotEmpty()) dispatcher.runNext()
            session.sideload("not-entered", input)
            // The body is still queued. Cancellation makes its completion handler run on another thread.
            thread(name = "resource-parent-cancel") { parent.cancel() }.join(5000)
            completing = thread(name = "resource-cancelled-continuation") { dispatcher.runNext() }
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            val started = CompletableDeferred<Unit>()
            val barrier = async(Dispatchers.Default) {
                started.complete(Unit)
                if (terminal) session.closeAndJoin() else session.quiesce()
            }
            started.await()
            val escaped = withTimeoutOrNull(250) { barrier.await(); true } ?: false
            assertFalse("Barrier returned while the abandoned input.close was still blocked", escaped)
            assertEquals(1L, closeExited.count)
            closeRelease.countDown()
            withTimeout(5000) { barrier.await() }
            assertTrue(closeExited.await(5, TimeUnit.SECONDS))
        } finally {
            closeRelease.countDown(); completing?.join(5000)
            session.closeAndJoin(); parent.cancelAndJoin()
        }
    }
}
