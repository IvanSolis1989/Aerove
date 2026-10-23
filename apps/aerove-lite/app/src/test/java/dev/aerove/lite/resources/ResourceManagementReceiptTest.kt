package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementReceiptTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualRuntimeGenerationAndStoppedAcknowledgementReplaceSavedGeneration() = runBlocking<Unit> {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(temporary.root, "runtime"))
        val profiles = ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(File(temporary.root, "runtime"),
            validator, NativeResourceValidator { _, _, _, _ -> null }))
        val id = profiles.importText("Fixture", "proxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n").id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var actual: String? = "actual-script-runtime-generation"
        var calls = 0
        val session = ResourceManagementSession(ResourceManagementRepository(profiles), scope,
            apply = { error("Legacy callback must not run when typed receipt is supplied") },
            applyWithReceipt = { calls++; ResourceApplyReceipt(actual) })
        try {
            session.load(id); withTimeout(5000) { session.state.first { it.snapshot != null && it.busy.isEmpty() } }
            session.refresh(); withTimeout(5000) { session.state.first { calls == 1 && it.busy.isEmpty() } }
            assertEquals(actual, session.state.value.appliedGeneration)
            assertNotEquals(session.state.value.snapshot!!.prepared.generation, session.state.value.appliedGeneration)
            actual = null
            session.refresh(); withTimeout(5000) { session.state.first { calls == 2 && it.busy.isEmpty() } }
            assertNull(session.state.value.appliedGeneration)
            assertNull(session.state.value.failure)
        } finally { session.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        var legacyCalled = false
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val legacy = ResourceManagementSession(ResourceManagementRepository(profiles), legacyScope, { legacyCalled = true })
        try {
            legacy.load(id); withTimeout(5000) { legacy.state.first { it.snapshot != null && it.busy.isEmpty() } }
            legacy.refresh(); withTimeout(5000) { legacy.state.first { legacyCalled && it.busy.isEmpty() } }
            assertNull(legacy.state.value.appliedGeneration)
        } finally { legacy.closeAndJoin(); legacyScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
