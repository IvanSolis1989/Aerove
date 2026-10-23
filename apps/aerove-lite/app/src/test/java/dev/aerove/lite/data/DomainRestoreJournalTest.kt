package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DomainRestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val paths = listOf("settings/settings.json", "profiles/index.json", "backups/webdav.json",
        "settings/overrides.json", "settings/builder-resources.yaml")
    private fun put(root: File, path: String, value: String) = File(root, path).apply { parentFile.mkdirs(); writeText(value) }

    private fun retainsCause(failure: Throwable?, original: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = failure
        while (current != null && seen.add(current)) {
            if (current === original) return true
            current = current.cause
        }
        return false
    }

    @Test fun failedRestoreRollsBackAllLibrariesAndRemovesOnlyNewOwnedFiles() = runBlocking<Unit> {
        val root = temporary.newFolder()
        paths.dropLast(1).forEach { put(root, it, "before:$it") }
        val unrelated = put(root, "settings/unrelated-user-file", "keep")
        try {
            atomicDomainRestore(root) {
                paths.forEach { put(root, it, "after:$it") }
                error("fail after all writes")
            }
            fail("restore must fail")
        } catch (expected: IllegalStateException) { assertEquals("fail after all writes", expected.message); assertTrue(rollbackVerified(expected)) }
        paths.dropLast(1).forEach { assertEquals("before:$it", File(root, it).readText()) }
        assertFalse(File(root, paths.last()).exists())
        assertEquals("keep", unrelated.readText())
        assertFalse(File(root, ".restore-journal").exists())
    }

    @Test fun registeredCacheIndexesRollbackAndArbitraryDynamicTargetsAreRejected() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val path = "runtime/resources/library/" + "a".repeat(64) + ".json"
        put(root, path, "old index")
        try { atomicDomainRestore(root, listOf(path)) { put(root, path, "new index"); error("rollback") } }
        catch (failure: IllegalStateException) { assertTrue(rollbackVerified(failure)) }
        assertEquals("old index", File(root, path).readText())
        try { atomicDomainRestore(root, listOf("settings/unrelated-user-file")) { fail("invalid journal path") }; fail() }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(root, "settings/unrelated-user-file").exists())
    }

    @Test fun verifiedOutcomeSurvivesCoroutineCauseWrappingAndUnverifiedCauseAlwaysWins() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val file = put(root, paths.first(), "original")
        val failure = runCatching { atomicDomainRestore(root) { file.writeText("partial"); throw java.io.IOException("restore failed") } }.exceptionOrNull()!!
        val wrapped = java.io.IOException("coroutine wrapper", failure)
        assertTrue(rollbackVerified(wrapped))
        val cycleA = java.io.IOException("cycle A")
        val cycleB = java.io.IOException("cycle B", cycleA)
        cycleA.initCause(cycleB)
        assertFalse(rollbackVerified(cycleA))
        invalidateRestoreRollback(failure, java.io.IOException("final read failed"))
        assertFalse(rollbackVerified(wrapped))
        assertEquals("original", file.readText())
    }

    @Test fun verifiedRollbackPreservesExactUtf8BytesIncludingBom() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val file = put(root, paths.first(), "\uFEFFbefore: π\n")
        val original = file.readBytes()
        val failure = runCatching { atomicDomainRestore(root) { file.writeText("partial"); error("fail") } }.exceptionOrNull()!!
        assertTrue(rollbackVerified(failure))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun corruptRollbackJournalExplicitlyPreventsRuntimeResume() = runBlocking<Unit> {
        val root = temporary.newFolder()
        paths.forEach { put(root, it, "before:$it") }
        val original = java.io.IOException("restore failed")
        val caught = runCatching {
            atomicDomainRestore(root) {
                paths.forEach { put(root, it, "partial:$it") }
                File(root, ".restore-journal/4.json").delete()
                throw original
            }
        }.exceptionOrNull()
        assertTrue(retainsCause(caught, original))
        assertFalse(rollbackVerified(original))
        assertTrue(original.suppressed.filterIsInstance<RestoreRollbackStatus>().any { !it.verified })
        assertTrue(File(root, ".restore-journal").isDirectory)
        paths.forEach { assertEquals("partial:$it", File(root, it).readText()) }
        assertFalse(rollbackVerified(java.io.IOException("unrelated")))
    }

    @Test fun deferredFinalReadFailureInvalidatesAnOtherwiseVerifiedRollback() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val settings = LiteSettingsStore(root)
        val baseline = settings.read()
        val original = java.io.IOException("restore failed")
        val caught = runCatching {
            settings.deferPublication {
                try {
                    atomicDomainRestore(root) {
                        settings.save(baseline.copy(ipv6 = !baseline.ipv6), false)
                        throw original
                    }
                } catch (failure: Throwable) {
                    assertTrue(rollbackVerified(failure))
                    File(root, "settings/settings.json").writeText("{invalid final read")
                    throw failure
                }
            }
        }.exceptionOrNull()
        assertTrue(retainsCause(caught, original))
        assertFalse(rollbackVerified(checkNotNull(caught)))
        assertEquals(baseline, settings.changes.value)
    }

    @Test fun committedRestoreRetainsAllLibraryWritesWithoutAnotherRollback() = runBlocking<Unit> {
        val root = temporary.newFolder()
        atomicDomainRestore(root) { paths.forEach { put(root, it, "committed:$it") } }
        privateDomainTransaction(root) { paths.forEach { assertEquals("committed:$it", File(root, it).readText()) } }
        assertFalse(File(root, ".restore-journal").exists())
    }

    @Test fun crashedVersionedJournalRecoversLibrariesBeforeExposingAnyStore() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val journal = File(root, ".restore-journal").apply { mkdir() }
        val files = JSONArray()
        paths.forEachIndexed { index, path ->
            put(root, path, "partial:$path")
            put(journal, "$index.json", "original:$path")
            files.put(JSONObject().put("path", path).put("present", true))
        }
        put(journal, "state.json", JSONObject().put("schema", 2).put("files", files).toString())
        privateDomainTransaction(root) { paths.forEach { assertEquals("original:$it", File(root, it).readText()) } }
        assertFalse(journal.exists())
    }

    @Test fun legacyThreeFileJournalNeverDeletesNewLibraries() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val journal = File(root, ".restore-journal").apply { mkdir() }
        paths.take(3).forEachIndexed { index, path -> put(root, path, "partial"); put(journal, "$index.json", "original:$path") }
        paths.drop(3).forEach { put(root, it, "new-library:$it") }
        put(journal, "state.json", "[true,true,true]")
        privateDomainTransaction(root) { }
        paths.take(3).forEach { assertEquals("original:$it", File(root, it).readText()) }
        paths.drop(3).forEach { assertEquals("new-library:$it", File(root, it).readText()) }
    }

    @Test fun corruptOrForgedJournalCannotPartiallyRestoreAnotherFile() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val journal = File(root, ".restore-journal").apply { mkdir() }
        val files = JSONArray()
        paths.forEachIndexed { index, path ->
            put(root, path, "partial:$path")
            if (index != paths.lastIndex) put(journal, "$index.json", "original:$path")
            files.put(JSONObject().put("path", path).put("present", true))
        }
        val state = JSONObject().put("schema", 2).put("files", files)
        put(journal, "state.json", state.toString())
        try { privateDomainTransaction(root) { fail("missing payload must reject") }; fail() }
        catch (_: IllegalArgumentException) { }
        paths.forEach { assertEquals("partial:$it", File(root, it).readText()) }
        files.getJSONObject(0).put("path", "settings/unrelated-user-file")
        put(journal, "state.json", state.toString())
        try { privateDomainTransaction(root) { fail("forged path must reject") }; fail() }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(root, "settings/unrelated-user-file").exists())
    }
}
