package dev.aerove.lite.flutter

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeGeoPublication114Test {
    @get:Rule val temporary = TemporaryFolder()
    private val proof = RuntimeAppliedProof(7, 9, "11")

    private fun handoff(yaml: String, resources: List<RuntimeHandoffResource> = emptyList()): RuntimeHandoff {
        val settings = JSONObject().put("network", JSONObject()).put("requests", JSONObject()).put("backgroundPolicy", JSONObject())
            .put("externalController", JSONObject()).put("dnsPreferences", JSONObject()).put("geoResources", JSONObject())
            .put("ipv6", false).put("unifiedDelay", true).put("tcpConcurrent", true).put("autoStart", false)
            .put("mode", "Rule").put("appFilterMode", "All").put("findProcessMode", "strict").put("packages", JSONArray())
        val options = JSONObject().put("enable", false).put("ipv6", false).put("dnsHijacking", false)
            .put("allowBypass", false).put("systemProxy", false).put("port", 0).put("accessControlProps", JSONObject())
            .put("bypassDomain", JSONArray()).put("stack", "lwip")
        val notification = JSONObject().put("stopTip", "Stop").put("startTip", "Start").put("localNetworkTip", "LAN")
            .put("currentProfileName", "Profile").put("stopText", "Stop").put("showStopAction", true).put("onlyStatisticsProxy", false)
        return RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "source", "source-revision", "a".repeat(64), yaml, runtimeSha256(yaml.toByteArray()),
            JSONObject().put("digest", "source-digest").put("yaml-md5", "b".repeat(32)).put("groups", 0).put("proxies", 0).put("rules", 1).toString(),
            settings.toString(), options.toString(), notification.toString(), resources)
    }

    private suspend fun applied(root: File): Pair<RuntimeHandoffStore, MeowRuntimeSnapshot> {
        val pinned = File(root, "pinned.pem").apply { writeText("non-Geo private bytes") }
        val store = RuntimeHandoffStore(root)
        val snapshot = store.commit(handoff("rules: ['MATCH,DIRECT']\n",
            listOf(RuntimeHandoffResource("pinned.pem", pinned.path, runtimeSha256(pinned.readBytes())))), 0, null)
        return store to store.commitGraphApplied(snapshot.revision, snapshot.intentEpoch, proof, snapshot.handoff)
    }

    private suspend fun stage(root: File, store: RuntimeHandoffStore, old: MeowRuntimeSnapshot): MeowGeoPublication {
        val id = UUID.randomUUID().toString()
        val source = File(root, "$id.bin").apply { writeText("new Geo generation") }
        val input = handoff("rules: ['MATCH,DIRECT']\ngeodata: {auto-update: false}\n",
            old.handoff.resources + RuntimeHandoffResource("geo/$id/mmdb.bin", source.path, runtimeSha256(source.readBytes())))
        return store.stageGeoPublication(checkNotNull(store.readAppliedIdentity()), id, input)
    }

    @Test fun lostObserverCanRepeatTheCommittedPublicationWithoutRebrandingOrChangingEpoch() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val (store, old) = applied(root)
        val publication = stage(root, store, old)
        assertEquals(old.handoff.compiledSha256, store.readCurrent()!!.handoff.compiledSha256)
        val first = store.commitGeoPublication(publication, proof)
        val currentFile = File(root, "runtime-handoffs/current.json")
        val committedBytes = currentFile.readBytes()
        assertEquals(first, store.commitGeoPublication(publication, proof))
        assertArrayEquals(committedBytes, currentFile.readBytes())
        assertEquals(old.intentEpoch, first.intentEpoch)
        assertTrue(File(publication.next.resourceDirectory, publication.next.handoff.resources.last().relativePath).isFile)
        try { store.commitGeoPublication(publication, proof.copy(serviceOwner = 99)); fail("Foreign owner accepted") }
        catch (_: IllegalArgumentException) {}
        assertArrayEquals(committedBytes, currentFile.readBytes())
    }

    @Test fun joinedRestartDiscardsOnlyUnpublishedCandidateAndRetainsTheOldGraph() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val (store, old) = applied(root)
        val publication = stage(root, store, old)
        val staged = File(publication.next.resourceDirectory, publication.next.handoff.resources.last().relativePath)
        assertTrue(staged.isFile)
        val reopened = RuntimeHandoffStore(root)
        reopened.discardAbandonedGeoAfterNativeJoin()
        assertFalse(staged.exists())
        assertFalse(File(root, "runtime-handoffs/geo-pending.json").exists())
        assertEquals(old.handoff.compiledSha256, reopened.readCurrent()!!.handoff.compiledSha256)
        assertTrue(File(old.resourceDirectory, "pinned.pem").isFile)
    }

    @Test fun joinedRestartRetainsCommittedResourcesWhenOnlyJournalDeletionWasInterrupted() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val (store, old) = applied(root)
        val publication = stage(root, store, old)
        val journal = File(root, "runtime-handoffs/geo-pending.json")
        val stagedJournal = journal.readBytes()
        val committed = store.commitGeoPublication(publication, proof)
        journal.writeBytes(stagedJournal)
        val reopened = RuntimeHandoffStore(root)
        reopened.discardAbandonedGeoAfterNativeJoin()
        assertEquals(committed, reopened.readAppliedIdentity())
        assertTrue(File(publication.next.resourceDirectory, publication.next.handoff.resources.last().relativePath).isFile)
        assertFalse(journal.exists())
    }

    @Test fun abortAfterNewDesiredPreservesDurableLastAppliedGeoForRestore() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val (store, old) = applied(root)
        val publication = stage(root, store, old)
        val journal = File(root, "runtime-handoffs/geo-pending.json")
        val pendingBytes = journal.readBytes()
        val committed = store.commitGeoPublication(publication, proof)
        journal.writeBytes(pendingBytes)
        val committedAsset = publication.next.handoff.resources.last()
        val committedFile = File(publication.next.resourceDirectory, committedAsset.relativePath)
        val committedBytes = committedFile.readBytes()
        val desired = store.commit(handoff("rules: ['MATCH,REJECT']\n"), committed.intentEpoch, committed.revision)
        store.requestStart(desired.revision, desired.intentEpoch)
        assertEquals(committed.revision, store.readLastApplied()!!.revision)
        assertNotEquals(committed.revision, desired.revision)

        store.abortGeoPublication(publication)

        assertTrue("Stop discard deleted the durable lastApplied Geo asset", committedFile.isFile)
        assertArrayEquals(committedBytes, committedFile.readBytes())
        assertFalse(journal.exists())
        val reopened = RuntimeHandoffStore(root)
        val restored = reopened.restoreLastApplied(desired.intentEpoch)
        assertEquals(committed.revision, restored.revision)
        assertEquals(committed.compiledSha256, reopened.readCurrent()!!.handoff.compiledSha256)
        assertArrayEquals(committedBytes, File(restored.resourceDirectory, committedAsset.relativePath).readBytes())
    }

    @Test fun cleanupMatchesActualResourceDirectoryRatherThanOnlyTheRelativeName() = runBlocking<Unit> {
        for (restart in listOf(false, true)) {
            val root = temporary.newFolder()
            val (store, old) = applied(root)
            val publication = stage(root, store, old)
            val candidateAsset = publication.next.handoff.resources.last()
            val candidateFile = File(publication.next.resourceDirectory, candidateAsset.relativePath)
            val desiredSource = File(root, "different-generation.bin").apply { writeText("desired owns another generation") }
            val desired = store.commit(handoff("rules: ['MATCH,REJECT']\n", listOf(RuntimeHandoffResource(
                candidateAsset.relativePath, desiredSource.path, runtimeSha256(desiredSource.readBytes())))),
                old.intentEpoch, old.revision)
            val desiredFile = File(desired.resourceDirectory, candidateAsset.relativePath)
            assertNotEquals(candidateFile.parentFile, desiredFile.parentFile)
            assertTrue(candidateFile.isFile)
            assertTrue(desiredFile.isFile)

            if (restart) RuntimeHandoffStore(root).discardAbandonedGeoAfterNativeJoin()
            else store.abortGeoPublication(publication)

            assertFalse("Unreferenced candidate retained merely because another directory has the same relative name", candidateFile.exists())
            assertArrayEquals(desiredSource.readBytes(), desiredFile.readBytes())
            assertEquals(desired.revision, RuntimeHandoffStore(root).readCurrent()!!.revision)
            assertEquals(old.revision, RuntimeHandoffStore(root).readLastApplied()!!.revision)
        }
    }
}
