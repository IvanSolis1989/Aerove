package dev.aerove.lite.maintenance

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MaintenanceFeatureTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val root: File) : MaintenanceBackend {
        var revision = "one"
        var entered = false
        var executions = 0
        var resets = 0
        var failure = false
        var wait: CompletableDeferred<Unit>? = null
        val live = File(root, "live-profile").apply { writeText("live") }
        val leased = File(root, "leased-generation").apply { writeText("leased") }
        val orphan = File(root, "orphan").apply { writeText("orphan") }
        val identity = File(root, "signing-identity").apply { writeText("keep") }
        override suspend fun <T> exclusive(operation: MaintenanceOperation, action: suspend () -> T): T {
            check(!entered)
            entered = true
            try { wait?.await(); return action() } finally { entered = false }
        }
        override suspend fun inspect() = MaintenanceInventory(revision, 1, 2, 6)
        override suspend fun pruneCache() {
            check(entered)
            if (failure) error("fixture private error")
            executions++
            // Fixture owner has the exact reference set; no recursive/computed directory deletion.
            Files.delete(orphan.toPath())
        }
        override suspend fun clearAppDataAndExit() {
            check(entered)
            resets++
            Files.deleteIfExists(live.toPath())
        }
    }

    @Test fun planRequiresExplicitSingleUseConfirmationAndPreservesLiveLeasesAndIdentity() = runBlocking<Unit> {
        val backend = Fixture(temporary.root)
        val feature = MaintenanceFeature(this, backend)
        yield()
        assertEquals(0, backend.executions)
        feature.request(MaintenanceOperation.PruneCache); yield()
        assertTrue(backend.orphan.exists())
        assertFalse(backend.entered)
        val confirmation = requireNotNull(feature.state.value.confirmation)
        feature.confirm(confirmation.id + 1); yield()
        assertTrue(backend.orphan.exists())
        feature.confirm(confirmation.id); feature.confirm(confirmation.id); yield()
        assertEquals(1, backend.executions)
        assertFalse(backend.orphan.exists())
        assertTrue(backend.live.exists() && backend.leased.exists() && backend.identity.exists())
        assertEquals(MaintenanceNotice.Completed, feature.state.value.notice)
        feature.close()
    }

    @Test fun changedInventoryAndDismissCannotDelete() = runBlocking<Unit> {
        val backend = Fixture(temporary.root)
        val feature = MaintenanceFeature(this, backend)
        feature.request(MaintenanceOperation.ClearAllData); yield()
        val first = requireNotNull(feature.state.value.confirmation)
        backend.revision = "two"
        feature.confirm(first.id); yield()
        assertEquals(MaintenanceNotice.Changed, feature.state.value.notice)
        assertEquals(0, backend.resets)
        feature.request(MaintenanceOperation.ClearAllData); yield()
        val second = requireNotNull(feature.state.value.confirmation)
        feature.dismiss(); feature.confirm(second.id); yield()
        assertTrue(backend.live.exists())
        feature.close()
    }

    @Test fun cancellationAndFailureReleaseOwnerAndAllowExplicitRetry() = runBlocking<Unit> {
        val backend = Fixture(temporary.root)
        val feature = MaintenanceFeature(this, backend)
        feature.request(MaintenanceOperation.PruneCache); yield()
        backend.wait = CompletableDeferred()
        feature.confirm(requireNotNull(feature.state.value.confirmation).id); yield()
        feature.cancel(); yield()
        assertFalse(backend.entered)
        assertTrue(backend.orphan.exists())
        backend.wait = null; backend.failure = true
        feature.request(MaintenanceOperation.PruneCache); yield()
        feature.confirm(requireNotNull(feature.state.value.confirmation).id); yield()
        assertEquals(MaintenanceNotice.Failed, feature.state.value.notice)
        assertFalse(backend.entered)
        backend.failure = false
        feature.request(MaintenanceOperation.PruneCache); yield()
        feature.confirm(requireNotNull(feature.state.value.confirmation).id); yield()
        assertEquals(MaintenanceNotice.Completed, feature.state.value.notice)
        feature.close()
    }

    @Test fun closedFeatureCannotStartAnyNewInspectionOrOperation() = runBlocking<Unit> {
        val backend = Fixture(temporary.root)
        val feature = MaintenanceFeature(this, backend)
        feature.close()
        feature.request(MaintenanceOperation.ClearAllData); yield()
        assertNull(feature.state.value.confirmation)
        assertEquals(0, backend.resets)
        assertTrue(backend.live.exists())
    }

    @Test fun confirmedResetUsesOwnerExactlyOnceAndKeepsIdentity() = runBlocking<Unit> {
        val backend = Fixture(temporary.root)
        val feature = MaintenanceFeature(this, backend)
        feature.request(MaintenanceOperation.ClearAllData); yield()
        val confirmation = requireNotNull(feature.state.value.confirmation)
        feature.confirm(confirmation.id); feature.confirm(confirmation.id); yield()
        assertEquals(1, backend.resets)
        assertFalse(backend.live.exists())
        assertEquals("keep", backend.identity.readText())
        feature.close()
    }

    @Test fun fiveTapUnlockResetsAfterGapAndRequiresFreshFiveTaps() {
        val detector = DeveloperUnlock()
        assertFalse(detector.tap(0)); assertFalse(detector.tap(500))
        assertFalse(detector.tap(1500))
        listOf(1600L, 1700L, 1800L).forEach { assertFalse(detector.tap(it)) }
        assertTrue(detector.tap(1900))
        assertFalse(detector.tap(1950))
        detector.reset()
        assertFalse(detector.tap(2000))
    }

    @Test fun consentRequiresExactRestatementAndRespectsImeCommittedPrefix() {
        val statement = "No network service."
        assertEquals(Restatement.Complete, restatement("  $statement  ", statement))
        assertEquals(Restatement.Partial, restatement("No n拼音", statement, 4, 6))
        assertEquals(Restatement.Diverged, restatement("Different", statement))
        assertEquals(Restatement.Partial, restatement("No", statement))
        assertEquals(1, CURRENT_DISCLAIMER_VERSION)
    }

    @Test fun developerLogTestIsExactlyBoundedAndContainsNoUserMaterial() {
        val samples = developerLogSamples(kotlin.random.Random(1))
        assertEquals(1000, samples.size)
        samples.forEachIndexed { index, value ->
            assertTrue(value.startsWith("[$index]"))
            assertTrue(value.substringAfter(']').length in 20..200)
            assertTrue(value.substringAfter(']').all { it.isLetterOrDigit() })
        }
    }
}
