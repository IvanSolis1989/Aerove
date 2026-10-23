package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SettingsRestorePublicationTest {
    @Test fun successfulDomainPublishesOnlyAfterLibrariesAndIndexCommit() = runBlocking {
        val root = Files.createTempDirectory("settings-publication-success").toFile()
        try {
            val store = LiteSettingsStore(root)
            val before = store.save(LiteSettings())
            val after = before.copy(pureBlack = true)
            store.deferPublication {
                atomicDomainRestore(root, extraPaths = emptyList()) {
                    store.save(after)
                    assertEquals(before, store.changes.value)
                    store.read()
                    assertEquals(before, store.changes.value)
                }
                assertEquals(before, store.changes.value)
            }
            assertEquals(after, store.changes.value)
            assertEquals(after, LiteSettingsStore(root).read())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedOrCancelledDomainNeverExposesCandidateAndKeepsOriginalThrowable() = runBlocking {
        for (failure in listOf(IllegalStateException("library failed"), CancellationException("cancelled"))) {
            val root = Files.createTempDirectory("settings-publication-rollback").toFile()
            try {
                val store = LiteSettingsStore(root)
                val before = store.save(LiteSettings())
                var caught: Throwable? = null
                try {
                    store.deferPublication {
                        atomicDomainRestore(root, extraPaths = emptyList()) {
                            store.save(before.copy(pureBlack = true))
                            assertEquals(before, store.changes.value)
                            throw failure
                        }
                    }
                } catch (actual: Throwable) { caught = actual }
                assertTrue("Original failure lineage must survive coroutine stacktrace recovery",
                    generateSequence(caught) { it.cause }.any { it === failure })
                assertEquals(before, store.changes.value)
                assertEquals(before, LiteSettingsStore(root).read())
                store.save(before.copy(textScaleEnabled = true))
                assertTrue(store.changes.value!!.textScaleEnabled)
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun concurrentDeferredDomainsAreSerializedAndPublishTheirOwnFinalState() = runBlocking {
        val root = Files.createTempDirectory("settings-publication-concurrent").toFile()
        try {
            val store = LiteSettingsStore(root)
            val before = store.save(LiteSettings())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var secondEntered = false
            val first = async { store.deferPublication { atomicDomainRestore(root, extraPaths = emptyList()) {
                store.save(before.copy(pureBlack = true)); entered.complete(Unit); release.await()
            } } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { store.deferPublication {
                secondEntered = true
                assertTrue(store.changes.value!!.pureBlack)
                store.save(store.read().copy(textScaleEnabled = true))
            } }
            assertFalse(secondEntered)
            assertEquals(before, store.changes.value)
            release.complete(Unit)
            first.await(); second.await()
            assertTrue(store.changes.value!!.pureBlack)
            assertTrue(store.changes.value!!.textScaleEnabled)
        } finally { root.deleteRecursively() }
    }
}
