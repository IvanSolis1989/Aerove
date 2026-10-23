package dev.aerove.lite.data

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.integration.ResourceRuntimeApply
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ResourceRuntimeApplyTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun prepared(revision: String = "revision", generation: String? = "requested") =
        PreparedProfile("profile", "mode: rule", "mode: rule", revision, generation, null, null)
    private fun endpoint(port: Int = 9091, revision: String = "revision", generation: String? = "old") =
        CoreEndpoint(port, secret, AppliedConfigSnapshot("profile", File("unused-test-snapshot")), revision, generation)

    @Test fun stoppedResourceSaveDoesNotDispatchAStartOrPretendAGenerationIsApplied() = runBlocking<Unit> {
        val applier = ResourceRuntimeApply(MutableStateFlow(VpnSnapshot()), { "profile" },
            { error("Stopped data-only save needs no runtime read") }, { error("Must not start") },
            { error("Must not request Core") }, { true })
        assertNull(applier.apply(prepared()).appliedGeneration)
    }
    @Test fun readyRuntimeUsesCurrentSavedDynamicGenerationAndAuthenticatedRouteReadback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"core-epoch":2,"picks-version":1,"picks":{}}""")); server.start()
            val before = endpoint()
            val actual = endpoint(server.port, generation = "dynamic-script-generation")
            val snapshots = MutableStateFlow(VpnSnapshot(VpnPhase.Running, before))
            val applier = ResourceRuntimeApply(snapshots, { "profile" }, { prepared(generation = "dynamic-script-generation") },
                { snapshots.value = VpnSnapshot(VpnPhase.Running, actual) },
                { ready -> CoreApiClient(ControlEndpoint(ready.port, ready.secret)).use { assertEquals(2uL, it.routeSnapshot().coreEpoch) } }, { true })
            assertEquals("dynamic-script-generation", applier.apply(prepared()).appliedGeneration)
            val request = server.takeRequest()
            assertEquals("/aerove/route", request.path)
            assertEquals("Bearer $secret", request.getHeader("Authorization"))
        }
    }
    @Test fun replacedSourceOrRuntimeCannotProduceAnAppliedReceipt() = runBlocking<Unit> {
        for (changedSource in listOf(true, false)) {
            val before = endpoint()
            val actual = endpoint(generation = "new")
            val snapshots = MutableStateFlow(VpnSnapshot(VpnPhase.Running, before))
            val applier = ResourceRuntimeApply(snapshots, { "profile" },
                { prepared(revision = if (changedSource) "new-source" else "revision", generation = "new") },
                { snapshots.value = VpnSnapshot(VpnPhase.Running, actual) },
                { snapshots.value = VpnSnapshot(VpnPhase.Stopped) }, { true })
            try { applier.apply(prepared()); fail("Changed lineage/runtime cannot be acknowledged") }
            catch (_: IllegalStateException) { }
        }
    }
}
