package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.integration.RestoreRuntimeBaseline
import dev.aerove.lite.integration.RestoreRuntimeOwner
import dev.aerove.lite.integration.RestoreRuntimeTarget
import dev.aerove.lite.vpn.MaintenanceRuntimeContext
import dev.aerove.lite.vpn.VpnPlan
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RestoreRuntimeIntegrationTest {
    @Test fun archivedAutoRunCannotCreateNewUserRunningIntent() = runBlocking<Unit> {
        val before = LiteSettings()
        val restored = before.copy(autoStart = true, backgroundPolicy = before.backgroundPolicy.copy(autoRun = true))
        var resumed: Boolean? = null
        val context = MaintenanceRuntimeContext(false, null, null)
        val owner = RestoreRuntimeOwner(stop = { context }, capture = { RestoreRuntimeBaseline(RestoreRuntimeTarget(null, before), null) },
            target = { RestoreRuntimeTarget("restored", restored) }, resume = { receipt, target, resume ->
                assertSame(context, receipt); assertEquals(restored, target!!.settings); resumed = resume
            }, rollbackSafe = { _, _ -> error("Success must not use rollback") })
        owner.execute { stop, loaded -> stop(); loaded() }
        assertEquals(false, resumed)
    }

    @Test fun successfulRestoreUsesNewTargetAndPreservesActualBuilderReceipt() = runBlocking<Unit> {
        val builder = LiteSettings()
        val restored = builder.copy(ipv6 = false)
        val context = MaintenanceRuntimeContext(true, VpnPlan("before", builder), builder, 3, 5)
        val events = mutableListOf<String>()
        val owner = RestoreRuntimeOwner(stop = { events += "joined"; context }, capture = {
            events += "captured"; RestoreRuntimeBaseline(RestoreRuntimeTarget("before", builder), null)
        }, target = { events += "readback"; RestoreRuntimeTarget("new", restored) }, resume = { receipt, target, resume ->
            assertTrue(resume); assertSame(builder, receipt.builderSettings)
            assertEquals("new", target!!.profileId); assertEquals(restored, target.settings); events += "ready"
        }, rollbackSafe = { _, _ -> false })
        owner.execute { stop, loaded -> stop(); events += "write"; events += "published"; loaded() }
        assertEquals(listOf("joined", "captured", "write", "published", "readback", "ready"), events)
    }

    @Test fun failureResumesOnlyAfterActualVerifiedRollbackAndReadback() = runBlocking<Unit> {
        val root = Files.createTempDirectory("restore-runtime").toFile()
        try {
            val store = LiteSettingsStore(root)
            val before = store.save(LiteSettings())
            val failure = IllegalStateException("write failed")
            var resumed = false
            val receipt = MaintenanceRuntimeContext(true, VpnPlan("before", before), before)
            val owner = RestoreRuntimeOwner(stop = { receipt }, capture = { RestoreRuntimeBaseline(RestoreRuntimeTarget("before", before), null) },
                target = { error("No successful load") }, resume = { _, target, resume ->
                    assertEquals(before, store.read()); assertEquals("before", target!!.profileId); resumed = resume
                }, rollbackSafe = { error, baseline -> rollbackVerified(error) && store.read() == baseline.target.settings })
            val thrown = runCatching { owner.execute { stop, _ ->
                stop(); store.deferPublication { atomicDomainRestore(root, extraPaths = emptyList()) {
                    store.save(before.copy(pureBlack = true)); throw failure
                } }
            } }.exceptionOrNull()
            assertNotNull(thrown); assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
            assertTrue(resumed)
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownRollbackAndResumeFailurePreserveOriginalAndRemainStopped() = runBlocking<Unit> {
        val failure = IllegalArgumentException("unverified restore")
        val recovery = IllegalStateException("resume cleanup")
        val before = LiteSettings()
        var allowed = true
        val owner = RestoreRuntimeOwner(stop = { MaintenanceRuntimeContext(true, VpnPlan("old", before), before) },
            capture = { RestoreRuntimeBaseline(RestoreRuntimeTarget("old", before), null) }, target = { error("No load") },
            resume = { _, target, resume -> allowed = resume; assertNull(target); throw recovery }, rollbackSafe = { _, _ -> false })
        val thrown = runCatching { owner.execute { stop, _ -> stop(); throw failure } }.exceptionOrNull()
        assertSame(failure, thrown); assertFalse(allowed); assertTrue(failure.suppressed.contains(recovery))
    }
}
