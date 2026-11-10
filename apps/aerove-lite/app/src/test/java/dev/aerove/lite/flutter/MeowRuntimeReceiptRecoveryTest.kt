package dev.aerove.lite.flutter

import dev.aerove.lite.data.NativeConfigValidator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowRuntimeReceiptRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Effects : MeowSetupEffects {
        var runtime: MeowSetupRuntime? = null
        var onStaged: suspend (MeowRuntimeSnapshot) -> Unit = {}
        override fun isInitialized() = true
        override suspend fun readRuntime() = runtime
        override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) = onStaged(snapshot)
        override suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection) = Unit
        override suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String) = false
    }
    private fun home() = temporary.newFolder().canonicalFile
    private fun shared(enabled: Boolean = false) = linkedMapOf<String, Any?>(
        "setupParams" to null, "vpnOptions" to mapOf("enable" to enabled, "port" to 7890, "ipv6" to false,
            "dnsHijacking" to false, "allowBypass" to false, "systemProxy" to false, "bypassDomain" to emptyList<String>(),
            "stack" to if (enabled) "lwip" else "", "routeAddress" to emptyList<String>(), "mtu" to 9000,
            "congestionController" to if (enabled) "builtin" else "", "accessControlProps" to mapOf("enable" to false, "mode" to "rejectSelected",
                "acceptList" to emptyList<String>(), "rejectList" to emptyList<String>(), "sort" to "none", "isFilterSystemApp" to true, "isFilterNonInternetApp" to true)),
        "stopTip" to "stop tip", "startTip" to "start tip", "localNetworkTip" to "local tip", "currentProfileName" to "profile",
        "stopText" to "stop", "onlyStatisticsProxy" to true, "showStopAction" to false)
    private fun record(digest: String = "original-shared-build-digest") = linkedMapOf<String, Any?>("digest" to digest, "yaml-md5" to "a".repeat(32), "groups" to 7, "proxies" to 9, "rules" to 13)
    private fun setup() = linkedMapOf<String, Any?>("selected-map" to mapOf("Pick" to "local"), "test-url" to "https://example.com/probe", "skip-cert-verify" to true, "applied" to record())
    private fun config(home: File) = MeowConfigProtocol(home, NativeConfigValidator { null }, { emptySet() })

    @Test fun persistedReceiptWithoutLiveRuntimeRequiresFreshSetupAndKeepsHistoricalRecovery() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val store = RuntimeHandoffStore(home); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.setupConfig(setup())
            val snapshot = store.readCurrent()!!
            store.commitGraphApplied(snapshot.revision, snapshot.intentEpoch, RuntimeAppliedProof(1, 2, "4"), snapshot.handoff)
            val restored = RuntimeHandoffStore(home)
            val detachedEffects = Effects()
            val detached = MeowSetupProtocol(home, restored, config, detachedEffects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            detached.syncState(shared())
            assertEquals(mapOf("init" to true, "running" to false, "revision" to null, "applied" to null), detached.getAppliedSetup())
            assertEquals(snapshot.revision, restored.readLastApplied()!!.revision)
            assertEquals(snapshot.handoff.setupRecordJson, restored.readLastApplied()!!.handoff.setupRecordJson)
            detachedEffects.onStaged = { next ->
                restored.commitGraphApplied(next.revision, next.intentEpoch, RuntimeAppliedProof(3, 4, "5"), next.handoff)
                detachedEffects.runtime = MeowSetupRuntime(next.revision, next.intentEpoch, next.handoff.compiledSha256, "5", 3, 4, false)
            }
            detached.setupConfig(setup())
            val ready = detached.getAppliedSetup()
            assertEquals(false, ready["running"])
            assertNotNull(ready["revision"])
            assertEquals(record(), ready["applied"])
        }
    }

    @Test fun supersededReceiptCannotReportRunningOrAppliedWhileSuccessorIsStaged() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val store = RuntimeHandoffStore(home); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.setupConfig(setup())
            val before = store.readCurrent()!!
            store.requestStart(before.revision, before.intentEpoch)
            store.commitApplied(before.revision, before.intentEpoch, RuntimeAppliedProof(1, 2, "4"))
            effects.runtime = MeowSetupRuntime(before.revision, before.intentEpoch, before.handoff.compiledSha256, "4", 1, 2)
            assertTrue(api.getAppliedSetup()["running"] as Boolean)
            api.setupConfig(setup().apply { put("applied", record("pending successor")) })
            assertNotEquals(before.revision, store.readCurrent()!!.revision)
            assertEquals(before.revision, store.readLastApplied()!!.revision)
            assertEquals(mapOf("init" to true, "running" to false, "revision" to null, "applied" to null), api.getAppliedSetup())
        }
    }

}
