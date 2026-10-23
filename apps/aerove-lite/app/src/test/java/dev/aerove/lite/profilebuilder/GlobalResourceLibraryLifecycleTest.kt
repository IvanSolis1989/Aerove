package dev.aerove.lite.profilebuilder

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlobalResourceLibraryLifecycleTest {
    @get:Rule val directory = TemporaryFolder()
    private fun session(library: BuilderResourceLibrary) = GlobalResourceLibrarySession(library,
        GlobalResourceDeletion { before, _, _, next -> library.replace(before, next) })
    @Test fun quiesceWaitsForNonCancellableTailBeforeRestoreAndBlocksNewAdmissions() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(directory.root); val session = session(library); session.load()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val file = File(directory.root, "journal-tail")
        val operation = launch(start = CoroutineStart.UNDISPATCHED) { session.perform {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); file.writeText("late old journal"); library.snapshot() }
        } }
        entered.await()
        val barrier = async(start = CoroutineStart.UNDISPATCHED) { session.quiesce() }
        assertFalse(barrier.isCompleted)
        assertFalse(session.perform { error("Maintenance must reject admission") })
        release.complete(Unit); barrier.await(); operation.join()
        assertEquals("late old journal", file.readText())
        file.writeText("restored")
        assertFalse(session.perform { error("Still quiesced") })
        session.resumeAfterMaintenance(); assertTrue(session.load())
        assertEquals("restored", file.readText()); session.closeAndJoin()
    }
    @Test fun ordinaryCancelAllowsRetryButMaintenanceStillWaitsForAbandonedTail() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(directory.root); val session = session(library); session.load()
        val old = library.snapshot(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) { session.perform {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); old }
        } }
        entered.await(); session.cancel()
        assertTrue(session.putProxy(null, "new", mapOf("type" to "direct")))
        assertFalse(operation.isCompleted)
        val barrier = async(start = CoroutineStart.UNDISPATCHED) { session.quiesce() }
        assertFalse(barrier.isCompleted)
        release.complete(Unit); barrier.await(); operation.join()
        assertEquals(listOf("new"), session.state.value.snapshot!!.resources.proxies.map { it.name })
        assertEquals(listOf("new"), library.snapshot().resources.proxies.map { it.name })
        session.closeAndJoin()
    }
    @Test fun closeAndJoinWaitsForTailAndPermanentlyClosesAdmission() = runBlocking<Unit> {
        val library = BuilderResourceLibrary(directory.root); val session = session(library); session.load()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) { session.perform {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); library.snapshot() }
        } }
        entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { session.closeAndJoin() }
        assertFalse(closing.isCompleted); assertFalse(session.load())
        release.complete(Unit); closing.await(); operation.join()
        try { session.resumeAfterMaintenance(); fail("Closed session cannot resume") } catch (_: IllegalStateException) {}
        assertFalse(session.load())
    }
}
